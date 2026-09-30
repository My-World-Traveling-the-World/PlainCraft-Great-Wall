package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import local.plaincraft.greatwall.terrain.OracleLane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlannerSchedulerTest {
    @TempDir Path directory;

    @Test void samplingFailureIsNotReplacedByGenericClosedInParentPlan() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        try (var io = Executors.newSingleThreadExecutor();
             var scheduler = new PlannerScheduler(recipe, () -> new AnalyticTerrainOracle((x, z) -> {
                 throw new WallFailure(FailureCode.INVALID_RESOURCE, "sampling-sentinel-original");
             }, true), new SegmentCacheStore(directory.resolve("failure/cache"), recipe), io);
             var plan = scheduler.requirePlan(0, Priority.PLAYER)) {
            scheduler.startupPump();
            var failure = assertThrows(ExecutionException.class, () -> plan.future().get(5, TimeUnit.SECONDS));
            Throwable root = failure;
            while (root.getCause() != null) root = root.getCause();
            assertInstanceOf(WallFailure.class, root);
            assertEquals(FailureCode.INVALID_RESOURCE, ((WallFailure) root).code());
            assertTrue(root.getMessage().contains("sampling-sentinel-original"), root.toString());
            var first = scheduler.failures().get("first");
            assertNotNull(first);
            assertEquals("ROUTE", first.phase());
            assertTrue(first.originalStack().contains("sampling-sentinel-original"));
            assertThrows(UnsupportedOperationException.class, () -> scheduler.failures().clear());
        }
    }

    private static ScheduledExecutorService ticks(PlannerScheduler scheduler) {
        var timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleAtFixedRate(() -> scheduler.tick(new SliceBudget(65536, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100))), 0, 5, TimeUnit.MILLISECONDS);
        return timer;
    }

    @Test void hundredCallersShareOneCompute() throws Exception {
        var recipe = PlanningFixtures.recipe(0); var created = new AtomicInteger();
        try (var io = Executors.newSingleThreadExecutor();
             var scheduler = new PlannerScheduler(recipe, () -> {
                 created.incrementAndGet(); return new AnalyticTerrainOracle((x, z) -> AnalyticTerrainOracle.dry(64), false);
             }, new SegmentCacheStore(directory.resolve("cache"), recipe), io);
             var timer = ticks(scheduler)) {
            var leases = new ArrayList<PlanLease<SegmentGeometry>>();
            for (int i = 0; i < 100; i++) leases.add(scheduler.requireGeometry(0, Priority.PLAYER));
            var expected = leases.getFirst().future().get(30, TimeUnit.SECONDS);
            for (var lease : leases) { assertSame(expected, lease.future().get(5, TimeUnit.SECONDS)); lease.close(); }
            assertEquals(2, created.get(), "one oracle per lane, reused by two routes and hydrology");
        }
    }

    @Test void rootLanesTransferWithoutReinitializationAndSchedulerOwnsDrain() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        var created = new AtomicInteger();
        var lanes = List.of(
                new OracleLane(recipe.instance(), 0, recipe.terrainHash(), () -> {
                    created.incrementAndGet(); return new AnalyticTerrainOracle((x, z) -> AnalyticTerrainOracle.dry(64), true);
                }),
                new OracleLane(recipe.instance(), 1, recipe.terrainHash(), () -> {
                    created.incrementAndGet(); return new AnalyticTerrainOracle((x, z) -> AnalyticTerrainOracle.dry(64), true);
                }));
        try (var io = Executors.newSingleThreadExecutor()) {
            var identity = lanes.getFirst().submit(oracle -> System.identityHashCode(oracle)).get(5, TimeUnit.SECONDS);
            var scheduler = new PlannerScheduler(recipe, lanes, new SegmentCacheStore(directory.resolve("cache"), recipe), io);
            try {
                scheduler.startupPump();
                try (var route = scheduler.requireRoute(0, Priority.PLAYER)) {
                    assertEquals(0, route.future().get(30, TimeUnit.SECONDS).key().index());
                }
                assertEquals(identity, lanes.getFirst().submit(System::identityHashCode).get(5, TimeUnit.SECONDS));
                assertEquals(2, created.get());
                assertEquals(1L, lanes.getFirst().diagnostics().get("factory-calls"));
            } finally {
                scheduler.close(); scheduler.drain().get(10, TimeUnit.SECONDS);
            }
            for (var lane : lanes) {
                assertEquals(1L, lane.diagnostics().get("oracle-closes"));
                assertTrue(lane.drain().isDone());
            }
        } finally { lanes.forEach(OracleLane::close); }
    }

    @Test void foreignOrClosedBootstrapLanesCannotBeTransferred() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        var factory = (java.util.function.Supplier<local.plaincraft.greatwall.terrain.NaturalTerrainOracle>)
                () -> new AnalyticTerrainOracle((x, z) -> AnalyticTerrainOracle.dry(64), true);
        var foreign = new OracleLane(java.util.UUID.randomUUID(), 0, recipe.terrainHash(), factory);
        var second = new OracleLane(recipe.instance(), 1, recipe.terrainHash(), factory);
        var first = new OracleLane(recipe.instance(), 0, recipe.terrainHash(), factory);
        try (var io = Executors.newSingleThreadExecutor()) {
            var store = new SegmentCacheStore(directory.resolve("cache"), recipe);
            assertThrows(WallFailure.class, () -> new PlannerScheduler(recipe, List.of(foreign, second), store, io));
            first.close();
            assertThrows(WallFailure.class, () -> new PlannerScheduler(recipe, List.of(first, second), store, io));
            assertEquals(0L, foreign.diagnostics().getOrDefault("factory-calls", 0L));
        } finally {
            for (var lane : List.of(first, second, foreign)) { lane.close(); lane.drain().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test void heldNativeInitializationDoesNotStopStartupPumpOrNeedTicks() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var created = new AtomicInteger();
        try (var io = Executors.newSingleThreadExecutor()) {
            var scheduler = new PlannerScheduler(recipe, () -> {
                created.incrementAndGet(); entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("init not released"); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                return new AnalyticTerrainOracle((x, z) -> AnalyticTerrainOracle.dry(64), true);
            }, new SegmentCacheStore(directory.resolve("cache"), recipe), io);
            try {
                scheduler.startupPump();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                long iterations = scheduler.diagnostics().getOrDefault("startup-pump-iterations", 0L);
                long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (scheduler.diagnostics().getOrDefault("startup-pump-iterations", 0L) <= iterations + 5
                        && System.nanoTime() < timeout) Thread.sleep(2);
                assertTrue(scheduler.diagnostics().get("startup-pump-iterations") > iterations + 5);
                assertFalse(scheduler.diagnostics().containsKey("startup-ready"));
                assertFalse(scheduler.diagnostics().containsKey("tick-enqueued"));
                try (var route = scheduler.requireRoute(0, Priority.PLAYER)) {
                    assertFalse(route.future().isDone());
                    release.countDown();
                    assertEquals(0, route.future().get(30, TimeUnit.SECONDS).key().index());
                }
                assertEquals(2, created.get());
            } finally {
                release.countDown(); scheduler.close(); scheduler.drain().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test void nativeFactoryFailureTerminatesSchedulerDependenciesAndDrains() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        try (var io = Executors.newSingleThreadExecutor()) {
            var scheduler = new PlannerScheduler(recipe, () -> { throw new IllegalStateException("init failure"); },
                    new SegmentCacheStore(directory.resolve("cache"), recipe), io);
            try (var route = scheduler.requireRoute(0, Priority.PLAYER);
                 var geometry = scheduler.requireGeometry(0, Priority.PLAYER)) {
                scheduler.startupPump();
                assertThrows(ExecutionException.class, () -> route.future().get(5, TimeUnit.SECONDS));
                assertThrows(ExecutionException.class, () -> geometry.future().get(5, TimeUnit.SECONDS));
                scheduler.drain().get(5, TimeUnit.SECONDS);
                assertTrue(scheduler.diagnostics().containsKey("closed"));
            } finally { scheduler.close(); }
        }
    }

    @Test void cancelOneLeaseKeepsOthers() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        try (var io = Executors.newSingleThreadExecutor();
             var scheduler = new PlannerScheduler(recipe, () -> AnalyticTerrainOracle.ridge(true), new SegmentCacheStore(directory.resolve("cache"), recipe), io);
             var timer = ticks(scheduler)) {
            var cancelled = scheduler.requireGeometry(0, Priority.PLAYER);
            var retained = scheduler.requireGeometry(0, Priority.PLAYER);
            cancelled.close();
            assertEquals(0, retained.future().get(30, TimeUnit.SECONDS).key().index());
            assertTrue(cancelled.future().isCancelled()); retained.close();
        }
    }

    @Test void closeTerminatesAllFutures() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        try (var io = Executors.newSingleThreadExecutor()) {
            var scheduler = new PlannerScheduler(recipe, () -> AnalyticTerrainOracle.ridge(false), new SegmentCacheStore(directory.resolve("cache"), recipe), io);
            var leases = List.of(scheduler.requireRoute(0, Priority.PLAYER), scheduler.requireGeometry(0, Priority.PLAYER),
                    scheduler.requirePlan(1, Priority.PLAYER));
            scheduler.close();
            for (var lease : leases) {
                assertThrows(Exception.class, () -> lease.future().get(5, TimeUnit.SECONDS));
                lease.close();
            }
            assertThrows(WallFailure.class, () -> scheduler.requireRoute(0, Priority.PLAYER));
        }
    }

    @Test void lateWorldACallbackCannotPublishWorldB() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        var delayedIo = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
        var a = new PlannerScheduler(recipe, () -> AnalyticTerrainOracle.ridge(false), new SegmentCacheStore(directory.resolve("a/cache"), recipe), delayedIo::add);
        var old = a.requireGeometry(0, Priority.PLAYER);
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (delayedIo.isEmpty() && System.nanoTime() < timeout) Thread.sleep(5);
        assertFalse(delayedIo.isEmpty());
        a.close();
        try (var io = Executors.newSingleThreadExecutor();
             var b = new PlannerScheduler(recipe, () -> AnalyticTerrainOracle.ridge(false), new SegmentCacheStore(directory.resolve("b/cache"), recipe), io);
             var timer = ticks(b)) {
            for (var task : delayedIo) task.run();
            assertTrue(old.future().isCompletedExceptionally());
            try (var fresh = b.requireGeometry(0, Priority.PLAYER)) {
                assertEquals(0, fresh.future().get(30, TimeUnit.SECONDS).key().index());
            }
        }
    }

    @Test void neighborHydrologyNeverWaitsOnNeighborGeometry() throws Exception {
        var recipe = PlanningFixtures.recipe(1);
        try (var io = Executors.newSingleThreadExecutor();
             var scheduler = new PlannerScheduler(recipe, () -> AnalyticTerrainOracle.ridge(true), new SegmentCacheStore(directory.resolve("cache"), recipe), io);
             var timer = ticks(scheduler)) {
            var leases = new ArrayList<PlanLease<SegmentGeometry>>();
            for (long i : new long[] {3, 1, 2, 0}) leases.add(scheduler.requireGeometry(i, Priority.PLAYER));
            for (var lease : leases) {
                var actual = lease.future().get(30, TimeUnit.SECONDS);
                var expected = scheduler.computeGeometryNow(actual.key().index(), AnalyticTerrainOracle.ridge(true));
                assertArrayEquals(CanonicalCodec.geometry(expected), CanonicalCodec.geometry(actual));
                lease.close();
            }
        }
    }

    @Test void lastSafeSegmentEndsBeforeWorldLimit() throws Exception {
        var recipe = PlanningFixtures.recipe(0, 1 << 30, 0, 6000);
        try (var io = Executors.newSingleThreadExecutor();
             var scheduler = new PlannerScheduler(recipe, () -> AnalyticTerrainOracle.ridge(true), new SegmentCacheStore(directory.resolve("cache"), recipe), io);
             var timer = ticks(scheduler)) {
            try (var first = scheduler.requireGeometry(0, Priority.PLAYER); var last = scheduler.requireGeometry(1, Priority.PLAYER)) {
                assertTrue(first.future().get(30, TimeUnit.SECONDS).terminal().isEmpty());
                var value = last.future().get(30, TimeUnit.SECONDS);
                assertEquals(TerminalKind.WORLD_LIMIT, value.terminal().orElseThrow().kind());
                assertEquals(value.localSQ8().getLast(), value.terminal().get().localSQ8());
            }
            assertThrows(WallFailure.class, () -> scheduler.requireGeometry(2, Priority.PLAYER));
        }
    }

    @Test void startupPumpProgressesWithoutNormalTicks() throws Exception {
        var recipe = PlanningFixtures.recipe(0, 1 << 30, 0, 6000);
        try (var io = Executors.newSingleThreadExecutor();
             var scheduler = new PlannerScheduler(recipe, () -> AnalyticTerrainOracle.ridge(true), new SegmentCacheStore(directory.resolve("cache"), recipe), io)) {
            var lease = scheduler.requirePlan(1, Priority.PLAYER);
            scheduler.startupPump();
            var plan = lease.future().get(30, TimeUnit.SECONDS);
            assertFalse(plan.stations().stations().isEmpty());
            assertEquals(1, plan.geometry().key().index());
            assertFalse(plan.sites().isEmpty());
            lease.close();
        }
    }

    @Test void prewarmedStartupStillServesNewPlansBeforeFirstTick() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        try (var io = Executors.newSingleThreadExecutor();
             var scheduler = new PlannerScheduler(recipe, () -> new AnalyticTerrainOracle(
                     (x, z) -> AnalyticTerrainOracle.dry(64), true),
                     new SegmentCacheStore(directory.resolve("cache"), recipe), io)) {
            scheduler.startupPump();
            long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!scheduler.diagnostics().containsKey("startup-ready") && System.nanoTime() < timeout) {
                assertFalse(scheduler.diagnostics().containsKey("startup-failed"));
                Thread.sleep(2);
            }
            assertTrue(scheduler.diagnostics().containsKey("startup-ready"), "prewarm must finish before the counterexample");
            try (var later = scheduler.requirePlan(6, Priority.PLAYER)) {
                var plan = later.future().get(10, TimeUnit.SECONDS);
                assertEquals(6, plan.geometry().key().index());
                assertFalse(plan.stations().stations().isEmpty());
            }
            scheduler.tick(new SliceBudget(4096, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25)));
            timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!scheduler.diagnostics().containsKey("startup-handoff") && System.nanoTime() < timeout) {
                Thread.sleep(2);
            }
            assertTrue(scheduler.diagnostics().containsKey("startup-handoff"), "first tick must retire the startup timer");
        }
    }

    @Test void otherDimensionAndDistantLateralChunksReturnExplicitEmptyWindows() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        try (var io = Executors.newSingleThreadExecutor();
             var scheduler = new PlannerScheduler(recipe, () -> AnalyticTerrainOracle.ridge(true), new SegmentCacheStore(directory.resolve("cache"), recipe), io)) {
            try (var nether = scheduler.requireWindow(new ChunkKey("minecraft:the_nether", 0, 0), 32, Priority.PLAYER);
                 var distant = scheduler.requireWindow(new ChunkKey(recipe.dimension(), 0, 10000), 32, Priority.PLAYER)) {
                assertTrue(nether.future().get(1, TimeUnit.SECONDS).plans().isEmpty());
                assertTrue(distant.future().get(1, TimeUnit.SECONDS).plans().isEmpty());
            }
        }
    }

    @Test void rejectedIoExecutorCannotLeaveAnUnfinishedFuture() {
        var recipe = PlanningFixtures.recipe(0);
        try (var scheduler = new PlannerScheduler(recipe, () -> AnalyticTerrainOracle.ridge(false),
                new SegmentCacheStore(directory.resolve("cache"), recipe), task -> { throw new RejectedExecutionException("fixture"); })) {
            var lease = scheduler.requireGeometry(0, Priority.PLAYER);
            assertThrows(Exception.class, () -> lease.future().get(5, TimeUnit.SECONDS));
            assertTrue(lease.future().isCompletedExceptionally());
            assertEquals(FailureCode.IO_FAILED, scheduler.failures().get("first").code());
            assertTrue(scheduler.failures().get("first").originalStack().contains("fixture"));
            lease.close();
        }
    }

    @Test void nonOverworldAndTamperedRecipeCannotCreateSecondPlanner() {
        var valid = PlanningFixtures.recipe(0);
        var otherDimension = new WorldRecipe(valid.instance(), valid.wallId(), valid.seed(), "minecraft:the_nether",
                valid.frozenSpawn(), valid.axis(), valid.bounds(), valid.config(), valid.terrainHash(), valid.paletteHash(),
                valid.templatesHash(), valid.recipeHash());
        assertThrows(WallFailure.class, () -> new PlannerScheduler(otherDimension, () -> AnalyticTerrainOracle.ridge(true),
                new SegmentCacheStore(directory.resolve("nether/cache"), otherDimension), Runnable::run));
        var tampered = new WorldRecipe(valid.instance(), valid.wallId(), 99, valid.dimension(),
                valid.frozenSpawn(), valid.axis(), valid.bounds(), valid.config(), valid.terrainHash(), valid.paletteHash(),
                valid.templatesHash(), valid.recipeHash());
        assertThrows(WallFailure.class, () -> new PlannerScheduler(tampered, () -> AnalyticTerrainOracle.ridge(true),
                new SegmentCacheStore(directory.resolve("tampered/cache"), tampered), Runnable::run));
    }

    @Test void manyDistinctPlansCannotReserveAwayTheirDependencies() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        try (var io = Executors.newSingleThreadExecutor();
             var scheduler = new PlannerScheduler(recipe, () -> new AnalyticTerrainOracle(
                     (x, z) -> AnalyticTerrainOracle.dry(64), true), new SegmentCacheStore(directory.resolve("cache"), recipe), io);
             var timer = ticks(scheduler)) {
            var leases = new ArrayList<PlanLease<SegmentPlan>>();
            try {
                for (int i = 19; i >= 0; i--) leases.add(scheduler.requirePlan(i, Priority.PLAYER));
                for (var lease : leases) {
                    var plan = lease.future().get(30, TimeUnit.SECONDS);
                    assertFalse(plan.stations().stations().isEmpty());
                }
            } finally { leases.forEach(PlanLease::close); }
        }
    }
}
