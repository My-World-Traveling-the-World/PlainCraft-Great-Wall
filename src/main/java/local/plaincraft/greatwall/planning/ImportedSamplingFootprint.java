package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import local.plaincraft.greatwall.component.EndCap;
import local.plaincraft.greatwall.component.WaterGate;
import local.plaincraft.greatwall.component.ImportedSiteGeometry;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 用终态站线和构件占地取证；普通墙不需要整段陪着最宽的楼一起扫。 */
public record ImportedSamplingFootprint(Map<Integer, List<Bounds>> byStation) {
    public record Attachment(long arc, long sampleAt, Bounds bounds) {
        Attachment(long arc, Bounds bounds) { this(arc, arc, bounds); }
    }
    // 施工检查世界坐标的 ±7 方形，另外留一格给站距取整及转角接缝。
    static final int RADIUS = 8;

    static ImportedSamplingFootprint of(SegmentPlan plan, List<Long> arcs) {
        var entries = new HashMap<Integer, List<Bounds>>();
        long base = plan.prefix().lengthBeforeQ8();
        for (var a : attachments(plan)) add(entries, arcs, a.sampleAt() - base, a.bounds());
        entries.replaceAll((index, boxes) -> List.copyOf(boxes));
        return new ImportedSamplingFootprint(Map.copyOf(entries));
    }

    /** 取证和区块枚举共用包围盒，采到的列必须能送到实现器。 */
    public static List<Attachment> attachments(SegmentPlan plan) {
        var entries = new ArrayList<Attachment>();
        var stations = plan.stations().stations();
        if (stations.size() < 2) return List.of();
        long base = plan.prefix().lengthBeforeQ8(), end = stations.getLast().globalSQ8();
        for (var site : plan.sites()) {
            if (site.state() != SiteState.PLANNED) continue;
            var box = ImportedSiteGeometry.bounds(site);
            // 地表出口检查会读楼体边界外的相邻列。
            entries.add(new Attachment(site.globalSQ8(), new Bounds(box.minX() - 1, box.minY(), box.minZ() - 1,
                    box.maxXExclusive() + 1, box.maxYExclusive(), box.maxZExclusive() + 1)));
        }
        for (var water : plan.geometry().water()) {
            if (water.classification() != WaterClass.NARROW) continue;
            // 没有水门双岸契约的连续墙不需要整段矩形附件，墙带逐列已覆盖其河床。
            if (water.banks().isEmpty()) continue;
            long center = base + (water.crossing().fromQ8() + water.crossing().toQ8()) / 2;
            if (base + water.crossing().fromQ8() < 0) continue;
            // 跨缝水门允许读到段外；按实现器的站线外推取证。
            var frame = frameAt(stations, center);
            if (frame != null) entries.add(new Attachment(center, Rasterizer.worldBounds(frame,
                    WaterGate.footprint(base + water.crossing().fromQ8(), base + water.crossing().toQ8(), center))));
        }
        var capArcs = new LinkedHashSet<CapCandidate>();
        for (var water : plan.geometry().water()) {
            // 短水门也可能因桥墩无支撑而收口，两岸的候选都要预留取证。
            if (water.classification() == WaterClass.DRY) continue;
            if (water.classification() == WaterClass.NARROW && water.banks().isEmpty()) continue;
            addCapArcs(capArcs, base + water.crossing().fromQ8(), false, base, end);
            addCapArcs(capArcs, base + water.crossing().toQ8(), true, base, end);
        }
        for (var gap : plan.stations().breaks()) {
            addCapArcs(capArcs, gap.range().fromQ8(), false, base, end);
            addCapArcs(capArcs, gap.range().toQ8(), true, base, end);
        }
        plan.geometry().terminal().ifPresent(t -> addCapArcs(capArcs, base + t.localSQ8(), false, base, end));
        entries.addAll(capAttachments(capArcs,stations,false));
        return List.copyOf(entries);
    }

    /** 实现期才出现的断点沿用同一退让表；额外一列供收口的地表出口检查。 */
    public static List<Attachment> realizedCaps(SegmentPlan plan,List<BreakInterval> breaks) {
        var stations=plan.stations().stations();
        if(stations.size()<2)return List.of();
        var caps=new LinkedHashSet<CapCandidate>();
        long from=stations.getFirst().globalSQ8(),to=stations.getLast().globalSQ8();
        for(var gap:breaks) {
            addCapArcs(caps,gap.range().fromQ8(),false,from,to);
            addCapArcs(caps,gap.range().toQ8(),true,from,to);
        }
        return capAttachments(caps,stations,true);
    }

    private static List<Attachment> capAttachments(LinkedHashSet<CapCandidate> caps,List<Station> stations,boolean apron) {
        var entries=new ArrayList<Attachment>();
        for (var cap : caps) {
            long s = cap.station();
            var frame = frameAt(stations, s);
            if(frame==null)continue;
            var box=Rasterizer.worldBounds(frame,EndCap.potentialLocalBounds());
            if(apron)box=new Bounds(box.minX()-1,box.minY(),box.minZ()-1,box.maxXExclusive()+1,box.maxYExclusive(),box.maxZExclusive()+1);
            entries.add(new Attachment(cap.edge(),s,box));
        }
        return List.copyOf(entries);
    }

    private record CapCandidate(long edge, long station) {}

    private static void addCapArcs(LinkedHashSet<CapCandidate> arcs, long edge, boolean reverse, long from, long to) {
        if (edge < from || edge > to) return;
        // 按每次后退的位置分摊采样，而不是把整段收口候选集中到一个切片。
        for (int retreat = 0; retreat <= 128; retreat += 4) {
            long s = edge + (reverse ? retreat : -retreat) * 256L;
            if (s < from || s > to) break;
            // 施工单元按断点选择收口，后退到的列仍属于这个断点的证据。
            arcs.add(new CapCandidate(edge, s));
        }
    }

    // 与施工取端点时的切线规则一致：节点恰好命中时用入边。
    private static Frame frameAt(List<Station> stations, long s) {
        int lo = 1, hi = stations.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (stations.get(mid).globalSQ8() < s) lo = mid + 1; else hi = mid;
        }
        var a = stations.get(lo - 1); var b = stations.get(lo);
        long dx = b.point().xQ8() - a.point().xQ8(), dz = b.point().zQ8() - a.point().zQ8();
        double length = StrictMath.hypot(dx, dz);
        if (length == 0) return null;
        long t = s - a.globalSQ8(), span = b.globalSQ8() - a.globalSQ8();
        var p = new Point2q(a.point().xQ8() + FixedMath.mulDiv(dx, t, span), a.point().zQ8() + FixedMath.mulDiv(dz, t, span));
        return new Frame(p, (int) StrictMath.round(dx * (1L << 30) / length),
                (int) StrictMath.round(dz * (1L << 30) / length), a.walkY(), false);
    }

    private static void add(Map<Integer, List<Bounds>> entries, List<Long> arcs, long local, Bounds box) {
        int index = java.util.Collections.binarySearch(arcs, local);
        if (index < 0) index = Math.min(arcs.size() - 1, -index - 1);
        entries.computeIfAbsent(index, ignored -> new ArrayList<>()).add(box);
    }

    List<WallFinePlanner.Target> section(ImportedSamplePath path, int index, long global) {
        var p = path.points().get(index);
        int x = FixedMath.floorBlock(p.xQ8()), z = FixedMath.floorBlock(p.zQ8());
        var result = new ArrayList<WallFinePlanner.Target>();
        // 相邻方形重叠大，只交出新增的边条；回折和楼体重叠再由会话去重。
        if (index == 0) addSquare(result, x, z, global, null);
        else addSquare(result, x, z, global, path.points().get(index - 1));
        for (var box : byStation.getOrDefault(index, List.of())) {
            for (int bz = box.minZ(); bz < box.maxZExclusive(); bz++) for (int bx = box.minX(); bx < box.maxXExclusive(); bx++)
                result.add(new WallFinePlanner.Target(bx, bz, 0, global, x, z));
        }
        return result;
    }

    private static void addSquare(List<WallFinePlanner.Target> result, int x, int z, long global, Point2q prior) {
        int px = prior == null ? x - 2 * RADIUS - 1 : FixedMath.floorBlock(prior.xQ8());
        int pz = prior == null ? z : FixedMath.floorBlock(prior.zQ8());
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            int row = z + dz;
            if (Math.abs(row - pz) > RADIUS) {
                strip(result, x - RADIUS, x + RADIUS, row, global, x, z);
            } else {
                strip(result, x - RADIUS, Math.min(x + RADIUS, px - RADIUS - 1), row, global, x, z);
                strip(result, Math.max(x - RADIUS, px + RADIUS + 1), x + RADIUS, row, global, x, z);
            }
        }
    }

    private static void strip(List<WallFinePlanner.Target> result, int from, int to, int row, long global, int x, int z) {
        for (int bx = from; bx <= to; bx++) result.add(new WallFinePlanner.Target(bx, row, 0, global, x, z));
    }
}
