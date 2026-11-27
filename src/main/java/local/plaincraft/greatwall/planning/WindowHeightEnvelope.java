package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import local.plaincraft.greatwall.component.ImportedSiteGeometry;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallRecipeRules;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 全窗精采结束后一次求最低缓坡上包络，段缝和楼台可以一起抬高，施工只照冻结结果落块。 */
public final class WindowHeightEnvelope {
    private static final String ZERO = "0".repeat(64);
    private record Node(Station station, int height) {}

    static List<SegmentPlan> solve(WorldRecipe recipe, List<SegmentPlan> plans, FineHeightIndex terrain) {
        return solve(recipe, plans, terrain, StructureObstacleIndex.empty(0));
    }

    public static List<SegmentPlan> solve(WorldRecipe recipe, List<SegmentPlan> plans, FineHeightIndex terrain,
            StructureObstacleIndex obstacles) {
        if (!WallRecipeRules.landFirst(recipe.config()) || plans.isEmpty()) return plans;
        var sorted = plans.stream().sorted(Comparator.comparingLong(p -> p.geometry().key().index())).toList();
        var nodes = new TreeMap<Long, Node>();
        var sites = sorted.stream().flatMap(p -> p.sites().stream()).filter(s -> s.state() == SiteState.PLANNED).toList();
        for (var plan : sorted) for (var station : plan.stations().stations()) {
            int x = FixedMath.floorBlock(station.point().xQ8()), z = FixedMath.floorBlock(station.point().zQ8());
            int target = station.walkY();
            long local = station.globalSQ8() - plan.prefix().lengthBeforeQ8();
            boolean sampled = plan.geometry().terminal().map(t -> local <= t.localSQ8() + 32L * 256).orElse(true);
            // 读已有精采的实际墙宽，山体侧面也必须在马道下；不再向世界额外采样。
            // 站距约四格，额外两格覆盖站间窄峰；半径8的逐格精采已包含这些列。
            for (int dz = -8; sampled && dz <= 8; dz++) for (int dx = -8; dx <= 8; dx++) {
                var column = terrain.at(x + dx, z + dz);
                if (column == null) throw new WallFailure(FailureCode.T0_MISSING, "缓坡求解缺少墙宽精采列");
                target = Math.max(target, column.groundTopY() + 20);
            }
            final int height = target;
            nodes.merge(station.globalSQ8(), new Node(station, height),
                    (a, b) -> new Node(a.station(), Math.max(a.height(), b.height())));
        }
        var list = new ArrayList<>(nodes.values());
        int[] heights = new int[list.size()];
        long[] limits = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            heights[i] = list.get(i).height();
            if (i == 0) continue;
            long a = list.get(i - 1).station().globalSQ8(), b = list.get(i).station().globalSQ8();
            // 短于四格的楼口接缝保持同高，不能借全局相位在一两格内提前跳一级。
            limits[i] = (b - a) / 1024;
            for (var site : sites) if (ImportedSiteGeometry.levelAt(site, a) && ImportedSiteGeometry.levelAt(site, b)) {
                limits[i] = 0; break;
            }
        }
        // 两次最大值传播是线性的；零预算边让整个平台同时抬升，既不压低高点也不产生陡接缝。
        for (int i = 1; i < heights.length; i++) heights[i] = Math.toIntExact(Math.max(heights[i], heights[i - 1] - limits[i]));
        for (int i = heights.length - 2; i >= 0; i--) heights[i] = Math.toIntExact(Math.max(heights[i], heights[i + 1] - limits[i + 1]));
        var solved = new TreeMap<Long, Integer>();
        for (int i = 0; i < list.size(); i++) {
            if (heights[i] >= recipe.bounds().maxYExclusive() - 3)
                throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "缓坡抬升超过世界高度上限");
            solved.put(list.get(i).station().globalSQ8(), heights[i]);
        }
        var result = new ArrayList<SegmentPlan>();
        var binder = new SiteScheduler(recipe);
        for (var plan : sorted) {
            var stations = plan.stations().stations().stream().map(s -> new Station(s.globalSQ8(), s.point(),
                    solved.get(s.globalSQ8()), s.flags() & ~16)).toList();
            // 原来由硬段缝产生的坡度缺口已可解；世界边界等真实断口仍保留。
            var breaks = plan.stations().breaks().stream().filter(b -> b.reason() != BreakReason.SLOPE).toList();
            var raw = new StationTable(stations, breaks, ZERO, plan.stations().biomeKeys());
            var table = new StationTable(stations, breaks, DeterministicHash.sha256(CanonicalCodec.stations(raw)), raw.biomeKeys());
            var bound = new ArrayList<SiteRecord>();
            for (var site : binder.bindHeights(plan.sites(), table)) {
                if (site.state() == SiteState.PLANNED) {
                    var box = ImportedSiteGeometry.bounds(site);
                    var reason = box.maxYExclusive() > recipe.bounds().maxYExclusive() ? BreakReason.WORLD_LIMIT
                            : obstacles.intersects(box, box.minY(), box.maxYExclusive()) ? BreakReason.STRUCTURE_CONFLICT : null;
                    if (reason != null) {
                        // 起点是唯一的出墙端面；普通楼终态放不下时保留已经精采、解坡的连续马道。
                        if (site.id().kind() == SiteKind.FORT && site.id().ordinal() == 0)
                            throw new WallFailure(reason == BreakReason.WORLD_LIMIT ? FailureCode.OUT_OF_BOUNDS : FailureCode.OWNERSHIP_CONFLICT,
                                    "起点澄海楼缓坡终态不合格 reason=" + reason + " bounds=" + box);
                        site = new SiteRecord(site.id(), site.globalSQ8(), site.frame(), box, SiteState.SUPPRESSED,
                                site.absorbedBy(), java.util.Optional.of(reason));
                    }
                }
                bound.add(site);
            }
            var rawPlan = new SegmentPlan(plan.geometry(), plan.prefix(), table, bound, ZERO);
            result.add(new SegmentPlan(plan.geometry(), plan.prefix(), table, bound,
                    DeterministicHash.sha256(CanonicalCodec.plan(rawPlan))));
        }
        return List.copyOf(result);
    }
}
