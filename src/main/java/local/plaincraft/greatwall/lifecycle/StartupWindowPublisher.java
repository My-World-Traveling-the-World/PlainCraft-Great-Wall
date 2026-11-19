package local.plaincraft.greatwall.lifecycle;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.generation.CorridorIndex;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningChunkCompiler;

/** 拥有整窗编译发布的结案规则；只有发布及足迹处理后才通知就绪，不能在采样完成时提前开门。 */
final class StartupWindowPublisher {
    @FunctionalInterface interface Compile {
        int compile(PlanWindow window, PlanningChunkCompiler.OwnerProgress progress, Consumer<ChunkWritePlan> sink);
    }
    interface Access {
        void active();
        void install(ChunkWritePlan plan);
        void footprint(ChunkWritePlan plan,CorridorIndex corridor);
        void enqueue(Map<ChunkKey,String> hashes);
        long leases();
        long misses();
        long suppressed();
    }
    private final WorldRecipe recipe;
    private final Runnable release;
    private final Compile compiler;
    private final Access access;
    StartupWindowPublisher(WorldRecipe recipe,Runnable release,Compile compiler,Access access) {
        this.recipe=recipe;this.release=release;this.compiler=compiler;this.access=access;
    }
    // 只搬读写边界；原零成功拒绝、部分失败阈值和预热租约释放次序均原样保留。
    void publish(List<SegmentPlan> plans) {
        if (plans.isEmpty()) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PLANNING_COMPILE_EMPTY 未取得可用分段计划，本次不施工");
            release.run();
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.signalReady();
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
            return;
        }
        var window = new PlanWindow(plans);
        long corridorStarted = System.nanoTime();
        var corridor = CorridorIndex.prepareStartupWindow(window, recipe.dimension());
        GreatWallMod.LOGGER.info("[GreatWall:预热] 走廊保护索引已建立 segments={} boxes={} elapsedMs={}",
                plans.size(), corridor.boxes().size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - corridorStarted));
        var publication = new StartupPlanPublication();
        java.util.function.Consumer<ChunkWritePlan> install = access::install;
        java.util.function.Consumer<ChunkWritePlan> footprint = plan -> access.footprint(plan,corridor);
        long started = System.nanoTime();
        PrewarmProgress.setSubProgress(0, 0);
        int compiled = compiler.compile(window,
                (done, total) -> PrewarmProgress.setSubProgress(PrewarmProgress.Stage.COMPILE, done, total),
                plan -> {
                    access.active(); // 关闭或失败后的迟到批次不能再写施工数据。
                    publication.accept(plan, install, footprint);
                });
        access.active();
        long publishNanos = publication.publishNanos();
        long compileMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started - publishNanos);
        int published = publication.published();
        int failed = publication.failed();
        GreatWallMod.LOGGER.info("[GreatWall:S1C] PLANNING_COMPILE_TRIGGERED segments={} chunks={} published={} "
                        + "failed={} compileMs={} publishMs={} vegetationLeases={} guardMisses={} suppressed={}；整窗编译发布已结案",
                plans.size(), compiled, published, failed, compileMillis, TimeUnit.NANOSECONDS.toMillis(publishNanos),
                access.leases(), access.misses(), access.suppressed());
        int tolerated = Math.max(8, compiled / 20);
        if (published == 0) {
            GreatWallMod.LOGGER.error("[GreatWall:S1C] PLANNING_COMPILE_EMPTY owners={} failed={}；没有成功发布的区块，保持未发布状态",
                    compiled, failed);
            release.run();
            PrewarmProgress.finish(false);
            return;
        }
        access.enqueue(publication.queuedHashes());
        if (failed > tolerated) {
            GreatWallMod.LOGGER.error("[GreatWall:S1C] PLANNING_COMPILE_FAILED published={} failed={} of "
                            + "{} owners (tolerated {})；发布失败超过容忍上限",
                    published, failed, compiled, tolerated, publication.firstFailure());
            release.run();
            PrewarmProgress.finish(false);
            return;
        }
        if (failed > 0) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PLANNING_COMPILE_PARTIAL published={} failed={} of "
                            + "{} owners (tolerated {})；部分区块发布失败，保留已接纳区块的补建队列",
                    published, failed, compiled, tolerated, publication.firstFailure());
        }
        release.run();
        PrewarmProgress.setSubProgress(compiled, compiled);
        PrewarmProgress.setStage(PrewarmProgress.Stage.DONE);
        PrewarmProgress.signalReady();
        PrewarmProgress.markPublished();
    }
}
