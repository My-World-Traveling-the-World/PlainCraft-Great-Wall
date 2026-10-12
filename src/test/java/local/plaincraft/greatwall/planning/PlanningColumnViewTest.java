package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.OptionalInt;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

/**
 * Proves the old {@link SurfaceProbe} keeps working unchanged when fed a planning-backed
 * {@link PlanningColumnView} instead of a per-block T0 column. This is the bridge that lets
 * RealizationPlanner consume planning data without any per-block terrain dependency.
 */
class PlanningColumnViewTest {
    private static final DimensionBounds BOUNDS = new DimensionBounds(-64, 7000, 29_000_000);

    private static ColumnSample dry(int ground) {
        return new ColumnSample(ground, OptionalInt.empty(), OptionalInt.empty(), "minecraft:plains", Quality.REFERENCE, false);
    }
    private static ColumnSample wet(int waterTop, int bed) {
        return new ColumnSample(bed, OptionalInt.of(waterTop), OptionalInt.of(bed), "minecraft:river", Quality.REFERENCE, false);
    }

    @Test void dryColumnProbesAsSupported() {
        var view = new PlanningColumnView(1000, 0, dry(60));
        var result = new SurfaceProbe().probe(view, 90, BOUNDS);
        assertEquals(ProbeKind.SUPPORTED, result.kind());
        assertEquals(60, result.groundTopY());
    }

    @Test void waterColumnProbesAsWater() {
        var view = new PlanningColumnView(1000, 0, wet(66, 60));
        var result = new SurfaceProbe().probe(view, 90, BOUNDS);
        assertEquals(ProbeKind.WATER, result.kind());
        assertEquals(66, result.waterTopY().getAsInt());
    }

    @Test void solidSupportAndReplaceableAreConsistent() {
        var view = new PlanningColumnView(1000, 0, dry(60));
        assertTrue(view.solidSupport(59), "below ground is solid");
        assertFalse(view.solidSupport(60), "at ground is not solid support");
        assertTrue(view.airOrReplaceablePlant(60), "at ground is replaceable air");
        assertFalse(view.water(60), "dry column has no water");
    }

    @Test void waterColumnReportsWaterInWaterColumn() {
        var view = new PlanningColumnView(1000, 0, wet(66, 60));
        assertTrue(view.water(63), "inside water column");
        assertFalse(view.water(66), "at water surface is air");
        assertTrue(view.solidSupport(59), "below bed is solid");
    }
}
