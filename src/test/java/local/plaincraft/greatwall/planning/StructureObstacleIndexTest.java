package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import local.plaincraft.greatwall.generation.CorridorStructureScan.OwnedBox;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class StructureObstacleIndexTest {
    private static final int MARGIN = 2;

    private static OwnedBox box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new OwnedBox("minecraft:village", 0L,
                new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ));
    }

    @Test void emptyIndexNeverBlocks() {
        var index = StructureObstacleIndex.empty(MARGIN);
        assertFalse(index.blocked(100L << 8, 100L << 8));
        assertEquals(0, index.boxCount());
    }

    @Test void pointInsideBoxIsBlocked() {
        var index = StructureObstacleIndex.of(List.of(box(0, 60, 0, 31, 90, 31)), MARGIN);
        assertTrue(index.blocked(16L << 8, 16L << 8), "center of box must block");
        assertTrue(index.blocked(0L << 8, 0L << 8), "min corner is inclusive");
        assertTrue(index.blocked(31L << 8, 31L << 8), "max corner is inclusive");
    }

    @Test void pointOutsideBoxWithinMarginIsBlocked() {
        var index = StructureObstacleIndex.of(List.of(box(10, 60, 10, 20, 90, 20)), MARGIN);
        // x=8 is 2 blocks before minX=10, inside margin 2
        assertTrue(index.blocked(8L << 8, 15L << 8));
        // x=22 is 2 blocks after maxX=20, inside margin 2
        assertTrue(index.blocked(22L << 8, 15L << 8));
    }

    @Test void pointBeyondMarginIsNotBlocked() {
        var index = StructureObstacleIndex.of(List.of(box(10, 60, 10, 20, 90, 20)), MARGIN);
        // x=7 is 3 blocks before minX=10, outside margin 2
        assertFalse(index.blocked(7L << 8, 15L << 8));
        // far away
        assertFalse(index.blocked(1000L << 8, 1000L << 8));
    }

    @Test void negativeCoordinatesAreHandled() {
        var index = StructureObstacleIndex.of(List.of(box(-32, 60, -32, -1, 90, -1)), MARGIN);
        assertTrue(index.blocked(-16L << 8, -16L << 8));
        // (4,4) is 5 blocks past maxX/maxZ=-1, outside margin 2
        assertFalse(index.blocked(4L << 8, 4L << 8));
    }

    @Test void marginStacksWithBoxEdgesAcrossCells() {
        var index = StructureObstacleIndex.of(List.of(box(63, 60, 63, 65, 90, 65)), MARGIN);
        // box spans grid cell boundary (64), so both sides must resolve via different cells
        assertTrue(index.blocked(64L << 8, 64L << 8));
        assertTrue(index.blocked(63L << 8, 64L << 8));
    }

    @Test void sameLayerFilterIgnoresUndergroundAndSkyStructures() {
        // A structure whose Y range [0,40] sits far below the wall's surface band [60,90].
        var index = StructureObstacleIndex.of(List.of(box(0, 0, 0, 31, 40, 31)), MARGIN);
        // XZ inside the box, but Y band does not overlap -> not blocked.
        assertFalse(index.blocked(16L << 8, 16L << 8, 60, 90));
        // Overlapping Y band blocks.
        assertTrue(index.blocked(16L << 8, 16L << 8, 20, 80));
        // The height-agnostic overload still blocks (backward-compatible surface query).
        assertTrue(index.blocked(16L << 8, 16L << 8));
    }
}
