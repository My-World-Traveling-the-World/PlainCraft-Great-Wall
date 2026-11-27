package local.plaincraft.greatwall.generation;

import java.io.IOException;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Function;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan;

/** 重开时从真实计划恢复占地；合法空计划与读不到已知计划要分别结案。 */
public final class WallRoadRecovery {
    private WallRoadRecovery() {}

    @FunctionalInterface interface Load { ChunkWritePlan read(ChunkKey key) throws IOException; }

    /** 只在同一开局的编译与最终核对之间留回执，旧档、换版本或任一守卫重建都重新发布。 */
    static final class Published {
        private record Receipt(String hash, StructurePlacementGuard structures, long generation, int columns) {}
        private final java.util.concurrent.ConcurrentHashMap<ChunkKey,Receipt> receipts = new java.util.concurrent.ConcurrentHashMap<>();

        void publish(ChunkWritePlan plan, StructurePlacementGuard structures) {
            long generation = local.plaincraft.greatwall.compat.road.WallRoadGuard.generation();
            int columns = WallPlanSupport.publishPlannedColumns(plan, structures);
            receipts.put(plan.owner(), new Receipt(plan.hash(), structures, generation, columns));
        }

        int confirm(ChunkWritePlan plan, StructurePlacementGuard structures) {
            var receipt = receipts.remove(plan.owner());
            if (receipt != null && receipt.structures() == structures && receipt.hash().equals(plan.hash())
                    && receipt.generation() == local.plaincraft.greatwall.compat.road.WallRoadGuard.generation()) {
                return receipt.columns();
            }
            return WallPlanSupport.publishPlannedColumns(plan, structures);
        }
    }

    public record Report(int chunks, int columns, long voxels, int missing, int readFailed,
                         int fromDisk, boolean inventoryFailed) {
        public boolean complete() { return !inventoryFailed && missing == 0 && readFailed == 0; }
    }

    static Report restore(Set<ChunkKey> keys, boolean inventoryFailed, Function<ChunkKey, ChunkWritePlan> cached,
                          Load load, Consumer<ChunkWritePlan> cacheRecovered) {
        return restore(keys, inventoryFailed, cached, load, cacheRecovered, null);
    }

    // 重进档必须同时恢复道路与建筑保护，二者读取同一份持久施工证据。
    static Report restore(Set<ChunkKey> keys, boolean inventoryFailed, Function<ChunkKey, ChunkWritePlan> cached,
                          Load load, Consumer<ChunkWritePlan> cacheRecovered, StructurePlacementGuard structures) {
        return restore(keys, inventoryFailed, cached, load, cacheRecovered, structures, ignored -> {}, new Published());
    }

    static Report restore(Set<ChunkKey> keys, boolean inventoryFailed, Function<ChunkKey, ChunkWritePlan> cached,
                          Load load, Consumer<ChunkWritePlan> cacheRecovered, StructurePlacementGuard structures,
                          IntConsumer progress, Published published) {
        int columns = 0, missing = 0, failed = 0, fromDisk = 0;
        long voxels = 0;
        int checked = 0;
        for (ChunkKey key : keys) {
            // 前序区块无论命中、缺失或读失败都已检查；是否放行由最终报告决定。
            if ((checked & 31) == 0) progress.accept(checked);
            checked++;
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
            columns += published.confirm(plan, structures);
        }
        progress.accept(checked);
        return new Report(keys.size(), columns, voxels, missing, failed, fromDisk, inventoryFailed);
    }
}
