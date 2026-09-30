package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.persistence.WorldManifest;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldBootstrapTest {
    @TempDir Path directory;
    private static final String HASH = "a".repeat(64);
    private static final ChunkKey EARLY = new ChunkKey("minecraft:overworld", -63, -63);
    private static final ChunkKey REMOTE = new ChunkKey("minecraft:overworld", 500, 500);
    private static final DimensionBounds BOUNDS = new DimensionBounds(-64, 320, 30000000);

    @Test void earlySpawnWaitsOnlyForAuthorizationAndRemoteDoesNotPass() throws Exception {
        var queued = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
        var bootstrap = new WorldBootstrap(new WorldManifest(directory.resolve("manifest")), queued::add, 0, false);
        try {
            bootstrap.authorizeSpawnProbe(EARLY, 11);
            var early = bootstrap.beforeRecipe(EARLY);
            var remote = bootstrap.beforeRecipe(REMOTE);
            assertFalse(early.isDone()); assertFalse(remote.isDone());
            queued.remove().run();
            early.get(5, TimeUnit.SECONDS);
            assertFalse(remote.isDone());
            assertFalse(bootstrap.ready().isDone());
            assertFalse(bootstrap.diagnostics().containsKey("root-started"));
        } finally { bootstrap.close(); bootstrap.drain().get(5, TimeUnit.SECONDS); }
    }

    @Test void frozenRecipeIsDurableAndRootTransfersOneNativeFactoryPerLane() throws Exception {
        var manifest = new WorldManifest(directory.resolve("manifest"));
        var made = new AtomicInteger();
        try (var io = Executors.newSingleThreadExecutor()) {
            var bootstrap = new WorldBootstrap(manifest, io, 0, false);
            try {
                bootstrap.authorizeSpawnProbe(EARLY, 11);
                bootstrap.spawnChosen(new Point3i(-1000, 64, -1000));
                bootstrap.prepareFrozen(frozen(made));
                var recipe = bootstrap.ready().get(30, TimeUnit.SECONDS);
                assertEquals(recipe, manifest.read().orElseThrow());
                assertEquals(new Point3i(-1000, 64, -1000), recipe.frozenSpawn());
                assertEquals(0L, bootstrap.progress().get("root").over40msBatches());
                var planner = bootstrap.transferToPlanner(new SegmentCacheStore(directory.resolve("cache"), recipe), io);
                planner.startupPump();
                try (var route = planner.requireRoute(0, Priority.PLAYER)) {
                    route.future().get(30, TimeUnit.SECONDS);
                }
                assertEquals(2, made.get());
                assertThrows(IllegalStateException.class,
                        () -> bootstrap.transferToPlanner(new SegmentCacheStore(directory.resolve("cache"), recipe), io));
            } finally { bootstrap.close(); bootstrap.drain().get(10, TimeUnit.SECONDS); }
        }
    }

    @Test void reopenedRecipeWaitsForActualInputsAndIgnoresChangedWorldSpawn() throws Exception {
        var manifest = new WorldManifest(directory.resolve("manifest"));
        WorldRecipe first;
        try (var io = Executors.newSingleThreadExecutor()) {
            var bootstrap = new WorldBootstrap(manifest, io, 1, false);
            try {
                bootstrap.authorizeSpawnProbe(EARLY, 11);
                bootstrap.spawnChosen(new Point3i(-1000, 64, -1000));
                bootstrap.prepareFrozen(frozen(new AtomicInteger()));
                first = bootstrap.ready().get(30, TimeUnit.SECONDS);
            } finally { bootstrap.close(); bootstrap.drain().get(10, TimeUnit.SECONDS); }
            var made = new AtomicInteger();
            var reopen = new WorldBootstrap(manifest, io, 1, true);
            try {
                assertFalse(reopen.ready().isDone());
                reopen.spawnChosen(new Point3i(12000, 70, 34000));
                reopen.prepareFrozen(frozen(made));
                assertEquals(first, reopen.ready().get(10, TimeUnit.SECONDS));
                assertEquals(0, made.get(), "replay validation must not resample the root");
                assertTrue(reopen.earlyChunks().isEmpty(), "reopen grants no new early-domain blank exemption");
            } finally { reopen.close(); reopen.drain().get(10, TimeUnit.SECONDS); }
        }
    }

    @Test void initializedUnmarkedWorldFailsInsteadOfBackfill() throws Exception {
        try (var io = Executors.newSingleThreadExecutor()) {
            var bootstrap = new WorldBootstrap(new WorldManifest(directory.resolve("manifest")), io, 1, true);
            var error = assertThrows(ExecutionException.class, () -> bootstrap.ready().get(10, TimeUnit.SECONDS));
            assertInstanceOf(WallFailure.class, error.getCause());
            assertEquals(FailureCode.EXISTING_WORLD, ((WallFailure) error.getCause()).code());
            bootstrap.drain().get(10, TimeUnit.SECONDS);
        }
    }

    @Test void canceledReadyViewCannotCancelRootProducer() throws Exception {
        try (var io = Executors.newSingleThreadExecutor()) {
            var bootstrap = new WorldBootstrap(new WorldManifest(directory.resolve("manifest")), io, 1, false);
            try {
                var canceled = bootstrap.ready(); assertTrue(canceled.cancel(false));
                bootstrap.authorizeSpawnProbe(EARLY, 11);
                bootstrap.spawnChosen(new Point3i(-1000, 64, -1000));
                bootstrap.prepareFrozen(frozen(new AtomicInteger()));
                assertNotNull(bootstrap.ready().get(30, TimeUnit.SECONDS));
                assertTrue(canceled.isCancelled());
            } finally { bootstrap.close(); bootstrap.drain().get(10, TimeUnit.SECONDS); }
        }
    }

    @Test void closeDuringAuthorizationDoesNotStartLateOracleOrPublishRecipe() throws Exception {
        var queue = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
        var made = new AtomicInteger();
        var bootstrap = new WorldBootstrap(new WorldManifest(directory.resolve("manifest")), queue::add, 0, false);
        bootstrap.authorizeSpawnProbe(EARLY, 11);
        bootstrap.spawnChosen(new Point3i(-1000, 64, -1000));
        bootstrap.prepareFrozen(frozen(made));
        bootstrap.close();
        assertFalse(bootstrap.drain().isDone(), "queued disk authorization is still a live producer");
        queue.remove().run();
        bootstrap.drain().get(5, TimeUnit.SECONDS);
        assertTrue(bootstrap.ready().isCompletedExceptionally());
        assertEquals(0, made.get());
    }

    private static WorldBootstrap.Frozen frozen(AtomicInteger made) {
        return new WorldBootstrap.Frozen(BOUNDS, HASH, HASH, HASH, () -> {
            made.incrementAndGet(); return new AnalyticTerrainOracle((x, z) -> AnalyticTerrainOracle.dry(64), true);
        }, (oracle, cursor) -> true);
    }
}
