package local.plaincraft.greatwall.planning;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.config.GreatWallConfig;
import local.plaincraft.greatwall.diagnostics.BudgetCache;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.terrain.HydrologyClassifier;
import local.plaincraft.greatwall.terrain.LanePolicy;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;
import local.plaincraft.greatwall.terrain.OracleLane;

/** Pure S1A scheduler. Integration must acquire its futures before any FEATURES region lock. */
public final class PlannerScheduler implements AutoCloseable {
    public record FailureEvidence(String phase, long segment, long operations, FailureCode code,
            String exceptionType, String message, String originalStack) {}
    private static final long MIB = 1024L * 1024;
    private final WorldRecipe recipe;
    private final ConcurrentHashMap<SegmentKey, CompletableFuture<RouteShape>> routeFlights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SegmentKey, CompletableFuture<SegmentGeometry>> geometryFlights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SegmentKey, CompletableFuture<SegmentPlan>> planFlights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, JobProgress> progress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlanLease<?>> callerLeases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<PlanLease<Object>>> acquisitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> state = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FailureEvidence> failures = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor pump;
    private final PrefixSummaryIndex prefixes;
    private final SegmentCacheStore cache;
    private final Executor io;
    private final AnchorField anchors;
    private final RidgePlanner ridge;
    private final HydrologyClassifier hydrology = new HydrologyClassifier();
    /**
     * Per-segment hydrology operation ceiling. A real-world segment needed
     * >25M operations before terminating, which exceeded the server watchdog
     * window. Beyond this ceiling the remaining unresolved water terminates
     * as WIDE, so a slow island can never stall the whole wall.
     */
    private static final long HYDROLOGY_OPERATION_CEILING = 4_000_000L;
    /** Opening segments pre-computed by the bulk prewarm, once the blocking startup peer is done. */
    private static final long STARTUP_PREWARM_SEGMENTS = 4;
    /** Thread-name prefixes owned by this scheduler; used to refuse self-blocking waits. */
    private static final String pumpThreadPrefix = "pgw-planner-";
    private static final String laneThreadPrefix = "pgw-oracle-";
    private final HeightContractPlanner heights;
    private final SiteScheduler sites;
    private final BudgetCache<CacheKey, Object> memory;
    private final List<OracleLane> lanes;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final List<Lane> laneState;
    private final PriorityQueue<Job<?>> pending = new PriorityQueue<>(Comparator.<Job<?>, Priority>comparing(j -> j.priority)
            .thenComparingLong(j -> j.sequence));
    private final ArrayDeque<Runnable> geometryQueue = new ArrayDeque<>();
    private final ArrayDeque<Runnable> ioQueue = new ArrayDeque<>();
    private final ArrayDeque<Runnable> planAdmissions = new ArrayDeque<>(), geometryAdmissions = new ArrayDeque<>();
    private final Map<UUID, Job<?>> activeJobs = new HashMap<>();
    private int activeGeometry, credits, activeIo, admittedPlans, admittedGeometries;
    /** Native lanes currently initializing; bounded by {@link LanePolicy#MAX_CONCURRENT_INITIALIZATIONS}. */
    private int initializing;
    private long deadline, sequence, background = 4;
    private ScheduledFuture<?> startupTimer;
    /**
     * Drives the bulk prewarm independently of the server tick.
     *
     * <p>Cancelled when the prewarm settles or the scheduler closes. See
     * {@link #afterRoadBulk()} for why the prewarm does not rely on ticks.
     */
    private ScheduledFuture<?> prewarmTimer;
    private PlanLease<SegmentPlan> backgroundLease;

    public PlannerScheduler(WorldRecipe recipe, Supplier<NaturalTerrainOracle> oracleFactory, SegmentCacheStore cache, Executor io) {
        this(recipe, Objects.requireNonNull(oracleFactory), null, cache, io);
    }

    /** Successful construction transfers both session-owned lanes from bootstrap to this scheduler. */
    public PlannerScheduler(WorldRecipe recipe, List<OracleLane> bootstrapLanes, SegmentCacheStore cache, Executor io) {
        this(recipe, null, List.copyOf(bootstrapLanes), cache, io);
    }

    private PlannerScheduler(WorldRecipe recipe, Supplier<NaturalTerrainOracle> oracleFactory,
            List<OracleLane> bootstrapLanes, SegmentCacheStore cache, Executor io) {
        this.recipe = Objects.requireNonNull(recipe);
        this.cache = Objects.requireNonNull(cache); this.io = Objects.requireNonNull(io);
        if (!"minecraft:overworld".equals(recipe.dimension())) {
            throw new WallFailure(FailureCode.INVALID_RESOURCE, "only the overworld may own a wall planner");
        }
        if (!DeterministicHash.sha256(CanonicalCodec.recipe(recipe)).equals(recipe.recipeHash())) {
            throw new WallFailure(FailureCode.RECIPE_MISMATCH, "recipe semantic hash is not canonical");
        }
        if (!cache.recipeHash().equals(recipe.recipeHash())) throw new IllegalArgumentException("cache recipe mismatch");
        if (bootstrapLanes != null) {
            if (bootstrapLanes.size() < LanePolicy.MIN_LANES) {
                throw new IllegalArgumentException("at least " + LanePolicy.MIN_LANES + " bootstrap lanes required");
            }
            for (int index = 0; index < bootstrapLanes.size(); index++) {
                var lane = bootstrapLanes.get(index);
                if (lane.index() != index || !lane.session().equals(recipe.instance())
                        || !lane.fingerprint().equals(recipe.terrainHash())
                        || lane.diagnostics().containsKey("closed")) {
                    throw new WallFailure(FailureCode.STALE_SESSION, "bootstrap lane identity/settings/owner mismatch");
                }
            }
        }
        this.anchors = new AnchorField(recipe); this.ridge = new RidgePlanner(recipe, anchors);
        this.heights = new HeightContractPlanner(recipe); this.sites = new SiteScheduler(recipe);
        this.pump = new ScheduledThreadPoolExecutor(1, factory("pgw-planner-" + recipe.instance()));
        pump.setRemoveOnCancelPolicy(true);
        this.lanes = bootstrapLanes != null ? List.copyOf(bootstrapLanes)
                : lanes(recipe, oracleFactory, LanePolicy.resolve().startupLaneCount());
        this.laneState = this.lanes.stream().map(ignored -> new Lane()).toList();
        this.memory = new BudgetCache<>(recipe.config().segmentBytes(), 256, 4096, pump, PlannerScheduler::retainedBytes);
        this.prefixes = new PrefixSummaryIndex(i -> requireGeometry(i, Priority.PLAYER_PREFETCH), pump, cache, this::submitIo);
    }

    private static List<OracleLane> lanes(WorldRecipe recipe, Supplier<NaturalTerrainOracle> oracleFactory,
                                          int count) {
        var built = new java.util.ArrayList<OracleLane>(count);
        for (int index = 0; index < count; index++) {
            built.add(new OracleLane(recipe.instance(), index, recipe.terrainHash(), oracleFactory));
        }
        return List.copyOf(built);
    }

    public PlanLease<RouteShape> requireRoute(long segment, Priority priority) {
        var key = key(segment);
        promote(segment, priority);
        return acquire(new CacheKey("route", segment), MIB, RouteShape.class, () -> {
            var result = flight(routeFlights, key);
            enqueue(new RouteJob(key, priority, result));
            return result.thenApply(v -> v);
        });
    }

    public PlanLease<SegmentGeometry> requireGeometry(long segment, Priority priority) {
        var key = key(segment);
        promote(segment, priority);
        return acquire(new CacheKey("geometry", segment), 2 * MIB, SegmentGeometry.class, () -> {
            var result = flight(geometryFlights, key);
            geometryQueue.add(() -> startGeometry(key, priority, result));
            drainGeometry();
            return result.thenApply(v -> v);
        });
    }

    public PlanLease<SegmentPlan> requirePlan(long segment, Priority priority) {
        var key = key(segment);
        promote(segment, priority);
        return acquire(new CacheKey("plan", segment), 4 * MIB, SegmentPlan.class, () -> {
            var result = flight(planFlights, key);
            var geometry = requireGeometry(segment, priority);
            var prefix = prefixes.requireBefore(segment);
            geometry.future().thenCombineAsync(prefix.future(), Pair::new, pump).whenCompleteAsync((inputs, error) -> {
                if (error != null) { geometry.close(); prefix.close(); result.completeExceptionally(error); return; }
                if (closed()) { geometry.close(); prefix.close(); result.completeExceptionally(closedFailure()); return; }
                if (inputs.prefix.terminal().isPresent() && inputs.prefix.terminal().get().segment() < segment) {
                    var table = new StationTable(List.of(), List.of(), "0".repeat(64));
                    table = new StationTable(List.of(), List.of(), DeterministicHash.sha256(CanonicalCodec.stations(table)));
                    result.complete(hashedPlan(inputs.geometry, inputs.prefix, table, List.of()));
                    geometry.close(); prefix.close(); return;
                }
                ioRead(() -> cache.readPlan(key)).whenCompleteAsync((cached, cacheError) -> {
                    if (closed()) { geometry.close(); prefix.close(); result.completeExceptionally(closedFailure()); return; }
                    if (cacheError == null && cached.isPresent() && cached.get().prefix().equals(inputs.prefix)
                            && cached.get().geometry().hash().equals(inputs.geometry.hash())) {
                        result.complete(cached.get()); geometry.close(); prefix.close(); return;
                    }
                    preparePlan(key, priority, inputs, result, List.of(geometry, prefix));
                }, pump);
            }, pump);
            return result.thenApply(v -> v);
        });
    }

    public PlanLease<PlanWindow> requireWindow(ChunkKey chunk, int marginBlocks, Priority priority) {
        if (closed()) throw closedFailure();
        if (marginBlocks < 0 || marginBlocks > 512) throw new IllegalArgumentException("window margin 0..512");
        if (!chunk.dimension().equals(recipe.dimension())) {
            return new PlanLease<>(UUID.randomUUID(), CompletableFuture.completedFuture(new PlanWindow(List.of())), () -> {});
        }
        int x0 = Math.toIntExact(Math.multiplyExact((long) chunk.x(), 16));
        int z0 = Math.toIntExact(Math.multiplyExact((long) chunk.z(), 16));
        long minU = Long.MAX_VALUE, maxU = Long.MIN_VALUE, minB = Long.MAX_VALUE, maxB = Long.MIN_VALUE;
        for (int dx : new int[] {0, 15}) for (int dz : new int[] {0, 15}) {
            int x = Math.addExact(x0, dx), z = Math.addExact(z0, dz);
            long u = Projection.axisUQ8(recipe.axis(), x, z);
            minU = Math.min(minU, u); maxU = Math.max(maxU, u);
            long side = Math.addExact(-Math.multiplyExact((long) x - recipe.axis().origin().x(), recipe.axis().ezQ30()),
                    Math.multiplyExact((long) z - recipe.axis().origin().z(), recipe.axis().exQ30()));
            long b = FixedMath.roundDiv(side, 1L << 22);
            minB = Math.min(minB, b); maxB = Math.max(maxB, b);
        }
        long lateral = (512L + 512 + 96 + marginBlocks) * 256;
        if (minB > lateral || maxB < -lateral || maxU + (96L + marginBlocks) * 256 < 0) {
            return new PlanLease<>(UUID.randomUUID(), CompletableFuture.completedFuture(new PlanWindow(List.of())), () -> {});
        }
        long first = Math.max(0, Projection.segmentAt(minU - (96L + marginBlocks) * 256));
        long last = Math.max(0, Projection.segmentAt(maxU + (96L + marginBlocks) * 256));
        if (last - first >= 5) throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "window requires more than five segments");
        var leases = new ArrayList<PlanLease<SegmentPlan>>();
        CompletableFuture<List<SegmentPlan>> chain = CompletableFuture.completedFuture(List.of());
        try {
            for (long i = first; i <= last; i++) {
                if (!safeSegment(i)) break;
                var lease = requirePlan(i, priority); leases.add(lease);
                chain = chain.thenCombineAsync(lease.future(), (list, plan) -> {
                    var copy = new ArrayList<>(list); copy.add(plan); return List.copyOf(copy);
                }, pump);
            }
        } catch (Throwable failure) {
            leases.forEach(PlanLease::close);
            return new PlanLease<>(UUID.randomUUID(), CompletableFuture.failedFuture(failure), () -> {});
        }
        return outer(chain.thenApply(list -> new PlanWindow(list.stream().filter(p -> p.prefix().terminal().isEmpty()
                || p.prefix().terminal().get().segment() >= p.geometry().key().index()).toList())), () -> leases.forEach(PlanLease::close));
    }

    /**
     * Starts the scheduling pump in <em>demand-only</em> mode: the pump runs so that on-demand
     * planning (which optional integrations depend on) makes progress, but the bulk prewarm of the
     * first segments is deliberately not enqueued.
     *
     * <p>This split exists to break a startup cycle. RoadWeaver blocks inside
     * {@code MinecraftServer.prepareLevels} and asks the wall for its recipe and plans while it does
     * so. If the wall were to spend that window running its own bulk prewarm, both sides would wait
     * on each other. Demand-only startup satisfies every dependency the other side needs without
     * competing for the same native lanes, and {@link #afterRoadBulk()} starts the bulk once the
     * other side has finished.
     */
    public void startupPump() {
        if (closed()) throw closedFailure();
        if (state.putIfAbsent("startup", 1L) != null) return;
        state.put("demand-only", 1L);
        pump.execute(() -> {
            startupTimer = pump.scheduleWithFixedDelay(() -> {
                state.merge("startup-pump-iterations", 1L, Long::sum);
                credits = 4096; deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25); dispatch();
            }, 0, 1, TimeUnit.MILLISECONDS);
            // Demand-only: readiness means "the pump is running", not "the first segments are done".
            // Spawn preparation can still require a segment, and it is served from this pump.
            state.put("startup-ready", 1L);
            // Take over as a self-driving pump the moment server ticks stop arriving,
            // so the server thread and this scheduler can never starve each other.
            startWatchdog();
        });
    }

    /**
     * Enqueues the bulk prewarm of the opening segments. Call only after any blocking startup peer
     * has finished, so the two never contend for the same native lanes.
     *
     * <p>The count comes from {@link GreatWallConfig#prewarmSegmentsTarget()}: an unbounded wall
     * prewarms only its opening segments, while a bounded wall short enough to finish prewarms
     * every segment it will ever build. That is what makes "start the game with the wall already
     * planned" achievable without ever waiting on an unbounded amount of work.
     */
    public void afterRoadBulk() {
        if (closed()) throw closedFailure();
        if (state.putIfAbsent("after-road-bulk", 1L) != null) return;
        pump.execute(() -> {
            long target = prewarmTargetSegments();
            long started = System.nanoTime();
            state.put("prewarm-target-segments", target);
            state.put("prewarm-start-nanos", started);
            // Published for two consumers: the loading overlay, which draws this as a card
            // during level loading, and a startup peer (RoadWeaver) that waits for the
            // prewarm to finish before it begins its own road generation.
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.begin(
                    (int) Math.min(Integer.MAX_VALUE, target), laneState.size());
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.enterPlanning();
            var completedSegments = new java.util.concurrent.atomic.AtomicInteger();
            var prewarm = new ArrayList<PlanLease<SegmentPlan>>();
            for (long i = 0; i < target && safeSegment(i); i++) {
                // Per-segment timing is logged individually: an operator measuring
                // how long one segment takes needs the segments separated, and a
                // single total would hide whether the cost is front-loaded.
                long segmentStarted = System.nanoTime();
                var lease = requirePlan(i, Priority.PLAYER_PREFETCH);
                long segment = i;
                lease.future().whenCompleteAsync((plan, error) -> {
                    long elapsedMs = Math.round((System.nanoTime() - segmentStarted) / 1_000_000.0D);
                    if (error == null) {
                        // Counted even when a segment fails: the card shows how much of the
                        // prewarm is behind us, and a failed segment is behind us.
                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setCompleted(
                                completedSegments.incrementAndGet());
                        GreatWallMod.LOGGER.info("[GreatWall:S1C] PREWARM_SEGMENT segment={} elapsedMs={}",
                                segment, elapsedMs);
                    } else {
                        GreatWallMod.LOGGER.warn("[GreatWall:S1C] PREWARM_SEGMENT_FAILED segment={} elapsedMs={} reason={}",
                                segment, elapsedMs, error.toString());
                    }
                }, pump);
                prewarm.add(lease);
            }
            state.put("prewarm-requested-segments", (long) prewarm.size());
            GreatWallMod.LOGGER.info("[GreatWall:S1C] PREWARM_BEGIN requested={} target={}",
                    prewarm.size(), target);
            // A dedicated driver for the duration of the prewarm.
            //
            // Until now the only drives were the server tick and the stall watchdog. The
            // startup timer that originally filled this role is cancelled by the first tick
            // (see tick()), so once the server is ticking the prewarm advances only as often
            // as ticks arrive - and a measured session recorded 257 watchdog rescues, meaning
            // the tick stopped arriving for three seconds at a time, repeatedly, while a
            // startup peer held the server thread.
            //
            // The prewarm is bounded work with a known end, and nothing else in this mod
            // competes for the native lanes while it runs, so it can drive itself. The timer
            // is cancelled when the prewarm settles, on either outcome.
            prewarmTimer = pump.scheduleWithFixedDelay(() -> {
                if (closed() || state.containsKey("bulk-ready") || state.containsKey("bulk-failed")) return;
                state.merge("prewarm-driver-iterations", 1L, Long::sum);
                credits = 4096;
                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25);
                dispatch();
            }, 0, 1, TimeUnit.MILLISECONDS);
            CompletableFuture.allOf(prewarm.stream().map(PlanLease::future).toArray(CompletableFuture[]::new))
                    .whenCompleteAsync((value, error) -> {
                        long totalMs = Math.round((System.nanoTime() - started) / 1_000_000.0D);
                        // Stop the dedicated driver first: the prewarm is over on both paths,
                        // and a timer that keeps granting credits would spin against a queue
                        // that no longer has prewarm work in it.
                        stopPrewarmTimer();
                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(error == null);
                        prewarm.forEach(PlanLease::close);
                        state.put(error == null ? "bulk-ready" : "bulk-failed", 1L);
                        state.put("prewarm-total-millis", totalMs);
                        if (error == null) {
                            // The concurrency numbers are included so one session answers whether
                            // the hardware-derived limits actually took effect. Comparing this
                            // line against the previous 22.2-minute run is the point of the
                            // change, and without the limits recorded here a slower run would
                            // not say whether the scheduler or the terrain was responsible.
                            GreatWallMod.LOGGER.info("[GreatWall:S1C] PREWARM_COMPLETE segments={} totalMs={} "
                                            + "avgMsPerSegment={} geometryInflight={} lanes={}",
                                    prewarm.size(), totalMs,
                                    prewarm.isEmpty() ? 0L : totalMs / prewarm.size(),
                                    recipe.config().maxGeometryInflight(), laneState.size());
                        } else {
                            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PREWARM_FAILED segments={} totalMs={} reason={}",
                                    prewarm.size(), totalMs, error.toString());
                        }
                        if (error != null && startupTimer != null) startupTimer.cancel(false);
                    }, pump);
        });
    }

    /**
     * Stops the prewarm's dedicated driver, if one is running.
     *
     * <p>Separate from the prewarm's own completion callback so {@link #close()} also
     * releases it: a session that ends mid-prewarm must not leave a timer holding the
     * scheduler's executor open.
     */
    private void stopPrewarmTimer() {
        var timer = prewarmTimer;
        prewarmTimer = null;
        if (timer != null) timer.cancel(false);
    }

    /**
     * Segments to prewarm.
     *
     * <p>The configured value wins when set; otherwise a bounded wall at or below
     * the full-prewarm threshold is prewarmed completely and everything else keeps
     * the historical opening-segment count. The config is read here rather than at
     * construction so a Mod Menu edit applies to the next world without a restart.
     */
    private long prewarmTargetSegments() {
        try {
            long configured = GreatWallConfig.get().prewarmSegmentsTarget();
            return configured > 0L ? configured : STARTUP_PREWARM_SEGMENTS;
        } catch (RuntimeException | LinkageError unavailable) {
            // A broken client config must not stop planning; fall back to the opening segments.
            state.put("prewarm-config-unavailable", 1L);
            return STARTUP_PREWARM_SEGMENTS;
        }
    }

    public void tick(SliceBudget budget) {
        Objects.requireNonNull(budget);
        if (closed()) return;
        // Only the most recent server budget is retained; a stalled pump cannot queue unbounded ticks.
        state.put("tick-operations", (long) budget.maxOperations()); state.put("tick-deadline", budget.deadlineNanos());
        state.put("last-tick-nanos", System.nanoTime());
        if (state.putIfAbsent("tick-enqueued", 1L) != null) return;
        pump.execute(() -> {
            state.remove("tick-enqueued");
            if (startupTimer != null) {
                startupTimer.cancel(false);
                startupTimer = null;
                state.put("startup-handoff", 1L);
            }
            credits = Math.toIntExact(Math.min(65536, state.getOrDefault("tick-operations", 0L)));
            deadline = state.getOrDefault("tick-deadline", 0L);
            if (state.containsKey("bulk-ready") && !state.containsKey("background-terminal") && backgroundLease == null && safeSegment(background)) {
                backgroundLease = requirePlan(background++, Priority.BACKGROUND);
                var lease = backgroundLease;
                lease.future().whenCompleteAsync((v, e) -> {
                    if (v != null && (v.geometry().terminal().isPresent() || v.prefix().terminal().isPresent())) state.put("background-terminal", 1L);
                    lease.close(); backgroundLease = null;
                }, pump);
            }
            dispatch();
        });
    }

    /**
     * Upper bound on how long the scheduler may go without a server tick before
     * it re-arms its own timer.
     *
     * <h2>Why this exists</h2>
     * {@link #tick} cancels {@code startupTimer} on its first call and takes over
     * as the only source of {@code credits}/{@code deadline}. That is fine while
     * the server thread keeps ticking, but the server thread is also the thread
     * that blocks on a synchronous chunk load. A real session showed the two
     * starving each other: the server waited for a chunk whose features step was
     * parked on this scheduler, while this scheduler waited for a server tick
     * that could not arrive until that chunk resolved. Neither side has a
     * timeout, so the pair stayed stuck for as long as the session lived.
     *
     * <p>The watchdog restores the self-driving pump that {@code startupTimer}
     * originally provided, but only while ticks have actually stopped, so the
     * normal case keeps using the server's own budget rather than competing with
     * it for the native lanes.
     */
    private static final long WATCHDOG_STALL_NANOS =
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                    Math.max(500L, Long.getLong("greatwall.planner.watchdogStallMs", 3_000L)));
    private ScheduledFuture<?> watchdogTimer;

    /**
     * Starts the stall watchdog. Called once the scheduler is installed.
     *
     * <p>Cheap by construction: the task only inspects a timestamp and returns
     * unless ticks have genuinely stopped.
     */
    public void startWatchdog() {
        if (closed()) throw closedFailure();
        if (state.putIfAbsent("watchdog", 1L) != null) return;
        pump.execute(() -> {
            try {
                watchdogTimer = pump.scheduleWithFixedDelay(this::watchdogCheck, 1, 1,
                        java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                if (!closed()) state.put("watchdog-rejected", 1L);
            }
        });
    }

    private void watchdogCheck() {
        if (closed()) return;
        long last = state.getOrDefault("last-tick-nanos", 0L);
        // Before the first tick the startup timer is still the driver, so there is
        // nothing to rescue yet.
        if (last == 0L) return;
        if (System.nanoTime() - last < WATCHDOG_STALL_NANOS) return;
        // Ticks have stopped. Supply the budget ourselves until they resume.
        state.merge("watchdog-rescues", 1L, Long::sum);
        credits = 4096;
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25);
        dispatch();
    }

    public Map<UUID, JobProgress> progress() { return Map.copyOf(progress); }
    public Map<String, FailureEvidence> failures() { return Map.copyOf(failures); }
    public Map<String, Long> diagnostics() {
        var result = new HashMap<>(state);
        for (var lane : lanes) lane.diagnostics().forEach((key, value) -> result.put("lane-" + lane.index() + "-" + key, value));
        return Map.copyOf(result);
    }
    public CompletableFuture<Void> drain() { return drained.copy(); }

    /**
     * Publishes the resolved corridor-structure obstacle index to the ridge planner. Must be called
     * after the planning-time structure scan settles and before {@link #afterRoadBulk()} starts the
     * bulk prewarm, so the detour stage observes the final index.
     */
    public void setObstacles(StructureObstacleIndex index) {
        ridge.setObstacles(index);
    }

    public SegmentGeometry computeGeometryNow(long segment, NaturalTerrainOracle diagnosticOracle) {
        key(segment);
        if (!diagnosticOracle.supportsSynchronousDiagnostics()) {
            throw new IllegalStateException("synchronous geometry is restricted to explicitly supplied analytic fixtures");
        }
        String thread = Thread.currentThread().getName().toLowerCase(java.util.Locale.ROOT);
        if (thread.contains("worldgen") || thread.contains("c2me")) {
            throw new IllegalStateException("synchronous diagnostic computation is forbidden on worldgen threads");
        }
        long started = System.nanoTime();
        try (var oracle = diagnosticOracle) {
            var center = computeRoute(segment, oracle);
            var previous = segment == 0 ? Optional.<RouteShape>empty() : Optional.of(computeRoute(segment - 1, oracle));
            var next = safeSegment(segment + 1) ? Optional.of(computeRoute(segment + 1, oracle)) : Optional.<RouteShape>empty();
            var cursor = hydrology.begin(new RouteNeighborhood(center, previous, next), oracle, 96000);
            while (hydrology.advance(cursor, 64) != WorkState.DONE) if (closed()) throw closedFailure();
            return geometry(center, hydrology.finish(cursor), cursor.logicalColumns(), next.isEmpty());
        } finally {
            state.merge("synchronous-count", 1L, Long::sum);
            state.merge("synchronous-max-nanos", System.nanoTime() - started, Math::max);
        }
    }

    private RouteShape computeRoute(long segment, NaturalTerrainOracle oracle) {
        var c = ridge.begin(key(segment), oracle);
        while (ridge.advance(c, 64) != WorkState.DONE) if (closed()) throw closedFailure();
        return ridge.finish(c);
    }

    private void startGeometry(SegmentKey key, Priority priority, CompletableFuture<SegmentGeometry> result) {
        activeGeometry++;
        result.whenCompleteAsync((v, error) -> { activeGeometry--; drainGeometry(); }, pump);
        ioRead(() -> cache.readGeometry(key)).whenCompleteAsync((cached, error) -> {
            if (closed()) { result.completeExceptionally(closedFailure()); return; }
            if (error == null && cached.isPresent()) { result.complete(cached.get()); return; }
            var center = requireRoute(key.index(), priority);
            var previous = key.index() == 0 ? null : requireRoute(key.index() - 1, priority);
            var next = safeSegment(key.index() + 1) ? requireRoute(key.index() + 1, priority) : null;
            var previousFuture = previous == null ? CompletableFuture.<RouteShape>completedFuture(null) : previous.future();
            var nextFuture = next == null ? CompletableFuture.<RouteShape>completedFuture(null) : next.future();
            var owned = new ArrayList<PlanLease<?>>(); owned.add(center); if (previous != null) owned.add(previous); if (next != null) owned.add(next);
            center.future().thenCombineAsync(previousFuture, (a, b) -> new RouteNeighborhood(a, Optional.ofNullable(b), Optional.empty()), pump)
                    .thenCombineAsync(nextFuture, (a, b) -> new RouteNeighborhood(a.center(), a.previous(), Optional.ofNullable(b)), pump)
                    .whenCompleteAsync((routes, failure) -> {
                        if (failure != null) { owned.forEach(PlanLease::close); result.completeExceptionally(failure); return; }
                        var job = new HydroJob(routes, priority, result);
                        result.whenComplete((v, e) -> owned.forEach(PlanLease::close));
                        enqueue(job);
                    }, pump);
        }, pump);
    }

    private void drainGeometry() {
        while (!closed() && activeGeometry < recipe.config().maxGeometryInflight() && !geometryQueue.isEmpty()) geometryQueue.removeFirst().run();
    }

    private void preparePlan(SegmentKey key, Priority priority, Pair inputs, CompletableFuture<SegmentPlan> result, List<PlanLease<?>> initial) {
        var owned = new ArrayList<PlanLease<?>>(initial);
        CompletableFuture<GeometryWindow> window = CompletableFuture.completedFuture(new GeometryWindow(List.of(inputs.geometry), List.of(inputs.prefix)));
        for (long neighbor : new long[] {key.index() - 1, key.index() + 1}) {
            if (neighbor < 0 || !safeSegment(neighbor) || inputs.geometry.terminal().isPresent() && neighbor > key.index()) continue;
            var geometry = requireGeometry(neighbor, priority); var prefix = prefixes.requireBefore(neighbor);
            owned.add(geometry); owned.add(prefix);
            var pair = geometry.future().thenCombineAsync(prefix.future(), Pair::new, pump);
            window = window.thenCombineAsync(pair, (w, p) -> {
                if (p.prefix.beforeSegment() != p.geometry.key().index()) return w;
                var gs = new ArrayList<>(w.segments()); gs.add(p.geometry);
                var ps = new ArrayList<>(w.prefixes()); ps.add(p.prefix);
                return new GeometryWindow(gs, ps);
            }, pump);
        }
        result.whenComplete((v, e) -> owned.forEach(PlanLease::close));
        window.whenCompleteAsync((w, error) -> {
            if (error != null) { result.completeExceptionally(error); return; }
            try {
                long from = inputs.prefix.lengthBeforeQ8(), to = from + inputs.geometry.localSQ8().getLast();
                var provisional = sites.schedule(w, from, to);
                var points = heights.samplePoints(inputs.geometry, inputs.prefix, provisional);
                enqueue(new ContractJob(inputs, provisional, points, priority, result));
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        }, pump);
    }

    private SegmentGeometry geometry(RouteShape route, List<WaterEvidence> water, int hydroColumns, boolean last) {
        Optional<Terminal> terminal = water.stream().filter(w -> w.classification() == WaterClass.WIDE
                && w.crossing().fromQ8() >= 0 && w.crossing().fromQ8() < route.localSQ8().getLast())
                .map(w -> new Terminal(route.key().index(), w.crossing().fromQ8(), TerminalKind.OCEAN))
                .min(Comparator.comparingLong(Terminal::localSQ8));
        if (terminal.isEmpty() && last) terminal = Optional.of(new Terminal(route.key().index(), route.localSQ8().getLast(), TerminalKind.WORLD_LIMIT));
        var g = new SegmentGeometry(route.key(), route.points(), route.localSQ8(), water, terminal, route.start(), route.end(),
                route.searchColumns() + route.boundaryColumns() + hydroColumns, route.degradation(), "0".repeat(64));
        return new SegmentGeometry(g.key(), g.points(), g.localSQ8(), g.water(), g.terminal(), g.start(), g.end(), g.logicalColumns(), g.degradation(),
                DeterministicHash.sha256(CanonicalCodec.geometry(g)));
    }

    private static SegmentPlan hashedPlan(SegmentGeometry g, PrefixSummary p, StationTable t, List<SiteRecord> sites) {
        var plan = new SegmentPlan(g, p, t, sites, "0".repeat(64));
        return new SegmentPlan(g, p, t, sites, DeterministicHash.sha256(CanonicalCodec.plan(plan)));
    }

    private void enqueue(Job<?> job) {
        if (closed()) { job.result.completeExceptionally(closedFailure()); return; }
        pending.add(job); activeJobs.put(job.id, job);
        progress.put(job.id, new JobProgress(job.id, job.phase, 0, 1, true));
        dispatch();
    }

    private void promote(long segment, Priority priority) {
        Objects.requireNonNull(priority);
        pump.execute(() -> {
            for (var job : activeJobs.values()) {
                long index = switch (job) {
                    case RouteJob route -> route.key.index();
                    case HydroJob hydro -> hydro.routes.center().key().index();
                    case ContractJob contract -> contract.inputs.geometry.key().index();
                    default -> throw new IllegalStateException("unknown job");
                };
                if (index == segment && priority.compareTo(job.priority) < 0) {
                    boolean queued = pending.remove(job);
                    job.priority = priority;
                    if (queued) pending.add(job);
                }
            }
        });
    }

    private void dispatch() {
        if (closed()) return;
        for (int lane = 0; lane < laneState.size() && credits > 0 && System.nanoTime() < deadline; lane++) {
            var slot = laneState.get(lane);
            if (slot.busy) continue;
            var nativeLane = lanes.get(lane);
            if (!slot.ready) {
                // Admission for native oracle construction.
                //
                // This used to be a constant of four, on the theory that it kept N native
                // RandomState instances from peaking together. It did not: every lane retains
                // its oracle for the life of the session, so all N exist at steady state
                // regardless, and the cap only spread their creation across batches - measured
                // at about 232 seconds of a startup prewarm on sixteen cores spent before any
                // planning began. The bound is now the lane ceiling, so all lanes initialize
                // together and the cost is paid once, concurrently.
                if (!slot.initializing && initializing < LanePolicy.MAX_CONCURRENT_INITIALIZATIONS) {
                    slot.initializing = true;
                    initializing++;
                    nativeLane.start().whenCompleteAsync((value, error) -> {
                        initializing--;
                        if (error != null) {
                            state.merge("lane-initialization-failures", 1L, Long::sum);
                            recordFailure(error, "NATIVE_INITIALIZATION", -1, 0);
                            routeFlights.forEach((key, future) -> future.completeExceptionally(error));
                            geometryFlights.forEach((key, future) -> future.completeExceptionally(error));
                            planFlights.forEach((key, future) -> future.completeExceptionally(error));
                            close();
                        } else { slot.ready = true; dispatch(); }
                    }, pump);
                }
                continue;
            }
            if (slot.job == null) slot.job = pending.poll();
            if (slot.job == null) continue;
            int operations = Math.min(64, credits); credits -= operations;
            var job = slot.job; slot.busy = true;
            nativeLane.submit(oracle -> {
                Throwable error = null; boolean done = false; Object value = null;
                long started = System.nanoTime();
                try {
                    if (closed()) throw closedFailure();
                    // Hand the whole batch budget to one advance call rather than looping
                    // with a limit of one primitive at a time.
                    //
                    // The loop this replaces issued `advance(oracle, 1)` up to 64 times, and
                    // the difference is not a micro-optimisation. A job's advance is a
                    // resumable state machine, and several of its steps need a terrain column
                    // sampled before the state machine can progress at all: RidgePlanner.sample
                    // issues begin, then returns, then needs one advance per inner step, then
                    // finish - three or more separate calls, each of which had to be its own
                    // batch. With a limit of one, every one of those calls ended the loop body
                    // having done no recoverable work, so the batch budget was spent on
                    // bookkeeping between steps rather than on the steps themselves.
                    //
                    // A measured session ran 1,337,565 batches at 0.995 ms each - 22.2 minutes,
                    // matching its wall clock almost exactly - for work that the same code
                    // performs directly in computeGeometryNow via advance(cursor, 64). Passing
                    // the batch's own operation count lets a column sampling sequence complete
                    // inside a single batch instead of spanning three.
                    //
                    // The 25 ms target still applies, just coarser: it is now checked once per
                    // batch rather than once per primitive, so an unusually slow primitive can
                    // overrun it. That is already the case - the same session recorded a
                    // 108.6 ms batch and only 9 batches over 40 ms out of 1.34 million - and the
                    // deadline exists to keep a batch from monopolising a lane, not to bound a
                    // single primitive.
                    done = job.advance(oracle, operations);
                    if (done) value = job.value();
                } catch (Throwable failure) { error = failure; done = true; }
                long duration = System.nanoTime() - started;
                state.merge("max-batch-nanos", duration, Math::max);
                state.merge("batch-count", 1L, Long::sum);
                if (duration > TimeUnit.MILLISECONDS.toNanos(40)) state.merge("over-40ms-batches", 1L, Long::sum);
                return new Batch(done, error, value);
            }).whenComplete((batch, submitError) -> {
                try { pump.execute(() -> {
                    slot.busy = false;
                    if (closed()) { job.result.completeExceptionally(closedFailure()); return; }
                    // A failed job may still own an unfinished native cursor. Never reuse that lane.
                    Throwable failure = submitError != null ? submitError : batch.failure();
                    if (failure != null) {
                        recordFailure(failure, job.phase, segmentOf(job), job.operations);
                        job.result.completeExceptionally(failure);
                        close();
                        return;
                    }
                    boolean finished = batch.finished();
                    if (finished) {
                        slot.job = null; activeJobs.remove(job.id); progress.remove(job.id);
                        job.complete(batch.value());
                    } else {
                        progress.put(job.id, new JobProgress(job.id, job.phase, job.operations, Long.MAX_VALUE, true));
                    }
                    dispatch();
                }); } catch (RejectedExecutionException stopped) { job.result.completeExceptionally(closedFailure()); }
            });
        }
    }

    private <T> CompletableFuture<T> flight(ConcurrentHashMap<SegmentKey, CompletableFuture<T>> flights, SegmentKey key) {
        var future = new CompletableFuture<T>();
        if (flights.putIfAbsent(key, future) != null) throw new IllegalStateException("cache started duplicate loader");
        future.whenComplete((v, error) -> flights.remove(key, future)); return future;
    }

    private <T> PlanLease<T> acquire(CacheKey key, long upper, Class<T> type, Supplier<CompletionStage<Object>> loader) {
        if (closed()) return new PlanLease<>(UUID.randomUUID(), CompletableFuture.failedFuture(closedFailure()), () -> {});
        var acquired = new CompletableFuture<PlanLease<Object>>();
        UUID id = UUID.randomUUID();
        boolean[] accepted = {false};
        state.compute("acquisitions", (k, old) -> {
            long count = old == null ? 0 : old;
            if (count >= 4096) return count;
            accepted[0] = true; return count + 1;
        });
        if (!accepted[0]) return new PlanLease<>(id, CompletableFuture.failedFuture(
                new WallFailure(FailureCode.CAPACITY, "planner acquisition capacity")), () -> {});
        acquisitions.put(id, acquired);
        acquired.whenComplete((v, e) -> {
            acquisitions.remove(id); state.compute("acquisitions", (k, old) -> old - 1);
        });
        pump.execute(() -> {
            if (closed()) { acquired.completeExceptionally(closedFailure()); return; }
            Runnable start = () -> startAcquire(key, upper, loader, acquired);
            if (key.kind.equals("plan")) planAdmissions.add(start);
            else if (key.kind.equals("geometry")) geometryAdmissions.add(start);
            else start.run();
            drainAdmissions();
        });
        CompletableFuture<T> view = acquired.thenCompose(lease -> lease.future().thenApply(type::cast));
        return outer(view, () -> acquired.thenAccept(PlanLease::close));
    }

    private void startAcquire(CacheKey key, long upper, Supplier<CompletionStage<Object>> loader,
            CompletableFuture<PlanLease<Object>> result) {
        if (key.kind.equals("plan")) admittedPlans++;
        if (key.kind.equals("geometry")) admittedGeometries++;
        memory.acquire(key, upper, loader).whenCompleteAsync((lease, failure) -> {
            if (key.kind.equals("plan")) admittedPlans--;
            if (key.kind.equals("geometry")) admittedGeometries--;
            if (failure != null) result.completeExceptionally(failure);
            else if (!result.complete(lease)) lease.close();
            drainAdmissions();
        }, pump);
    }

    private void drainAdmissions() {
        // Reserve dependency headroom before allocating a parent plan/geometry reservation.
        while (!closed() && admittedGeometries < 4 && !geometryAdmissions.isEmpty()) geometryAdmissions.removeFirst().run();
        while (!closed() && admittedPlans < 4 && !planAdmissions.isEmpty()) planAdmissions.removeFirst().run();
    }

    private <T> PlanLease<T> outer(CompletionStage<T> shared, Runnable release) {
        UUID id = UUID.randomUUID();
        boolean[] admitted = {false};
        state.compute("leases", (k, old) -> {
            long count = old == null ? 0 : old;
            if (closed() || count >= 4096) return count;
            admitted[0] = true; return count + 1;
        });
        if (!admitted[0]) {
            release.run();
            return new PlanLease<>(id, CompletableFuture.failedFuture(closed() ? closedFailure()
                    : new WallFailure(FailureCode.CAPACITY, "planner lease limit")), () -> {});
        }
        var lease = new PlanLease<T>(id, shared, () -> {
            callerLeases.remove(id); state.compute("leases", (k, old) -> old - 1); release.run();
        });
        callerLeases.put(id, lease);
        if (closed()) lease.close();
        return lease;
    }

    private SegmentKey key(long segment) {
        if (closed()) throw closedFailure();
        if (!safeSegment(segment)) throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "segment exceeds complete frozen envelope");
        return new SegmentKey(recipe.recipeHash(), recipe.dimension(), segment);
    }

    /**
     * Whether a segment index is inside the region this world may build.
     *
     * <h2>Two limits, both required</h2>
     * The frozen envelope is a generation-semantics boundary: past it the
     * projection and carrier maths would leave the dimension's horizontal limit,
     * so it can never be relaxed.
     *
     * <p>The configured length is a player choice, applied here because this
     * method is the single gate every segment access passes through — planning,
     * background prewarm, neighbour lookups and the terminal check all call it.
     * Enforcing the length anywhere else would leave paths that bypass it.
     *
     * <p>An unconfigured wall keeps the historical behaviour exactly: the whole
     * frozen envelope is buildable, and no configured terminal is ever produced.
     */
    private boolean safeSegment(long segment) {
        if (segment < 0) return false;
        if (!withinConfiguredLength(segment)) return false;
        try {
            // 512 carrier + 512 ridge + 96 component + 48 reference side-column envelope.
            long radius = 1168L * 256, limit = recipe.bounds().horizontalLimit() * 256L;
            for (long index : new long[] {segment, Math.addExact(segment, 1)}) {
                long u = Math.multiplyExact(index, 2048L * 256);
                long x = Math.addExact(recipe.axis().origin().x() * 256L, FixedMath.mulDiv(u, recipe.axis().exQ30(), 1L << 30));
                long z = Math.addExact(recipe.axis().origin().z() * 256L, FixedMath.mulDiv(u, recipe.axis().ezQ30(), 1L << 30));
                if (x - radius < -limit || x + radius > limit || z - radius < -limit || z + radius > limit) return false;
            }
            return true;
        } catch (ArithmeticException overflow) { return false; }
    }

    /**
     * Configured length gate.
     *
     * <p>{@code configuredSegments} is the number of whole 2048-block segments the
     * requested length spans, so segment {@code n} starts at {@code n * 2048}
     * blocks along the axis. Segment {@code configuredSegments} is therefore the
     * first one entirely past the requested length and is refused.
     *
     * <p>A configuration that cannot be read leaves the wall unbounded rather
     * than silently truncating it: a missing file must never shorten a world.
     */
    private boolean withinConfiguredLength(long segment) {
        long allowed = configuredSegmentLimit();
        return allowed <= 0L || segment < allowed;
    }

    /** Cached because this is called for every segment access on the planner's hot path. */
    private volatile long configuredSegmentLimitCache = Long.MIN_VALUE;

    private long configuredSegmentLimit() {
        long cached = configuredSegmentLimitCache;
        if (cached != Long.MIN_VALUE) return cached;
        long limit;
        try {
            limit = GreatWallConfig.get().configuredSegments();
        } catch (RuntimeException | LinkageError unavailable) {
            // Fail open: an unreadable config must not shorten an existing wall.
            state.put("wall-length-config-unavailable", 1L);
            limit = 0L;
        }
        configuredSegmentLimitCache = limit;
        return limit;
    }

    /** Segments this wall will build; {@code 0} when unbounded. Exposed for diagnostics. */
    public long configuredSegmentLimitForDiagnostics() { return configuredSegmentLimit(); }

    private <T> CompletableFuture<Optional<T>> ioRead(IoRead<T> operation) {
        if (state.containsKey("cache-disabled")) return CompletableFuture.completedFuture(Optional.empty());
        var result = new CompletableFuture<Optional<T>>();
        try {
            submitIo(() -> {
                try { result.complete(operation.read()); }
                catch (IOException error) { state.put("cache-disabled", 1L); result.complete(Optional.empty()); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException full) { state.put("cache-disabled", 1L); result.complete(Optional.empty()); }
        return result;
    }

    private void submitIo(Runnable task) {
        if (closed()) throw new RejectedExecutionException("planner closed");
        boolean[] admitted = {false};
        state.compute("io-pending", (k, old) -> {
            long count = old == null ? 0 : old;
            if (count >= 128) return count;
            admitted[0] = true; return count + 1;
        });
        if (!admitted[0]) throw new RejectedExecutionException("planner cache IO queue capacity");
        try {
            pump.execute(() -> {
                ioQueue.add(() -> { try { task.run(); } finally { state.compute("io-pending", (k, old) -> old - 1); } });
                drainIo();
            });
        } catch (RuntimeException rejected) { state.compute("io-pending", (k, old) -> old - 1); throw rejected; }
    }

    private void drainIo() {
        if (activeIo != 0 || ioQueue.isEmpty() || closed()) return;
        activeIo = 1; Runnable next = ioQueue.removeFirst();
        try { io.execute(() -> {
            try { next.run(); }
            finally {
                try { pump.execute(() -> { activeIo = 0; drainIo(); }); }
                catch (RejectedExecutionException ignored) { /* Closing already completed public promises. */ }
            }
        }); } catch (RuntimeException rejected) {
            activeIo = 0; state.put("cache-disabled", 1L);
            var failure = new WallFailure(FailureCode.IO_FAILED, "IO executor rejected a required continuation", rejected);
            recordFailure(failure, "CACHE_IO", -1, 0);
            routeFlights.forEach((key, future) -> future.completeExceptionally(failure));
            geometryFlights.forEach((key, future) -> future.completeExceptionally(failure));
            planFlights.forEach((key, future) -> future.completeExceptionally(failure));
            close();
        }
    }

    private void persist(SegmentGeometry value) {
        if (!state.containsKey("cache-disabled") && !closed()) try {
            submitIo(() -> { try { cache.writeGeometry(value); } catch (IOException error) { state.put("cache-disabled", 1L); } });
        } catch (RejectedExecutionException full) { state.put("cache-disabled", 1L); }
    }
    private void persist(SegmentPlan value) {
        if (!state.containsKey("cache-disabled") && !closed()) try {
            submitIo(() -> { try { cache.writePlan(value); } catch (IOException error) { state.put("cache-disabled", 1L); } });
        } catch (RejectedExecutionException full) { state.put("cache-disabled", 1L); }
    }

    private static long retainedBytes(Object value) {
        if (value instanceof RouteShape r) return 4096L + 80L * r.points().size();
        if (value instanceof SegmentGeometry g) {
            long result = 4096L + 80L * g.points().size();
            for (var w : g.water()) result += 512L + 48L * (w.banks().size() + w.piers().size());
            return result;
        }
        if (value instanceof SegmentPlan p) return retainedBytes(p.geometry()) + 4096L + 96L * p.stations().stations().size()
                + 128L * p.stations().breaks().size() + 2048L * p.sites().size();
        throw new IllegalArgumentException("unknown cached object");
    }

    private boolean closed() { return state.containsKey("closed"); }
    private void recordFailure(Throwable error, String phase, long segment, long operations) {
        Throwable root = error;
        FailureCode code = null;
        while (true) {
            if (code == null && root instanceof WallFailure failure) code = failure.code();
            if (root.getCause() == null) break;
            root = root.getCause();
        }
        var text = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(text));
        failures.putIfAbsent("first", new FailureEvidence(phase, segment, operations,
                code == null ? FailureCode.INVALID_RESOURCE : code,
                root.getClass().getName(), String.valueOf(root.getMessage()), text.toString()));
    }
    private static long segmentOf(Job<?> job) {
        return switch (job) {
            case PlannerScheduler.RouteJob route -> route.key.index();
            case PlannerScheduler.HydroJob hydro -> hydro.routes.center().key().index();
            case PlannerScheduler.ContractJob contract -> contract.inputs.geometry.key().index();
            default -> throw new IllegalStateException("unknown job");
        };
    }
    private WallFailure closedFailure() {
        var first = failures.get("first");
        return first == null ? new WallFailure(FailureCode.CLOSED, "planner session closed")
                : new WallFailure(first.code(), "planner failed in " + first.phase() + " segment="
                        + first.segment() + ": " + first.exceptionType() + ": " + first.message());
    }
    /**
     * Refuses to run on a thread that the planner itself owns.
     *
     * <p>Used by callers that block on planner futures. Waiting on this scheduler from one of its own
     * threads cannot succeed — the work being awaited can only progress on the pump that is blocked —
     * so the hazard is converted into an immediate, named failure.
     */
    public void assertOutsidePlanningThread(String purpose) {
        String thread = Thread.currentThread().getName();
        if (thread.startsWith(pumpThreadPrefix) || thread.startsWith(laneThreadPrefix)) {
            throw new IllegalStateException(purpose + " must not block on the planner from a planner-owned thread: " + thread);
        }
    }

    private static ThreadFactory factory(String name) {
        return runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; };
    }

    @Override public void close() {
        if (state.putIfAbsent("closed", 1L) != null) return;
        routeFlights.forEach((key, future) -> future.completeExceptionally(closedFailure()));
        geometryFlights.forEach((key, future) -> future.completeExceptionally(closedFailure()));
        planFlights.forEach((key, future) -> future.completeExceptionally(closedFailure()));
        acquisitions.forEach((id, future) -> future.completeExceptionally(closedFailure()));
        prefixes.close(); callerLeases.forEach((id, lease) -> lease.close()); memory.close();
        pump.execute(() -> {
            if (startupTimer != null) startupTimer.cancel(false);
            if (watchdogTimer != null) watchdogTimer.cancel(false);
            if (prewarmTimer != null) prewarmTimer.cancel(false);
            pending.forEach(job -> job.result.completeExceptionally(closedFailure()));
            pending.clear(); geometryQueue.clear(); ioQueue.clear(); planAdmissions.clear(); geometryAdmissions.clear();
            activeJobs.clear(); progress.clear();
            lanes.forEach(OracleLane::close);
            CompletableFuture.allOf(lanes.stream().map(OracleLane::drain).toArray(CompletableFuture[]::new))
                    .whenComplete((value, error) -> {
                        if (error == null) drained.complete(null); else drained.completeExceptionally(error);
                    });
            pump.shutdown();
        });
    }

    private record CacheKey(String kind, long segment) {}
    private record Pair(SegmentGeometry geometry, PrefixSummary prefix) {}
    @FunctionalInterface private interface IoRead<T> { Optional<T> read() throws IOException; }
    private record Batch(boolean finished, Throwable failure, Object value) {}
    private static final class Lane { private Job<?> job; private boolean busy, initializing, ready; }

    private abstract class Job<T> {
        private final UUID id = UUID.randomUUID();
        private Priority priority;
        private final long sequence = PlannerScheduler.this.sequence++;
        private final String phase;
        final CompletableFuture<T> result;
        long operations;
        private Job(Priority priority, String phase, CompletableFuture<T> result) {
            this.priority = Objects.requireNonNull(priority); this.phase = phase; this.result = result;
        }
        abstract boolean advance(NaturalTerrainOracle oracle, int limit);
        abstract T value();
        @SuppressWarnings("unchecked") private void complete(Object value) { result.complete((T) value); }
    }

    private final class RouteJob extends Job<RouteShape> {
        private final SegmentKey key;
        private RidgePlanner.Cursor cursor;
        private RouteJob(SegmentKey key, Priority priority, CompletableFuture<RouteShape> result) {
            super(priority, "ROUTE", result); this.key = key;
        }
        @Override boolean advance(NaturalTerrainOracle oracle, int limit) {
            if (cursor == null) cursor = ridge.begin(key, oracle);
            operations += limit; return ridge.advance(cursor, limit) == WorkState.DONE;
        }
        @Override RouteShape value() { return ridge.finish(cursor); }
    }

    private final class HydroJob extends Job<SegmentGeometry> {
        private final RouteNeighborhood routes;
        private HydrologyClassifier.Cursor cursor;
        private HydroJob(RouteNeighborhood routes, Priority priority, CompletableFuture<SegmentGeometry> result) {
            super(priority, "HYDROLOGY", result); this.routes = routes;
            result.thenAcceptAsync(PlannerScheduler.this::persist, pump);
        }
        @Override boolean advance(NaturalTerrainOracle oracle, int limit) {
            if (cursor == null) cursor = hydrology.begin(routes, oracle, 96000);
            operations += limit;
            // A segment whose hydrology exceeds this operation ceiling terminates
            // its remaining unresolved water as WIDE instead of burning unbounded
            // time certifying an island that terminates either way. Already
            // certified NARROW/WIDE/SUPPORT_GAP evidence is preserved.
            if (operations > HYDROLOGY_OPERATION_CEILING && !cursor.done()) {
                hydrology.forceComplete(cursor);
                return true;
            }
            return hydrology.advance(cursor, limit) == WorkState.DONE;
        }
        @Override SegmentGeometry value() { return geometry(routes.center(), hydrology.finish(cursor), cursor.logicalColumns(), routes.next().isEmpty()); }
    }

    private final class ContractJob extends Job<SegmentPlan> {
        private final Pair inputs;
        private final List<SiteRecord> provisional;
        private final List<Point2q> points;
        private final Map<Point2q, ColumnSample> columns = new HashMap<>();
        private int index;
        private NaturalTerrainOracle.ColumnCursor column;
        private ContractJob(Pair inputs, List<SiteRecord> provisional, List<Point2q> points, Priority priority, CompletableFuture<SegmentPlan> result) {
            super(priority, "HEIGHT_CONTRACT", result); this.inputs = inputs; this.provisional = provisional; this.points = points;
            result.thenAcceptAsync(PlannerScheduler.this::persist, pump);
        }
        @Override boolean advance(NaturalTerrainOracle oracle, int limit) {
            for (int i = 0; i < limit && index < points.size(); i++) {
                if (closed()) throw closedFailure();
                var p = points.get(index); operations++;
                if (column == null) column = oracle.begin(FixedMath.floorBlock(p.xQ8()), FixedMath.floorBlock(p.zQ8()), SampleKind.REFERENCE);
                else if (!column.done()) oracle.advance(column, 1);
                else { columns.put(p, oracle.finish(column)); column = null; index++; }
            }
            return index == points.size();
        }
        @Override SegmentPlan value() {
            var table = heights.solve(inputs.geometry, inputs.prefix, provisional, columns);
            return hashedPlan(inputs.geometry, inputs.prefix, table, sites.bindHeights(provisional, table));
        }
    }
}
