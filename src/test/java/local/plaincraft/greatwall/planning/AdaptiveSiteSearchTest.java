package local.plaincraft.greatwall.planning;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import local.plaincraft.greatwall.component.ImportedSiteGeometry;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveSiteSearchTest {
    private static final String H = PlanningFixtures.HASH;

    static WorldRecipe recipe() {
        var r = ImportedSiteScheduleTest.recipe(3);
        var c = r.config();
        return new WorldRecipe(r.instance(), r.wallId(), r.seed(), r.dimension(), r.frozenSpawn(), r.axis(), r.bounds(),
                new GenerationConfig(2, c.templateVersion(), c.segmentBlocks(), c.stationBlocks(), c.columnBudget(),
                        c.carrierAmplitude(), c.corridorHalfWidth(), c.maxGeometryInflight(), c.segmentBytes(),
                        c.terrainBytes(), c.compiledBytes(), c.parametersHash()), r.terrainHash(), r.paletteHash(),
                r.templatesHash(), r.recipeHash());
    }

    @ParameterizedTest @ValueSource(ints = {-1, 1})
    void movesAnEnemyTowerToTheDrySide(int direction) {
        var recipe = recipe();
        var scheduler = new SiteScheduler(recipe);
        var segment = straight(recipe);
        var prefix = PrefixSummaryIndex.origin();
        var search = new AdaptiveSiteSearch(recipe, scheduler, StructureObstacleIndex.empty(0),
                new GeometryWindow(List.of(segment), List.of(prefix)), 0, 1000L * 256);
        // 不是给名义点周围补一点假水：整座敌楼必须退出湿地，墙线两侧接引也得落在干地。
        try (var oracle = new AnalyticTerrainOracle((x, z) -> direction * (x - 300) < -10 && x >= 100 && x <= 500
                ? AnalyticTerrainOracle.water(63, 58) : AnalyticTerrainOracle.dry(64), true)) {
            for (int i = 0; i < 100000 && !search.done(); i++) search.advance(oracle);
        }
        assertTrue(search.done());
        var tower = search.result().stream().filter(s -> s.id().kind() == SiteKind.ENEMY_TOWER
                && s.id().ordinal() == 1).findFirst().orElseThrow();
        assertEquals(SiteState.PLANNED, tower.state());
        assertTrue(direction * (tower.globalSQ8() - 300L * 256) >= 32L * 256, tower.toString());
        var b = ImportedSiteGeometry.bounds(tower);
        assertTrue(direction > 0 ? b.minX() >= 290 : b.maxXExclusive() <= 311, b.toString());
    }

    @Test void submergedInteriorColumnRejectsThePlatformWithoutCuttingTheWall() {
        var recipe = recipe();
        var segment = straight(recipe);
        var prefix = PrefixSummaryIndex.origin();
        var site = new SiteScheduler(recipe).at(new SiteId(recipe.wallId(), SiteKind.ENEMY_TOWER, 1),
                300L * 256, segment, 300L * 256);
        var height = new HeightContractPlanner(recipe);
        var columns = new HashMap<Point2q, ColumnSample>();
        for (var p : height.samplePoints(segment, prefix, List.of(site))) columns.put(p, AnalyticTerrainOracle.dry(64));
        var b = site.bounds();
        var interior = new Point2q((b.minX() + 17L) * 256 + 128, (b.minZ() + 5L) * 256 + 128);
        assertNotNull(columns.get(interior));
        var flat = height.solveWithSites(segment, prefix, List.of(site), columns);
        assertEquals(SiteState.PLANNED, flat.sites().getFirst().state());
        columns.put(interior, AnalyticTerrainOracle.water(63, 58));
        var wet = height.solveWithSites(segment, prefix, List.of(site), columns);
        assertEquals(SiteState.SUPPRESSED, wet.sites().getFirst().state());
        assertEquals(Optional.of(BreakReason.NO_SUPPORT), wet.sites().getFirst().reason());
        assertTrue(wet.stations().breaks().isEmpty());
        assertTrue(wet.stations().stations().size() > 200);
    }

    private static SegmentGeometry straight(WorldRecipe recipe) {
        var a = new Point2q(128, -50L * 256 + 128);
        var b = new Point2q(1000L * 256 + 128, a.zQ8());
        return new SegmentGeometry(new SegmentKey(recipe.recipeHash(), recipe.dimension(), 0), List.of(a, b),
                List.of(0L, 1000L * 256), List.of(), Optional.empty(), new Boundary(0, a, 84, H),
                new Boundary(1, b, 84, H), 0, 0, H);
    }
}
