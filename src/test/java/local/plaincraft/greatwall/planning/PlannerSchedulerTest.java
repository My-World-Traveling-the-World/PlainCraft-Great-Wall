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
import local.plaincraft.greatwall.terrain.LanePolicy;
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

    @Test void fragmentedWaterTerminatesGeometryAndPermanentPrefixWithoutChangingSamplingFailures() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        try (var io = Executors.newSingleThreadExecutor();
             var scheduler = new PlannerScheduler(recipe, () -> new AnalyticTerrainOracle((x, z) ->
                     x >= 1100 && x < 1140
                             ? AnalyticTerrainOracle.water(x < 1120 ? 63 : 66, 58)
                             : AnalyticTerrainOracle.dry(64), true),
                     new SegmentCacheStore(directory.resolve("fragmented/cache"), recipe), io);
             var geometry = scheduler.requireGeometry(0, Priority.PLAYER)) {
            scheduler.startupPump();
            var actual = geometry.future().get(30, TimeUnit.SECONDS);
            var water = actual.water().stream().filter(w -> w.classification() == WaterClass.WIDE)
                    .findFirst().orElseThrow();
            var terminal = actual.terminal().orElseThrow();
            assertEquals(TerminalKind.OCEAN, terminal.kind());
            assertEquals(0, terminal.segment());
            assertEquals(water.crossing().fromQ8(), terminal.localSQ8());
            var reduced = PrefixSummaryIndex.reduce(PrefixSummaryIndex.origin(), actual);
            assertEquals(terminal, reduced.terminal().orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> PrefixSummaryIndex.reduce(reduced,
                    PlanningFixtures.geometry(recipe, 1, java.util.Optional.empty())));
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
            assertEquals(LanePolicy.resolve().startupLaneCount(), created.get(),
                    "one oracle per lane, reused by two routes and hydrology");
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
        int lanes = LanePolicy.resolve().startupLaneCount();
        // Demand-only startup means lanes initialize lazily, so hold just the first one. What this
        // test protects is that a blocked native initialization neither stalls the pump nor requires
        // a server tick to make progress.
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
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
                // The pump's first task runs asynchronously, so wait for it rather than asserting.
                long ready = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!scheduler.diagnostics().containsKey("startup-ready") && System.nanoTime() < ready) {
                    Thread.sleep(2);
                }
                assertTrue(scheduler.diagnostics().containsKey("startup-ready"),
                        "demand-only startup is ready without waiting for any bulk prewarm");
                // Demand the work that forces lane initialization.
                var held = scheduler.requireRoute(0, Priority.PLAYER);
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS), "a lane must begin initializing");
                    long iterations = scheduler.diagnostics().getOrDefault("startup-pump-iterations", 0L);
                    long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (scheduler.diagnostics().getOrDefault("startup-pump-iterations", 0L) <= iterations + 5
                            && System.nanoTime() < timeout) Thread.sleep(2);
                    assertTrue(scheduler.diagnostics().get("startup-pump-iterations") > iterations + 5,
                            "the pump must keep running while a native initialization is blocked");
                    assertFalse(scheduler.diagnostics().containsKey("tick-enqueued"),
                            "progress must not require a server tick");
                    assertFalse(held.future().isDone(), "no work can complete before its lane is ready");
                    release.countDown();
                    assertEquals(0, held.future().get(30, TimeUnit.SECONDS).key().index());
                } finally { held.close(); }
                assertTrue(created.get() >= 1 && created.get() <= lanes,
                        "lanes remain bounded by the resolved policy: " + created.get() + " of " + lanes);
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
        var recipe = PlanningFixtures.recipe(0, 1 << 30, 0, 7000);
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
        var recipe = PlanningFixtures.recipe(0, 1 << 30, 0, 7000);
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
            // Demand-only startup is ready as soon as the pump runs; the bulk prewarm is a separate,
            // later phase so it can never contend with a blocking startup peer.
            scheduler.startupPump();
            long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!scheduler.diagnostics().containsKey("startup-ready") && System.nanoTime() < timeout) {
                assertFalse(scheduler.diagnostics().containsKey("startup-failed"));
                Thread.sleep(2);
            }
            assertTrue(scheduler.diagnostics().containsKey("startup-ready"), "demand-only pump must start");
            assertTrue(scheduler.diagnostics().containsKey("demand-only"), "early startup must not bulk-prewarm");
            // 编译回调必须拿到已经钉住的完整批次；否则启动时会丢计划或重新规划。
            var pinnedAtHook = new CompletableFuture<List<PlanLease<SegmentPlan>>>();
            var hookCalls = new AtomicInteger();
            scheduler.onBulkReady(() -> {
                hookCalls.incrementAndGet();
                pinnedAtHook.complete(scheduler.prewarmLeases());
            });
            scheduler.afterRoadBulk();
            scheduler.afterRoadBulk();
            timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!scheduler.diagnostics().containsKey("bulk-ready") && System.nanoTime() < timeout) {
                assertFalse(scheduler.diagnostics().containsKey("bulk-failed"));
                Thread.sleep(2);
            }
            assertTrue(scheduler.diagnostics().containsKey("bulk-ready"), "bulk prewarm must finish once requested");
            var pinned = pinnedAtHook.get(5, TimeUnit.SECONDS);
            assertFalse(pinned.isEmpty());
            assertEquals((long) pinned.size(), scheduler.diagnostics().get("prewarm-requested-segments"));
            for (int i = 0; i < pinned.size(); i++) {
                var plan = pinned.get(i).future().get(1, TimeUnit.SECONDS);
                assertEquals(i, plan.geometry().key().index());
            }
            assertEquals(1, hookCalls.get());
            scheduler.releasePrewarmLeases();
            assertTrue(scheduler.prewarmLeases().isEmpty());
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

    @Test void fineSampleIndexCompletesWhenTheEnvelopeHoldsFewerSegmentsThanTheTarget() throws Exception {
        // The frozen envelope of this fixture holds exactly two segments (the second ends at the world
        // limit), while the prewarm target is larger. The routes to sample must therefore be counted as
        // they are enqueued: seeding the counter with the configured target left it stuck above zero,
        // so the aggregate never completed, the fine-sample driver never stopped and the session sat in
        // FINE_SAMPLE forever - the startup hang this test pins down.
        long configured = local.plaincraft.greatwall.config.GreatWallConfig.get().prewarmSegmentsTarget();
        long effective = configured > 0L ? configured : 4; // PlannerScheduler.STARTUP_PREWARM_SEGMENTS
        assertTrue(effective > 2, "precondition: prewarm target must exceed the two safe segments");
        var recipe = PlanningFixtures.recipe(0, 1 << 30, 0, 7000);
        try (var io = Executors.newSingleThreadExecutor();
             var scheduler = new PlannerScheduler(recipe, () -> AnalyticTerrainOracle.ridge(true), new SegmentCacheStore(directory.resolve("cache"), recipe), io);
             var timer = ticks(scheduler)) {
            try (var first = scheduler.requireRoute(0, Priority.PLAYER);
                 var second = scheduler.requireRoute(1, Priority.PLAYER)) {
                var firstRoute = first.future().get(30, TimeUnit.SECONDS);
                var secondRoute = second.future().get(30, TimeUnit.SECONDS);
                var future = scheduler.requireFineSamples(Priority.PLAYER);
                // The index is published from the completion callback, so a build defect there shows up
                // as a timeout rather than a wrong answer. The budget is generous on purpose: the walk
                // itself is seconds, and an unbounded wait would turn a hang into a stuck test run.
                var index = future.get(120, TimeUnit.SECONDS);
                assertTrue(index.size() > 0, "the index must carry the sampled columns");
                // Both enqueued segments have to be represented: the index may only be published once
                // the enqueue loop has finished, or the last segments would be dropped silently.
                var head = firstRoute.points().getFirst();
                var tail = secondRoute.points().getLast();
                assertTrue(index.contains(local.plaincraft.greatwall.geometry.FixedMath.floorBlock(head.xQ8()),
                        local.plaincraft.greatwall.geometry.FixedMath.floorBlock(head.zQ8())),
                        "the first segment's opening column must be sampled");
                assertTrue(index.contains(local.plaincraft.greatwall.geometry.FixedMath.floorBlock(tail.xQ8()),
                        local.plaincraft.greatwall.geometry.FixedMath.floorBlock(tail.zQ8())),
                        "the last safe segment's final column must be sampled");
            }
        }
    }
}
