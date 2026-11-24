package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import local.plaincraft.greatwall.component.ImportedSiteGeometry;
import local.plaincraft.greatwall.model.WallRecipeRules;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 平台高度由地形决定；楼体超界只能拒绝整站，不能压低平台或裁掉屋顶。 */
final class ImportedSiteHeightPolicy {
    record Result(List<SiteRecord> sites, List<BreakInterval> breaks) {}

    static Result apply(WorldRecipe recipe, List<SiteRecord> sites, Map<Point2q, ColumnSample> columns,
            long start, long end) {
        var accepted = new ArrayList<SiteRecord>();
        var breaks = new ArrayList<BreakInterval>();
        boolean adaptive = WallRecipeRules.landFirst(recipe.config());
        for (var site : sites) {
            if (site.state() != SiteState.PLANNED) { accepted.add(site); continue; }
            var box = ImportedSiteGeometry.bounds(site);
            int highest = Integer.MIN_VALUE;
            boolean unsafe = false;
            for (int z = box.minZ(); z < box.maxZExclusive(); z++)
                for (int x = box.minX(); x < box.maxXExclusive(); x++) {
                    var point = new Point2q((long) x * 256 + 128, (long) z * 256 + 128);
                    var column = columns.get(point);
                    if (column == null) throw new IllegalArgumentException("missing frozen platform column " + point);
                    highest = Math.max(highest, column.groundTopY());
                    // 粗选网格可能跳过一格窄水或截断列，不能据此批准整座楼。
                    if (adaptive && (column.waterTopY().isPresent() || column.upperCensored()
                            || column.quality() != Quality.REFERENCE)) unsafe = true;
                }
            long platform = (long) highest + 15;
            long low = platform + box.minY() - site.frame().walkY();
            long high = platform + box.maxYExclusive() - site.frame().walkY();
            if (!unsafe && low >= recipe.bounds().minY() && high <= recipe.bounds().maxYExclusive()) {
                accepted.add(site);
            } else {
                accepted.add(new SiteRecord(site.id(), site.globalSQ8(), site.frame(), site.bounds(),
                        SiteState.SUPPRESSED, site.absorbedBy(), Optional.of(unsafe ? BreakReason.NO_SUPPORT : BreakReason.WORLD_LIMIT)));
                // 新世界尚未落块，撤掉楼位后可用原墙线；水文和墙本身的保护仍单独裁决。
                if (adaptive) continue;
                // 留出整座楼和接引范围，不能在拒绝后的原路线中塞入一堵没有端口的普通墙。
                long reach = ImportedSiteGeometry.reach(site.id()) * 256L;
                long from = Math.max(start, site.globalSQ8() - reach), to = Math.min(end, site.globalSQ8() + reach);
                if (to > from) breaks.add(new BreakInterval(new ArcInterval(from, to),
                        BreakReason.WORLD_LIMIT, BreakScope.PLANNED));
            }
        }
        return new Result(List.copyOf(accepted), List.copyOf(breaks));
    }
}
