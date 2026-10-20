package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the sea along a wall route from biome data alone, before anything is sampled.
 *
 * <h2>Why</h2>
 * A wall ends where it meets wide water, and that decision used to cost a full column sample of
 * every route point - including the ones in the middle of the sea, which are the deepest and
 * therefore the most expensive columns in the whole run. The biome at sea level already says where
 * the water is, and it costs about a thousandth of a column sample to ask.
 *
 * <p>What the caller needs from this class is only <em>where to look</em>: the two coasts and a width.
 * The exact shoreline is still established by sampling a handful of columns around each detected
 * boundary, so a few blocks of disagreement between a biome border and the water line costs nothing.
 *
 * <h2>What counts as sea</h2>
 * <ul>
 *   <li>An ocean biome: {@code #minecraft:is_ocean} when the pack provides the tag, else any biome
 *       whose key contains {@code ocean}. A pack that renames its seas simply gets no runs, and the
 *       caller keeps its old water-based decision.</li>
 *   <li>At least {@link #MIN_RUN_POINTS} consecutive points. A pond crossed by a two-point dip in
 *       the biome map is not a coastline.</li>
 *   <li>Land islands shorter than {@link #MAX_ISLAND_GAP_POINTS} do not split a run: an archipelago
 *       is one sea to a wall, and splitting it would turn one crossing into three.</li>
 * </ul>
 */
public final class OceanRouteScan {
    /** Shortest run of ocean points that counts as a sea, in route points. */
    public static final int MIN_RUN_POINTS = 8;
    /** Longest run of land points that does not split a sea into two (an island in it). */
    public static final int MAX_ISLAND_GAP_POINTS = 4;
    /** A sea wider than this many blocks ends the wall; a narrower one is bridged. */
    public static final int TERMINATE_WIDTH_BLOCKS = 50;

    private OceanRouteScan() {
    }

    /**
     * One run of sea along the route, in point indices and in blocks.
     *
     * @param fromIndex   first route point inside the water
     * @param toIndex     last route point inside the water, inclusive
     * @param widthBlocks arc length of the run, from the first to the last water point
     */
    public record OceanRun(int fromIndex, int toIndex, int widthBlocks) {
        /** {@return whether a wall stops at this sea instead of bridging it} */
        public boolean terminates() {
            return widthBlocks > TERMINATE_WIDTH_BLOCKS;
        }
    }

    /**
     * Scans the biome keys taken along a route.
     *
     * @param biomeKeys one key per route point, in route order; {@code null} entries are treated as land
     * @param arcPerPoint the arc coordinate of each point, used to express a run in blocks
     * @return the seas in route order, never null
     */
    public static List<OceanRun> scan(List<String> biomeKeys, List<Long> arcPerPoint) {
        if (biomeKeys == null || biomeKeys.isEmpty()) return List.of();
        if (arcPerPoint == null || arcPerPoint.size() != biomeKeys.size()) {
            throw new IllegalArgumentException("arcPerPoint must align with biomeKeys");
        }
        int size = biomeKeys.size();
        var ocean = new boolean[size];
        for (int i = 0; i < size; i++) ocean[i] = isOcean(biomeKeys.get(i));

        var runs = new ArrayList<OceanRun>();
        int start = -1;
        int lastOcean = -1;
        int landGap = 0;
        for (int i = 0; i < size; i++) {
            if (ocean[i]) {
                if (start < 0) start = i;
                lastOcean = i;
                landGap = 0;
                continue;
            }
            if (start < 0) continue;
            // A short island stays inside the run; anything longer closes it.
            if (++landGap > MAX_ISLAND_GAP_POINTS) {
                add(runs, start, lastOcean, arcPerPoint);
                start = -1;
                landGap = 0;
            }
        }
        if (start >= 0) add(runs, start, lastOcean, arcPerPoint);
        return List.copyOf(runs);
    }

    /** {@return the first sea a wall would stop at, or empty when the route never meets one} */
    public static java.util.Optional<OceanRun> firstTerminating(List<OceanRun> runs) {
        for (var run : runs) {
            if (run.terminates()) return java.util.Optional.of(run);
        }
        return java.util.Optional.empty();
    }

    /**
     * {@return the point index a wall should end at when it stops at {@code run}}
     *
     * <p>The last land before the water, not the first water: a wall that ends one point into the sea
     * spends its final section standing in the surf.
     */
    public static int shoreIndex(OceanRun run) {
        return Math.max(0, run.fromIndex() - 1);
    }

    /** {@return whether one biome key names an ocean} */
    public static boolean isOcean(String biomeKey) {
        if (biomeKey == null) return false;
        String key = biomeKey.toLowerCase(java.util.Locale.ROOT);
        // A pack's own seas keep the suffix but may not carry the tag, so the name is the fallback
        // rather than the only test. Rivers and beaches deliberately do not match.
        if (key.endsWith(":river") || key.endsWith(":frozen_river") || key.endsWith(":beach")
                || key.endsWith(":snowy_beach") || key.endsWith(":stony_shore")) {
            return false;
        }
        return key.contains("ocean") || key.contains(":sea") || key.endsWith("_seas");
    }

    private static void add(List<OceanRun> runs, int from, int to, List<Long> arcPerPoint) {
        if (to < from) return;
        if (to - from + 1 < MIN_RUN_POINTS) return;
        long span = arcPerPoint.get(to) - arcPerPoint.get(from);
        runs.add(new OceanRun(from, to, (int) Math.max(0L, span / 256L)));
    }
}
