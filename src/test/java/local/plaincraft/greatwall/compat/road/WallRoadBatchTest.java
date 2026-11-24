package local.plaincraft.greatwall.compat.road;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import local.plaincraft.greatwall.generation.WallPlanSupport;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 按旧逐列发布结果对拍，覆盖负坐标、四个字、重叠计划和纯空气列。 */
class WallRoadBatchTest {
    private static final String HASH = "0".repeat(64);
    @AfterEach void reset() { WallRoadGuard.clear(); }

    @Test void batchMatchesEveryExactAndPaddedAnswer() {
        var random = new Random(9128);
        var voxels = new ArrayList<VoxelIntent>();
        for (int i = 0; i < 5000; i++) {
            int x = random.nextInt(64) - 32, z = random.nextInt(64) - 32;
            voxels.add(voxel(x, i % 64, z, i % 2 == 0 ? IntentKind.OWNED_VOID : IntentKind.SOLID));
        }
        boolean[] expected = answers(voxels, true);
        WallRoadGuard.clear();
        var distinct = new HashSet<Long>();
        for (var v : voxels) distinct.add(((long) v.pos().x() << 32) ^ (v.pos().z() & 0xffffffffL));
        assertEquals(distinct.size(), WallPlanSupport.publishPlannedColumns(plan(voxels)));
        assertArrayEquals(expected, answers(voxels, false));
        assertEquals(distinct.size(), WallPlanSupport.publishPlannedColumns(plan(voxels)), "重复发布计数口径不依赖全局已有位");
        WallRoadGuard.clear();
        WallPlanSupport.markPlannedColumns(plan(voxels));
        assertArrayEquals(expected, answers(voxels, false), "clear后不能合并到脱离当前世界的旧数组");
    }

    @Test void ownerChunkMaskKeepsSparseNegativeColumnsAndDeduplicatesLayers() {
        var voxels = new ArrayList<VoxelIntent>();
        var owner = new ChunkKey("minecraft:overworld", -3, 1);
        for (int y = -60; y < -20; y++) {
            voxels.add(voxel(-48, y, 16, IntentKind.SOLID));
            voxels.add(voxel(-38, y, 21, IntentKind.OWNED_VOID));
            voxels.add(voxel(-33, y, 31, IntentKind.SOLID));
        }
        // 120 个体素只占三列，低位、跨字和最后一位都必须被标记。
        assertEquals(3, WallPlanSupport.publishPlannedColumns(plan(owner, voxels)));
        assertEquals(1, WallRoadGuard.markedChunks());
        assertEquals(3, WallRoadGuard.markedColumnsIn(-3, 1));
        assertTrue(WallRoadGuard.isMarked(-48, 16));
        assertTrue(WallRoadGuard.isMarked(-38, 21));
        assertTrue(WallRoadGuard.isMarked(-33, 31));
        assertFalse(WallRoadGuard.isMarked(-39, 21));
        assertFalse(WallRoadGuard.isMarked(16, -48));
    }

    @Test void pureAirAndEveryWordAreRetained() {
        var voxels = new ArrayList<VoxelIntent>();
        for (int z = -16; z < 0; z++) for (int x = -16; x < 0; x++)
            voxels.add(voxel(x, 2, z, IntentKind.OWNED_VOID));
        assertEquals(256, WallPlanSupport.publishPlannedColumns(plan(voxels)));
        assertEquals(256, WallRoadGuard.markedColumnsIn(-1, -1));
        assertFalse(WallRoadGuard.isMarked(0, 0));
        assertEquals(0, WallPlanSupport.publishPlannedColumns(plan(List.of())));
        WallRoadGuard.markChunk(100, 100, 0, 0, 0, 0);
        assertEquals(1, WallRoadGuard.markedChunks(), "空批次不能制造保护区块");
    }

    @Test void structureProtectionReceivesActualOwnerAndSpillColumnsInTheSamePublish() {
        var guard = new local.plaincraft.greatwall.generation.StructurePlacementGuard();
        var voxels = List.of(voxel(-48, 20, 16, IntentKind.SOLID),
                voxel(80, 40, 100, IntentKind.OWNED_VOID));
        assertEquals(2, WallPlanSupport.publishPlannedColumns(plan(voxels), guard));
        assertTrue(guard.intersects(new Bounds(-64, 0, 16, -63, 100, 17)));
        assertTrue(guard.intersects(new Bounds(96, 0, 100, 97, 100, 101)));
        assertFalse(guard.intersects(new Bounds(97, 0, 100, 98, 100, 101)));
        assertFalse(guard.intersects(new Bounds(0, 0, 0, 1, 100, 1)));
    }

    private static boolean[] answers(List<VoxelIntent> voxels, boolean publish) {
        if (publish) {
            WallRoadGuard.clear();
            for (var v : voxels) WallRoadGuard.markColumn(v.pos().x(), v.pos().z());
        }
        var result = new boolean[68 * 68 * 3]; int i = 0;
        for (int x = -34; x < 34; x++) for (int z = -34; z < 34; z++) {
            result[i++] = WallRoadGuard.isMarked(x, z);
            result[i++] = WallRoadGuard.blocks(x, 0, z);
            result[i++] = WallRoadGuard.coarseMayAffect(x, z);
        }
        return result;
    }
    private static VoxelIntent voxel(int x, int y, int z, IntentKind kind) {
        return new VoxelIntent(new Point3i(x,y,z), kind, kind == IntentKind.OWNED_VOID ? Part.AIR : Part.CORE,
                new BlockSpec(kind == IntentKind.OWNED_VOID ? "minecraft:air" : "minecraft:stone", Map.of()),
                new SiteId("wall", SiteKind.WALL, 0), 100, true, false);
    }
    private static ChunkWritePlan plan(List<VoxelIntent> voxels) {
        return plan(new ChunkKey("minecraft:overworld", 0, 0), voxels);
    }
    private static ChunkWritePlan plan(ChunkKey owner, List<VoxelIntent> voxels) {
        return new ChunkWritePlan(owner, HASH, HASH, voxels, List.of(), List.of(), List.of(), List.of(), List.of(), HASH);
    }
}
