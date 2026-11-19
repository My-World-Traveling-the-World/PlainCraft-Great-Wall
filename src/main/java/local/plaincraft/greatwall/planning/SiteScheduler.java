package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import local.plaincraft.greatwall.component.ImportedSiteGeometry;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Global true-distance schedule. Absorption never renumbers a site or moves its semantic S. */
public final class SiteScheduler {
    private static final Comparator<SiteId> IDS = Comparator.comparing(SiteId::wallId)
            .thenComparing(v -> v.kind().name()).thenComparingLong(SiteId::ordinal);
    private static final Comparator<SiteRecord> ORDER = Comparator.comparingLong(SiteRecord::globalSQ8).thenComparing(SiteRecord::id, IDS);
    private final WorldRecipe recipe;

    public SiteScheduler(WorldRecipe recipe) { this.recipe = Objects.requireNonNull(recipe); }
    boolean imported() { return ImportedSiteGeometry.enabled(recipe); }

    public long gatePositionQ8(long ordinal) {
        if (ordinal < 0) throw new IllegalArgumentException("negative gate ordinal");
        if (ordinal == 0) return 0;
        return Math.multiplyExact(Math.addExact(Math.multiplyExact(800, ordinal), DeterministicHash.bounded(
                DeterministicHash.fields(recipe.seed(), "gate", ordinal), 201) - 100), 256);
    }

    public long fortPositionQ8(long ordinal) {
        if (ordinal < 0) throw new IllegalArgumentException("negative fort ordinal");
        if (ordinal == 0) return 0;
        return Math.multiplyExact(Math.addExact(Math.multiplyExact(4000, ordinal), DeterministicHash.bounded(
                DeterministicHash.fields(recipe.seed(), "fort", ordinal), 1001) - 500), 256);
    }

    public List<SiteRecord> schedule(GeometryWindow window, long fromQ8, long toQ8) {
        if (fromQ8 < 0 || toQ8 <= fromQ8 || toQ8 - fromQ8 > 5L * 4096 * 256) throw new IllegalArgumentException("bounded schedule interval required");
        var geometries = new HashMap<Long, SegmentGeometry>();
        var prefixes = new HashMap<Long, PrefixSummary>();
        for (var g : window.segments()) {
            if (!g.key().recipeHash().equals(recipe.recipeHash()) || !g.key().dimension().equals(recipe.dimension())) {
                throw new WallFailure(FailureCode.RECIPE_MISMATCH, "schedule window recipe mismatch");
            }
            if (geometries.putIfAbsent(g.key().index(), g) != null) throw new IllegalArgumentException("duplicate geometry");
        }
        for (var p : window.prefixes()) if (prefixes.putIfAbsent(p.beforeSegment(), p) != null) throw new IllegalArgumentException("duplicate prefix");
        long terminalS = Long.MAX_VALUE;
        for (var g : geometries.values()) {
            var prefix = prefixes.get(g.key().index());
            if (prefix == null) throw new IllegalArgumentException("missing exact schedule prefix");
            if (g.terminal().isPresent()) terminalS = Math.min(terminalS, Math.addExact(prefix.lengthBeforeQ8(), g.terminal().get().localSQ8()));
        }
        long halo = ImportedSiteGeometry.enabled(recipe) ? 512L * 256 : 256L * 256;
        long low = Math.max(0, fromQ8 - halo), high = Math.addExact(toQ8, halo);
        var sites = new ArrayList<SiteRecord>();
        for (long n = Math.max(1, low / (300 * 256)); n <= high / (300 * 256) + 1; n++) {
            SiteKind kind = (n & 1) == 1 ? SiteKind.ENEMY_TOWER : SiteKind.BEACON;
            add(sites, new SiteId(recipe.wallId(), kind, kind == SiteKind.BEACON ? n / 2 : n),
                    Math.multiplyExact(n, 300 * 256), low, high, terminalS, geometries, prefixes);
        }
        for (long n = Math.max(1, Math.floorDiv(low - 100 * 256, 800 * 256)); n <= (high + 100 * 256) / (800 * 256) + 1; n++) {
            add(sites, new SiteId(recipe.wallId(), SiteKind.GATE, n), gatePositionQ8(n), low, high, terminalS, geometries, prefixes);
        }
        for (long n = Math.max(0, Math.floorDiv(low - 500 * 256, 4000 * 256)); n <= (high + 500 * 256) / (4000 * 256) + 1; n++) {
            add(sites, new SiteId(recipe.wallId(), SiteKind.FORT, n), fortPositionQ8(n), low, high, terminalS, geometries, prefixes);
        }
        sites.sort(ORDER);
        var resolved = ImportedSiteGeometry.enabled(recipe) ? resolveImported(sites) : resolve(sites);
        var selected = new ArrayList<SiteRecord>();
        var included = new HashSet<SiteId>();
        for (var s : resolved) {
            Bounds local = footprint(s);
            long start = s.globalSQ8() + local.minX() * 256L, end = s.globalSQ8() + local.maxXExclusive() * 256L;
            if (start < toQ8 && end > fromQ8 || s.globalSQ8() >= fromQ8 && s.globalSQ8() < toQ8) {
                selected.add(s); included.add(s.id());
                s.absorbedBy().ifPresent(included::add);
            }
        }
        for (var s : resolved) if (s.absorbedBy().filter(included::contains).isPresent()) included.add(s.id());
        for (var s : resolved) if (included.contains(s.id()) && selected.stream().noneMatch(v -> v.id().equals(s.id()))) selected.add(s);
        selected.sort(ORDER);
        if (selected.size() > 64) throw new WallFailure(FailureCode.BUDGET_EXHAUSTED, "more than 64 sites per plan");
        return List.copyOf(selected);
    }

    public List<SiteRecord> bindHeights(List<SiteRecord> provisional, StationTable heights) {
        var planner = new HeightContractPlanner(recipe);
        var byId = new HashMap<SiteId, SiteRecord>(); provisional.forEach(s -> byId.put(s.id(), s));
        var result = new ArrayList<SiteRecord>();
        if (heights.stations().isEmpty() && !provisional.isEmpty()) throw new IllegalArgumentException("missing site height contract");
        for (var s : provisional) {
            var reference = s.absorbedBy().map(id -> Objects.requireNonNull(byId.get(id), "missing winner")).orElse(s);
            long sampleS = Math.clamp(reference.globalSQ8(), heights.stations().getFirst().globalSQ8(), heights.stations().getLast().globalSQ8());
            int y = planner.walkY(heights, sampleS);
            var frame = new Frame(s.frame().center(), s.frame().txQ30(), s.frame().tzQ30(), y, s.frame().mirror());
            var f = footprint(reference);
            long start = reference.globalSQ8() + f.minX() * 256L, end = reference.globalSQ8() + f.maxXExclusive() * 256L;
            boolean broken = heights.breaks().stream().anyMatch(b -> b.scope() == BreakScope.PLANNED && b.range().fromQ8() < end && b.range().toQ8() > start);
            result.add(new SiteRecord(s.id(), s.globalSQ8(), frame, worldBounds(s.id(), frame),
                    broken ? SiteState.SUPPRESSED : s.state(), s.absorbedBy(),
                    broken ? Optional.of(BreakReason.SLOPE) : s.reason()));
        }
        return List.copyOf(result);
    }

    static Bounds footprint(SiteKind kind) {
        return switch (kind) {
            case ENEMY_TOWER -> new Bounds(-5, -33, -5, 6, 8, 6);
            case BEACON -> new Bounds(-5, -32, -5, 5, 7, 5);
            case GATE -> new Bounds(-30, -33, -15, 30, 4, 15);
            case FORT -> new Bounds(-24, -33, -24, 24, 8, 24);
            case RAMP -> new Bounds(-30, -33, -15, 30, 3, 15);
            default -> throw new IllegalArgumentException("not a scheduled fixed-footprint site: " + kind);
        };
    }

    private Bounds footprint(SiteRecord site) {
        if (!ImportedSiteGeometry.enabled(recipe)) return footprint(site.id().kind());
        int reach = ImportedSiteGeometry.reach(site.id());
        return new Bounds(-reach, -15, -reach, reach + 1, 43, reach + 1);
    }

    private void add(List<SiteRecord> sites, SiteId id, long s, long low, long high, long terminal,
            Map<Long, SegmentGeometry> geometries, Map<Long, PrefixSummary> prefixes) {
        if (s < low || s > high || s >= terminal) return;
        SegmentGeometry geometry = null;
        long localS = 0;
        for (long index : geometries.keySet().stream().sorted().toList()) {
            var g = geometries.get(index); long start = prefixes.get(index).lengthBeforeQ8();
            if (s >= start && s < start + g.localSQ8().getLast()) { geometry = g; localS = s - start; break; }
        }
        if (geometry == null) return;
        var point = ArcLength.pointAt(geometry, localS);
        int edge = ArcLength.edgeAt(geometry.localSQ8(), localS);
        var a = geometry.points().get(edge); var b = geometry.points().get(edge + 1);
        double dx = b.xQ8() - a.xQ8(), dz = b.zQ8() - a.zQ8(), length = StrictMath.hypot(dx, dz);
        int tx = (int) StrictMath.floor(dx * (1L << 30) / length + 0.5), tz = (int) StrictMath.floor(dz * (1L << 30) / length + 0.5);
        boolean mirror = (DeterministicHash.fields(recipe.seed(), "site-mirror:" + id.kind().name(), id.ordinal()) & 1) != 0;
        long total = geometry.localSQ8().getLast();
        int y = Math.toIntExact(geometry.start().walkY() + FixedMath.roundDiv((geometry.end().walkY() - (long) geometry.start().walkY()) * localS, total));
        var frame = new Frame(point, tx, tz, y, mirror);
        if (ImportedSiteGeometry.enabled(recipe)) frame = ImportedSiteGeometry.cardinal(frame);
        sites.add(new SiteRecord(id, s, frame, worldBounds(id, frame), SiteState.PLANNED, Optional.empty(), Optional.empty()));
    }

    private List<SiteRecord> resolve(List<SiteRecord> sites) {
        var result = new ArrayList<SiteRecord>();
        var visited = new HashSet<SiteId>();
        for (var start : sites) {
            if (!visited.add(start.id())) continue;
            var group = new ArrayList<SiteRecord>(); group.add(start);
            for (int i = 0; i < group.size(); i++) {
                for (var candidate : sites) if (!visited.contains(candidate.id()) && overlaps(group.get(i).bounds(), candidate.bounds())) {
                    visited.add(candidate.id()); group.add(candidate);
                }
            }
            long min = group.stream().mapToLong(SiteRecord::globalSQ8).min().orElseThrow();
            long max = group.stream().mapToLong(SiteRecord::globalSQ8).max().orElseThrow();
            if (max - min > 256L * 256 || group.stream().filter(s -> s.id().kind() == SiteKind.FORT).count() > 1) {
                throw new WallFailure(FailureCode.BUDGET_EXHAUSTED, "oversized site conflict component");
            }
            group.sort(Comparator.<SiteRecord>comparingInt(s -> priority(s.id().kind())).reversed().thenComparing(SiteRecord::id, IDS));
            var winner = group.getFirst(); result.add(winner);
            var towerKinds = new HashSet<SiteKind>();
            int sockets = 0;
            for (var s : group.subList(1, group.size()).stream().sorted(Comparator.comparing(SiteRecord::id, IDS)).toList()) {
                boolean absorb = winner.id().kind() == SiteKind.FORT && sockets < 2
                        && (s.id().kind() == SiteKind.BEACON || s.id().kind() == SiteKind.ENEMY_TOWER) && towerKinds.add(s.id().kind());
                Frame frame = s.frame();
                if (absorb) {
                    Point2q center = Projection.local(winner.frame(), (sockets++ == 0 ? -12 : 12) * 256, -17 * 256);
                    frame = new Frame(center, winner.frame().txQ30(), winner.frame().tzQ30(), winner.frame().walkY(), frame.mirror());
                }
                result.add(new SiteRecord(s.id(), s.globalSQ8(), frame, worldBounds(s.id(), frame),
                        absorb ? SiteState.ABSORBED : SiteState.SUPPRESSED, Optional.of(winner.id()),
                        absorb ? Optional.empty() : Optional.of(BreakReason.CONFLICT)));
            }
        }
        result.sort(ORDER); return List.copyOf(result);
    }

    private static int priority(SiteKind kind) {
        return switch (kind) { case FORT -> 4; case GATE -> 3; case BEACON, ENEMY_TOWER -> 2; default -> 1; };
    }
    private static boolean overlaps(Bounds a, Bounds b) {
        return a.minX() < b.maxXExclusive() && b.minX() < a.maxXExclusive()
                && a.minZ() < b.maxZExclusive() && b.minZ() < a.maxZExclusive();
    }
    private Bounds worldBounds(SiteId id, Frame frame) {
        // 新配方按真模板预告占地；旧配方仍保留原包围盒，避免重开时改写身份。
        if (ImportedSiteGeometry.enabled(recipe)) {
            return ImportedSiteGeometry.layout(id, frame).boundsWithStraightWalls(0);
        }
        var f = footprint(id.kind());
        long minX = Long.MAX_VALUE, minZ = Long.MAX_VALUE, maxX = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
        for (int x : new int[] {f.minX(), f.maxXExclusive()}) for (int z : new int[] {f.minZ(), f.maxZExclusive()}) {
            var p = Projection.local(frame, x * 256L, (frame.mirror() ? -z : z) * 256L);
            minX = Math.min(minX, p.xQ8()); maxX = Math.max(maxX, p.xQ8());
            minZ = Math.min(minZ, p.zQ8()); maxZ = Math.max(maxZ, p.zQ8());
        }
        return new Bounds(FixedMath.floorBlock(minX), Math.addExact(frame.walkY(), f.minY()), FixedMath.floorBlock(minZ),
                Math.toIntExact(-Math.floorDiv(-maxX, 256)), Math.addExact(frame.walkY(), f.maxYExclusive()),
                Math.toIntExact(-Math.floorDiv(-maxZ, 256)));
    }

    private List<SiteRecord> resolveImported(List<SiteRecord> sites) {
        var order = Comparator.<SiteRecord>comparingInt(s -> priority(s.id().kind())).reversed().thenComparing(SiteRecord::id, IDS);
        var result = new ArrayList<SiteRecord>();
        for (var site : sites) {
            // 只比较直接冲突的固定候选，不靠窗口内贪心顺序，跨规划段也能得到同一个赢家。
            var winner = sites.stream().filter(other -> !other.id().equals(site.id()) && order.compare(other, site) < 0
                    && (Math.abs(other.globalSQ8() - site.globalSQ8())
                    < (ImportedSiteGeometry.reach(other.id()) + ImportedSiteGeometry.reach(site.id())) * 256L
                    || overlaps(other.bounds(), site.bounds()))).min(order);
            result.add(winner.map(other -> new SiteRecord(site.id(), site.globalSQ8(), site.frame(), site.bounds(),
                    SiteState.SUPPRESSED, Optional.of(other.id()), Optional.of(BreakReason.CONFLICT))).orElse(site));
        }
        return List.copyOf(result);
    }
}
