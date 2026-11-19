package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.geometry.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 用实际素材高度测上界等号及越一格，避免另一条过严常数取代旧错误。 */
class ImportedSiteHeightTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @ParameterizedTest @EnumSource(Asset.class)
    void exactRoofLimitIsAcceptedAndOneHigherIsSuppressed(Asset asset) {
        var f = ImportedProductionPlanTest.fixture(asset, 1);
        int above = f.site().bounds().maxYExclusive() - f.site().frame().walkY();
        int terrain = 320 - above - 15;
        for (int extra = 0; extra <= 1; extra++) {
            var recipe = boundedRecipe(f.recipe());
            int platform = terrain + extra + 15;
            var old = f.plan().geometry();
            var geometry = geometry(recipe, old, platform);
            var planner = new HeightContractPlanner(recipe);
            var columns = new HashMap<Point2q, ColumnSample>();
            for (var p : planner.samplePoints(geometry, f.plan().prefix(), List.of(f.site())))
                columns.put(p, AnalyticTerrainOracle.dry(terrain + extra));
            var solved = assertDoesNotThrow(() -> planner.solveWithSites(geometry, f.plan().prefix(), List.of(f.site()), columns));
            var site = solved.sites().getFirst();
            if (extra == 0) {
                assertEquals(SiteState.PLANNED, site.state());
                assertEquals(platform, planner.walkY(solved.stations(), f.site().globalSQ8()));
                assertTrue(solved.stations().breaks().isEmpty(), "合法最高平台不应因统一43格上限被拒绝");
            } else {
                assertEquals(SiteState.SUPPRESSED, site.state());
                assertEquals(Optional.of(BreakReason.WORLD_LIMIT), site.reason());
                assertFalse(solved.stations().breaks().isEmpty(), "拒绝整站时必须保留明确断区");
                assertTrue(solved.stations().breaks().stream().allMatch(b -> b.reason() == BreakReason.WORLD_LIMIT));
                var bound = new SiteScheduler(recipe).bindHeights(solved.sites(), solved.stations()).getFirst();
                assertEquals(Optional.of(BreakReason.WORLD_LIMIT), bound.reason());
            }
            assertFalse(solved.stations().stations().isEmpty());
            for (var station : solved.stations().stations()) {
                assertTrue(station.walkY() + 2 < 320);
                assertTrue(station.walkY() >= -64 + 15);
            }
        }
    }

    @ParameterizedTest @EnumSource(Asset.class)
    void rejectionRestoresSampledOriginalRouteAndUsesHighestFootprintColumn(Asset asset) {
        var f = ImportedProductionPlanTest.fixture(asset, 0);
        var recipe = boundedRecipe(f.recipe());
        var geometry = geometry(recipe, f.plan().geometry(), 84);
        var planner = new HeightContractPlanner(recipe);
        var columns = new HashMap<Point2q, ColumnSample>();
        for (var p : planner.samplePoints(geometry, f.plan().prefix(), List.of(f.site())))
            columns.put(p, AnalyticTerrainOracle.dry(64));
        var box = f.site().bounds();
        columns.put(new Point2q(box.minX() * 256L + 128, box.minZ() * 256L + 128), AnalyticTerrainOracle.dry(310));
        var solved = planner.solveWithSites(geometry, f.plan().prefix(), List.of(f.site()), columns);
        assertEquals(SiteState.SUPPRESSED, solved.sites().getFirst().state());
        for (var station : solved.stations().stations()) assertEquals(
                ArcLength.pointAt(geometry, station.globalSQ8() - f.plan().prefix().lengthBeforeQ8()), station.point());
        // 兼容只取站表的调用也必须保留拒绝原因，不能绑定时又恢复为PLANNED。
        var bound = new SiteScheduler(recipe).bindHeights(List.of(f.site()), solved.stations()).getFirst();
        assertEquals(SiteState.SUPPRESSED, bound.state());
        assertEquals(Optional.of(BreakReason.WORLD_LIMIT), bound.reason());
    }

    private static WorldRecipe boundedRecipe(WorldRecipe r) {
        var candidate = new WorldRecipe(r.instance(),r.wallId(),r.seed(),r.dimension(),r.frozenSpawn(),r.axis(),
                new DimensionBounds(-64,320,r.bounds().horizontalLimit()),r.config(),r.terrainHash(),r.paletteHash(),
                r.templatesHash(),r.recipeHash());
        return new WorldRecipe(candidate.instance(),candidate.wallId(),candidate.seed(),candidate.dimension(),
                candidate.frozenSpawn(),candidate.axis(),candidate.bounds(),candidate.config(),candidate.terrainHash(),
                candidate.paletteHash(),candidate.templatesHash(),DeterministicHash.sha256(CanonicalCodec.recipe(candidate)));
    }
    private static SegmentGeometry geometry(WorldRecipe recipe, SegmentGeometry g, int y) {
        var candidate = new SegmentGeometry(new SegmentKey(recipe.recipeHash(), recipe.dimension(), g.key().index()),
                g.points(),g.localSQ8(),g.water(),g.terminal(),new Boundary(0,g.points().getFirst(),y,g.hash()),
                new Boundary(1,g.points().getLast(),y,g.hash()),g.logicalColumns(),g.degradation(),g.hash());
        return new SegmentGeometry(candidate.key(),candidate.points(),candidate.localSQ8(),candidate.water(),candidate.terminal(),
                candidate.start(),candidate.end(),candidate.logicalColumns(),candidate.degradation(),
                DeterministicHash.sha256(CanonicalCodec.geometry(candidate)));
    }
}
