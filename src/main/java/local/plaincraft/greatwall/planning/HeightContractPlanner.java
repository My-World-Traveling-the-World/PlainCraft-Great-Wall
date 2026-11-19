package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import local.plaincraft.greatwall.component.ImportedSiteGeometry;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Frozen walk-height contract. Only supplied columns are read; construction never adjusts W. */
public final class HeightContractPlanner {
    private final WorldRecipe recipe;
    public HeightContractPlanner(WorldRecipe recipe) { this.recipe = Objects.requireNonNull(recipe); }

    public List<Point2q> samplePoints(SegmentGeometry geometry, PrefixSummary prefix, List<SiteRecord> sites) {
        var values = stationPositions(geometry, prefix, sites);
        var result = new LinkedHashSet<Point2q>();
        for (long s : values.keySet()) {
            if (s != prefix.lengthBeforeQ8() && s != prefix.lengthBeforeQ8() + geometry.localSQ8().getLast()) {
                result.add(pointAt(geometry, prefix, sites, s));
            }
        }
        for (var site : sites) if (site.state() == SiteState.PLANNED) result.addAll(platformSamples(site));
        int budget = ImportedSiteGeometry.enabled(recipe) ? 65536 : 5998;
        if (result.size() > budget) throw new WallFailure(FailureCode.BUDGET_EXHAUSTED, "height columns exceed contract budget");
        return List.copyOf(result);
    }

    public StationTable solve(SegmentGeometry geometry, PrefixSummary prefix, List<SiteRecord> sites, Map<Point2q, ColumnSample> columns) {
        if (!geometry.key().recipeHash().equals(recipe.recipeHash()) || prefix.beforeSegment() != geometry.key().index()) {
            throw new WallFailure(FailureCode.RECIPE_MISMATCH, "height inputs do not share a segment prefix");
        }
        var positions = stationPositions(geometry, prefix, sites);
        var breaks = new ArrayList<BreakInterval>();
        long start = prefix.lengthBeforeQ8(), end = Math.addExact(start, geometry.localSQ8().getLast());
        var nodes = new ArrayList<Node>();
        for (var entry : positions.entrySet()) {
            long s = entry.getKey();
            var point = pointAt(geometry, prefix, sites, s);
            int target = s == start ? geometry.start().walkY() : s == end ? geometry.end().walkY()
                    : Math.addExact(required(columns, point).groundTopY(), 20);
            nodes.add(new Node(s, point, target, entry.getValue(), s == start || s == end));
        }
        for (var site : sites.stream().filter(s -> s.state() == SiteState.PLANNED)
                .sorted(Comparator.comparingLong(SiteRecord::globalSQ8).thenComparing(s -> s.id().kind().name())).toList()) {
            if (ImportedSiteGeometry.enabled(recipe)) {
                // 按整个实物占地取最高地表；不可拿九点中位数让门楼底层埋进山里。
                int platformY = Math.addExact(platformSamples(site).stream()
                        .mapToInt(p -> required(columns, p).groundTopY()).max().orElseThrow(), 15);
                for (var node : nodes) if (ImportedSiteGeometry.levelAt(site, node.s)) {
                    node.hard = true; node.target = platformY; node.flags |= 1;
                }
                continue;
            }
            var footprint = SiteScheduler.footprint(site.id().kind());
            long a = site.globalSQ8() + footprint.minX() * 256L, b = site.globalSQ8() + footprint.maxXExclusive() * 256L;
            boolean includesStart = a <= start && b >= start, includesEnd = a <= end && b >= end;
            if (includesStart && includesEnd && geometry.start().walkY() != geometry.end().walkY()) {
                addBreak(breaks, Math.max(start, a), Math.min(end, b)); continue;
            }
            int platformY;
            if (includesStart) platformY = geometry.start().walkY();
            else if (includesEnd) platformY = geometry.end().walkY();
            else {
                var heights = platformSamples(site).stream().mapToInt(p -> required(columns, p).groundTopY()).sorted().toArray();
                platformY = Math.addExact(heights[4], 20);
            }
            for (var node : nodes) if (node.s >= a && node.s <= b) {
                if (node.hard && node.target != platformY) {
                    addBreak(breaks, Math.max(start, a), Math.min(end, b)); continue;
                }
                node.hard = true; node.target = platformY; node.flags |= 1;
            }
        }
        int min = Math.addExact(recipe.bounds().minY(), ImportedSiteGeometry.enabled(recipe) ? 15 : 20);
        int max = Math.subtractExact(recipe.bounds().maxYExclusive(), ImportedSiteGeometry.enabled(recipe) ? 43 : 16);
        int n = nodes.size();
        long[] limits = new long[n];
        boolean[] cuts = new boolean[n];
        for (int i = 1; i < n; i++) limits[i] = Math.floorDiv(3 * (nodes.get(i).s - nodes.get(i - 1).s), 4 * 256);
        int lastHard = -1;
        long reach = 0;
        for (int i = 0; i < n; i++) {
            var node = nodes.get(i); reach += limits[i];
            if (node.hard) {
                if (node.target < min || node.target > max) throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "hard W outside dimension");
                if (lastHard >= 0 && Math.abs((long) node.target - nodes.get(lastHard).target) > reach) {
                    addBreak(breaks, nodes.get(lastHard).s, node.s);
                }
                lastHard = i; reach = 0;
            }
        }
        for (int i = 1; i < n; i++) {
            long a = nodes.get(i - 1).s, b = nodes.get(i).s;
            cuts[i] = breaks.stream().anyMatch(v -> v.range().fromQ8() < b && v.range().toQ8() > a);
        }
        long[] low = new long[n], high = new long[n];
        for (int i = 0; i < n; i++) {
            var node = nodes.get(i);
            low[i] = node.hard ? node.target : min; high[i] = node.hard ? node.target : max;
            if (i > 0 && !cuts[i]) { low[i] = Math.max(low[i], low[i - 1] - limits[i]); high[i] = Math.min(high[i], high[i - 1] + limits[i]); }
        }
        for (int i = n - 2; i >= 0; i--) if (!cuts[i + 1]) {
            low[i] = Math.max(low[i], low[i + 1] - limits[i + 1]); high[i] = Math.min(high[i], high[i + 1] + limits[i + 1]);
        }
        var stations = new ArrayList<Station>();
        for (int i = 0; i < n; i++) {
            var node = nodes.get(i);
            if (i > 0 && !cuts[i]) {
                low[i] = Math.max(low[i], stations.getLast().walkY() - limits[i]);
                high[i] = Math.min(high[i], stations.getLast().walkY() + limits[i]);
            }
            if (low[i] > high[i]) throw new IllegalStateException("infeasible interval escaped hard-break classification");
            int y = Math.toIntExact(Math.clamp(node.target, low[i], high[i]));
            int flags = node.flags | (cuts[i] || i + 1 < n && cuts[i + 1] ? 16 : 0);
            stations.add(new Station(node.s, node.point, y, flags));
        }
        // Segment boundary stations (start/end) carry no sampled column of their
        // own: their walk height is anchored to geometry, not terrain. Derive their
        // biome from the nearest interior station so the ribbon stays complete.
        var biomeKeys = new java.util.LinkedHashMap<Long, String>();
        for (int i = 0; i < n; i++) {
            var node = nodes.get(i);
            ColumnSample sample;
            if (node.s == start) sample = required(columns, nodes.get(i + 1).point);
            else if (node.s == end) sample = required(columns, nodes.get(i - 1).point);
            else sample = required(columns, node.point);
            biomeKeys.put(node.s, sample.biomeKey());
        }
        var merged = mergeBreaks(breaks);
        var table = new StationTable(stations, merged, "0".repeat(64), biomeKeys);
        return new StationTable(stations, merged, DeterministicHash.sha256(CanonicalCodec.stations(table)), biomeKeys);
    }

    public int walkY(StationTable table, long globalSQ8) {
        var list = table.stations();
        if (list.isEmpty() || globalSQ8 < list.getFirst().globalSQ8() || globalSQ8 > list.getLast().globalSQ8()) {
            throw new IllegalArgumentException("height query outside station table");
        }
        int lo = 0, hi = list.size() - 1;
        while (lo + 1 < hi) { int m = (lo + hi) >>> 1; if (list.get(m).globalSQ8() <= globalSQ8) lo = m; else hi = m; }
        var a = list.get(lo); var b = list.get(hi);
        if (a.globalSQ8() == globalSQ8) return a.walkY();
        return Math.toIntExact(a.walkY() + FixedMath.roundDiv(Math.multiplyExact(b.walkY() - (long) a.walkY(), globalSQ8 - a.globalSQ8()),
                b.globalSQ8() - a.globalSQ8()));
    }

    private TreeMap<Long, Integer> stationPositions(SegmentGeometry g, PrefixSummary prefix, List<SiteRecord> sites) {
        long start = prefix.lengthBeforeQ8(), end = Math.addExact(start, g.localSQ8().getLast());
        var result = new TreeMap<Long, Integer>(); result.put(start, 2); result.put(end, 2);
        for (long s = Math.addExact(start - Math.floorMod(start, 4 * 256), 4 * 256); s < end; s += 4 * 256) result.put(s, 0);
        for (var site : sites) if (site.state() == SiteState.PLANNED) {
            if (ImportedSiteGeometry.enabled(recipe)) {
                for (long s : ImportedSiteGeometry.knots(site)) if (s >= start && s <= end) result.merge(s, 1, (a, b) -> a | b);
                continue;
            }
            var f = SiteScheduler.footprint(site.id().kind());
            for (int offset : new int[] {f.minX(), 0, f.maxXExclusive()}) {
                long s = site.globalSQ8() + offset * 256L;
                if (s >= start && s <= end) result.merge(s, 1, (a, b) -> a | b);
            }
            if (site.id().kind() == SiteKind.GATE) for (int offset : new int[] {-6, -2, 2, 6}) {
                long s = site.globalSQ8() + offset * 256L;
                if (s >= start && s <= end) result.merge(s, 4, (a, b) -> a | b);
            }
        }
        return result;
    }

    private List<Point2q> platformSamples(SiteRecord site) {
        if (ImportedSiteGeometry.enabled(recipe)) {
            var b = ImportedSiteGeometry.bounds(site);
            var result = new ArrayList<Point2q>();
            for (int z = b.minZ(); z < b.maxZExclusive(); z++) for (int x = b.minX(); x < b.maxXExclusive(); x++)
                result.add(new Point2q((long) x * 256 + 128, (long) z * 256 + 128));
            return result;
        }
        var f = SiteScheduler.footprint(site.id().kind());
        var result = new ArrayList<Point2q>();
        for (long x : new long[] {f.minX() * 256L + 128, 0, f.maxXExclusive() * 256L - 128}) {
            for (long z : new long[] {f.minZ() * 256L + 128, 0, f.maxZExclusive() * 256L - 128}) {
                result.add(Projection.local(site.frame(), x, site.frame().mirror() ? -z : z));
            }
        }
        return List.copyOf(result);
    }
    private Point2q pointAt(SegmentGeometry geometry, PrefixSummary prefix, List<SiteRecord> sites, long s) {
        var original = ArcLength.pointAt(geometry, s - prefix.lengthBeforeQ8());
        return ImportedSiteGeometry.enabled(recipe) ? ImportedSiteGeometry.routePoint(original, s, sites) : original;
    }
    private static ColumnSample required(Map<Point2q, ColumnSample> columns, Point2q point) {
        var result = columns.get(point);
        if (result == null) throw new IllegalArgumentException("missing frozen contract column " + point);
        return result;
    }
    private static void addBreak(List<BreakInterval> breaks, long from, long to) {
        if (to > from) breaks.add(new BreakInterval(new ArcInterval(from, to), BreakReason.SLOPE, BreakScope.PLANNED));
    }
    private static List<BreakInterval> mergeBreaks(List<BreakInterval> breaks) {
        var sorted = breaks.stream().sorted(Comparator.comparingLong(b -> b.range().fromQ8())).toList();
        var result = new ArrayList<BreakInterval>();
        for (var b : sorted) {
            if (!result.isEmpty() && result.getLast().range().toQ8() >= b.range().fromQ8()) {
                var last = result.removeLast();
                result.add(new BreakInterval(new ArcInterval(last.range().fromQ8(), Math.max(last.range().toQ8(), b.range().toQ8())), BreakReason.SLOPE, BreakScope.PLANNED));
            } else result.add(b);
        }
        return List.copyOf(result);
    }
    private static final class Node {
        private final long s;
        private final Point2q point;
        private int target, flags;
        private boolean hard;
        private Node(long s, Point2q point, int target, int flags, boolean hard) {
            this.s = s; this.point = point; this.target = target; this.flags = flags; this.hard = hard;
        }
    }
}
