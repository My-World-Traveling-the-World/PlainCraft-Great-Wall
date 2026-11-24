package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.config.GreatWallConfig;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 精采样负责切片、进度、汇总及缓存；通过原调度器驱动同一批地形通道。 */
final class FineSampleStage {
    private final PlannerScheduler scheduler;
    private final ScheduledThreadPoolExecutor pump;
    private final PrefixSummaryIndex prefixes;
    private final SiteScheduler sites;
    private final int laneCount;
    private final Function<SegmentKey, CompletableFuture<SegmentGeometry>> geometryFlight;

    FineSampleStage(PlannerScheduler scheduler, ScheduledThreadPoolExecutor pump,
            PrefixSummaryIndex prefixes, SiteScheduler sites, int laneCount,
            Function<SegmentKey, CompletableFuture<SegmentGeometry>> geometryFlight) {
        this.scheduler = scheduler;
        this.pump = pump;
        this.prefixes = prefixes;
        this.sites = sites;
        this.laneCount = laneCount;
        this.geometryFlight = geometryFlight;
    }

    /** 用全局调用计数区分多次启动与单次切片重复。 */
    private static final java.util.concurrent.atomic.AtomicLong FINE_SAMPLE_CALLS = new java.util.concurrent.atomic.AtomicLong();
    /** 加载会阻塞服务端 tick，因此用定时预算在原地形通道推进采样。 */
    private volatile ScheduledFuture<?> fineSampleTimer;

    // 原生通道的批量预算由配置给出，游标一次最多接收 4096 个原语。

    /** 间隔记录阶段进度，避免热路径刷屏。 */
    private static final long FINE_SAMPLE_REPORT_MILLIS = 30_000L;

    /** 同一套计数供进度和停滞判据使用，区分慢和不再前进。 */
    private volatile java.util.concurrent.atomic.AtomicInteger fineSampleDone;
    private volatile java.util.concurrent.atomic.AtomicInteger fineSampleTotal;
    /** 分开记录去重前列数、总截面、宽截面和实际探测。 */
    private volatile java.util.concurrent.atomic.AtomicInteger fineSampleRaw;
    private volatile java.util.concurrent.atomic.AtomicLong fineSampleSections;
    private volatile java.util.concurrent.atomic.AtomicLong fineSampleWideSections;
    private volatile java.util.concurrent.atomic.AtomicLong fineSampleProbed;
    private volatile long fineSampleStartNanos;
    private volatile long fineSampleLastLogNanos;
    /** 保存提前发布回调，让原停滞策略可以结束启动等待。 */
    private volatile Runnable fineSampleAbandon;
    /** 强制标志按调用重建，避免前一次结案影响本次样本。 */
    private volatile java.util.concurrent.atomic.AtomicBoolean fineSampleForced = new java.util.concurrent.atomic.AtomicBoolean();
    /** 只记录本轮待落盘证据，正常结案不得通过 Future 长期持有整窗索引。 */
    private FineSampleEvidence evidenceToClose;
    private CompletableFuture<FineHeightIndex> activeSample;

    /** 固定切片数，避免通道数改变切片边界和认领结果。 */
    private static final int FINE_SAMPLE_SLICES_PER_SEGMENT = 48;
    private static final long FINE_SAMPLE_RUNAWAY_NUMERATOR = 4L;
    /** 停滞按时间窗口采样，避免每轮收集通道指标。 */
    private static final long FINE_SAMPLE_RUNAWAY_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** 窗口样本过少时不推断重复走列。 */
    private static final long FINE_SAMPLE_RUNAWAY_MIN_COLUMNS = 1_000L;
    /** 分母尚处于初始阶段时不做比例判断。 */
    private static final long FINE_SAMPLE_RUNAWAY_MIN_EXPECTED = 1_000L;
    /** 连续多个低发现窗口才判重复，避免短暂缓存命中误触发。 */
    private static final int FINE_SAMPLE_RUNAWAY_IDLE_WINDOWS = 3;
    /** 独立检查无线程推进又有未完成任务的窗口，避免零进度永远不进比例判据。 */
    private static final int FINE_SAMPLE_STALL_WINDOWS = 3;
    private long runawayLastColumns;
    private long runawayLastMisses;
    private long runawayLastCheckNanos;
    private int runawayIdleChecks;
    private int runawayStallChecks;
    /** 指标保留路径和切片未完成数，以区分完成和停滞。 */
    private volatile java.util.concurrent.atomic.AtomicInteger fineSampleSlicesPending;
    private volatile java.util.concurrent.atomic.AtomicInteger fineSampleRoutesPending;

    /** 相同世界配置复用上次已采过的列；默认无磁盘缓存。 */
    private volatile local.plaincraft.greatwall.terrain.FineSampleCache fineSampleCache =
            local.plaincraft.greatwall.terrain.FineSampleCache.none();

    /** 切片提交前装好缓存，同一次取样采用相同证据来源。 */
    void setFineSampleCache(local.plaincraft.greatwall.terrain.FineSampleCache cache) {
        this.fineSampleCache = java.util.Objects.requireNonNull(cache, "cache");
    }

    /** 生成开头段的截面地形索引供编译，落块期不需要逐方块重采。 */
    synchronized CompletableFuture<FineHeightIndex> requireFineSamples(Priority priority) {
        if (scheduler.closed()) return CompletableFuture.failedFuture(scheduler.closedFailure());
        // 启动精采样是单次请求；并发调用共享结果，不能互相覆盖关闭与停滞回调。
        if (activeSample != null) return activeSample;
        if (evidenceToClose != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("本次启动精采样已结案"));
        }
        var aggregate = new CompletableFuture<FineHeightIndex>();
        var cache = fineSampleCache;
        var evidence = new FineSampleEvidence(cache);
        activeSample = aggregate;
        evidenceToClose = evidence;
        var sampledColumns = new java.util.concurrent.atomic.AtomicInteger();
        // 保留去重前列数，便于发现截面交叠成本。
        var rawColumns = new java.util.concurrent.atomic.AtomicInteger();
        // 区分宽截面与原生探测，便于诊断两种开销。
        var walkedSections = new java.util.concurrent.atomic.AtomicLong();
        var wideSections = new java.util.concurrent.atomic.AtomicLong();
        var probedColumns = new java.util.concurrent.atomic.AtomicLong();
        // 与批量预热采用同一目标段数，保持计划与证据窗口对齐。
        long target = scheduler.prewarmTargetSegments();

        final int projectedSegments = (int) Math.max(1L, target);
        // 固定 48 片分布到任意数量通道；采样范围不依赖硬件线程数。
        int slices = FINE_SAMPLE_SLICES_PER_SEGMENT;
        // 按列报进度，大切片工作时也能持续更新。
        var expectedColumns = new java.util.concurrent.atomic.AtomicInteger(scheduler.landFirstPlanning() ? 0 : 1);
        // 全部路线已知前不冒充精确分母；收齐矩形后只做一次并集扫描。
        var samplingArea = new SamplingArea();
        var areaPublished = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable publishArea = () -> {
            if (scheduler.landFirstPlanning() && areaPublished.compareAndSet(false, true)) {
                expectedColumns.set(samplingArea.finish());
            }
        };
        // 提前估算整段组，避免后续补分母使进度倒退。
        var expectedSeeded = new java.util.concurrent.atomic.AtomicBoolean();
        // 进度按实际网格步长估计，保持与游标的网格口径一致。
        final int gridStep = local.plaincraft.greatwall.config.GreatWallConfig.parameters()
                .fineSampleGridStep();
        // 驱动开始前先公布计数，避免日志读到上次调用。
        fineSampleDone = sampledColumns;
        fineSampleTotal = expectedColumns;
        fineSampleRaw = rawColumns;
        fineSampleSections = walkedSections;
        fineSampleWideSections = wideSections;
        fineSampleProbed = probedColumns;
        fineSampleStartNanos = System.nanoTime();
        fineSampleLastLogNanos = 0L;
        // 上次强制结案不能让本次在样本不足时提前发布。
        var forcedFlag = new java.util.concurrent.atomic.AtomicBoolean();
        fineSampleForced = forcedFlag;
        runawayLastColumns = 0L;
        runawayLastMisses = 0L;
        runawayLastCheckNanos = 0L;
        runawayIdleChecks = 0;
        // 将调用计数与实际切片数一并记录，便于排查重复提交。
        GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_CALL call={} segments={} slicesPerSegment={}",
                FINE_SAMPLE_CALLS.incrementAndGet(), (int) Math.min(Integer.MAX_VALUE, target),
                slices);
        // 相邻截面共享不可变地形列，交叠坐标不用重复探测。
        var walks = new java.util.concurrent.ConcurrentHashMap<Long,
                local.plaincraft.greatwall.model.WallTypes.ColumnSample>();
        // 跨切片声明列所有权，避免同时探测同一列。
        var claims = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
        // 只保留仍在采样的段，避免先完成的几何一直占用缓存，等最慢的段才能归还。
        var leases = new ConcurrentHashMap<Long, FineSampleSegmentLease>();
        // 只计实际提交的段，配置目标可能大于冻结边界。
        var routesPending = new java.util.concurrent.atomic.AtomicInteger();
        var slicesPending = new java.util.concurrent.atomic.AtomicInteger();
        // 向定时器公开未完成数，以便检测队列不再前进。
        fineSampleRoutesPending = routesPending;
        fineSampleSlicesPending = slicesPending;
        runawayStallChecks = 0;
        // 提交循环未结束时，暂时归零不能视为阶段结束。
        var enqueueing = new java.util.concurrent.atomic.AtomicBoolean(true);
        // 完备后只发布一次，防止重复汇总、落盘和写日志。
        var published = new java.util.concurrent.atomic.AtomicBoolean(false);
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.PREWARM, 0, 1);
        var completion = new Runnable() {
            @Override public void run() {
                // 保留提前发布策略；常规结案须等待路径和切片全部完成。
                if (!forcedFlag.get()) {
                    if (enqueueing.get()) return;
                    if (routesPending.get() > 0) return;
                    if (scheduler.landFirstPlanning()) {
                        publishArea.run();
                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                                local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.FINE_SAMPLE,
                                sampledColumns.get(), expectedColumns.get());
                    }
                    if (slicesPending.get() > 0) return;
                }
                if (aggregate.isDone() || !published.compareAndSet(false, true)) return;
                if (scheduler.landFirstPlanning()) publishArea.run();
                int done = sampledColumns.get();
                // 新版分母是完整占地并集；旧版维持其估计口径。
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.FINE_SAMPLE, Math.min(done, Math.max(1, expectedColumns.get())), expectedColumns.get());
                // 拆开地形步进、构建和缓存成本，便于定位耗时。
                long blockMs = scheduler.laneDiagnostic("block-ms");
                long sliceMs = scheduler.laneDiagnostic("slice-ms");
                long constructMs = scheduler.laneDiagnostic("construct-ms");
                long cellMs = scheduler.laneDiagnostic("cell-ms");
                long biomeMs = scheduler.laneDiagnostic("biome-ms");
                long surfaceMs = scheduler.laneDiagnostic("surface-ms");
                long walkMs = blockMs + sliceMs + constructMs + cellMs + biomeMs + surfaceMs;
                GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_DONE columns={} raw={} repeatedPct={} "
                                + "expectedColumns={} chunkCacheHits={} chunkCacheMisses={} lanes={} "
                                + "walkedBlocks={} blocksPerColumn={} leadRetries={} retryPct={} "
                                + "blockMs={} sliceMs={} constructMs={} cellMs={} biomeMs={} surfaceMs={} "
                                + "blockPct={} slicePct={} walkMs={} msPerColumn={} "
                                + "surfaceMemoHits={} sections={} wideSections={} widePct={} "
                                + "columnsPerSection={} probesPerSection={} probesPerColumn={}",
                        done, rawColumns.get(),
                        rawColumns.get() == 0 ? 0 : Math.round(100f * (rawColumns.get() - done) / rawColumns.get()),
                        expectedColumns.get(),
                        scheduler.laneDiagnostic("chunk-cache-hits"), scheduler.laneDiagnostic("chunk-cache-misses"),
                        laneCount,
                        scheduler.laneDiagnostic("walked-blocks"),
                        done == 0 ? 0 : scheduler.laneDiagnostic("walked-blocks") / done,
                        scheduler.laneDiagnostic("lead-retries"),
                        done == 0 ? 0 : Math.round(100f * scheduler.laneDiagnostic("lead-retries") / done),
                        blockMs, sliceMs, constructMs, cellMs, biomeMs, surfaceMs,
                        percentOf(blockMs, walkMs), percentOf(sliceMs, walkMs), walkMs,
                        done == 0 ? 0 : walkMs / done,
                        scheduler.laneDiagnostic("surface-memo-hits"),
                        walkedSections.get(), wideSections.get(),
                        walkedSections.get() == 0 ? 0
                                : Math.round(100f * wideSections.get() / walkedSections.get()),
                        walkedSections.get() == 0 ? 0 : rawColumns.get() / walkedSections.get(),
                        walkedSections.get() == 0 ? 0 : probedColumns.get() / walkedSections.get(),
                        done == 0 ? 0 : probedColumns.get() / done);
                try {
                    var index = evidence.finish(scheduler.landFirstPlanning());
                    if (index == null) return; // 并发取消或关闭已接管结案。
                    int emitted = index.orderedColumns().size(), distinct = index.size();
                    // 复用正式索引的大小，避免仅为日志再遍历坐标并建一张集合。
                    GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_COLUMNS emitted={} distinct={} "
                                    + "duplicatePct={} sections={}",
                            emitted, distinct, emitted == 0 ? 0 : Math.round(100f * (emitted - distinct) / emitted),
                            walkedSections.get());
                    // 关闭可能发生在坐标索引构建中；旧世界不能在此时发布成功。
                    if (scheduler.closed()) aggregate.completeExceptionally(scheduler.closedFailure());
                    else aggregate.complete(index);
                } catch (Throwable failure) {
                    // 最后一个切片触发的汇总异常也必须完成等待者。
                    aggregate.completeExceptionally(failure);
                }
            }
        };
        // 停滞触发后移除提前结案回调，避免重复发布。
        // 新版必须取齐施工证据；驱动确实停滞时明确失败。
        fineSampleAbandon = scheduler.landFirstPlanning()
                ? () -> aggregate.completeExceptionally(new IllegalStateException("长城精采样停止推进，施工证据不完整"))
                : completion;
        aggregate.whenComplete((v, e) -> {
            // 提前结案或失败时扫尾，取消与迟到的备用路线申请可以并发。
            synchronized (this) {
                activeSample = null;
                fineSampleAbandon = null;
                stopFineSampleDriver();
            }
            if (e != null) evidence.discardSlices();
            leases.values().forEach(FineSampleSegmentLease::close);
        });
        try {
            // 先登记清理再启动驱动，防止关闭后留下持有切片的回调。
            startFineSampleDriver();
            for (long segment = 0; segment < target && !aggregate.isDone(); segment++) {
                long index = segment;
                var segmentLease = new FineSampleSegmentLease(() -> leases.remove(index));
                leases.put(index, segmentLease);
                if (aggregate.isDone()) { segmentLease.close(); break; }
                // 缓存几何已经有路线点及弧长，重开不用再做一次山脊搜索。
                var lease = segmentLease.own(scheduler.requireGeometry(index, priority));
                // 注册前先计数，即时完成的路径也不能漏记。
                routesPending.incrementAndGet();
                // 几何等待超过原时限才请求路线，正常缓存命中直接取样。
                CompletableFuture<java.util.Optional<RoutePath>> path;
                if (sites.imported()) {
                    // 不创建旧超时支线，避免两分钟后又悄悄启动一条未使用的旧路线。
                    var planLease = segmentLease.own(scheduler.requirePlan(index, priority));
                    path = planLease.future().thenApply(plan -> ImportedSamplePath.forSampling(plan, scheduler.landFirstPlanning()));
                } else path = lease.future()
                        .<RoutePath>thenApply(geometry -> geometry)
                        .completeOnTimeout(null, 120, java.util.concurrent.TimeUnit.SECONDS)
                        .thenComposeAsync(sampled -> {
                            if (sampled != null) return CompletableFuture.completedFuture(sampled);
                            if (aggregate.isDone()) return CompletableFuture.failedFuture(new CancellationException("采样已结案"));
                            GreatWallMod.LOGGER.warn("[GreatWall:S1C] FINE_SAMPLE_PATH_FALLBACK segment={} "
                                    + "reason=geometry-timeout; sampling the recomputed route instead", index);
                            var routeLease = segmentLease.own(scheduler.requireRoute(index, Priority.PLAYER_PREFETCH));
                            return routeLease.future().thenApply(route -> (RoutePath) route);
                        }, pump).thenApply(java.util.Optional::of);
                // 路径确定时估算完整窗口，不等各段前缀再叠加分母。
                path.thenAcceptAsync(candidate -> {
                    if (aggregate.isDone() || candidate.isEmpty() || scheduler.landFirstPlanning()) return;
                    var sampled = candidate.orElseThrow();
                    // 分母与切片沿用同一末端判定。
                    long points = Math.max(1L, terminalPointLimit(sampled, sampled.points().size())
                            + 2L * WallFinePlanner.APRON_BLOCKS);
                    // 记录各段点数，便于识别重复走了整条路线。
                    GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_SEGMENT segment={} routePoints={} "
                                    + "sectionsExpected={} columnsExpected={}",
                            index, sampled.points().size(),
                            (int) (points / Math.max(1, gridStep)),
                            (int) Math.min(Integer.MAX_VALUE - 1L, (points / Math.max(1, gridStep))
                                    * (2L * (WallFinePlanner.LATERAL / Math.max(1, gridStep)) + 1L)));
                    // 大部分路点使用基础截面；历史宽截面约占四分之一，用于估计进度。
                    long baseWidth = 2L * (WallFinePlanner.BASE_LATERAL / Math.max(1, gridStep)) + 1L;
                    long wideWidth = 2L * (WallFinePlanner.LATERAL / Math.max(1, gridStep)) + 1L;
                    long perPoint = (3L * baseWidth + wideWidth) / 4L;
                    long estimated = (points / Math.max(1, gridStep)) * perPoint;
                    // 首段乘总段数，后到更长的段只上调估计，不再累加。
                    int projected = (int) Math.min(Integer.MAX_VALUE - 1L, estimated * projectedSegments);
                    if (expectedSeeded.compareAndSet(false, true)) {
                        expectedColumns.set(projected);
                    } else {
                        expectedColumns.updateAndGet(current -> Math.max(current, projected));
                    }
                }, pump);
                path.whenCompleteAsync((candidate, error) -> {
                    if (error != null || scheduler.closed() || aggregate.isDone()) {
                        if (error != null) aggregate.completeExceptionally(error);
                        else if (scheduler.closed()) aggregate.completeExceptionally(scheduler.closedFailure());
                        segmentLease.releaseUser();
                        routesPending.decrementAndGet();
                        if (!aggregate.isDone()) completion.run();
                        return;
                    }
                    if (candidate.isEmpty()) {
                        // 合法终止段也归还租约和待完成计数，否则有效前段采完仍无法发布索引。
                        GreatWallMod.LOGGER.info("【长城精采样跳过已终止空段】 FINE_SAMPLE_TERMINATED segment={}", index);
                        segmentLease.releaseUser();
                        routesPending.decrementAndGet();
                        completion.run();
                        return;
                    }
                    var sampled = candidate.orElseThrow();
                    if (scheduler.landFirstPlanning()) samplingArea.add((ImportedSamplePath)sampled);
                    // 建筑按绝对弧长排布，先拿段前缀才能定宽截面。
                    CompletableFuture<PrefixSummary> before;
                    try {
                        before = prefixes.requireBefore(index).future();
                    } catch (RuntimeException failure) {
                        // 同步拒绝也要结案，否则异常会藏在无人消费的回调 Future 里。
                        aggregate.completeExceptionally(failure);
                        segmentLease.releaseUser();
                        routesPending.decrementAndGet();
                        return;
                    }
                    before.whenCompleteAsync((prefix, prefixError) -> {
                    try {
                        if (prefixError != null) { aggregate.completeExceptionally(prefixError); return; }
                        if (aggregate.isDone()) return;
                        var wideAt = widePoints(sampled, prefix.lengthBeforeQ8());
                        int points = sampled.points().size();
                        // 保留原几何在途末端判据；缺少就绪证据就采全段，避免编译缺列。
                        if (!(sampled instanceof ImportedSamplePath)) points = terminalPointLimit(sampled, points);
                        int perSlice = Math.max(1, (points + slices - 1) / slices);
                        for (int s = 0; s < slices; s++) {
                            int from = s * perSlice;
                            if (from >= points) break;
                            int to = Math.min(points, from + perSlice);
                            // 路径回调已设分母，安排切片不能重复加估值。
                            var job = scheduler.enqueueFineSample(sampled, from, to, priority,
                                    new CompletableFuture<List<WallFinePlanner.FineColumn>>(), cache,
                                    wideAt, walks, claims, prefix.lengthBeforeQ8());
                            slicesPending.incrementAndGet();
                            segmentLease.retainSlice();
                            job.result().whenCompleteAsync((columns, sampleError) -> {
                                try {
                                    if (sampleError != null) { aggregate.completeExceptionally(sampleError); return; }
                                    if (aggregate.isDone()) return;
                                    if (evidence.accept(WallFinePlanner.sampleOrder(sampled, from, to),
                                            columns, job.probed())) {
                                        sampledColumns.addAndGet(columns.size());
                                        rawColumns.addAndGet(job.emittedRaw());
                                        walkedSections.addAndGet(job.sections());
                                        wideSections.addAndGet(job.wideSections());
                                        probedColumns.addAndGet(job.probedColumns());
                                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                                                local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.FINE_SAMPLE,
                                                sampledColumns.get(), expectedColumns.get());
                                    }
                                } catch (Throwable t) { aggregate.completeExceptionally(t); }
                                finally {
                                    // 最后一个切片交回缓存额度，其他段无需等待本轮聚合发布。
                                    segmentLease.releaseUser();
                                    slicesPending.decrementAndGet();
                                    if (!aggregate.isDone()) completion.run();
                                }
                            }, pump);
                        }
                    } catch (Throwable t) {
                        aggregate.completeExceptionally(t);
                    } finally {
                        // 路径和切片都排空才能发布，单一计数可能过早归零。
                        segmentLease.releaseUser();
                        routesPending.decrementAndGet();
                        if (!aggregate.isDone()) completion.run();
                    }
                    }, pump);
                }, pump);
            }
        } catch (Throwable t) {
            // 提交异常也要结案，否则驱动会空转。
            aggregate.completeExceptionally(t);
        } finally {
            enqueueing.set(false);
        }
        // 没有安全段的窗口也须结案，此时没有切片回调替它发布。
        if (!aggregate.isDone()) completion.run();
        return aggregate;
    }

    /** 单阶段复用同一定时器，重复请求不能叠加驱动。 */
    private void startFineSampleDriver() {
        if (fineSampleTimer != null || scheduler.closed()) return;
        fineSampleTimer = pump.scheduleWithFixedDelay(() -> {
            if (scheduler.closed() || fineSampleTimer == null) return;
            reportFineSampleProgress();
            scheduler.driveStartupSlice();
        }, 0, GreatWallConfig.get().fineSampleDriverIntervalMs(), TimeUnit.MILLISECONDS);
    }

    /** 定时检测异常进度；常态只读计数，保持推进开销低。 */
    private boolean checkFineSampleRunaway() {
        var abandon = fineSampleAbandon;
        var done = fineSampleDone;
        var total = fineSampleTotal;
        if (abandon == null || done == null || total == null) return false;
        long expected = total.get();
        long columns = done.get();
        // 估计分母仍是初始值一时不用比例判断，避免误触发。
        if (!scheduler.landFirstPlanning() && expected >= FINE_SAMPLE_RUNAWAY_MIN_EXPECTED
                && columns > FINE_SAMPLE_RUNAWAY_NUMERATOR * expected) {
            return abandonFineSample(abandon, columns, expected, "ratio");
        }
        // 列增长但没有新地形单元，可能是在反复走同一区段。
        long now = System.nanoTime();
        if (now - runawayLastCheckNanos < FINE_SAMPLE_RUNAWAY_WINDOW_NANOS) return false;
        runawayLastCheckNanos = now;
        long misses = scheduler.laneDiagnostic("chunk-cache-misses");
        long hits = scheduler.laneDiagnostic("chunk-cache-hits");
        if (hits + misses == 0L) {
            // 缺少通道缓存诊断时无法判断重复，保留比例判据。
            runawayIdleChecks = 0;
            return false;
        }
        long columnDelta = columns - runawayLastColumns;
        long missDelta = Math.max(0L, misses - runawayLastMisses);
        runawayLastColumns = columns;
        runawayLastMisses = misses;
        // 慢任务也可能暂不产出列；有未完成工作且通道全空闲才计停滞。
        if (columnDelta == 0L && outstandingFineSampleWork() && scheduler.lanesIdle()) {
            if (++runawayStallChecks >= FINE_SAMPLE_STALL_WINDOWS) {
                return abandonFineSample(abandon, columns, expected, "stalled=" + runawayStallChecks);
            }
        } else {
            runawayStallChecks = 0;
        }
        // 同一区块能提供许多不同的实测列，缓存命中不能作为新版采样的结案依据。
        if (scheduler.landFirstPlanning()) return false;
        if (columnDelta < FINE_SAMPLE_RUNAWAY_MIN_COLUMNS || missDelta > columnDelta / 50L) {
            runawayIdleChecks = 0;   // 仍在发现新地形。
            return false;
        }
        if (++runawayIdleChecks < FINE_SAMPLE_RUNAWAY_IDLE_WINDOWS) return false;
        return abandonFineSample(abandon, columns, expected, "no-new-terrain=" + runawayIdleChecks);
    }

    /** 提前结案走同一发布出口，并保留触发原因供现场复查。 */
    private boolean abandonFineSample(Runnable abandon, long columns, long expected, String why) {
        // 先移除回调再发布，重复定时触发不能再次发布。
        fineSampleAbandon = null;
        if (fineSampleForced != null) fineSampleForced.set(true);
        stopFineSampleDriver();
        GreatWallMod.LOGGER.warn("[GreatWall:S1C] FINE_SAMPLE_RUNAWAY reason={} columns={} expected={} ratio={} "
                        + "sections={} publishPartial={}",
                why, columns, expected, expected <= 0 ? 0 : columns / expected,
                fineSampleSections == null ? 0L : fineSampleSections.get(), !scheduler.landFirstPlanning());
        try {
            abandon.run();
        } catch (RuntimeException alreadyPublished) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] FINE_SAMPLE_RUNAWAY publish failed", alreadyPublished);
        }
        return true;
    }

    /** 仅按日志周期记录吞吐和预计剩余时间，其他轮次尽快返回。 */
    private void reportFineSampleProgress() {
        if (checkFineSampleRunaway()) return;
        var done = fineSampleDone;
        var total = fineSampleTotal;
        if (done == null || total == null) return;
        long start = fineSampleStartNanos;
        if (start == 0L) return;
        long now = System.nanoTime();
        long last = fineSampleLastLogNanos;
        if (last != 0L && now - last < TimeUnit.MILLISECONDS.toNanos(FINE_SAMPLE_REPORT_MILLIS)) return;
        fineSampleLastLogNanos = now;
        int columns = done.get();
        int expected = total.get();
        var raw = fineSampleRaw;
        var sectionCounter = fineSampleSections;
        var wideCounter = fineSampleWideSections;
        var probeCounter = fineSampleProbed;
        long rawColumns = raw == null ? 0L : raw.get();
        long sections = sectionCounter == null ? 0L : sectionCounter.get();
        long wideSections = wideCounter == null ? 0L : wideCounter.get();
        long probes = probeCounter == null ? 0L : probeCounter.get();
        double seconds = Math.max(0.001, (now - start) / 1_000_000_000.0);
        double rate = columns / seconds;
        String eta = expected > columns && rate > 0.0
                ? String.format(java.util.Locale.ROOT, "%.0f", (expected - columns) / rate)
                : "?";
        GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_PROGRESS columns={} expected={} rate={}/s "
                        + "eta={}s raw={} repeatedPct={} expectedSections={} lanes={} chunkCacheHits={} "
                        + "chunkCacheMisses={} walkedBlocks={} "
                        + "blocksPerColumn={} leadRetries={} retryPct={} blockMs={} sliceMs={} constructMs={} "
                        + "cellMs={} biomeMs={} surfaceMs={} surfaceMemoHits={} sections={} wideSections={} "
                        + "widePct={} columnsPerSection={} probesPerSection={}",
                columns, expected, String.format(java.util.Locale.ROOT, "%.0f", rate), eta,
                // 预估截面、实采截面与列重复率同报，区分范围和探测开销。
                rawColumns,
                rawColumns == 0 ? 0 : Math.round(100f * (rawColumns - columns) / rawColumns),
                sections == 0 ? 0 : expected / Math.max(1, rawColumns / sections),
                laneCount, scheduler.laneDiagnostic("chunk-cache-hits"), scheduler.laneDiagnostic("chunk-cache-misses"),
                scheduler.laneDiagnostic("walked-blocks"),
                columns == 0 ? 0 : scheduler.laneDiagnostic("walked-blocks") / columns,
                scheduler.laneDiagnostic("lead-retries"),
                columns == 0 ? 0 : Math.round(100f * scheduler.laneDiagnostic("lead-retries") / columns),
                scheduler.laneDiagnostic("block-ms"), scheduler.laneDiagnostic("slice-ms"), scheduler.laneDiagnostic("construct-ms"),
                scheduler.laneDiagnostic("cell-ms"), scheduler.laneDiagnostic("biome-ms"), scheduler.laneDiagnostic("surface-ms"),
                scheduler.laneDiagnostic("surface-memo-hits"),
                sections, wideSections,
                sections == 0 ? 0 : Math.round(100f * wideSections / sections),
                sections == 0 ? 0 : rawColumns / sections,
                sections == 0 ? 0 : probes / sections);
    }

    /** 无论完成还是关闭，都可以幂等撤下定时器。 */
    void stopFineSampleDriver() {
        var timer = fineSampleTimer;
        fineSampleTimer = null;
        if (timer != null) timer.cancel(false);
    }

    /** 避免日志在总耗时为零时除零。 */
    private static int percentOf(long part, long whole) {
        return whole <= 0L ? 0 : (int) Math.round(100.0D * part / whole);
    }

    /** 存在未完成路径或切片才允许判定停滞。 */
    private boolean outstandingFineSampleWork() {
        var slices = fineSampleSlicesPending;
        var routes = fineSampleRoutesPending;
        if (slices == null) return false;
        return slices.get() > 0 || (routes != null && routes.get() > 0);
    }

    /** 相同末端判断只记录一次，避免同一段重复刷日志。 */
    private static final java.util.Set<String> TERMINAL_CUT_NOTES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void noteTerminalCut(String outcome, long cap, int before, int after) {
        if (!TERMINAL_CUT_NOTES.add(outcome + ":" + (cap < 0 ? "-" : String.valueOf(cap / 256)))) return;
        GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_TERMINAL outcome={} terminalBlock={} pointsBefore={} pointsAfter={}",
                outcome, cap < 0 ? -1 : cap / 256, before, after);
    }

    private int terminalPointLimit(RoutePath path, int points) {
        try {
            var flight = geometryFlight.apply(path.key());
            if (flight == null) { noteTerminalCut("no-flight", -1L, points, points); return points; }
            if (!flight.isDone()) { noteTerminalCut("flight-pending", -1L, points, points); return points; }
            if (flight.isCompletedExceptionally()) { noteTerminalCut("flight-failed", -1L, points, points); return points; }
            var geometry = flight.getNow(null);
            if (geometry == null || geometry.terminal().isEmpty()) return points;
            long cap = geometry.terminal().get().localSQ8() + 32L * 256;
            var arcs = path.localSQ8();
            int lo = 0, hi = arcs.size() - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (arcs.get(mid) <= cap) lo = mid; else hi = mid - 1;
            }
            int cut = Math.max(1, Math.min(points, lo + 1));
            noteTerminalCut("cut", cap, points, cut);
            return cut;
        } catch (RuntimeException unsettled) {
            return points;
        }
    }

    /** 城门和堡垒足迹可能超出窄截面，按候选建筑弧长留好证据。 */
    private java.util.function.IntPredicate widePoints(RoutePath path, long fromQ8) {
        if (path instanceof ImportedSamplePath) {
            // 实物楼体按固定轴向占地，先完整覆盖；只有验证完终态列集合后才能继续稀疏化。
            return ignored -> true;
        }
        var arcs = path.localSQ8();
        int points = arcs.size();
        var wide = new java.util.BitSet(points);
        long toQ8 = fromQ8 + arcs.get(points - 1);
        // 留出轴向余量，覆盖建筑旋转及路线偏差。
        long marginQ8 = 96L * 256L;
        for (long ordinal = Math.max(0L, (fromQ8 - marginQ8) / (800L * 256L) - 1L); ; ordinal++) {
            long site = sites.gatePositionQ8(ordinal);
            if (site - marginQ8 > toQ8) break;
            markWide(wide, arcs, fromQ8, site, marginQ8);
        }
        for (long ordinal = Math.max(0L, (fromQ8 - marginQ8) / (4000L * 256L) - 1L); ; ordinal++) {
            long site = sites.fortPositionQ8(ordinal);
            if (site - marginQ8 > toQ8) break;
            markWide(wide, arcs, fromQ8, site, marginQ8);
        }
        return wide::get;
    }

    /** 用二分确定覆盖起点，只标实际落在建筑窗口内的点。 */
    private static void markWide(java.util.BitSet wide, List<Long> arcs, long fromQ8, long siteQ8, long marginQ8) {
        long lowArc = siteQ8 - marginQ8 - fromQ8;
        long highArc = siteQ8 + marginQ8 - fromQ8;
        int points = arcs.size();
        int lo = 0, hi = points;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arcs.get(mid) < lowArc) lo = mid + 1; else hi = mid;
        }
        for (int i = lo; i < points && arcs.get(i) <= highArc; i++) wide.set(i);
    }

    void persistOnClose() {
        CompletableFuture<FineHeightIndex> current;
        FineSampleEvidence evidence;
        synchronized (this) {
            current = activeSample;
            evidence = evidenceToClose;
            evidenceToClose = null;
        }
        // 先结束等待者，再串行保存已走过的列。构建索引完成也不能抢回已取消的结果。
        if (current != null) current.completeExceptionally(scheduler.closedFailure());
        if (evidence != null) {
            try {
                evidence.close();
            } catch (RuntimeException cannotPersist) {
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] FINE_SAMPLE_CACHE_CLOSE_FAILED reason={}",
                        cannotPersist.toString());
            }
        }
    }
}
