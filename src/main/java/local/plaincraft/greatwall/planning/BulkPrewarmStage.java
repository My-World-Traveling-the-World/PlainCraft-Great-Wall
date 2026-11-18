package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.concurrent.*;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 批量预热独立管理计划租约和结案，沿用调度器的单线程推进器。 */
final class BulkPrewarmStage {
    private final PlannerScheduler scheduler;
    private final PlannerPump pump;
    private final ConcurrentHashMap<String, Long> state;
    private final int laneCount;
    private final int maxGeometryInflight;

    BulkPrewarmStage(PlannerScheduler scheduler, PlannerPump pump, ConcurrentHashMap<String, Long> state,
            int laneCount, int maxGeometryInflight) {
        this.scheduler = scheduler;
        this.pump = pump;
        this.state = state;
        this.laneCount = laneCount;
        this.maxGeometryInflight = maxGeometryInflight;
    }

    /** 服务端阻塞时仍须推进预热，结案或停服撤下定时器。 */
    private ScheduledFuture<?> prewarmTimer;

    /** 段租约保存到编译读取后，避免计划被淘汰后重算。 */
    private final java.util.List<PlanLease<SegmentPlan>> prewarmLeases =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** 成功后通知会话把编译安排到服务端队列，启动等待可同步泵送。 */
    private volatile Runnable bulkReadyHook;

    /** 后注册的回调替换本会话早先的回调。 */
    void onBulkReady(Runnable hook) {
        this.bulkReadyHook = hook;
    }

    /** 检查关闭与提交不原子，必须报告被拒的提交，否则加载等待无法收口。 */
    private boolean pumpSubmit(Runnable task) {
        try {
            pump.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            GreatWallMod.LOGGER.error("[GreatWall:S1C] PREWARM_NOT_QUEUED reason=pump-closed; the wall does "
                    + "not prewarm in this session and the startup stage is released");
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
            return false;
        }
    }

    /** 批量预热等会话安装结构索引，再按配置和安全包络安排段任务。 */
    void afterRoadBulk() {
        if (scheduler.closed()) {
            // 生命周期通知可能迟到；关闭后给加载方明确失败。
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PREWARM_SKIPPED reason=planner-closed");
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
            return;
        }
        if (state.putIfAbsent("after-road-bulk", 1L) != null) return;
        pumpSubmit(() -> {
            // 只允许持有启动门的提交任务在异常时结束启动等待。
            pump.enterPrewarmBody();
            long target = scheduler.prewarmTargetSegments();
            long started = System.nanoTime();
            state.put("prewarm-target-segments", target);
            state.put("prewarm-start-nanos", started);
            // 发布预计段数供加载进度使用；道路仍等足迹编译发布。
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.begin(
                    (int) Math.min(Integer.MAX_VALUE, target), laneCount);
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.enterPlanning();
            var completedSegments = new java.util.concurrent.atomic.AtomicInteger();
            var prewarm = new ArrayList<PlanLease<SegmentPlan>>();
            for (long i = 0; i < target && scheduler.safeSegment(i); i++) {
                // 分段单独计时才能发现哪一段拖长启动。
                long segmentStarted = System.nanoTime();
                var lease = scheduler.requirePlan(i, Priority.PLAYER_PREFETCH);
                long segment = i;
                lease.future().whenCompleteAsync((plan, error) -> {
                    long elapsedMs = Math.round((System.nanoTime() - segmentStarted) / 1_000_000.0D);
                    if (error == null) {
                        // 仅累计成功结案，异常在另一分支报告。
                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setCompleted(
                                completedSegments.incrementAndGet());
                        // 同时记录站点、建筑、终点与弧长以核对采样覆盖。
                        var segmentPlan = plan;
                        long terminalSQ8 = segmentPlan == null ? -1L
                                : segmentPlan.geometry().terminal().map(terminal -> terminal.localSQ8()).orElse(-1L);
                        int stationCount = segmentPlan == null ? 0 : segmentPlan.stations().stations().size();
                        int siteCount = segmentPlan == null ? 0 : segmentPlan.sites().size();
                        GreatWallMod.LOGGER.info("[GreatWall:S1C] PREWARM_SEGMENT segment={} elapsedMs={} "
                                        + "stations={} sites={} terminalSQ8={} routeSQ8={}",
                                segment, elapsedMs, stationCount, siteCount, terminalSQ8,
                                segmentPlan == null ? -1L : segmentPlan.geometry().localSQ8().getLast());
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
            // 预热通过原推进器自驱，服务端 tick 暂停也能计算。
            prewarmTimer = pump.scheduleWithFixedDelay(() -> {
                if (scheduler.closed() || state.containsKey("bulk-ready") || state.containsKey("bulk-failed")) return;
                state.merge("prewarm-driver-iterations", 1L, Long::sum);
                scheduler.driveStartupSlice();
            }, 0, 1, TimeUnit.MILLISECONDS);
            CompletableFuture.allOf(prewarm.stream().map(PlanLease::future).toArray(CompletableFuture[]::new))
                    .whenCompleteAsync((value, error) -> {
                        long totalMs = Math.round((System.nanoTime() - started) / 1_000_000.0D);
                        // 批次结束立即撤驱动，避免继续消耗预算。
                        stopPrewarmTimer();
                        // 只通知段计划完成；编译和足迹未发布，严格道路等待不放行。
                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.signalReady();
                        if (error == null) {
                            // 成功计划交给编译使用，失败归还本批所有租约。
                            prewarmLeases.addAll(prewarm);
                        } else {
                            prewarm.forEach(PlanLease::close);
                        }
                        state.put(error == null ? "bulk-ready" : "bulk-failed", 1L);
                        state.put("prewarm-total-millis", totalMs);
                        // 通过会话回调投递编译，启动等待期间也不依赖服务端正常 tick。
                        if (error == null) {
                            var hook = bulkReadyHook;
                            if (hook != null) {
                                try {
                                    hook.run();
                                } catch (Throwable hookFailure) {
                                    GreatWallMod.LOGGER.warn("[GreatWall:S1C] BULK_READY_HOOK_FAILED",
                                            hookFailure);
                                }
                            }
                        }
                        if (error == null) {
                            // 同时记录通道数、缓存与几何并发，区分调度和地形开销。
                            GreatWallMod.LOGGER.info("[GreatWall:S1C] PREWARM_COMPLETE segments={} totalMs={} "
                                            + "avgMsPerSegment={} geometryInflight={} lanes={} "
                                            + "chunkCacheHits={} chunkCacheMisses={}",
                                    prewarm.size(), totalMs,
                                    prewarm.isEmpty() ? 0L : totalMs / prewarm.size(),
                                    maxGeometryInflight, laneCount,
                                    scheduler.laneDiagnostic("chunk-cache-hits"), scheduler.laneDiagnostic("chunk-cache-misses"));
                        } else {
                            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PREWARM_FAILED segments={} totalMs={} reason={}",
                                    prewarm.size(), totalMs, error.toString());
                        }
                        if (error != null) scheduler.cancelDemandTimer();
                    }, pump);
        });
    }

    /** 阶段结束和调度器关闭都要停止定时推进。 */
    void stopPrewarmTimer() {
        var timer = prewarmTimer;
        prewarmTimer = null;
        if (timer != null) timer.cancel(false);
    }

    /** 快照供编译复用原租约，不重复请求墙段。 */
    java.util.List<PlanLease<SegmentPlan>> prewarmLeases() {
        synchronized (prewarmLeases) {
            return java.util.List.copyOf(prewarmLeases);
        }
    }

    /** 租约仅从持有表移出一次，关闭与编译可重复调用。 */
    void releasePrewarmLeases() {
        java.util.List<PlanLease<SegmentPlan>> copy;
        synchronized (prewarmLeases) {
            copy = java.util.List.copyOf(prewarmLeases);
            prewarmLeases.clear();
        }
        copy.forEach(PlanLease::close);
    }

}
