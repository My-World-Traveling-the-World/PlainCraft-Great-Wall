package local.plaincraft.greatwall.planning;

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

/** 所有规划任务共用同一个推进器；集成方须在持有 FEATURES 区域锁前获取未来结果。 */
public final class PlannerScheduler implements AutoCloseable {
    public record FailureEvidence(String phase, long segment, long operations, FailureCode code,
            String exceptionType, String message, String originalStack) {}
    private static final long MIB = 1024L * 1024;
    private final WorldRecipe recipe;
    private final ConcurrentHashMap<SegmentKey, CompletableFuture<RouteShape>> routeFlights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SegmentKey, CompletableFuture<SegmentGeometry>> geometryFlights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SegmentKey, CompletableFuture<SegmentPlan>> planFlights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, JobProgress> progress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> state = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FailureEvidence> failures = new ConcurrentHashMap<>();
    private final PlannerPump pump;
    private final FineSampleStage fineSamples;
    private final BulkPrewarmStage bulkPrewarm;
    private PlanLease<SegmentPlan> backgroundLease;
    private final PrefixSummaryIndex prefixes;
    private final PlannerCacheIo cacheIo;
    private final AnchorField anchors;
    private final RidgePlanner ridge;
    private final HydrologyClassifier hydrology = new HydrologyClassifier();
    /** 单段水文需要设操作上限，避免极端水面阻塞整个启动；保留已认证的证据。 */
    private static final long HYDROLOGY_OPERATION_CEILING = 4_000_000L;
    private final StartupWallRange startupRange;
    private final long configuredWallLength;
    private final boolean usesLocalWaterBreaks;
    /** 通过线程名前缀识别执行资源，防止任务阻塞等待自身。 */
    private static final String pumpThreadPrefix = "pgw-planner-";
    private static final String laneThreadPrefix = "pgw-oracle-";
    private final HeightContractPlanner heights;
    private final SiteScheduler sites;
    // 准入状态和缓存归协作类拥有，调度器只负责任务推进及生命周期次序。
    private final PlannerAdmissions admissions;
    private final List<OracleLane> lanes;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final List<Lane> laneState;
    private final PriorityQueue<Job<?>> pending = new PriorityQueue<>(Comparator.<Job<?>, Priority>comparing(j -> j.priority)
            .thenComparingLong(j -> j.sequence));
    private final ArrayDeque<Runnable> geometryQueue = new ArrayDeque<>();
    /** 调用线程入队、推进线程出队；持锁仅操作队列，在锁外执行几何计算。 */
    private final Object geometryLock = new Object();
    private final Map<UUID, Job<?>> activeJobs = new HashMap<>();
    private int activeGeometry, credits;
    /** 限制并发初始化，防止多个原生噪声状态同时扩大工作集。 */
    private int initializing;
    private long deadline, sequence, background;
    private ScheduledFuture<?> startupTimer;
    public PlannerScheduler(WorldRecipe recipe, Supplier<NaturalTerrainOracle> oracleFactory, SegmentCacheStore cache, Executor io) {
        this(recipe, Objects.requireNonNull(oracleFactory), null, cache, io);
    }

    /** 构造成功后调度器接管启动通道，负责释放和排空。 */
    public PlannerScheduler(WorldRecipe recipe, List<OracleLane> bootstrapLanes, SegmentCacheStore cache, Executor io) {
        this(recipe, null, List.copyOf(bootstrapLanes), cache, io);
    }

    private PlannerScheduler(WorldRecipe recipe, Supplier<NaturalTerrainOracle> oracleFactory,
            List<OracleLane> bootstrapLanes, SegmentCacheStore cache, Executor io) {
        this.recipe = Objects.requireNonNull(recipe);
        Objects.requireNonNull(cache); Objects.requireNonNull(io);
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
        long wallLength = GreatWallConfig.parameters().wallLengthBlocks();
        this.configuredWallLength = wallLength;
        this.usesLocalWaterBreaks = recipe.config().templateVersion()
                >= local.plaincraft.greatwall.model.WallRecipeRules.INTACT;
        long configuredSegments = GreatWallConfig.segmentsFor(wallLength);
        this.startupRange = StartupWallRange.resolve(wallLength, GreatWallConfig.get().unlimitedPrewarmBlocks(),
                segment -> StartupWallRange.segmentFits(recipe, configuredSegments, segment));
        // 后台从预热窗口尾部继续，不让扩大或缩小窗口造成错段重排。
        this.background = startupRange.segments();
        this.anchors = new AnchorField(recipe); this.ridge = new RidgePlanner(recipe, anchors);
        this.heights = new HeightContractPlanner(recipe); this.sites = new SiteScheduler(recipe);
        this.pump = new PlannerPump(factory("pgw-planner-" + recipe.instance()));
        pump.setRemoveOnCancelPolicy(true);
        this.lanes = bootstrapLanes != null ? List.copyOf(bootstrapLanes)
                : lanes(recipe, oracleFactory, LanePolicy.resolve().startupLaneCount());
        this.laneState = this.lanes.stream().map(ignored -> new Lane()).toList();
        this.admissions = new PlannerAdmissions(recipe.config().segmentBytes(), pump, state, this::closed,
                this::closedFailure, PlannerScheduler::retainedBytes);
        this.cacheIo = new PlannerCacheIo(pump, io, cache, state, this::failCacheIo);
        this.prefixes = new PrefixSummaryIndex(i -> requireGeometry(i, Priority.PLAYER_PREFETCH), pump, cache, cacheIo::execute);
        this.fineSamples = new FineSampleStage(this, pump, prefixes, sites, laneState.size(), geometryFlights::get);
        this.bulkPrewarm = new BulkPrewarmStage(this, pump, state, laneState.size(), recipe.config().maxGeometryInflight());
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
        return admissions.acquire(new PlannerAdmissions.Key("route", segment), MIB, RouteShape.class, () -> {
            var result = flight(routeFlights, key);
            enqueue(new RouteJob(key, priority, result));
            return result.thenApply(v -> v);
        });
    }

    public PlanLease<SegmentGeometry> requireGeometry(long segment, Priority priority) {
        var key = key(segment);
        promote(segment, priority);
        return admissions.acquire(new PlannerAdmissions.Key("geometry", segment), 2 * MIB, SegmentGeometry.class, () -> {
            var result = flight(geometryFlights, key);
            synchronized (geometryLock) { geometryQueue.add(() -> startGeometry(key, priority, result)); }
            drainGeometry();
            return result.thenApply(v -> v);
        });
    }

    public PlanLease<SegmentPlan> requirePlan(long segment, Priority priority) {
        var key = key(segment);
        promote(segment, priority);
        return admissions.acquire(new PlannerAdmissions.Key("plan", segment), 4 * MIB, SegmentPlan.class, () -> {
            var result = flight(planFlights, key);
            var geometry = requireGeometry(segment, priority);
            var prefix = prefixes.requireBefore(segment);
            geometry.future().thenCombineAsync(prefix.future(), Pair::new, pump).whenCompleteAsync((inputs, error) -> {
                if (error != null) { geometry.close(); prefix.close(); result.completeExceptionally(error); return; }
                if (closed()) { geometry.close(); prefix.close(); result.completeExceptionally(closedFailure()); return; }
                if (inputs.prefix.terminal().isPresent() && inputs.prefix.terminal().get().segment() < segment) {
                    // 终止后的段保留几何供编译墙身，但不生成站点、断点及建筑。
                    local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                            "[GreatWall:diag] PLAN_DEAD segment={} terminatedBySegment={} terminalSQ8={} "
                                    + "ownTerminalSQ8={} routeSQ8={} ratioPct={} - body still compiles, "
                                    + "stations/breaks/sites/components all empty",
                            segment, inputs.prefix.terminal().get().segment(),
                            inputs.prefix.terminal().get().localSQ8(),
                            inputs.geometry.terminal().map(Terminal::localSQ8).orElse(-1L),
                            inputs.geometry.localSQ8().getLast(),
                            inputs.geometry.localSQ8().getLast() == 0L ? 0L
                                    : Math.round(100.0 * inputs.prefix.terminal().get().localSQ8()
                                            / inputs.geometry.localSQ8().getLast()));
                    var table = new StationTable(List.of(), List.of(), "0".repeat(64));
                    table = new StationTable(List.of(), List.of(), DeterministicHash.sha256(CanonicalCodec.stations(table)));
                    result.complete(hashedPlan(inputs.geometry, inputs.prefix, table, List.of()));
                    geometry.close(); prefix.close(); return;
                }
                cacheIo.readPlan(key).whenCompleteAsync((cached, cacheError) -> {
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

    /** 对外保留原调度入口；精采样和批量预热不创建额外线程池。 */
    public CompletableFuture<FineHeightIndex> requireFineSamples(Priority priority) {
        return fineSamples.requireFineSamples(priority);
    }

    public void setFineSampleCache(local.plaincraft.greatwall.terrain.FineSampleCache cache) {
        fineSamples.setFineSampleCache(cache);
    }

    public void afterRoadBulk() { bulkPrewarm.afterRoadBulk(); }
    public void onBulkReady(Runnable hook) { bulkPrewarm.onBulkReady(hook); }
    public List<PlanLease<SegmentPlan>> prewarmLeases() { return bulkPrewarm.prewarmLeases(); }
    public void releasePrewarmLeases() { bulkPrewarm.releasePrewarmLeases(); }

    void driveStartupSlice() {
        // 预热和精采样共享原推进预算，避免新增互相抢占的调度器。
        credits = 4096;
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25);
        dispatch();
    }

    void cancelDemandTimer() {
        if (startupTimer != null) startupTimer.cancel(false);
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
        return admissions.outer(chain.thenApply(list -> new PlanWindow(list.stream().filter(p -> p.prefix().terminal().isEmpty()
                || p.prefix().terminal().get().segment() >= p.geometry().key().index()).toList())), () -> leases.forEach(PlanLease::close));
    }

    /** 先启动按需推进供入口查询；结构索引安装后再开始批量预热。 */
    public void startupPump() {
        if (closed()) throw closedFailure();
        if (state.putIfAbsent("startup", 1L) != null) return;
        state.put("demand-only", 1L);
        pump.execute(() -> {
            startupTimer = pump.scheduleWithFixedDelay(() -> {
                state.merge("startup-pump-iterations", 1L, Long::sum);
                credits = 4096; deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25); dispatch();
            }, 0, 1, TimeUnit.MILLISECONDS);
            // 此处就绪只表示按需推进可用，不表示首批计划已完成。
            state.put("startup-ready", 1L);
            // 服务端暂停 tick 时由看门狗推进，避免启动等待阻塞规划。
            startWatchdog();
        });
    }

    /** 初始化中的通道也算忙，精采样不能因此误判停滞。 */
    boolean lanesIdle() {
        for (var lane : laneState) {
            if (lane.busy || lane.initializing) return false;
        }
        return true;
    }

    /** 统一聚合原生通道日志指标，便于定位地形成本。 */
    long laneDiagnostic(String key) {        long total = 0L;
        for (var lane : lanes) total += lane.diagnostics().getOrDefault(key, 0L);
        return total;
    }

    public StartupWallRange startupRange() { return startupRange; }

    long prewarmTargetSegments() { return startupRange.segments(); }

    public void tick(SliceBudget budget) {
        Objects.requireNonNull(budget);
        if (closed()) return;
        // 只保留最新 tick 预算，避免推进暂时阻塞时形成无界队列。
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

    /** 首次 tick 撤掉启动定时器，以后由看门狗补足服务端暂停时的推进。 */
    private static final long WATCHDOG_STALL_NANOS =
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                    Math.max(500L, Long.getLong("greatwall.planner.watchdogStallMs", 3_000L)));
    private ScheduledFuture<?> watchdogTimer;

    /** 每轮先检查 tick 时间，仅在停滞时领取预算。 */
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
        // 首次 tick 前仍有初始化定时器，不需额外驱动。
        if (last == 0L) return;
        if (System.nanoTime() - last < WATCHDOG_STALL_NANOS) return;
        // tick 停止时补预算，恢复后交还服务端。
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

    /** 结构扫描结束后才能安装索引并启动批量任务，避免先算出不绕行路线。 */
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
            var cursor = hydrology.begin(new RouteNeighborhood(center, previous, next), oracle, 96000,
                    usesLocalWaterBreaks);
            while (hydrology.advance(cursor, 64) != WorkState.DONE) if (closed()) throw closedFailure();
            return geometry(center, hydrology.finish(cursor), cursor.logicalColumns(), next.isEmpty(),
                    usesLocalWaterBreaks ? List.of() : oceanRuns(center, oracle));
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
        cacheIo.readGeometry(key).whenCompleteAsync((cached, error) -> {
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
        while (!closed() && activeGeometry < recipe.config().maxGeometryInflight()) {
            Runnable next;
            // 持锁出队后才执行，避免几何计算堵住调用线程。
            synchronized (geometryLock) { next = geometryQueue.pollFirst(); }
            if (next == null) return;
            next.run();
        }
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

    static java.util.List<local.plaincraft.greatwall.planning.OceanRouteScan.OceanRun> oceanRuns(
            local.plaincraft.greatwall.model.WallTypes.RouteShape route, NaturalTerrainOracle source) {
        if (source == null || route == null) return java.util.List.of();
        var points = route.points();
        var arcs = route.localSQ8();
        if (points.isEmpty() || arcs.size() != points.size()) return java.util.List.of();
        var keys = new java.util.ArrayList<String>(points.size());
        for (var point : points) {
            keys.add(source.biomeKeyAt(local.plaincraft.greatwall.geometry.FixedMath.floorBlock(point.xQ8()),
                    local.plaincraft.greatwall.geometry.FixedMath.floorBlock(point.zQ8())));
        }
        return local.plaincraft.greatwall.planning.OceanRouteScan.scan(keys, arcs);
    }

    private SegmentGeometry geometry(RouteShape route, List<WaterEvidence> water, int hydroColumns, boolean last,
            List<local.plaincraft.greatwall.planning.OceanRouteScan.OceanRun> seas) {
        // 历史配方按首个海面截断；新配方以真实水段局部收口，计划终点只受长度/世界边界限制。
        Optional<Terminal> terminal = usesLocalWaterBreaks ? Optional.empty() : OceanRouteScan
                .firstTerminating(seas)
                .map(run -> new Terminal(route.key().index(),
                        route.localSQ8().get(Math.min(route.localSQ8().size() - 1,
                                local.plaincraft.greatwall.planning.OceanRouteScan.shoreIndex(run))),
                        TerminalKind.OCEAN));
        if (terminal.isPresent()) {
            local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                    "[GreatWall:S1C] OCEAN_ROUTE_SCAN segment={} seas={} terminal=biome localSQ8={}",
                    route.key().index(), seas.size(), terminal.get().localSQ8());
        }
        if (!usesLocalWaterBreaks && terminal.isEmpty()) terminal = water.stream().filter(w -> w.classification() == WaterClass.WIDE
                && w.crossing().fromQ8() >= 0 && w.crossing().fromQ8() < route.localSQ8().getLast())
                .map(w -> new Terminal(route.key().index(), w.crossing().fromQ8(), TerminalKind.OCEAN))
                .min(Comparator.comparingLong(Terminal::localSQ8));
        if (terminal.isEmpty() && last) {
            int index = route.points().size() - 1;
            if (usesLocalWaterBreaks && configuredWallLength > 0) {
                long remainingU = configuredWallLength - route.key().index() * GreatWallConfig.SEGMENT_BLOCKS;
                index = Math.toIntExact(Math.clamp(remainingU, 1, index));
            }
            terminal = Optional.of(new Terminal(route.key().index(), route.localSQ8().get(index), TerminalKind.WORLD_LIMIT));
        }
        // 分别记录群系、测深或窗口边界，便于定位提前终止。
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:diag] TERMINAL_SOURCE segment={} source={} localSQ8={} routeSQ8={} "
                        + "waterRuns={} wideRuns={} biomeTerminal={} last={}",
                route.key().index(),
                terminal.isEmpty() ? "none" : (terminal.get().kind() == TerminalKind.WORLD_LIMIT
                        ? "world-limit" : (water.stream().anyMatch(w -> w.classification() == WaterClass.WIDE)
                        && !seas.isEmpty() ? "wide-water-or-biome" : "wide-water")),
                terminal.map(Terminal::localSQ8).orElse(-1L), route.localSQ8().getLast(),
                water.size(), water.stream().filter(w -> w.classification() == WaterClass.WIDE).count(),
                !seas.isEmpty(), last);
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
                    // 精采样也有段号，提权必须识别它，避免误伤原推进器。
                    case FineSampleJob fine -> fine.path.key().index();
                    default -> -1L;
                };
                if (index < 0L) continue;
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
                // 限制原生地形初始化并发，压住多个噪声状态叠加的工作集。
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
            int operations = Math.min(GreatWallConfig.get().laneBatchOperations(), credits); credits -= operations;
            var job = slot.job; slot.busy = true;
            nativeLane.submit(oracle -> {
                Throwable error = null; boolean done = false; Object value = null;
                long started = System.nanoTime();
                try {
                    if (closed()) throw closedFailure();
                    // 每轮交付整批预算，避免每个原语都重复检查分派状态。
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
                    // 失败任务可能持有未收口的原生游标，通道不能直接复用。
                    Throwable failure = submitError != null ? submitError : batch.failure();
                    if (failure != null) {
                        recordFailure(failure, job.phase, segmentOf(job), job.operations);
                        job.result.completeExceptionally(failure);
                        // 先结束加载等待再关闭调度，避免等一个已不可能完成的批次。
                        GreatWallMod.LOGGER.warn("[GreatWall:S1C] PREWARM_ABORTED phase={} segment={} reason={}",
                                job.phase, segmentOf(job), failure.toString());
                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
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

    private SegmentKey key(long segment) {
        if (closed()) throw closedFailure();
        if (!safeSegment(segment)) throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "segment exceeds complete frozen envelope");
        return new SegmentKey(recipe.recipeHash(), recipe.dimension(), segment);
    }

    /** 配置长度和冻结包络都必须满足；后台、邻段和终止检查不能绕开。 */
    boolean safeSegment(long segment) {
        return StartupWallRange.segmentFits(recipe, startupRange.configuredSegments(), segment);
    }

    /** 诊断可建段数，零代表没有配置上限。 */
    public long configuredSegmentLimitForDiagnostics() { return startupRange.configuredSegments(); }

    private void failCacheIo(WallFailure failure) {
        // 缓存执行器拒绝时也要终结已接收的规划请求，不能把它伪装成普通缓存未命中。
        recordFailure(failure, "CACHE_IO", -1, 0);
        routeFlights.forEach((key, future) -> future.completeExceptionally(failure));
        geometryFlights.forEach((key, future) -> future.completeExceptionally(failure));
        planFlights.forEach((key, future) -> future.completeExceptionally(failure));
        close();
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

    boolean closed() { return state.containsKey("closed"); }
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
            case PlannerScheduler.FineSampleJob fine -> fine.path.key().index();
            // 未知任务只影响日志标签，不能影响推进器生存。
            default -> -1L;
        };
    }
    WallFailure closedFailure() {
        var first = failures.get("first");
        return first == null ? new WallFailure(FailureCode.CLOSED, "planner session closed")
                : new WallFailure(first.code(), "planner failed in " + first.phase() + " segment="
                        + first.segment() + ": " + first.exceptionType() + ": " + first.message());
    }
    /** 不允许推进线程或地形通道阻塞等待此调度器，所等工作正需要这些线程。 */
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
        admissions.failPending();
        prefixes.close(); admissions.closeLeases();
        // 关闭后再遍历一次弱一致租约表，防止并发插入让缓存一直被钉住。
        admissions.closeLeases();
        // 未进入编译就退出时也必须归还预热租约。
        releasePrewarmLeases();
        fineSamples.persistOnClose();
        admissions.closeMemory();
        pump.execute(() -> {
            if (startupTimer != null) startupTimer.cancel(false);
            if (watchdogTimer != null) watchdogTimer.cancel(false);
            bulkPrewarm.stopPrewarmTimer();
            fineSamples.stopFineSampleDriver();
            pending.forEach(job -> job.result.completeExceptionally(closedFailure()));
            // 已派发任务不在等待队列，关闭时须主动结束它们，避免晚回调被拒后永久挂起。
            activeJobs.values().forEach(job -> job.result.completeExceptionally(closedFailure()));
            pending.clear(); synchronized (geometryLock) { geometryQueue.clear(); } cacheIo.discardQueued();
            admissions.clearQueued();
            activeJobs.clear(); progress.clear();
            lanes.forEach(OracleLane::close);
            CompletableFuture.allOf(lanes.stream().map(OracleLane::drain).toArray(CompletableFuture[]::new))
                    .whenComplete((value, error) -> {
                        if (error == null) drained.complete(null); else drained.completeExceptionally(error);
                    });
            pump.shutdown();
        });
    }

    private record Pair(SegmentGeometry geometry, PrefixSummary prefix) {}
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

    // 阶段只拿完成信号和切片统计，游标调度与提权仍由同一队列负责。
    FineSampleJob enqueueFineSample(RoutePath path, int fromPoint, int toPoint, Priority priority,
            CompletableFuture<List<WallFinePlanner.FineColumn>> result,
            local.plaincraft.greatwall.terrain.FineSampleCache cache, java.util.function.IntPredicate wideAt,
            Map<Long, ColumnSample> walks, java.util.Set<Long> claims, long globalBaseQ8) {
        var job = new FineSampleJob(path, fromPoint, toPoint, priority, result, cache, wideAt, walks, claims, globalBaseQ8);
        enqueue(job);
        return job;
    }

    final class FineSampleJob extends Job<List<WallFinePlanner.FineColumn>> {
        private final RoutePath path;
        /** 路线半开区间，切片边界不能重复计作完整区间。 */
        private final int fromPoint, toPoint;
        private final local.plaincraft.greatwall.terrain.FineSampleCache cache;
        /** 建筑邻域需要全幅横截面，避免缺少地形证据。 */
        private final long globalBaseQ8;
        private final java.util.function.IntPredicate wideAt;
        /** 同一会话复用已走过的地形列，降低切片交叠开销。 */
        private final java.util.Map<Long, local.plaincraft.greatwall.model.WallTypes.ColumnSample> walks;
        /** 已认领地形列跨切片去重，游标仍在所属原生通道上推进。 */
        private final java.util.Set<Long> claims;
        private WallFinePlanner.Cursor cursor;
        private FineSampleJob(RoutePath path, int fromPoint, int toPoint, Priority priority,
                              CompletableFuture<List<WallFinePlanner.FineColumn>> result,
                              local.plaincraft.greatwall.terrain.FineSampleCache cache,
                              java.util.function.IntPredicate wideAt,
                              java.util.Map<Long, local.plaincraft.greatwall.model.WallTypes.ColumnSample> walks,
                              java.util.Set<Long> claims, long globalBaseQ8) {
            super(priority, "FINE_SAMPLE", result);
            this.globalBaseQ8 = globalBaseQ8;
            this.path = path;
            this.fromPoint = Math.max(0, fromPoint);
            this.toPoint = Math.min(path.points().size(), Math.max(this.fromPoint, toPoint));
            this.cache = cache;
            this.wideAt = wideAt;
            this.walks = walks;
            this.claims = claims;
        }
        @Override boolean advance(NaturalTerrainOracle oracle, int limit) {
            if (cursor == null) {
                cursor = WallFinePlanner.begin(path, oracle, fromPoint, toPoint, cache, wideAt, walks, claims,
                        fromPoint > 0, globalBaseQ8);
            }
            operations += limit;
            return WallFinePlanner.advance(cursor, limit) == WorkState.DONE;
        }
        @Override List<WallFinePlanner.FineColumn> value() { return WallFinePlanner.finish(cursor); }
        CompletableFuture<List<WallFinePlanner.FineColumn>> result() { return result; }
        /** 仅将本切片实际探测的地形列加入缓存。 */
        List<WallFinePlanner.FineColumn> probed() { return WallFinePlanner.probed(cursor); }
        /** 记录去重前列数，量化截面重叠开销。 */
        int emittedRaw() { return cursor == null ? 0 : WallFinePlanner.emittedRaw(cursor); }
        /** 分开统计总截面和宽截面，以便解释取样量。 */
        long sections() { return cursor == null ? 0 : WallFinePlanner.sections(cursor); }
        long wideSections() { return cursor == null ? 0 : WallFinePlanner.wideSections(cursor); }
        /** 仅累加真实探测，缓存或内存命中不算。 */
        long probedColumns() { return cursor == null ? 0 : WallFinePlanner.probedColumns(cursor); }
    }

    private final class HydroJob extends Job<SegmentGeometry> {
        private final RouteNeighborhood routes;
        private HydrologyClassifier.Cursor cursor;
        /** 保留游标所属通道供结案识别海域。 */
        private NaturalTerrainOracle oracle;
        private HydroJob(RouteNeighborhood routes, Priority priority, CompletableFuture<SegmentGeometry> result) {
            super(priority, "HYDROLOGY", result); this.routes = routes;
            result.thenAcceptAsync(cacheIo::persist, pump);
        }
        @Override boolean advance(NaturalTerrainOracle oracle, int limit) {
            if (cursor == null) cursor = hydrology.begin(routes, oracle, 96000, usesLocalWaterBreaks);
            this.oracle = oracle;
            operations += limit;
            try {
                // 超出上限仅结束未认证水面，保持已认证的窄水、宽水和地基缺口。
                if (operations > HYDROLOGY_OPERATION_CEILING && !cursor.done()) {
                    hydrology.forceComplete(cursor);
                    return true;
                }
                return hydrology.advance(cursor, limit) == WorkState.DONE;
            } catch (WallFailure unresolved) {
                // 单列不能认证河床时沿用宽水域结案，其他异常继续上报；强制结案会清理游标。
                if (unresolved.code() != FailureCode.HYDROLOGY_UNRESOLVED || cursor.done()) throw unresolved;
                GreatWallMod.LOGGER.warn("[GreatWall:hydro] HYDROLOGY_FORCED_COMPLETE segment={} reason={}",
                        routes.center().key().index(), unresolved.getMessage());
                hydrology.forceComplete(cursor);
                return true;
            }
        }
        @Override SegmentGeometry value() {
            return geometry(routes.center(), hydrology.finish(cursor), cursor.logicalColumns(),
                    routes.next().isEmpty(), usesLocalWaterBreaks ? List.of() : oceanRuns(routes.center(), oracle));
        }

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
            result.thenAcceptAsync(cacheIo::persist, pump);
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
            var solved = heights.solveWithSites(inputs.geometry, inputs.prefix, provisional, columns);
            return hashedPlan(inputs.geometry, inputs.prefix, solved.stations(),
                    sites.bindHeights(solved.sites(), solved.stations()));
        }
    }
}
