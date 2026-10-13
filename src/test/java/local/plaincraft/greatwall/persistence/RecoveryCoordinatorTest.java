package local.plaincraft.greatwall.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryCoordinatorTest {
    @TempDir Path temp;

    private WorldRecipe freeze(Path root) throws Exception {
        var sample = PlanningFixtures.recipe(1);
        var manifest = new WorldManifest(root.resolve("manifest"));
        UUID world = manifest.authorize(false, sample.seed(), WorldManifest.configHash(sample.config()),
                sample.config().parametersHash()).instance();
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

    @Test void verifiedManifestFileHashIsNotTheSemanticRecipeHash() throws Exception {
        var recipe = freeze(temp);
        var io = Executors.newSingleThreadExecutor();
        var journal = new HistoryJournal(temp, recipe.instance(), io);
        try {
            var coordinator = new RecoveryCoordinator(new WorldManifest(temp.resolve("manifest")),
                    journal, new ChunkEvidenceStore(temp.resolve("evidence")), Runnable::run);
            var report = await(coordinator.recover(recipe));
            assertEquals(RecoveryCoordinator.Status.CLEAN, report.status());
            assertEquals(0, report.recoveredSequence());
            String fileHash = AtomicFiles.sha256(Files.readAllBytes(temp.resolve("manifest/world.json")));
            assertNotEquals(recipe.recipeHash(), fileHash);
            assertEquals(fileHash, journal.state().manifestHash());
        } finally {
            journal.close();
            assertTrue(io.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test void recoveredSequenceIncludesDurableWalBeyondCheckpoint() throws Exception {
        var recipe = freeze(temp);
        var writer = Executors.newSingleThreadExecutor();
        var original = new HistoryJournal(temp, recipe.instance(), writer);
        try {
            assertEquals(0, await(original.open()).head().sequence());
            await(original.append(new DurableLog.Entry("beacon/" + recipe.wallId() + "/1",
                    HistoryTypes.Kind.BEACON_CREDIT.name(), 1,
                    Map.of("wallId", recipe.wallId(), "ordinal", "1"))));
        } finally {
            original.close();
            assertTrue(writer.awaitTermination(30, TimeUnit.SECONDS));
        }
        var io = Executors.newSingleThreadExecutor();
        var journal = new HistoryJournal(temp, recipe.instance(), io);
        try {
            var coordinator = new RecoveryCoordinator(new WorldManifest(temp.resolve("manifest")),
                    journal, new ChunkEvidenceStore(temp.resolve("evidence")), Runnable::run);
            assertEquals(1, await(coordinator.recover(recipe)).recoveredSequence());
            assertEquals(1, journal.state().beaconCount());
        } finally {
            journal.close();
            assertTrue(io.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test void aDifferentJournalRootCannotBeOpenedByMatchingExternalManifest() throws Exception {
        var expected = freeze(temp.resolve("expected"));
        Path foreign = temp.resolve("foreign");
        var other = freeze(foreign);
        var io = Executors.newSingleThreadExecutor();
        var journal = new HistoryJournal(foreign, other.instance(), io);
        try {
            var coordinator = new RecoveryCoordinator(new WorldManifest(temp.resolve("expected/manifest")),
                    journal, new ChunkEvidenceStore(temp.resolve("evidence")), Runnable::run);
            var error = assertThrows(ExecutionException.class, () -> await(coordinator.recover(expected)));
            assertEquals(FailureCode.RECIPE_MISMATCH, ((WallFailure) error.getCause()).code());
            assertFalse(Files.exists(foreign.resolve("history/HEAD")));
        } finally {
            journal.close();
            assertTrue(io.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    @Test void unmarkedManifestDoesNotInitializeEmptyHistory() throws Exception {
        var io = Executors.newSingleThreadExecutor();
        var journal = new HistoryJournal(temp, UUID.randomUUID(), io);
        try {
            var coordinator = new RecoveryCoordinator(new WorldManifest(temp.resolve("manifest")),
                    journal, new ChunkEvidenceStore(temp.resolve("evidence")), Runnable::run);
            assertEquals(RecoveryCoordinator.Status.NEEDS_OPERATOR,
                    await(coordinator.recover(PlanningFixtures.recipe(1))).status());
            assertFalse(Files.exists(temp.resolve("history")));
        } finally {
            journal.close();
            assertTrue(io.awaitTermination(30, TimeUnit.SECONDS));
        }
    }
}
