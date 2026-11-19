package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 精采样沿冻结后的接引墙线走；只给旧路线加宽仍会漏掉建筑正交端面外的弯段。 */
record ImportedSamplePath(SegmentKey key, List<Point2q> points, List<Long> localSQ8) implements RoutePath {
    static ImportedSamplePath of(SegmentPlan plan) {
        var points = new ArrayList<Point2q>();
        var arcs = new ArrayList<Long>();
        var stations = plan.stations().stations();
        long base = plan.prefix().lengthBeforeQ8();
        for (int i = 1; i < stations.size(); i++) {
            var a = stations.get(i - 1); var b = stations.get(i);
            long dx = b.point().xQ8() - a.point().xQ8(), dz = b.point().zQ8() - a.point().zQ8();
            int steps = Math.toIntExact(Math.max(1, (FixedMath.hypotQ8(dx, dz) + 255) / 256));
            for (int n = i == 1 ? 0 : 1; n <= steps; n++) {
                var p = new Point2q(a.point().xQ8() + FixedMath.mulDiv(dx, n, steps),
                        a.point().zQ8() + FixedMath.mulDiv(dz, n, steps));
                long s = a.globalSQ8() - base + FixedMath.mulDiv(b.globalSQ8() - a.globalSQ8(), n, steps);
                if (!points.isEmpty() && (points.getLast().equals(p) || arcs.getLast() >= s)) continue;
                points.add(p); arcs.add(s);
            }
        }
        if (points.size() < 2) throw new IllegalArgumentException("正式墙线至少需要两个不同点");
        return new ImportedSamplePath(plan.geometry().key(), List.copyOf(points), List.copyOf(arcs));
    }
}
