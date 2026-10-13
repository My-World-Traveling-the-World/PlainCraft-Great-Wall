package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.OptionalInt;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class FineHeightIndexTest {
    private static WallFinePlanner.FineColumn column(int x, int z, int ground) {
        return new WallFinePlanner.FineColumn(x, z, 0,
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

    private static WallFinePlanner.FineColumn sample(int x, int z, int lateral, int ground, int waterTop,
            Quality quality) {
        return new WallFinePlanner.FineColumn(x, z, lateral, new ColumnSample(ground,
                waterTop == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(waterTop),
                waterTop == Integer.MIN_VALUE ? OptionalInt.empty() : OptionalInt.of(waterTop - 4),
                "minecraft:plains", quality, false));
    }

    private static int lateralAt(FineHeightIndex index, int x, int z) {
        return index.columns().stream()
                .filter(column -> column.x() == x && column.z() == z)
                .findFirst().orElseThrow().lateral();
    }

    @Test void overlappingCrossSectionsResolveTheSameWayInEitherArrivalOrder() {
        // One point probed this column (70), its neighbour filled it (the maximum of that neighbour's
        // own probes, 72). Which slice finished first used to decide the published value.
        var probed = sample(1000, 0, 0, 70, Integer.MIN_VALUE, Quality.REFERENCE);
        var filled = sample(1000, 0, 3, 72, Integer.MIN_VALUE, Quality.REFERENCE);
        var forward = FineHeightIndex.of(List.of(probed, filled));
        var reverse = FineHeightIndex.of(List.of(filled, probed));
        assertEquals(1, forward.size());
        assertEquals(72, forward.at(1000, 0).groundTopY());
        assertEquals(72, reverse.at(1000, 0).groundTopY());
        assertEquals(forward.at(1000, 0), reverse.at(1000, 0));
    }

    @Test void conflictKeepsTheWaterReportOverAFilledNeighbour() {
        // A fill never reports water, so the wet sample is the real probe: it must win even though the
        // filled neighbour claims a higher, dry surface.
        var wetProbe = sample(1000, 0, 0, 70, 66, Quality.REFERENCE);
        var dryFill = sample(1000, 0, 4, 75, Integer.MIN_VALUE, Quality.REFERENCE);
        for (var order : List.of(List.of(wetProbe, dryFill), List.of(dryFill, wetProbe))) {
            var index = FineHeightIndex.of(order);
            assertEquals(OptionalInt.of(66), index.at(1000, 0).waterTopY());
            assertEquals(62, index.at(1000, 0).bedTopY().orElseThrow());
            assertEquals(70, index.at(1000, 0).groundTopY());
        }
    }

    @Test void conflictFallsBackToQualityThenLateral() {
        var coarse = sample(1000, 0, 0, 70, Integer.MIN_VALUE, Quality.COARSE);
        var reference = sample(1000, 0, 5, 70, Integer.MIN_VALUE, Quality.REFERENCE);
        assertEquals(Quality.REFERENCE, FineHeightIndex.of(List.of(coarse, reference)).at(1000, 0).quality());
        assertEquals(Quality.REFERENCE, FineHeightIndex.of(List.of(reference, coarse)).at(1000, 0).quality());
        // Identical samples: the smaller |lateral| wins, because WallFoundationPlanner reads lateral.
        var far = sample(1001, 0, 5, 70, Integer.MIN_VALUE, Quality.REFERENCE);
        var near = sample(1001, 0, -2, 70, Integer.MIN_VALUE, Quality.REFERENCE);
        assertEquals(-2, lateralAt(FineHeightIndex.of(List.of(far, near)), 1001, 0));
        assertEquals(-2, lateralAt(FineHeightIndex.of(List.of(near, far)), 1001, 0));
    }

    @Test void reorderedOverlapsProduceTheSameIndexColumnForColumn() {
        var columns = new java.util.ArrayList<WallFinePlanner.FineColumn>();
        for (int x = 0; x < 40; x++) {
            columns.add(sample(x, 0, 0, 64 + x, Integer.MIN_VALUE, Quality.REFERENCE));
            columns.add(sample(x, 0, 1, 64 + x + (x % 3), Integer.MIN_VALUE, Quality.COARSE));
            columns.add(sample(x, 0, -1, 64 + x - (x % 2), x % 5 == 0 ? 62 : Integer.MIN_VALUE, Quality.REFERENCE));
        }
        var reversed = new java.util.ArrayList<>(columns);
        java.util.Collections.reverse(reversed);
        var first = FineHeightIndex.of(columns);
        var second = FineHeightIndex.of(reversed);
        assertEquals(first.size(), second.size());
        for (int x = 0; x < 40; x++) {
            assertEquals(first.at(x, 0), second.at(x, 0), "column x=" + x);
        }
    }
}
