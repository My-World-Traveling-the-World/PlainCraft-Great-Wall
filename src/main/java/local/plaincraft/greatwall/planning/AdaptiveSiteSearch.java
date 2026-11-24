package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import local.plaincraft.greatwall.component.ImportedSiteGeometry;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;

/** 水文确定后在名义里程前后找楼址；只给胜出的位置做全占地逐列精采。 */
final class AdaptiveSiteSearch {
    private static final int STEP = 32, RADIUS = 192, PROBE_STEP = 12, FLAT_RELIEF = 8;
    private final WorldRecipe recipe;
    private final SiteScheduler scheduler;
    private final StructureObstacleIndex obstacles;
    private final List<SiteRecord> nominal;
    private final List<Owner> owners;
    private final List<SiteRecord> selected = new ArrayList<>();
    private final Map<Point2q, ColumnSample> probes = new HashMap<>();
    private int siteIndex, attempt, probeIndex, low, high;
    private SiteRecord candidate, best;
    private Owner owner;
    private int bestRelief = Integer.MAX_VALUE;
    private BreakReason rejection = BreakReason.SLOPE;
    private List<Point2q> points;
    private NaturalTerrainOracle.ColumnCursor column;

    AdaptiveSiteSearch(WorldRecipe recipe, SiteScheduler scheduler, StructureObstacleIndex obstacles,
            GeometryWindow window, long from, long to) {
        this.recipe = recipe;
        this.scheduler = scheduler;
        this.obstacles = obstacles;
        this.nominal = scheduler.candidates(window, from, to);
        var byIndex = new HashMap<Long, SegmentGeometry>();
        window.segments().forEach(g -> byIndex.put(g.key().index(), g));
        var entries = new ArrayList<Owner>();
        for (var prefix : window.prefixes()) {
            var geometry = byIndex.get(prefix.beforeSegment());
            entries.add(new Owner(geometry, prefix.lengthBeforeQ8()));
        }
        this.owners = List.copyOf(entries);
    }

    boolean done() { return siteIndex >= nominal.size(); }
    List<SiteRecord> result() { return List.copyOf(selected); }

    void advance(NaturalTerrainOracle oracle) {
        var site = nominal.get(siteIndex);
        // 澄海楼是起点唯一端口，移动它会在路线头部留下没有接口的墙。
        if (site.globalSQ8() == 0) { finish(site); return; }
        // 同一座楼的逐列游标无需反复查询名义里程属于哪一段。
        if (owner == null) owner = owners.stream().filter(o -> o.contains(site.globalSQ8())).findFirst().orElseThrow();
        if (candidate == null) {
            if (attempt > 2 * RADIUS / STEP) {
                finish(best == null ? new SiteRecord(site.id(), site.globalSQ8(), site.frame(), site.bounds(),
                        SiteState.SUPPRESSED, Optional.empty(), Optional.of(rejection)) : best);
                return;
            }
            // 同距离先向前、再向后。窗口重叠时只读名义位置所属段，重算不会换楼址。
            int offset = attempt == 0 ? 0 : ((attempt + 1) / 2) * STEP * (attempt % 2 == 1 ? 1 : -1);
            attempt++;
            long s = site.globalSQ8() + offset * 256L;
            long reach = ImportedSiteGeometry.reach(site.id()) * 256L;
            if (s - reach < owner.from || s + reach > owner.end()) return;
            for (var water : owner.geometry.water()) {
                long local = s - owner.from;
                if (water.classification() != WaterClass.DRY && water.crossing().fromQ8() < local + reach
                        && water.crossing().toQ8() > local - reach) { rejection = BreakReason.NO_SUPPORT; return; }
            }
            var placed = scheduler.at(site.id(), s, owner.geometry, s - owner.from);
            var box = ImportedSiteGeometry.bounds(placed);
            if (box.minX() < -recipe.bounds().horizontalLimit() || box.minZ() < -recipe.bounds().horizontalLimit()
                    || box.maxXExclusive() > recipe.bounds().horizontalLimit()
                    || box.maxZExclusive() > recipe.bounds().horizontalLimit()) { rejection = BreakReason.WORLD_LIMIT; return; }
            candidate = placed;
            points = samplePoints(box, owner, s, reach);
            probeIndex = 0; low = Integer.MAX_VALUE; high = Integer.MIN_VALUE;
        }
        var point = points.get(probeIndex);
        var sample = probes.get(point);
        if (sample == null) {
            if (column == null) { column = oracle.begin(FixedMath.floorBlock(point.xQ8()), FixedMath.floorBlock(point.zQ8()), SampleKind.REFERENCE); return; }
            if (!column.done()) { oracle.advance(column, 1); return; }
            sample = oracle.finish(column); column = null; probes.put(point, sample);
        }
        if (sample.upperCensored() || sample.quality() != Quality.REFERENCE || sample.waterTopY().isPresent()) {
            rejection = BreakReason.NO_SUPPORT; candidate = null; return;
        }
        if (probeIndex < points.size() - 2) {
            low = Math.min(low, sample.groundTopY()); high = Math.max(high, sample.groundTopY());
        }
        if (++probeIndex < points.size()) return;
        int walk = high + 15;
        var box = candidate.bounds();
        int shift = walk - candidate.frame().walkY();
        if (box.minY() + shift < recipe.bounds().minY() || box.maxYExclusive() + shift > recipe.bounds().maxYExclusive()) {
            rejection = BreakReason.WORLD_LIMIT; candidate = null; return;
        }
        // 抬高墙和地基可以，但平台必须给两侧 64 格接引留出逐级升降的距离。
        int relief = high - low;
        int left = probes.get(points.get(points.size() - 2)).groundTopY() + 20;
        int right = sample.groundTopY() + 20;
        long platformReach = (ImportedSiteGeometry.extent(site.id()) + ImportedSiteGeometry.STRAIGHT) * 256L;
        long s = candidate.globalSQ8();
        if (Math.abs(walk - left) > 30 || Math.abs(walk - right) > 30
                || Math.abs(walk - owner.geometry.start().walkY()) > (s - platformReach - owner.from) / 512
                || Math.abs(walk - owner.geometry.end().walkY()) > (owner.fullEnd() - s - platformReach) / 512) {
            rejection = BreakReason.SLOPE; candidate = null; return;
        }
        if (obstacles.intersects(box, box.minY() + shift, box.maxYExclusive() + shift)) {
            rejection = BreakReason.STRUCTURE_CONFLICT; candidate = null; return;
        }
        if (relief <= FLAT_RELIEF) { finish(candidate); return; }
        if (relief < bestRelief) { best = candidate; bestRelief = relief; }
        candidate = null;
    }

    private void finish(SiteRecord site) {
        selected.add(site); siteIndex++; attempt = 0; best = null; candidate = null;
        bestRelief = Integer.MAX_VALUE; rejection = BreakReason.SLOPE;
        owner = null;
    }

    private static List<Point2q> samplePoints(Bounds box, Owner owner, long s, long reach) {
        var result = new ArrayList<Point2q>();
        // 实际正交包围盒按 12 格网格抽样，两侧端点也取样，不能沿中线猜宽楼的平台。
        for (int z = box.minZ(); ; z = Math.min(z + PROBE_STEP, box.maxZExclusive() - 1)) {
            for (int x = box.minX(); ; x = Math.min(x + PROBE_STEP, box.maxXExclusive() - 1)) {
                result.add(new Point2q(x * 256L + 128, z * 256L + 128));
                if (x == box.maxXExclusive() - 1) break;
            }
            if (z == box.maxZExclusive() - 1) break;
        }
        for (long edge : new long[] {s - reach, s + reach}) {
            var p = ArcLength.pointAt(owner.geometry, edge - owner.from);
            result.add(new Point2q(FixedMath.floorBlock(p.xQ8()) * 256L + 128,
                    FixedMath.floorBlock(p.zQ8()) * 256L + 128));
        }
        return result;
    }

    private record Owner(SegmentGeometry geometry, long from) {
        long fullEnd() { return from + geometry.localSQ8().getLast(); }
        long end() { return geometry.terminal().map(t -> from + t.localSQ8()).orElseGet(this::fullEnd); }
        boolean contains(long s) { return s >= from && s < end(); }
    }
}
