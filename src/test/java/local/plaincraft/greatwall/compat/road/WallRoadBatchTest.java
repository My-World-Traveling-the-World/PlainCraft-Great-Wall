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
        return new ChunkWritePlan(new ChunkKey("minecraft:overworld",0,0),HASH,HASH,voxels,List.of(),List.of(),List.of(),List.of(),List.of(),HASH);
    }
}
