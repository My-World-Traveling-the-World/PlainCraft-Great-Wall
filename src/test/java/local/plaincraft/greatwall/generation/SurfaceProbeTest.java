package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.Set;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SurfaceProbeTest {
    private static final DimensionBounds BOUNDS = new DimensionBounds(-64, 8192, 29_999_000);
    private static final int W = 6000;

    @ParameterizedTest @ValueSource(ints = {0,4,5,12,13})
    void dropBoundary(int drop) {
        var c = new Column(W - 20 - drop);
        ProbeResult result = new SurfaceProbe().probe(c, W, BOUNDS);
        assertEquals(drop <= 12 ? ProbeKind.SUPPORTED : ProbeKind.NO_SUPPORT, result.kind());
        if (drop <= 12) assertEquals(drop, result.drop());
    }

    @Test void lowerGuardReadsWMinus35() {
        var c = new Column(W - 32);
        assertEquals(ProbeKind.SUPPORTED, new SurfaceProbe().probe(c, W, BOUNDS).kind());
        assertTrue(c.reads.contains(W - 35));
        assertFalse(c.reads.contains(W - 36));
    }

    @Test void upperSentinelDoesNotInventAir() {
        var c = new Column(W - 20); c.sentinel = false;
        assertEquals(ProbeKind.UPPER_CENSORED, new SurfaceProbe().probe(c, W, BOUNDS).kind());
        c.missing = true;
        assertThrows(WallFailure.class, () -> new SurfaceProbe().probe(c, W, BOUNDS));
        var high = new Column(W + 30);
        assertEquals(ProbeKind.UPPER_CENSORED, new SurfaceProbe().probe(high, W, BOUNDS).kind());
    }

    @Test void leavesPowderSnowAndCaveRoofNotFoundation() {
        var c = new Column(W - 20); c.thinRoof = W - 4;
        assertEquals(W - 20, new SurfaceProbe().probe(c, W, BOUNDS).groundTopY());
        c.plant = W - 8;
        assertEquals(W - 20, new SurfaceProbe().probe(c, W, BOUNDS).groundTopY());
        c.powderSnow = W - 5;
        assertEquals(W - 20, new SurfaceProbe().probe(c, W, BOUNDS).groundTopY());
    }

    @Test void protectedLowerLayerRejectsWholeColumnAndWaterReportsBed() {
        var c = new Column(W - 24); c.protectedY = W - 25;
        assertEquals(ProbeKind.PROTECTED, new SurfaceProbe().probe(c, W, BOUNDS).kind());
        c.protectedY = Integer.MIN_VALUE; c.waterTop = W - 18;
        var water = new SurfaceProbe().probe(c, W, BOUNDS);
        assertEquals(ProbeKind.WATER, water.kind());
        assertEquals(W - 18, water.waterTopY().orElseThrow());
        assertEquals(W - 24, water.bedTopY().orElseThrow());
    }

    @Test void dimensionBottomRequiresThreeLayersAndTopIsCensored() {
        assertEquals(ProbeKind.NO_SUPPORT, new SurfaceProbe().probe(new Column(-63), -40, BOUNDS).kind());
        assertEquals(ProbeKind.UPPER_CENSORED, new SurfaceProbe().probe(new Column(8170), 8190, BOUNDS).kind());
    }

    private static final class Column implements SurfaceProbe.ColumnView {
        private final int ground;
        private final Set<Integer> reads = new HashSet<>();
        private boolean sentinel = true, missing;
        private int protectedY = Integer.MIN_VALUE, thinRoof = Integer.MIN_VALUE;
        private int plant = Integer.MIN_VALUE, powderSnow = Integer.MIN_VALUE, waterTop = Integer.MIN_VALUE;
        Column(int ground) { this.ground = ground; }
        @Override public String state(int y) {
            reads.add(y);
            if (missing && y == W + 7) throw new WallFailure(FailureCode.T0_MISSING, "missing test page");
            if (y == plant) return "minecraft:oak_leaves";
            if (y == powderSnow) return "minecraft:powder_snow";
            if (y < ground || y == thinRoof) return "minecraft:stone";
            if (y < waterTop) return "minecraft:water";
            if (!sentinel && y == W + 7) return "minecraft:stone";
            return "minecraft:air";
        }
        @Override public boolean solidSupport(int y) { return state(y).equals("minecraft:stone"); }
        @Override public boolean protectedAt(int y) { state(y); return y == protectedY; }
        @Override public boolean water(int y) { return state(y).equals("minecraft:water"); }
        @Override public boolean airOrReplaceablePlant(int y) { String s = state(y); return s.equals("minecraft:air") || s.equals("minecraft:oak_leaves"); }
        @Override public int highestNonPlantFeet() { return Math.max(ground, waterTop); }
        @Override public String hash() { return "a".repeat(64); }
    }
}
