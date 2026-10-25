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
    public record FillColumn(int lateral, int surfaceY, int x, int z) {
        public FillColumn {
            if (lateral < -WallFinePlanner.LATERAL || lateral > WallFinePlanner.LATERAL)
                throw new IllegalArgumentException("lateral out of wall width");
        }
    }

    public static List<ColumnFoundation> plan(List<WallFinePlanner.FineColumn> samples) {
        Objects.requireNonNull(samples, "samples");
        // WallFinePlanner emits one lateral-major cross-section per route point: -L..+L. The old
        // code opened a group only at lateral==0, which silently discarded the negative half. A
        // wrap from +L to -L (or the reverse) is the actual cross-section boundary.
        var groups = new ArrayList<List<WallFinePlanner.FineColumn>>();
        List<WallFinePlanner.FineColumn> group = null;
        var pending = new ArrayList<WallFinePlanner.FineColumn>();
        for (var sample : samples) {
            if (sample.lateral() == 0) {
                if (group != null) groups.add(group);
                group = new ArrayList<>(pending);
                group.add(sample);
                pending.clear();
            } else if (group == null) {
                // A cross-section may be emitted starting at -L, before its lateral=0 sample.
                pending.add(sample);
            } else {
                group.add(sample);
            }
        }
        if (group != null) groups.add(group);
        var result = new ArrayList<ColumnFoundation>(groups.size());
        for (var columns : groups) {
            WallFinePlanner.FineColumn centre = null;
            for (var sample : columns) {
                if (sample.lateral() == 0) { centre = sample; break; }
            }
            if (centre == null) continue;
            // Base = highest ground surface across the complete cross-section.
            int base = Integer.MIN_VALUE;
            for (var sample : columns) base = Math.max(base, sample.sample().groundTopY());
            if (base == Integer.MIN_VALUE) base = 0;
            // Every column whose surface is below base needs a fill down to its surface.
            var fills = new ArrayList<FillColumn>();
            for (var sample : columns) {
                int surface = sample.sample().groundTopY();
                if (surface < base) fills.add(new FillColumn(sample.lateral(), surface, sample.x(), sample.z()));
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
