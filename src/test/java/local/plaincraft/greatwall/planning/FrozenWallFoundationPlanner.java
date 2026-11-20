// 独立冻结d8364723分组与地基算法，只共用不可变值对象，禁止委托新分组实现。
package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.planning.WallFoundationPlanner.*;
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
public final class FrozenWallFoundationPlanner {
    private FrozenWallFoundationPlanner() {
    }

    public static List<ColumnFoundation> plan(List<WallFinePlanner.FineColumn> samples) {
        Objects.requireNonNull(samples, "samples");
        // WallFinePlanner emits one lateral-major cross-section per route point: -L..+L. The old
        // code opened a group only at lateral==0, which silently discarded the negative half. A
        // wrap from +L to -L (or the reverse) is the actual cross-section boundary.
        var grouped = new java.util.TreeMap<Long, List<WallFinePlanner.FineColumn>>();
        for (var sample : samples) {
            if (!sample.hasSectionIdentity()) {
                // Compatibility for analytic fixtures without a route id: each centre owns a section.
                grouped.computeIfAbsent(Long.MIN_VALUE + sample.centreX(), ignored -> new ArrayList<>()).add(sample);
            } else {
                grouped.computeIfAbsent(sample.globalSQ8(), ignored -> new ArrayList<>()).add(sample);
            }
        }
        var groups = new ArrayList<>(grouped.values());
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
                if (surface < base) fills.add(new FillColumn(sample.lateral(), surface, sample.x(), sample.z(), base));
            }
            fills.sort(Comparator.comparingInt(FillColumn::lateral));
            result.add(new ColumnFoundation(centre.globalSQ8(), centre.x(), centre.z(), base, fills));
        }
        return List.copyOf(result);
    }

}
