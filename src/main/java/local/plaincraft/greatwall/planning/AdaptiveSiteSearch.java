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

/** 水文先排除不可用里程，粗网只作筛选；整座楼逐列合格后才确定楼址。 */
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
    private SiteRecord candidate;
    // 同一楼址的备选范围有限；没有平地时，按真实地形起伏和原偏移顺序择优。
    private record Choice(SiteRecord site, int order, int relief, int leftY, int rightY) {}
    private final List<Choice> roughSlopes = new ArrayList<>();
    private final Map<Point2q, ColumnSample> confirmed = new HashMap<>();
    private Choice exact, best;
    private int exactX, exactZ, exactLow, exactHigh;
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

    /** 仅移交本段仍要放置的楼址；重叠窗口或已淘汰楼的样本不必一直持有。 */
    void reuseVerified(List<SiteRecord> sites, Map<Point2q, ColumnSample> output) {
        for (var site : sites) if (site.state() == SiteState.PLANNED && site.globalSQ8() != 0)
            copyFootprint(site.bounds(), confirmed, output);
    }

    void advance(NaturalTerrainOracle oracle) {
        var site = nominal.get(siteIndex);
        // 澄海楼是起点唯一端口，移动它会在路线头部留下没有接口的墙。
        if (site.globalSQ8() == 0) { finish(site); return; }
        // 同一座楼的逐列游标无需反复查询名义里程属于哪一段。
        if (owner == null) owner = owners.stream().filter(o -> o.contains(site.globalSQ8())).findFirst().orElseThrow();
        if (exact != null) { advanceExact(oracle); return; }
        if (candidate == null) {
            if (attempt > 2 * RADIUS / STEP) {
                // 粗网起伏是真实起伏的下界，已经不可能胜出的备选不用再做整面精采。
                while (!roughSlopes.isEmpty()) {
                    var next = roughSlopes.removeFirst();
                    if (improves(next.relief(), next.order())) { startExact(next); return; }
                }
                finish(best == null ? new SiteRecord(site.id(), site.globalSQ8(), site.frame(), site.bounds(),
                        SiteState.SUPPRESSED, Optional.empty(), Optional.of(rejection)) : best.site());
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
        var sample = read(oracle, point);
        if (sample == null) return;
        if (unusable(sample)) {
            rejection = BreakReason.NO_SUPPORT; candidate = null; return;
        }
        if (probeIndex < points.size() - 2) {
            low = Math.min(low, sample.groundTopY()); high = Math.max(high, sample.groundTopY());
        }
        if (++probeIndex < points.size()) return;
        var option = new Choice(candidate, attempt, high - low,
                probes.get(points.get(points.size() - 2)).groundTopY() + 20, sample.groundTopY() + 20);
        candidate = null;
        if (option.relief() <= FLAT_RELIEF) startExact(option);
        else if (improves(option.relief(), option.order())) {
            roughSlopes.add(option);
            roughSlopes.sort(java.util.Comparator.comparingInt(Choice::relief).thenComparingInt(Choice::order));
        }
    }

    private boolean improves(int relief, int order) {
        return best == null || relief < bestRelief || relief == bestRelief && order < best.order();
    }

    private void startExact(Choice option) {
        exact = option;
        exactX = option.site().bounds().minX(); exactZ = option.site().bounds().minZ();
        exactLow = Integer.MAX_VALUE; exactHigh = Integer.MIN_VALUE;
    }

    /** 复用同一次搜索已走过的格子；每次只推一列的一步，不能让大楼整面探测霸占通道。 */
    private ColumnSample read(NaturalTerrainOracle oracle, Point2q point) {
        var sample = probes.get(point);
        if (sample != null) return sample;
        if (column == null) {
            column = oracle.begin(FixedMath.floorBlock(point.xQ8()), FixedMath.floorBlock(point.zQ8()), SampleKind.REFERENCE);
            return null;
        }
        if (!column.done()) { oracle.advance(column, 1); return null; }
        sample = oracle.finish(column); column = null; probes.put(point, sample);
        return sample;
    }

    private static Point2q center(int x, int z) { return new Point2q(x * 256L + 128, z * 256L + 128); }

    private static boolean unusable(ColumnSample sample) {
        return sample.upperCensored() || sample.quality() != Quality.REFERENCE || sample.waterTopY().isPresent();
    }

    private void advanceExact(NaturalTerrainOracle oracle) {
        var box = exact.site().bounds();
        var sample = read(oracle, center(exactX, exactZ));
        if (sample == null) return;
        if (unusable(sample)) { rejection = BreakReason.NO_SUPPORT; exact = null; return; }
        exactLow = Math.min(exactLow, sample.groundTopY());
        exactHigh = Math.max(exactHigh, sample.groundTopY());
        int relief = exactHigh - exactLow;
        if (relief > FLAT_RELIEF && !improves(relief, exact.order())) { exact = null; return; }
        if (++exactX < box.maxXExclusive()) return;
        exactX = box.minX();
        if (++exactZ < box.maxZExclusive()) return;

        // 最高列即使在粗网之间也决定整楼平台；用它检查屋顶、接引坡度以及所在高度的结构。
        var option = exact; exact = null;
        var site = option.site();
        int walk = exactHigh + 15, shift = walk - site.frame().walkY();
        if (box.minY() + shift < recipe.bounds().minY() || box.maxYExclusive() + shift > recipe.bounds().maxYExclusive()) {
            rejection = BreakReason.WORLD_LIMIT; return;
        }
        long platformReach = (ImportedSiteGeometry.extent(site.id()) + ImportedSiteGeometry.STRAIGHT) * 256L;
        long s = site.globalSQ8();
        if (Math.abs(walk - option.leftY()) > 30 || Math.abs(walk - option.rightY()) > 30
                || Math.abs(walk - owner.geometry.start().walkY()) > (s - platformReach - owner.from) / 512
                || Math.abs(walk - owner.geometry.end().walkY()) > (owner.fullEnd() - s - platformReach) / 512) {
            rejection = BreakReason.SLOPE; return;
        }
        if (obstacles.intersects(box, box.minY() + shift, box.maxYExclusive() + shift)) {
            rejection = BreakReason.STRUCTURE_CONFLICT; return;
        }
        if (relief <= FLAT_RELIEF) { finish(site); return; }
        if (improves(relief, option.order())) { best = option; bestRelief = relief; }
    }

    private static void copyFootprint(Bounds b, Map<Point2q, ColumnSample> source, Map<Point2q, ColumnSample> output) {
        for (int z = b.minZ(); z < b.maxZExclusive(); z++) for (int x = b.minX(); x < b.maxXExclusive(); x++) {
            var p = center(x, z);
            output.put(p, java.util.Objects.requireNonNull(source.get(p), "获选楼址缺少逐列证据"));
        }
    }

    private void finish(SiteRecord site) {
        if (site.state() == SiteState.PLANNED && site.globalSQ8() != 0) copyFootprint(site.bounds(), probes, confirmed);
        selected.add(site); siteIndex++; attempt = 0; best = null; candidate = null;
        bestRelief = Integer.MAX_VALUE; rejection = BreakReason.SLOPE;
        owner = null; probes.clear(); roughSlopes.clear();
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
