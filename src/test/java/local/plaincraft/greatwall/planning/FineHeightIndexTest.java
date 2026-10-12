package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.OptionalInt;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class FineHeightIndexTest {
    private static WallFinePlanner.FineColumn column(int x, int z, int ground) {
        return new WallFinePlanner.FineColumn(x, z,
                new ColumnSample(ground, OptionalInt.empty(), OptionalInt.empty(), "minecraft:plains", Quality.REFERENCE, false));
    }

    @Test void lookupByCoordinate() {
        var index = FineHeightIndex.of(List.of(
                column(1000, 0, 70),
                column(1001, 0, 72),
                column(1000, 1, 71)));
        assertEquals(3, index.size());
        assertEquals(70, index.at(1000, 0).groundTopY());
        assertEquals(72, index.at(1001, 0).groundTopY());
        assertEquals(71, index.at(1000, 1).groundTopY());
    }

    @Test void missingCoordinateReturnsNull() {
        var index = FineHeightIndex.of(List.of(column(1000, 0, 70)));
        assertNull(index.at(999, 0));
        assertFalse(index.contains(999, 0));
        assertTrue(index.contains(1000, 0));
    }

    @Test void negativeCoordinatesAreDistinct() {
        var index = FineHeightIndex.of(List.of(
                column(-1000, -500, 60),
                column(1000, 500, 80)));
        assertEquals(60, index.at(-1000, -500).groundTopY());
        assertEquals(80, index.at(1000, 500).groundTopY());
    }
}
