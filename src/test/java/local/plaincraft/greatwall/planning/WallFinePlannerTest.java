package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import local.plaincraft.greatwall.terrain.FineSampleCache;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class WallFinePlannerTest {
    private static RouteShape route(WorldRecipe recipe) {
        var planner = new RidgePlanner(recipe, new AnchorField(recipe));
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var cursor = planner.begin(new SegmentKey(recipe.recipeHash(), recipe.dimension(), 0), oracle);
            while (planner.advance(cursor, 64) != WorkState.DONE) { }
            return planner.finish(cursor);
        }
    }

    @Test void samplesFullWidthPerRoutePoint() {
        var recipe = PlanningFixtures.recipe(9282026);
        var route = route(recipe);
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var cursor = WallFinePlanner.begin(route, oracle);
            while (WallFinePlanner.advance(cursor, 1) != WorkState.DONE) { }
            var columns = WallFinePlanner.finish(cursor);
            // Each centre point yields up to 2*LATERAL+1 = 105 columns; straight runs deduplicate
            // overlapping neighbours, so the count is bounded by points * 105 and at least points.
            // Both route ends also contribute APRON_BLOCKS extra cross-sections (see below).
            assertTrue(columns.size() >= route.points().size(),
                    "at least one column per centre point, got " + columns.size());
            long widest = (long) (route.points().size() + 2 * WallFinePlanner.APRON_BLOCKS)
                    * (2 * WallFinePlanner.LATERAL + 1);
            assertTrue(columns.size() <= widest,
                    "at most full width per centre point, got " + columns.size() + " > " + widest);
        }
    }

    @Test void lateralBandSpansFullWidth() {
        var recipe = PlanningFixtures.recipe(9282026);
        var route = route(recipe);
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var cursor = WallFinePlanner.begin(route, oracle);
            while (WallFinePlanner.advance(cursor, 64) != WorkState.DONE) { }
            var columns = WallFinePlanner.finish(cursor);
            boolean sawCentre = false, sawMinus5 = false, sawPlus5 = false;
            for (var c : columns) {
                if (c.lateral() == 0) sawCentre = true;
                if (c.lateral() == -WallFinePlanner.LATERAL) sawMinus5 = true;
                if (c.lateral() == WallFinePlanner.LATERAL) sawPlus5 = true;
            }
            assertTrue(sawCentre, "centre column sampled");
            assertTrue(sawMinus5, "-5 lateral column sampled");
            assertTrue(sawPlus5, "+5 lateral column sampled");
        }
    }

    @Test void theSampledBandCoversARotatedFootprintAtBothEnds() {
        // Regression guard for "complete component T0 column missing": the compile step reads every
        // column of a component's axis aligned world bounds - including the cells behind the route
        // ends, where FORT0/GATE0 sit - and refuses to place the component when one is missing. The
        // band therefore has to cover the widest template (FORT: half extents 24 along and 24 across)
        // plus the one-cell padding Rasterizer.worldBounds adds, at whatever angle the ridge runs.
        var recipe = PlanningFixtures.recipe(9282026);
        var route = route(recipe);
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var cursor = WallFinePlanner.begin(route, oracle);
            while (WallFinePlanner.advance(cursor, 64) != WorkState.DONE) { }
            var sampled = new java.util.HashSet<Long>();
            for (var c : WallFinePlanner.finish(cursor)) {
                sampled.add(((long) c.x() << 32) ^ (c.z() & 0xffffffffL));
            }
            // The cross-sections are one-cell-wide lines one route point apart, so the raw set is a
            // ladder with occasional pinholes. The index fills those by dilating each column into its
            // neighbours (never overwriting a real sample), so the guard checks the dilated set.
            var dilated = new java.util.HashSet<Long>(sampled);
            for (long key : sampled) {
                int x = (int) (key >> 32), z = (int) key;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        dilated.add(((long) (x + dx) << 32) ^ ((z + dz) & 0xffffffffL));
                    }
                }
            }
            for (int end : new int[] {0, route.points().size() - 1}) {
                var centre = route.points().get(end);
                int cx = local.plaincraft.greatwall.geometry.FixedMath.floorBlock(centre.xQ8());
                int cz = local.plaincraft.greatwall.geometry.FixedMath.floorBlock(centre.zQ8());
                var missing = new java.util.ArrayList<String>();
                for (int x = cx - 25; x <= cx + 25; x++) {
                    for (int z = cz - 25; z <= cz + 25; z++) {
                        if (!dilated.contains(((long) x << 32) ^ (z & 0xffffffffL))) missing.add(x + "," + z);
                    }
                }
                assertTrue(missing.isEmpty(), "end " + end + " centre " + cx + "," + cz + ": "
                        + missing.size() + " of 2601 columns unsampled even after dilation, e.g. "
                        + missing.subList(0, Math.min(5, missing.size())));
            }
        }
    }

    @Test void skippingRedundantSectionsNeverDropsAColumn() {
        // The planner drops a cross-section whose columns it has already sampled: consecutive route
        // points sit a fraction of a block apart, so their sections round to the same integers, and a
        // column's sample is a pure function of its (x, z) - repeating the walk only costs time. This
        // guard walks every section the planner would have walked and asserts the finished set still
        // holds all of them, so a regression in the skip condition shows up as a missing column rather
        // than as a component that silently refuses to compile in production.
        var recipe = PlanningFixtures.recipe(9282026);
        var route = route(recipe);
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var cursor = WallFinePlanner.begin(route, oracle);
            while (WallFinePlanner.advance(cursor, 64) != WorkState.DONE) { }
            var emitted = new java.util.HashSet<Long>();
            for (var c : WallFinePlanner.finish(cursor)) emitted.add(key(c.x(), c.z()));
            var missing = new java.util.ArrayList<String>();
            for (int i = 0; i < route.points().size(); i++) {
                var centre = route.points().get(i);
                for (var target : WallFinePlanner.crossSection(route.points(), i,
                        local.plaincraft.greatwall.geometry.FixedMath.floorBlock(centre.xQ8()),
                        local.plaincraft.greatwall.geometry.FixedMath.floorBlock(centre.zQ8()))) {
                    if (!emitted.contains(key(target.x(), target.z()))) {
                        missing.add(target.x() + "," + target.z());
                    }
                }
            }
            assertTrue(missing.isEmpty(), "skipping lost " + missing.size()
                    + " centre columns, e.g. " + missing.subList(0, Math.min(5, missing.size())));
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /**
     * 每列只许发出一次。相邻路线点取整后压在同一个格子上，合并索引本来就会把它们去重，但发出这一层
     * 以前会把每一份拷贝都追加进去：一次真机开局因此走了 134 万列，而走廊唯一列只有约 27 万（≈5 倍）。
     * 这里的判据是最直接的——成品列表里不允许出现重复的 (x,z)。
     */
    @Test void everyColumnIsEmittedExactlyOnce() {
        var recipe = PlanningFixtures.recipe(9282026);
        var route = route(recipe);
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var cursor = WallFinePlanner.begin(route, oracle);
            while (WallFinePlanner.advance(cursor, 64) != WorkState.DONE) { }
            var columns = WallFinePlanner.finish(cursor);
            var seen = new java.util.HashSet<Long>();
            var duplicated = new java.util.ArrayList<String>();
            for (var c : columns) {
                if (!seen.add(key(c.x(), c.z()))) duplicated.add(c.x() + "," + c.z());
            }
            assertTrue(duplicated.isEmpty(), columns.size() + " columns, " + duplicated.size()
                    + " emitted more than once, e.g. "
                    + duplicated.subList(0, Math.min(5, duplicated.size())));
        }
    }

    /**
     * 采样网格（玩家可填的 {@code fineSampleGridStep}）必须真的抽稀，而且抽稀后每一列仍然挨着某个真采样。
     *
     * <p>网格是沿着（每 N 个路线点一条断面）与横向（每 N 格一列）两轴一起收的，缺一条轴就等于没提速；
     * 洞由 {@code PlanningColumnProvider} 的格点回填补上（半径 {@code max(FILL_RADIUS, N)}）。真正要守的
     * 是覆盖：回填补不到的格子在组件包围盒里出现一个，编译期就会整块拒绝那个组件——所以这里用全分辨率的
     * 那一遍做底，逐列检查它每个格子身边都有网格采样点。
     */
    @Test void aSamplingGridThinsTheWalkAndStillLeavesEveryColumnAProbeWithinTheFillRadius() {
        var recipe = PlanningFixtures.recipe(9282026);
        var route = route(recipe);
        var full = columns(route, 1);
        var grid = columns(route, 2);
        assertFalse(full.isEmpty(), "the full-resolution walk produced no columns at all");
        assertFalse(grid.isEmpty(), "grid step 2 produced no columns at all");
        var fullSet = new java.util.HashSet<Long>();
        for (var c : full) fullSet.add(key(c.x(), c.z()));
        for (var c : grid) {
            assertEquals(0, Math.floorMod(c.lateral(), 2),
                    "grid step 2 emitted an off-grid lateral " + c.lateral());
            assertTrue(fullSet.contains(key(c.x(), c.z())),
                    "grid step 2 emitted a column the full walk never saw: " + c.x() + "," + c.z());
        }
        int radius = 3; // PlanningColumnProvider.fillRadiusFor(2) == max(FILL_RADIUS, 2)
        var probes = new java.util.HashSet<Long>();
        for (var c : grid) probes.add(key(c.x(), c.z()));
        int worst = 0;
        var orphan = new java.util.ArrayList<String>();
        for (var c : full) {
            int best = Integer.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (probes.contains(key(c.x() + dx, c.z() + dz))) {
                        best = Math.min(best, Math.max(Math.abs(dx), Math.abs(dz)));
                    }
                }
            }
            if (best == Integer.MAX_VALUE) orphan.add(c.x() + "," + c.z());
            else worst = Math.max(worst, best);
        }
        assertTrue(orphan.isEmpty(), orphan.size() + " columns of the full walk have no grid probe within "
                + radius + " blocks, e.g. " + orphan.subList(0, Math.min(5, orphan.size())));
        assertTrue(worst <= radius, "worst column to probe distance " + worst + " exceeds the fill radius");
        assertTrue(grid.size() < full.size(), "grid step 2 walked " + grid.size()
                + " columns, the same walk with the grid off " + full.size() + ": the grid did not thin");
    }

    /**
     * 会话级 memo：同一列在一局里被走到第二次时，必须直接复用第一次的采样，不再重走 oracle。
     *
     * <p>盘缓存（{@code FineSampleCache}）只是开局读、收尾写，所以一局内部它答不上第二次提问；相邻路线点
     * 又频繁压在同一个格子上，于是同一列被反复走。这条测试把 memo 预置成一个哨兵高度：实现若真的先查
     * memo、再查盘、最后才走 oracle，中心列发出的高度就必然是哨兵值。
     */
    @Test void aWalkedColumnIsAnsweredFromTheSessionMemoWithoutProbingAgain() {
        var recipe = PlanningFixtures.recipe(9282026);
        var route = route(recipe);
        var points = route.points();
        var centre = points.get(0);
        int cx = local.plaincraft.greatwall.geometry.FixedMath.floorBlock(centre.xQ8());
        int cz = local.plaincraft.greatwall.geometry.FixedMath.floorBlock(centre.zQ8());
        // 中心列（lateral 0）在任何网格步长下都会被采，所以这条测试不受玩家配置里的网格影响。
        var centreTarget = WallFinePlanner.crossSection(points, 0, cx, cz).stream()
                .filter(target -> target.lateral() == 0).findFirst().orElseThrow();
        int sentinelGround = 999;
        var memo = new java.util.concurrent.ConcurrentHashMap<Long, ColumnSample>();
        memo.put(key(centreTarget.x(), centreTarget.z()), new ColumnSample(sentinelGround,
                OptionalInt.empty(), OptionalInt.empty(), "minecraft:plains", Quality.REFERENCE, false));
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var cursor = WallFinePlanner.begin(route, oracle, 0, points.size(), FineSampleCache.none(), null, memo);
            while (WallFinePlanner.advance(cursor, 64) != WorkState.DONE) { }
            var emitted = WallFinePlanner.finish(cursor).stream()
                    .filter(c -> c.x() == centreTarget.x() && c.z() == centreTarget.z())
                    .findFirst().orElseThrow();
            assertEquals(sentinelGround, emitted.sample().groundTopY(),
                    "memo 里的采样没有被复用：这一列仍然走了 oracle");
        }
    }

    /** Walks the route with one sampling grid step frozen for the duration, then restores the snapshot. */
    private static java.util.List<WallFinePlanner.FineColumn> columns(RouteShape route, int gridStep) {
        var config = local.plaincraft.greatwall.config.GreatWallConfig.get();
        int saved = config.fineSampleGridStep();
        try {
            config.setFineSampleGridStep(gridStep);
            // 就地冻结内存值：reload() 会重读玩家真实配置文件，既会撤销 setter 也会碰到玩家的设置。
            local.plaincraft.greatwall.config.GreatWallConfig.freezeParameters();
            try (var oracle = AnalyticTerrainOracle.ridge(true)) {
                var cursor = WallFinePlanner.begin(route, oracle);
                while (WallFinePlanner.advance(cursor, 64) != WorkState.DONE) { }
                return WallFinePlanner.finish(cursor);
            }
        } finally {
            config.setFineSampleGridStep(saved);
            local.plaincraft.greatwall.config.GreatWallConfig.reload();
        }
    }

    @Test void unfinishableAndOwnerConfined() {
        var recipe = PlanningFixtures.recipe(9282026);
        var route = route(recipe);
        var oracle = AnalyticTerrainOracle.ridge(false);
        var cursor = WallFinePlanner.begin(route, oracle);
        assertThrows(IllegalStateException.class, () -> WallFinePlanner.finish(cursor));
        assertThrows(IllegalArgumentException.class, () -> WallFinePlanner.advance(cursor, 0));
        oracle.close();
        assertThrows(IllegalStateException.class, () -> WallFinePlanner.advance(cursor, 1));
    }
}
