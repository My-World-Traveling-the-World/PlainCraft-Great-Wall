package local.plaincraft.greatwall.component;

import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 建筑内部的占位弦不能抢走接口外侧墙列，尤其角楼内部的弦与进墙方向不共线。 */
public final class ImportedRibbonProjection {
    private final List<RibbonEdgeIndex.Edge> edges;
    private final RibbonEdgeIndex index;

    public ImportedRibbonProjection(List<Station> stations, List<SiteRecord> sites) {
        var hidden = sites.stream().filter(s -> s.state() == SiteState.PLANNED)
                .map(s -> new ArcInterval(Math.max(0, s.globalSQ8() - ImportedSiteGeometry.extent(s.id()) * 256L),
                        s.globalSQ8() + ImportedSiteGeometry.extent(s.id()) * 256L + 256)).toList();
        var result = new ArrayList<RibbonEdgeIndex.Edge>();
        for (int i = 1; i < stations.size(); i++) {
            var spans = new ArrayList<ArcInterval>();
            spans.add(new ArcInterval(stations.get(i - 1).globalSQ8(), stations.get(i).globalSQ8()));
            for (var h : hidden) {
                var next = new ArrayList<ArcInterval>();
                for (var span : spans) {
                    if (h.toQ8() <= span.fromQ8() || h.fromQ8() >= span.toQ8()) next.add(span);
                    else {
                        if (span.fromQ8() < h.fromQ8()) next.add(new ArcInterval(span.fromQ8(), h.fromQ8()));
                        if (h.toQ8() < span.toQ8()) next.add(new ArcInterval(h.toQ8(), span.toQ8()));
                    }
                }
                spans = next;
            }
            for (var span : spans) {
                var a = ImportedRibbonSection.at(stations, span.fromQ8());
                var b = ImportedRibbonSection.at(stations, span.toQ8());
                long dx = b.point().xQ8() - a.point().xQ8(), dz = b.point().zQ8() - a.point().zQ8();
                long den = dx * dx + dz * dz;
                if (den != 0) result.add(new RibbonEdgeIndex.Edge(a, b, dx, dz, den, FixedMath.hypotQ8(dx, dz), result.size()));
            }
        }
        edges = List.copyOf(result);
        index = new RibbonEdgeIndex(edges);
    }

    public WallSection.ColumnContract contract(int x, int z) {
        return index.contract(x, z, null);
    }

    /** 只读诊断供离线计数；正常热路径不更新共享计数器。 */
    WallSection.ColumnContract contract(int x, int z, long[] visits) {
        return index.contract(x, z, visits);
    }
}
