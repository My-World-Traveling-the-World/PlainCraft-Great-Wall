package local.plaincraft.greatwall.compat.road;

import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 旧逐列查询作独立oracle；不能用新行掩码自己计算期望值。 */
class WallRoadNeighborhoodTest {
    @AfterEach void reset() { WallRoadGuard.clear(); }

    @Test void randomLayoutsMatchEveryRequestedSquareRadius() {
        var random = new Random(8291);
        for (int round = 0; round < 12; round++) {
            WallRoadGuard.clear();
            for (int i = 0; i < 300; i++)
                WallRoadGuard.markColumn(random.nextInt(192) - 96, random.nextInt(192) - 96);
            for (int i = 0; i < 400; i++) {
                int x = random.nextInt(256) - 128, z = random.nextInt(256) - 128;
                int radius = random.nextInt(18) - 2;
                assertEquals(frozen(x, z, Math.max(2, radius)), WallRoadGuard.blocksWithin(x, 0, z, radius));
            }
        }
    }

    @Test void everyWordChunkCornerAndSquareDiagonalKeepExactMargins() {
        for (int x : new int[]{-17, -16, -1, 0, 15, 16})
            for (int z : new int[]{-17, -16, -1, 0, 3, 4, 7, 8, 11, 12, 15, 16}) {
                WallRoadGuard.clear();
                WallRoadGuard.markColumn(x, z);
                for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++)
                    assertEquals(Math.abs(dx) <= 2 && Math.abs(dz) <= 2,
                            WallRoadGuard.blocks(x + dx, 70, z + dz));
            }
    }

    @Test void missThenPublishAndClearThenRepublishAreVisible() {
        assertFalse(WallRoadGuard.blocks(-1, 0, -1));
        WallRoadGuard.markColumn(1, 1);
        assertTrue(WallRoadGuard.blocks(-1, 0, -1));
        WallRoadGuard.clear();
        assertFalse(WallRoadGuard.blocks(-1, 0, -1));
        WallRoadGuard.markChunk(-1, -1, 0, 0, 0, Long.MIN_VALUE);
        assertTrue(WallRoadGuard.blocks(0, 0, 0));
        assertFalse(WallRoadGuard.blocks(2, 0, 2));
    }

    @Test void wrappedIntegerCoordinatesMatchOldAddition() {
        for (int x : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE})
            for (int z : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                WallRoadGuard.clear();
                WallRoadGuard.markColumn(x + 2, z - 2);
                assertEquals(frozen(x, z, 2), WallRoadGuard.blocks(x, 0, z));
                assertTrue(WallRoadGuard.blocks(x, 0, z));
            }
    }

    @Test void largeEmptyRangeDoesNotEnumerateAllChunksOrLoseMarks() {
        WallRoadGuard.markColumn(1000000, -1000000);
        assertFalse(WallRoadGuard.blocksWithin(0, 0, 0, 999999));
        assertTrue(WallRoadGuard.blocksWithin(0, 0, 0, 1000000));
        WallRoadGuard.clear();
        assertFalse(WallRoadGuard.blocksWithin(0, 0, 0, 1000000));
    }

    private static boolean frozen(int x, int z, int reach) {
        for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++)
            if (WallRoadGuard.isMarked(x + dx, z + dz)) return true;
        return false;
    }
}
