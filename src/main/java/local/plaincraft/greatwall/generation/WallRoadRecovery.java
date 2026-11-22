package local.plaincraft.greatwall.generation;

import java.io.IOException;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan;

/** 重开时从真实计划恢复占地；合法空计划与读不到已知计划要分别结案。 */
public final class WallRoadRecovery {
    private WallRoadRecovery() {}

    @FunctionalInterface interface Load { ChunkWritePlan read(ChunkKey key) throws IOException; }

    public record Report(int chunks, int columns, long voxels, int missing, int readFailed,
                         int fromDisk, boolean inventoryFailed) {
        public boolean complete() { return !inventoryFailed && missing == 0 && readFailed == 0; }
    }

    static Report restore(Set<ChunkKey> keys, boolean inventoryFailed, Function<ChunkKey, ChunkWritePlan> cached,
                          Load load, Consumer<ChunkWritePlan> cacheRecovered) {
        int columns = 0, missing = 0, failed = 0, fromDisk = 0;
        long voxels = 0;
        for (ChunkKey key : keys) {
            ChunkWritePlan plan = cached.apply(key);
            if (plan == null) {
                try {
                    plan = load.read(key);
                } catch (IOException | RuntimeException failure) {
                    failed++;
                    if (failed <= 4) GreatWallMod.LOGGER.warn(
                            "[GreatWall:S1C] ROAD_GUARD_PLAN_READ_FAILED 长城保护计划不可读 owner={},{} reason={}",
                            key.x(), key.z(), failure.toString());
                    continue;
                }
                if (plan == null) {
                    missing++;
                    continue;
                }
                cacheRecovered.accept(plan);
                fromDisk++;
            }
            voxels += plan.voxels().size();
            columns += WallPlanSupport.publishPlannedColumns(plan);
        }
        return new Report(keys.size(), columns, voxels, missing, failed, fromDisk, inventoryFailed);
    }
}
