package local.plaincraft.greatwall.lifecycle;

import java.util.function.Supplier;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.generation.WallRoadRecovery;

/** 结束长城自己的启动阶段；初始道路是否能开工取决于真实保护覆盖。 */
final class StartupRoadProtection {
    private StartupRoadProtection() {}

    static void finishAfterRestore(Supplier<WallRoadRecovery.Report> restore) {
        WallRoadRecovery.Report report;
        try {
            report = restore.get();
        } catch (RuntimeException failure) {
            GreatWallMod.LOGGER.error("[GreatWall:S1C] ROAD_GUARD_SEED_FAILED 长城保护发布失败", failure);
            PrewarmProgress.finish(false);
            return;
        }
        GreatWallMod.LOGGER.info("[GreatWall:S1C] ROAD_GUARD_SEEDED_FROM_PLANS chunks={} columns={} voxels={} missed={} fromDisk={} readFailed={} inventoryFailed={}",
                report.chunks(), report.columns(), report.voxels(), report.missing(), report.fromDisk(),
                report.readFailed(), report.inventoryFailed());
        if (!report.complete()) {
            // 让长城加载线程退出；道路经现有失败 API 结束等待，不会在未知占地上铺路。
            GreatWallMod.LOGGER.error("[GreatWall:S1C] ROAD_GUARD_SEED_INCOMPLETE 存档长城保护恢复不完整，本次不开放初始道路");
            PrewarmProgress.finish(false);
            return;
        }
        GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_PAINT_DEFERRED_TO_WORLD 保护已发布，墙体由进世界后的补建落块");
        PrewarmProgress.finish(true);
    }
}
