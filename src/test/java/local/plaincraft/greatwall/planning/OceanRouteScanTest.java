package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pins the biome scan that decides where a wall stops and which seas it bridges. */
class OceanRouteScanTest {

    /** Builds keys and arc coordinates: one point every 4 blocks, as the ridge planner emits them. */
    private static List<String> keys(String... pattern) {
        var out = new ArrayList<String>();
        for (String step : pattern) {
            int count = Integer.parseInt(step.substring(step.indexOf('*') + 1));
            String key = step.substring(0, step.indexOf('*'));
            for (int i = 0; i < count; i++) out.add(key);
        }
        return out;
    }

    private static List<Long> arcs(int points) {
        var out = new ArrayList<Long>(points);
        for (int i = 0; i < points; i++) out.add(i * 4L * 256L);
        return out;
    }

    @Test
    void aWideSeaIsFoundAndEndsTheWall() {
        var keys = keys("minecraft:plains*40", "minecraft:ocean*30", "minecraft:plains*40");
        var runs = OceanRouteScan.scan(keys, arcs(keys.size()));

        assertEquals(1, runs.size());
        var run = runs.getFirst();
        assertEquals(40, run.fromIndex());
        assertEquals(69, run.toIndex());
        // 29 gaps of 4 blocks between 30 points.
        assertEquals(116, run.widthBlocks());
        assertTrue(run.terminates());
        assertEquals(39, OceanRouteScan.shoreIndex(run), "the wall ends on the last land point");
        assertTrue(OceanRouteScan.firstTerminating(runs).isPresent());
    }

    @Test
    void aNarrowStraitIsBridgedNotTerminated() {
        // 10 points of water = 36 blocks, under the 50-block limit.
        var keys = keys("minecraft:plains*20", "minecraft:ocean*10", "minecraft:plains*20");
        var runs = OceanRouteScan.scan(keys, arcs(keys.size()));

        assertEquals(1, runs.size());
        assertFalse(runs.getFirst().terminates(), "36 blocks is a strait, not a sea");
        assertTrue(OceanRouteScan.firstTerminating(runs).isEmpty());
    }

    @Test
    void theFirstTerminatingRunWinsEvenWithEarlierStraits() {
        var keys = keys("minecraft:plains*10", "minecraft:ocean*9", "minecraft:plains*10",
                "minecraft:ocean*40", "minecraft:plains*10");
        var runs = OceanRouteScan.scan(keys, arcs(keys.size()));

        assertEquals(2, runs.size());
        assertFalse(runs.get(0).terminates());
        assertTrue(runs.get(1).terminates());
        assertEquals(1, runs.indexOf(OceanRouteScan.firstTerminating(runs).orElseThrow()));
    }

    @Test
    void aSmallIslandDoesNotSplitASea() {
        var keys = keys("minecraft:plains*10", "minecraft:ocean*20", "minecraft:plains*3",
                "minecraft:ocean*20", "minecraft:plains*10");
        var runs = OceanRouteScan.scan(keys, arcs(keys.size()));

        assertEquals(1, runs.size(), "three points of island stay inside the run");
        assertEquals(10, runs.getFirst().fromIndex());
        assertEquals(52, runs.getFirst().toIndex());
    }

    @Test
    void aLongLandBridgeSplitsTwoSeas() {
        var keys = keys("minecraft:plains*5", "minecraft:ocean*20", "minecraft:plains*9",
                "minecraft:ocean*20", "minecraft:plains*5");
        var runs = OceanRouteScan.scan(keys, arcs(keys.size()));

        assertEquals(2, runs.size(), "nine points of land is a continent between two seas");
    }

    @Test
    void aPondIsNotACoast() {
        var keys = keys("minecraft:plains*30", "minecraft:ocean*5", "minecraft:plains*30");
        assertTrue(OceanRouteScan.scan(keys, arcs(keys.size())).isEmpty(),
                "five points of water is a pond");
    }

    @Test
    void beachesRiversAndNullsAreLand() {
        assertFalse(OceanRouteScan.isOcean("minecraft:beach"));
        assertFalse(OceanRouteScan.isOcean("minecraft:snowy_beach"));
        assertFalse(OceanRouteScan.isOcean("minecraft:river"));
        assertFalse(OceanRouteScan.isOcean("minecraft:stony_shore"));
        assertFalse(OceanRouteScan.isOcean(null));
        assertTrue(OceanRouteScan.isOcean("minecraft:deep_ocean"));
        assertTrue(OceanRouteScan.isOcean("minecraft:warm_ocean"));
        assertTrue(OceanRouteScan.isOcean("terralith:moonlight_grove_ocean"));
    }

    @Test
    void aRouteThatNeverMeetsWaterHasNoRuns() {
        var keys = keys("minecraft:plains*80");
        assertTrue(OceanRouteScan.scan(keys, arcs(keys.size())).isEmpty());
        assertTrue(OceanRouteScan.scan(List.of(), List.of()).isEmpty());
    }

    @Test
    void arcCoordinatesMustAlignWithTheKeys() {
        var keys = keys("minecraft:ocean*20");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OceanRouteScan.scan(keys, List.of(0L)));
    }
}
