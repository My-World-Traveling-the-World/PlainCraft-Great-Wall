package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;

/**
 * Planning-time wall-base + fill decision over the full-width cross-section.
 *
 * <p>Given the per-column cross-section samples (see {@link WallFinePlanner}), this planner decides,
 * for every centre-line column, the wall base height and how far each lateral column must be filled
 * down to its own ground surface. The base is the highest ground surface across the cross-section,
 * so no column can ever hang; every lower column is filled to its surface (a defensive wall never
 * carves a mountain). This is the "planning decides what every column is" step: the output is a pure
 * function of the samples and is consumed verbatim by the rasterizer.
 */
public final class WallFoundationPlanner {
    private WallFoundationPlanner() {
    }

    /** One centre-line column's resolved base and per-lateral fill depths. */
    public record ColumnFoundation(int centreX, int centreZ, int baseY,
            List<FillColumn> fills) {
        public ColumnFoundation {
            fills = List.copyOf(fills);
        }
    }

    /** A lateral column at {@code lateral} offset that must be filled from the wall base down to
     *  {@code surfaceY} (exclusive of surfaceY; the fill occupies [surfaceY, baseY)). */
    public record FillColumn(int lateral, int surfaceY) {
        public FillColumn {
            if (lateral < -WallFinePlanner.LATERAL || lateral > WallFinePlanner.LATERAL)
                throw new IllegalArgumentException("lateral out of wall width");
        }
    }

    public static List<ColumnFoundation> plan(List<WallFinePlanner.FineColumn> samples) {
        Objects.requireNonNull(samples, "samples");
        // Group samples by their centre column. The centre is identified by (x,z) of the lateral=0
        // column, but a bent route can re-enter the same (x,z) with a different lateral, so we key on
        // the lateral=0 sample's coordinates plus a sequence to keep distinct centres stable.
        record Centre(int x, int z, int seq) {}
        var centres = new HashMap<Long, Centre>();
        var byCentre = new HashMap<Long, List<WallFinePlanner.FineColumn>>();
        int seq = 0;
        var ordered = new ArrayList<Long>();
        // WallFinePlanner emits samples in route order, lateral-major per centre. Reconstruct centres
        // by grouping consecutive runs that share the same lateral=0 sample coordinate.
        Long currentKey = null;
        for (var sample : samples) {
            if (sample.lateral() == 0) {
                var centre = new Centre(sample.x(), sample.z(), seq++);
                currentKey = key(sample.x(), sample.z(), seq - 1);
                centres.put(currentKey, centre);
                byCentre.put(currentKey, new ArrayList<>());
                ordered.add(currentKey);
            }
            if (currentKey == null) continue;
            byCentre.get(currentKey).add(sample);
        }
        var result = new ArrayList<ColumnFoundation>(ordered.size());
        for (var key : ordered) {
            var group = byCentre.get(key);
            var centre = centres.get(key);
            // Base = highest ground surface across the cross-section.
            int base = Integer.MIN_VALUE;
            for (var sample : group) base = Math.max(base, sample.sample().groundTopY());
            if (base == Integer.MIN_VALUE) base = 0;
            // Every column whose surface is below base needs a fill down to its surface.
            var fills = new ArrayList<FillColumn>();
            for (var sample : group) {
                int surface = sample.sample().groundTopY();
                if (surface < base) fills.add(new FillColumn(sample.lateral(), surface));
            }
            fills.sort(Comparator.comparingInt(FillColumn::lateral));
            result.add(new ColumnFoundation(centre.x(), centre.z(), base, fills));
        }
        return List.copyOf(result);
    }

    private static long key(int x, int z, int seq) {
        return ((long) x << 32) ^ ((long) z << 16) ^ seq;
    }
}
