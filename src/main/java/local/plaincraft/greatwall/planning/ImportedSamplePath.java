package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 精采样沿冻结后的接引墙线走；只给旧路线加宽仍会漏掉建筑正交端面外的弯段。 */
record ImportedSamplePath(SegmentKey key, List<Point2q> points, List<Long> localSQ8,
                          ImportedSamplingFootprint footprint) implements RoutePath {
    ImportedSamplePath(SegmentKey key, List<Point2q> points, List<Long> localSQ8) {
        this(key, points, localSQ8, null);
    }
    /** 前段已遇海的空计划没有施工足迹；不能让它取消前段有效墙线的整轮取证。 */
    static Optional<RoutePath> forSampling(SegmentPlan plan) {
        return forSampling(plan, false);
    }

    static Optional<RoutePath> forSampling(SegmentPlan plan, boolean exact) {
        if (plan.prefix().terminal().filter(t -> t.segment() < plan.geometry().key().index()).isPresent()) {
            // 只接纳调度器明确定义的终止空段，活动段缺站仍由 of 报错，防止吞掉坏计划。
            if (!plan.stations().stations().isEmpty() || !plan.sites().isEmpty()) {
                throw new IllegalArgumentException("终止后的计划不应携带站点或建筑");
            }
            return Optional.empty();
        }
        return Optional.of(of(plan, exact));
    }

    static ImportedSamplePath of(SegmentPlan plan) {
        return of(plan, false);
    }

    static ImportedSamplePath of(SegmentPlan plan, boolean exact) {
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
        if (plan.geometry().terminal().isPresent()) {
            // 完成的几何已从在途表移走，不能靠查询在途 Future 才知道宽水域终点。
            // 只保留终点后 32 格缓冲，采样器仍会为这条截短路线增加完整端部取证裙边。
            long cap=plan.geometry().terminal().orElseThrow().localSQ8()+32L*256;
            int limit=2;
            while(limit<arcs.size()&&arcs.get(limit)<=cap)limit++;
            points=new ArrayList<>(points.subList(0,limit));
            arcs=new ArrayList<>(arcs.subList(0,limit));
        }
        // 附件只属于本次精采游标，不进入存档或冻结计划的编码。
        return new ImportedSamplePath(plan.geometry().key(), List.copyOf(points), List.copyOf(arcs),
                exact ? ImportedSamplingFootprint.of(plan, arcs) : null);
    }
}
