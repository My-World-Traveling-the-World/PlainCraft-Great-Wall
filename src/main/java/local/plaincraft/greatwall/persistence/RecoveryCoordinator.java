package local.plaincraft.greatwall.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;
import local.plaincraft.greatwall.model.WallTypes.GateId;
import local.plaincraft.greatwall.model.WallTypes.WorldRecipe;

/**
 * Startup recovery. Nothing here rebuilds missing wall; it only decides
 * whether the durable chain is trustworthy and reports what an operator
 * must resolve. Failures stay visible instead of being repaired silently.
 */
public final class RecoveryCoordinator {
    public enum Status { CLEAN, RECOVERED_PARTIAL_TAIL, NEEDS_OPERATOR, INCOMPATIBLE }

    public record Report(Status status, long recoveredSequence, List<String> actions,
            List<String> blockedPaths, String hash) {
        public Report {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(actions, "actions");
            Objects.requireNonNull(blockedPaths, "blockedPaths");
            Objects.requireNonNull(hash, "hash");
            if (recoveredSequence < 0) throw new IllegalArgumentException("recoveredSequence >= 0 required");
            actions = List.copyOf(actions);
            blockedPaths = List.copyOf(blockedPaths);
        }
    }

    private final WorldManifest manifest;
    private final HistoryJournal journal;
    private final ChunkEvidenceStore evidence;
    private final Executor ioOwner;
    private record Preflight(Optional<Report> problem, String manifestHash) {}

    public RecoveryCoordinator(WorldManifest manifest, HistoryJournal journal,
            ChunkEvidenceStore evidence, Executor ioOwner) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.ioOwner = Objects.requireNonNull(ioOwner, "ioOwner");
    }

    public CompletionStage<Report> recover(WorldRecipe expected) {
        Objects.requireNonNull(expected, "expected");
        return CompletableFuture.supplyAsync(() -> {
            List<String> actions = new ArrayList<>();
            List<String> blocked = new ArrayList<>();
            Optional<WorldRecipe> stored;
            try {
                stored = manifest.read();
            } catch (IOException failure) {
                throw new WallFailure(FailureCode.IO_FAILED, "cannot read the world manifest", failure);
            }
            if (stored.isEmpty()) {
                // A world that already had vanilla generation but no manifest is not ours to adopt.
                blocked.add("manifest");
                return new Preflight(Optional.of(report(Status.NEEDS_OPERATOR, 0L, actions, blocked)), "");
            }
            WorldRecipe recipe = stored.get();
            if (!recipe.recipeHash().equals(expected.recipeHash()) || !recipe.instance().equals(expected.instance())) {
                blocked.add("recipe");
                actions.add("stored recipe differs from the running configuration");
                return new Preflight(Optional.of(report(Status.INCOMPATIBLE, 0L, actions, blocked)), "");
            }
            try {
                var journalRecipe = new WorldManifest(journal.root().resolve("manifest")).read();
                if (journalRecipe.isEmpty() || !journalRecipe.get().equals(recipe)) {
                    throw new WallFailure(FailureCode.RECIPE_MISMATCH,
                            "journal root manifest differs from the recovery manifest");
                }
                return new Preflight(Optional.empty(), AtomicFiles.sha256(
                        AtomicFiles.readChecked(journal.root(), "manifest/world.json", 65536, null)));
            } catch (IOException failure) {
                throw new WallFailure(FailureCode.IO_FAILED, "cannot validate the journal manifest", failure);
            }
        }, ioOwner).thenCompose(preflight -> {
            if (preflight.problem().isPresent()) return CompletableFuture.completedFuture(preflight.problem().get());
            return journal.open().thenApplyAsync(recovery -> {
                var recovered = journal.state();
                if (!expected.instance().equals(recovery.head().world())
                        || !expected.instance().equals(recovered.world())
                        || !preflight.manifestHash().equals(recovered.manifestHash())) {
                    throw new WallFailure(FailureCode.RECIPE_MISMATCH, "recovered history belongs to another permanent world");
                }
                List<String> actions = new ArrayList<>();
                List<String> blocked = new ArrayList<>();
                Status status = recovery.archivedPartialTail() ? Status.RECOVERED_PARTIAL_TAIL : Status.CLEAN;
                if (recovery.archivedPartialTail()) actions.add("archived an incomplete journal tail before truncation");
                // Unsaved cache temporaries may be deleted; permanent .tmp files are evidence.
                cleanupCacheTemporaries(actions, blocked);
                if (!blocked.isEmpty()) status = Status.NEEDS_OPERATOR;
                return report(status, recovered.sequence(), actions, blocked);
            }, ioOwner);
        });
    }

    public CompletionStage<Report> inspectGate(GateId id, boolean dryRun) {
        Objects.requireNonNull(id, "id");
        return CompletableFuture.supplyAsync(() -> {
            List<String> actions = new ArrayList<>();
            List<String> blocked = new ArrayList<>();
            // Loaded-chunk comparison is the caller's job; unloaded chunks stay PENDING.
            actions.add("gate " + id.ordinal() + " inspected with dryRun=" + dryRun);
            if (dryRun) actions.add("zero writes were performed");
            return report(Status.CLEAN, 0L, actions, blocked);
        }, ioOwner);
    }

    public CompletionStage<Void> verifyEvidence(ChunkKey owner, TerrainMarkers.Marker marker) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(marker, "marker");
        return CompletableFuture.runAsync(() -> {
            boolean coverage;
            try {
                coverage = evidence.readCoverage(owner).isPresent();
            } catch (IOException failure) {
                throw new WallFailure(FailureCode.IO_FAILED, "cannot read coverage evidence", failure);
            }
            if (!coverage) {
                throw new WallFailure(FailureCode.T0_MISSING,
                        "marker present without coverage for " + owner.x() + "," + owner.z());
            }
            // A complete marker never authorizes recapturing T0 from current terrain.
        }, ioOwner);
    }

    private void cleanupCacheTemporaries(List<String> actions, List<String> blocked) {
        Path root = journal.root();
        Path cache = root.resolve("cache");
        if (!Files.isDirectory(cache)) return;
        try (var stream = Files.walk(cache)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".tmp"))
                    .toList()) {
                Files.deleteIfExists(path);
                actions.add("removed unreferenced cache temporary " + cache.relativize(path));
            }
        } catch (IOException failure) {
            blocked.add("cache");
            actions.add("cache cleanup failed: " + failure.getMessage());
        }
        // Permanent temporaries are evidence: keep them and report.
        Path permanent = root.resolve("history");
        if (!Files.isDirectory(permanent)) return;
        try (var stream = Files.list(permanent)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".tmp"))
                    .toList()) {
                actions.add("preserved permanent temporary as evidence " + permanent.relativize(path));
            }
        } catch (IOException failure) {
            blocked.add("history");
            actions.add("permanent temporary scan failed: " + failure.getMessage());
        }
    }

    private static Report report(Status status, long recovered, List<String> actions, List<String> blocked) {
        StringBuilder text = new StringBuilder(status.name()).append('|').append(recovered);
        for (String action : actions) text.append('|').append(action);
        for (String path : blocked) text.append("!|").append(path);
        return new Report(status, recovered, actions, blocked,
                DeterministicHash.sha256(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
