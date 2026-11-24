package local.plaincraft.greatwall.generation;

import local.plaincraft.greatwall.model.WallTypes.Bounds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 覆盖负坐标、恰好16格和走廊空洞，不能把整个区块当建筑禁区。 */
class StructurePlacementGuardTest {
    @Test void marginIsExactlySixteenAndWorldsNeverShareMasks() {
        var guard = new StructurePlacementGuard();
        long[] mask = {1L, 0, 0, 0};
        guard.mark(-1, -1, mask);
        mask[0] = 0;
        assertTrue(guard.intersects(box(-32, -16)));
        assertTrue(guard.intersects(box(0, 0)));
        assertFalse(guard.intersects(box(1, 0)));
        assertFalse(guard.intersects(box(-33, -16)));
        assertFalse(new StructurePlacementGuard().intersects(box(-16, -16)));
    }

    @Test void scansTheWholeBuildingAndPreservesHolesAndMergedRows() {
        var guard = new StructurePlacementGuard();
        guard.mark(0, 0, new long[] {1L, 0, 0, 0});
        guard.mark(0, 0, new long[] {0, 0, 0, Long.MIN_VALUE});
        assertTrue(guard.intersects(box(31, 31)));
        assertTrue(guard.intersects(new Bounds(-80, 200, -1, 2, 220, 2)));
        guard.mark(100, 100, new long[] {1, 0, 0, 0});
        assertFalse(guard.intersects(box(800, 800)));
        assertFalse(guard.intersects(box(32, 32)));
    }

    private static Bounds box(int x, int z) { return new Bounds(x, -64, z, x + 1, 320, z + 1); }
}
