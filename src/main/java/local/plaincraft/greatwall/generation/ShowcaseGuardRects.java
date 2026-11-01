package local.plaincraft.greatwall.generation;

/**
 * Road-guard footprints for showcase templates whose content is laid out in bands.
 *
 * <h2>Why not one rectangle</h2>
 * Every rectangle published here is a hard "no road and no roadside decoration" answer, and the
 * kitset bench is 784 x 421 with 78.66% of its columns holding nothing - the eight module rows are
 * separated by ten-block aisles and the tail of the layout is empty. One box round the whole bench
 * therefore answered "blocked" for 330,064 columns of which 259,640 were air, and it also cut the
 * road short at the bench edge: eight of the twelve {@code ROAD_WALL_CONTACT} points sat inside it.
 *
 * <h2>Why bands, and why these numbers</h2>
 * The occupied columns form eight bands along z with 10-block gaps between them; no column of x is
 * empty across every band, so the cut can only run along z. Each band starts at the layout's west
 * edge and ends at that row's last occupied column, which makes the union a superset of every
 * non-air column: the bench itself stays fully protected, the aisles between rows open up.
 *
 * <p>Offsets are relative to the layout's north-west corner, which both publishing paths compute -
 * {@link ShowcaseStamp#start} from the stamped origin and {@link ShowcaseMarker#publishGuardLayout}
 * from the spawn - never relative to the world origin.
 */
final class ShowcaseGuardRects {
    /** {@code {relMinX, relMinZ, relMaxX, relMaxZ}} - one entry per occupied row of the bench. */
    private static final int[][] KITSET_BANDS = {
            {0, 0, 531, 54},
            {0, 65, 442, 126},
            {0, 137, 440, 194},
            {0, 205, 286, 242},
            {0, 253, 783, 301},
            {0, 312, 554, 345},
            {0, 356, 426, 379},
            {0, 390, 448, 410},
    };

    private ShowcaseGuardRects() {
    }

    /**
     * Publishes the guard footprints for {@code csvName}, relative to the layout's north-west corner.
     *
     * @return {@code false} when the template has no band table, so the caller publishes its own
     *         single rectangle instead of guessing one here
     */
    static boolean publish(String csvName, int originX, int originZ) {
        int[][] bands = bandsFor(csvName);
        if (bands == null) return false;
        for (int[] band : bands) {
            local.plaincraft.greatwall.compat.road.WallRoadGuard.publishRect(
                    originX + band[0], originZ + band[1], originX + band[2], originZ + band[3]);
        }
        return true;
    }

    private static int[][] bandsFor(String csvName) {
        // Exact, never a suffix: "watergate.csv".endsWith("gate.csv") is true, and that mistake
        // already cost the water gate its block-entity table once.
        return "kitset.csv".equals(csvName) ? KITSET_BANDS : null;
    }
}
