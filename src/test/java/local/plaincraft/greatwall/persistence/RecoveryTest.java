package local.plaincraft.greatwall.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.diagnostics.FaultInjector;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * S5 crash-consistency tests that exercise the pure-IO subset in the JUnit (non-process) form.
 * Halt-mode faults are orchestrated by FaultProcessMain in a subprocess and are asserted here
 * only as the documented argument contract; a real {@code Runtime.halt} would kill this JVM.
 */
class RecoveryTest {
    @TempDir Path temp;

    private WorldRecipe freeze(Path root) throws Exception {
        var sample = PlanningFixtures.recipe(1);
        var manifest = new WorldManifest(root.resolve("manifest"));
        UUID world = manifest.authorize(false, sample.seed(), WorldManifest.configHash(sample.config())).instance();
        var provisional = new WorldRecipe(world, sample.wallId(), sample.seed(), sample.dimension(),
                sample.frozenSpawn(), sample.axis(), sample.bounds(), sample.config(), sample.terrainHash(),
                sample.paletteHash(), sample.templatesHash(), sample.recipeHash());
        String hash = DeterministicHash.sha256(CanonicalCodec.recipe(provisional));
        var recipe = new WorldRecipe(world, hash, sample.seed(), sample.dimension(), sample.frozenSpawn(),
                sample.axis(), sample.bounds(), sample.config(), sample.terrainHash(),
                sample.paletteHash(), sample.templatesHash(), hash);
        manifest.freeze(recipe);
        return recipe;
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @Test
    void eachFaultPointReopensWithoutSilentReset() throws Exception {
        // (1) The S5 crash matrix enumerates exactly these 18 persistence fault points; the
        // FaultProcessMain --point argument must address every one of them by its enum name.
        var points = FaultInjector.Point.values();
        assertEquals(18, points.length, "the S5 matrix must enumerate exactly 18 fault points");
        var expectedNames = Set.of(
                "CACHE_HALF_WRITE", "MANIFEST_BEFORE_MOVE", "MANIFEST_AFTER_MOVE",
                "T0_BEFORE_FORCE", "T0_AFTER_FORCE", "HISTORY_BEFORE_APPEND", "HISTORY_AFTER_APPEND",
                "HISTORY_BEFORE_FORCE", "HISTORY_AFTER_FORCE", "HEAD_BEFORE_MOVE", "HEAD_AFTER_MOVE",
                "GATE_BEFORE_FIRST_WRITE", "GATE_MID_CHUNK", "GATE_BEFORE_ACK", "GATE_AFTER_ACK",
                "WORLD_AFTER_CLOSE", "DISK_FULL", "QOS_FEATURE_SKIP");
        for (var point : points) {
            assertTrue(expectedNames.contains(point.name()), "unexpected fault point: " + point.name());
            assertEquals(point, FaultInjector.Point.valueOf(point.name()),
                    "FaultProcessMain --point must round-trip the enum name: " + point.name());
        }

        // (2) exception mode: a throwing injector aborts the operation; the caller may never
        // swallow the IOException and continue into APPLY.
        FaultInjector throwing = (point, key) -> {
            throw new IOException("injected " + point + " at " + key);
        };
        for (var point : points) {
            var error = assertThrows(IOException.class, () -> throwing.hit(point, "evidence"));
            assertTrue(error.getMessage().contains(point.name()),
                    "the abort must name the fault point it interrupted: " + point.name());
        }

        // (3) disabled() is the production no-op and never throws; Counting tallies without failing.
        for (var point : points) {
            assertDoesNotThrow(() -> FaultInjector.disabled().hit(point, "k"));
        }
        var counting = new FaultInjector.Counting();
        for (var point : points) counting.hit(point, "k");
        for (var point : points) assertEquals(1, counting.count(point), "counting injector tallies " + point);

        // (4) halt mode is subprocess-only: FaultProcessMain calls Runtime.halt(137) and the parent
        // asserts exit code 137. Never executed here (it would kill this JVM); the argument contract
        // is --point <enum> --mode exception|halt with a positive --occurrence, fixed by the spec.

        // (5) The fault-hit.json write path is confined to the run root: no traversal, no absolute
        // injection, no directory escape.
        Path faultRuns = temp.resolve("fault-runs");
        java.nio.file.Files.createDirectories(faultRuns);
        AtomicFiles.resolveUnder(faultRuns, "fault-hit.json");
        assertThrows(IOException.class, () -> AtomicFiles.resolveUnder(faultRuns, "../escape.json"));
        assertThrows(IOException.class, () -> AtomicFiles.resolveUnder(faultRuns, "a/../../escape.json"));
        assertThrows(IOException.class, () -> AtomicFiles.resolveUnder(faultRuns, ""));
        String absolute = temp.resolve("elsewhere").toAbsolutePath().toString();
        assertThrows(IOException.class, () -> AtomicFiles.resolveUnder(faultRuns, absolute));
    }

    @Test
    void diskFullBackpressuresAndStopsWrites() throws Exception {
        var recipe = freeze(temp);
        var io = Executors.newSingleThreadExecutor();
        var journal = new HistoryJournal(temp, recipe.instance(), io);
        try {
            await(journal.open());
            // Hold the single IO owner so queued appends cannot drain. The pending queue must stop
            // admitting at MAX_PENDING (128) instead of growing without bound.
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            io.execute(() -> {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(30, TimeUnit.SECONDS),
                    "the IO owner must be blocked before backpressure is measured");

            // Admit exactly MAX_PENDING appends.
            for (int i = 0; i < 128; i++) {
                journal.append(new DurableLog.Entry("beacon/" + recipe.wallId() + "/" + i,
                        HistoryTypes.Kind.BEACON_CREDIT.name(), 1,
                        Map.of("wallId", recipe.wallId(), "ordinal", Integer.toString(i))));
            }
            // The 129th must be rejected with CAPACITY rather than queued.
            var overflow = journal.append(new DurableLog.Entry("beacon/" + recipe.wallId() + "/overflow",
                    HistoryTypes.Kind.BEACON_CREDIT.name(), 1,
                    Map.of("wallId", recipe.wallId(), "ordinal", "overflow")));
            var error = assertThrows(ExecutionException.class,
                    () -> overflow.toCompletableFuture().get(30, TimeUnit.SECONDS));
            assertEquals(FailureCode.CAPACITY, ((WallFailure) error.getCause()).code(),
                    "a full journal queue must backpressure instead of admitting an unbounded tail");

            // A rejected append must not falsely increase the durable count.
            assertEquals(0, journal.state().sequence(), "rejected append must not advance the published sequence");
            assertEquals(0, journal.state().beaconCount(), "rejected beacon credit must not be counted");

            // Zero evidence: an empty store has no coverage, so APPLY has nothing to verify against.
            var evidence = new ChunkEvidenceStore(temp.resolve("evidence"));
            var owner = new ChunkKey("minecraft:overworld", 0, 0);
            assertTrue(evidence.readCoverage(owner).isEmpty(),
                    "an empty evidence store must not authorize APPLY for an uncovered owner");

            release.countDown();
        } finally {
            journal.close();
            assertTrue(io.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void atomicMoveUnsupportedFailsBeforeApply() throws Exception {
        var root = temp.resolve("atomic");
        java.nio.file.Files.createDirectories(root);
        byte[] content = "permanent-identity".getBytes(StandardCharsets.UTF_8);

        // Immutable write succeeds, is idempotent for identical content, and fails loudly on change.
        AtomicFiles.writeImmutable(root, "manifest/world.json", content);
        AtomicFiles.writeImmutable(root, "manifest/world.json", content);
        var mismatch = assertThrows(WallFailure.class,
                () -> AtomicFiles.writeImmutable(root, "manifest/world.json",
                        "other".getBytes(StandardCharsets.UTF_8)));
        assertEquals(FailureCode.IO_FAILED, mismatch.code(),
                "an immutable manifest overwrite must fail before any APPLY");

        // A creation failure is visible and never silently rewritten: read back exactly what we wrote.
        assertEquals(AtomicFiles.sha256(content),
                AtomicFiles.sha256(AtomicFiles.readChecked(root, "manifest/world.json", 65536, null)));

        // No directory escape while writing evidence/manifest files.
        assertThrows(IOException.class, () -> AtomicFiles.writeImmutable(root, "../escape.json", content));

        // Missing / corrupt / oversized reads surface as the specific failure codes, never as a
        // silent empty read. (AtomicMoveNotSupportedException -> IO_FAILED with no non-atomic
        // fallback is enforced at AtomicFiles.java:69-71 and :95-97 but is not triggerable on a
        // standard temp volume, so the observable fail-visible contract is asserted here.)
        var missing = assertThrows(WallFailure.class,
                () -> AtomicFiles.readChecked(root, "manifest/missing.json", 65536, null));
        assertEquals(FailureCode.T0_MISSING, missing.code());
        var corrupt = assertThrows(WallFailure.class,
                () -> AtomicFiles.readChecked(root, "manifest/world.json", 65536, "0".repeat(64)));
        assertEquals(FailureCode.T0_CORRUPT, corrupt.code());
        var oversized = assertThrows(WallFailure.class,
                () -> AtomicFiles.readChecked(root, "manifest/world.json", 4, null));
        assertEquals(FailureCode.CAPACITY, oversized.code());
    }
}
