package local.plaincraft.greatwall.component;

import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 建筑内部的占位弦不能抢走接口外侧墙列，尤其角楼内部的弦与进墙方向不共线。 */
final class ImportedRibbonProjection {
    private record Edge(Station a, Station b, long dx, long dz, long denominator, long length) {}
    private final List<Edge> edges;
    private final long first, last;

    ImportedRibbonProjection(List<Station> stations, List<SiteRecord> sites) {
        first=stations.getFirst().globalSQ8(); last=stations.getLast().globalSQ8();
        var hidden = sites.stream().filter(s -> s.state() == SiteState.PLANNED)
                .map(s -> new ArcInterval(Math.max(0, s.globalSQ8() - ImportedSiteGeometry.extent(s.id()) * 256L),
                        s.globalSQ8() + ImportedSiteGeometry.extent(s.id()) * 256L + 256)).toList();
        var result = new ArrayList<Edge>();
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
                if (den != 0) result.add(new Edge(a, b, dx, dz, den, FixedMath.hypotQ8(dx, dz)));
            }
        }
        edges = List.copyOf(result);
    }

    WallSection.ColumnContract contract(int x, int z) {
        long px = x * 256L + 128, pz = z * 256L + 128, best = Long.MAX_VALUE;
        Edge chosen = null; long numerator = 0;
        for (var edge : edges) {
            var a = edge.a().point(); var b = edge.b().point();
            long boxX = Math.max(0, Math.max(Math.min(a.xQ8(), b.xQ8()) - px, px - Math.max(a.xQ8(), b.xQ8())));
            long boxZ = Math.max(0, Math.max(Math.min(a.zQ8(), b.zQ8()) - pz, pz - Math.max(a.zQ8(), b.zQ8())));
            if (boxX * boxX + boxZ * boxZ >= best) continue;
            long wx = px - a.xQ8(), wz = pz - a.zQ8();
            long raw = wx * edge.dx() + wz * edge.dz();
            long n = Math.clamp(raw, 0, edge.denominator());
            long dx = px - a.xQ8() - FixedMath.mulDiv(edge.dx(), n, edge.denominator());
            long dz = pz - a.zQ8() - FixedMath.mulDiv(edge.dz(), n, edge.denominator());
            long distance = dx * dx + dz * dz;
            if (distance >= best) continue;
            best = distance; chosen = edge;
            // 分段端面外的列必须保留纵向距离，不能都夹成同一个起点并重复长出半截墙。
            numerator = edge.a().globalSQ8()==first&&raw<0||edge.b().globalSQ8()==last&&raw>edge.denominator()?raw:n;
        }
        if (chosen == null) return null;
        long s = chosen.a().globalSQ8() + FixedMath.mulDiv(chosen.b().globalSQ8() - chosen.a().globalSQ8(),
                numerator, chosen.denominator());
        long lateral = FixedMath.mulDiv(chosen.dx(), pz - chosen.a().point().zQ8(), chosen.length())
                - FixedMath.mulDiv(chosen.dz(), px - chosen.a().point().xQ8(), chosen.length());
        int y = chosen.a().walkY() + (int) FixedMath.roundDiv((long) (chosen.b().walkY() - chosen.a().walkY())
                * numerator, chosen.denominator());
        return new WallSection.ColumnContract(s, FixedMath.floorBlock(lateral), y);
    }
}
