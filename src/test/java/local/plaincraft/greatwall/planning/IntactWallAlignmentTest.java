package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.nio.file.Path;
import local.plaincraft.greatwall.config.GreatWallConfig;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallRecipeRules;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 按生产的锚点、采样和计划链验证有限墙新规则，旧配方由原测试继续守。 */
class IntactWallAlignmentTest {
    @TempDir Path temp;

    @Test void finiteLengthExtendsBothWaysAndUnlimitedAxisDoesNotMove() {
        var root = new Axis(new Point3i(-27, 64, 11), 0, -(1 << 30));
        var centered = WallRecipeRules.centerFiniteAxis(root, 8191, 3);
        assertEquals(new Point3i(-27, 64, 4106), centered.origin());
        assertEquals(-4085, centered.origin().z() - 8191);
        assertSame(root, WallRecipeRules.centerFiniteAxis(root, 0, 3));
        assertSame(root, WallRecipeRules.centerFiniteAxis(root, 8191, 2));
    }

    @Test void aTallStationRaisesTheApproachOnBothSidesBeforeVoxelPlacement() {
        var recipe = ImportedSiteScheduleTest.recipe(3);
        var key = new SegmentKey(recipe.recipeHash(), recipe.dimension(), 0);
        var a = new Point2q(128, 128);
        var b = new Point2q(128L * 256 + 128, 128);
        var geometry = new SegmentGeometry(key, List.of(a, b), List.of(0L, 128L * 256), List.of(), Optional.empty(),
                new Boundary(0, a, 84, recipe.recipeHash()), new Boundary(1, b, 84, recipe.recipeHash()),
                0, 0, recipe.recipeHash());
        var heights = new HeightContractPlanner(recipe);
        var columns = new HashMap<Point2q, ColumnSample>();
        var prefix = PrefixSummaryIndex.origin();
        for (var p : heights.samplePoints(geometry, prefix, List.of()))
            columns.put(p, AnalyticTerrainOracle.dry(p.xQ8() / 256 == 64 ? 96 : 64));
        var table = heights.solve(geometry, prefix, List.of(), columns);
        assertTrue(table.breaks().isEmpty());
        assertEquals(116, heights.walkY(table, 64L * 256));
        assertEquals(114, heights.walkY(table, 60L * 256));
        assertEquals(114, heights.walkY(table, 68L * 256));
        for (long s = 256; s <= 128L * 256; s += 256)
            assertTrue(Math.abs(heights.walkY(table, s) - heights.walkY(table, s - 256)) <= 1);
    }

    @Test void bothSegmentsUseHeightFromAcrossTheirSharedSeam() {
        var recipe = ImportedSiteScheduleTest.recipe(3);
        var planner = new RidgePlanner(recipe, new AnchorField(recipe));
        int seam = recipe.axis().origin().x() + 2048;
        try (var oracle = new AnalyticTerrainOracle((x,z) -> AnalyticTerrainOracle.dry(x == seam + 16 ? 96 : 64), true)) {
            var a = planner.begin(new SegmentKey(recipe.recipeHash(), recipe.dimension(), 0), oracle);
            var b = planner.begin(new SegmentKey(recipe.recipeHash(), recipe.dimension(), 1), oracle);
            while (planner.advance(a, 64) != WorkState.DONE) {}
            while (planner.advance(b, 64) != WorkState.DONE) {}
            assertEquals(108, planner.finish(a).end().walkY());
            assertEquals(planner.finish(a).end(), planner.finish(b).start());
        }
    }

    @Test void wetFirstSegmentDoesNotEndTheWholeFiniteWall() {
        var sample = ImportedSiteScheduleTest.recipe(3);
        var hash = DeterministicHash.sha256(CanonicalCodec.recipe(sample));
        var recipe = new WorldRecipe(sample.instance(), hash, sample.seed(), sample.dimension(), sample.frozenSpawn(),
                sample.axis(), sample.bounds(), sample.config(), sample.terrainHash(), sample.paletteHash(), sample.templatesHash(), hash);
        int root = recipe.axis().origin().x();
        // 水跨越所有 Z，确保判宽来自真实水域而非恰好绕开的测试夹具。
        java.util.function.BiFunction<Integer,Integer,ColumnSample> terrain = (x,z) ->
                x > root + 100 && x <= root + 200
                        ? AnalyticTerrainOracle.water(63, 55) : AnalyticTerrainOracle.dry(64);
        var settings = GreatWallConfig.get();
        long oldLength = settings.wallLengthBlocks();
        settings.setWallLengthBlocks(3000);
        GreatWallConfig.freezeParameters();
        try (var io = Executors.newSingleThreadExecutor();
             var scheduler = new PlannerScheduler(recipe, () -> new AnalyticTerrainOracle(terrain, true),
                     new SegmentCacheStore(temp.resolve("wall"), recipe), io)) {
            var first = scheduler.computeGeometryNow(0, new AnalyticTerrainOracle(terrain, true));
            assertTrue(first.water().stream().anyMatch(w -> w.classification() == WaterClass.WIDE));
            assertTrue(PrefixSummaryIndex.reduce(PrefixSummaryIndex.origin(), first).terminal().isEmpty());
            var inland = scheduler.computeGeometryNow(1, new AnalyticTerrainOracle(terrain, true));
            assertTrue(inland.water().isEmpty());
            assertEquals(first.end(), inland.start());
            var limit = inland.terminal().orElseThrow();
            assertEquals(TerminalKind.WORLD_LIMIT, limit.kind());
            assertEquals(inland.localSQ8().get(952), limit.localSQ8());
        } finally {
            settings.setWallLengthBlocks(oldLength);
            GreatWallConfig.freezeParameters();
        }
    }
}
