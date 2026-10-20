package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The band window must never exclude a position the corridor accepts.
 *
 * <p>Narrowing the enumeration is only sound if {@code bandZRange} is a conservative superset of
 * {@code within()}. A counterexample here would mean a structure silently missing from the scan - a
 * wall built through a village - so the assertion is written as a brute-force sweep over every z at
 * every x, for corridors that include a degenerate one, a horizontal one, a vertical one and random
 * diagonals.
 */
class CorridorStructureScanBandTest {
    private static final int MIN = -40;
    private static final int MAX = 40;

    @Test
    void everyAcceptedPositionFallsInsideTheBandWindow() {
        var random = new Random(20261020L);
        for (int trial = 0; trial < 200; trial++) {
            int ox = random.nextInt(MAX - MIN + 1) + MIN;
            int oz = random.nextInt(MAX - MIN + 1) + MIN;
            int ex = random.nextInt(MAX - MIN + 1) + MIN;
            int ez = random.nextInt(MAX - MIN + 1) + MIN;
            int half = 1 + random.nextInt(6);
            if (trial == 0) { ex = ox; ez = oz; }                      // degenerate
            if (trial == 1) { ez = oz; }                                // horizontal
            if (trial == 2) { ex = ox; }                                // vertical
            assertCovers(new CorridorStructureScan.Corridor(ox, oz, ex, ez, half));
        }
    }

    private static void assertCovers(CorridorStructureScan.Corridor corridor) {
        for (int x = MIN; x <= MAX; x++) {
            int[] window = CorridorStructureScan.bandZRange(corridor, x, MIN, MAX);
            for (int z = MIN; z <= MAX; z++) {
                if (!corridor.within(x, z)) continue;
                String at = "corridor " + corridor + " accepts (" + x + "," + z + ")";
                assertTrue(window != null, at + " but the window is null");
                assertTrue(z >= window[0] && z <= window[1],
                        at + " outside window [" + window[0] + "," + window[1] + "]");
            }
        }
    }
}