package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class ProtectedColumnViewTest {
    private static final SurfaceProbe.ColumnView SOURCE = new SurfaceProbe.ColumnView() {
        public String state(int y) { return y < 64 ? "minecraft:stone" : "minecraft:air"; }
        public boolean solidSupport(int y) { return y < 64; }
        public boolean water(int y) { return false; }
        public boolean airOrReplaceablePlant(int y) { return y >= 64; }
        public int highestNonPlantFeet() { return 64; }
        public String hash() { return "a".repeat(64); }
        public boolean protectedAt(int y) { return y == 7; }
        public boolean naturalThinIce(int y) { return y == 63; }
    };

    @Test void prefilteredColumnsMatchTheIndependentOldScanIncludingBoundariesAndDuplicates() {
        var boxes = new ArrayList<Bounds>();
        var random = new Random(1729);
        for (int i = 0; i < 150; i++) {
            int x = random.nextInt(64) - 32, z = random.nextInt(64) - 32, y = random.nextInt(96) - 32;
            boxes.add(new Bounds(x, y, z, x + 8, y + 16, z + 8));
        }
        boxes.addAll(List.copyOf(boxes));
        for (int x = -32; x <= 32; x++) for (int z = -32; z <= 32; z++) {
            var pos = new Point3i(x, 0, z);
            var actual = ProtectedColumnView.wrap(SOURCE, pos, boxes);
            for (int y = -33; y < 96; y++) {
                boolean expected = SOURCE.protectedAt(y);
                for (var box : boxes) expected |= x >= box.minX() && x < box.maxXExclusive()
                        && z >= box.minZ() && z < box.maxZExclusive() && y >= box.minY() && y < box.maxYExclusive();
                assertEquals(expected, actual.protectedAt(y));
                assertEquals(SOURCE.naturalThinIce(y) && !expected, actual.naturalThinIce(y));
            }
            assertEquals(SOURCE.hash(), actual.hash());
            assertEquals(SOURCE.state(64), actual.state(64));
            assertEquals(SOURCE.highestNonPlantFeet(), actual.highestNonPlantFeet());
        }
    }

    @Test void noIntersectingStructureStillKeepsOriginalProtectionAndThinIcePrecedence() {
        var boxes = List.of(new Bounds(0, 0, 0, 16, 80, 16));
        var outside = ProtectedColumnView.wrap(SOURCE, new Point3i(16, 0, 16), boxes);
        assertEquals(SOURCE.protectedAt(7), outside.protectedAt(7));
        assertEquals(SOURCE.naturalThinIce(63), outside.naturalThinIce(63));
        var protectedColumn = ProtectedColumnView.wrap(SOURCE, new Point3i(15, 0, 15), boxes);
        assertTrue(protectedColumn.protectedAt(0));
        assertTrue(protectedColumn.protectedAt(79));
        assertFalse(protectedColumn.protectedAt(80));
    }
}
