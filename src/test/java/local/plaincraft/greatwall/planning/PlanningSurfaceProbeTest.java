package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.OptionalInt;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class PlanningSurfaceProbeTest {
    private static final DimensionBounds BOUNDS = new DimensionBounds(-64, 7000, 29_000_000);

    private static ColumnSample dry(int ground) {
        return new ColumnSample(ground, OptionalInt.empty(), OptionalInt.empty(), "minecraft:plains", Quality.REFERENCE, false);
    }
    private static ColumnSample wet(int waterTop, int bed) {
        return new ColumnSample(bed, OptionalInt.of(waterTop), OptionalInt.of(bed), "minecraft:river", Quality.REFERENCE, false);
    }

    @Test void dryColumnIsSupportedWithDrop() {
        var r = PlanningSurfaceProbe.probe(dry(60), 90, 90, false, BOUNDS);
        assertEquals(ProbeKind.SUPPORTED, r.kind());
        assertEquals(60, r.groundTopY());
        assertEquals(30, r.drop(), "base 90 - ground 60");
        assertTrue(r.waterTopY().isEmpty());
    }

    @Test void waterColumnIsWater() {
        var r = PlanningSurfaceProbe.probe(wet(66, 60), 90, 90, false, BOUNDS);
        assertEquals(ProbeKind.WATER, r.kind());
        assertEquals(66, r.waterTopY().getAsInt());
        assertEquals(60, r.bedTopY().getAsInt());
    }

    @Test void protectedColumnIsProtected() {
        var r = PlanningSurfaceProbe.probe(dry(80), 90, 90, true, BOUNDS);
        assertEquals(ProbeKind.PROTECTED, r.kind());
    }

    @Test void dropIsRelativeToBaseNotWalkY() {
        // base may exceed the walkY; drop is base - ground.
        var r = PlanningSurfaceProbe.probe(dry(50), 80, 90, false, BOUNDS);
        assertEquals(40, r.drop());
    }
}
