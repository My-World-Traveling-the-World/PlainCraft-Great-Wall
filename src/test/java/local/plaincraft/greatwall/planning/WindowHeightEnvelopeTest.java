package local.plaincraft.greatwall.planning;

import java.util.*;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 高点距段缝128格，旧64格预览看不到；最终全窗仍必须抬缝且保留四格缓坡。 */
class WindowHeightEnvelopeTest {
    private static final String H = PlanningFixtures.HASH;
    @Test void irregularShortStationsCannotBorrowGradeFromTheNextFourBlockPhase() {
        var recipe = AdaptiveSiteSearchTest.recipe();
        var original = plan(recipe, 0);
        var knots = List.of(new Station(0, new Point2q(128, 128), 84, 0),
                new Station(3L * 256, new Point2q(3L * 256 + 128, 128), 84, 0),
                new Station(5L * 256, new Point2q(5L * 256 + 128, 128), 85, 0),
                new Station(8L * 256, new Point2q(8L * 256 + 128, 128), 86, 0),
                new Station(64L * 256, new Point2q(64L * 256 + 128, 128), 100, 0));
        var input = new SegmentPlan(original.geometry(), original.prefix(), new StationTable(knots, List.of(), H), List.of(), H);
        var columns = new ArrayList<WallFinePlanner.FineColumn>();
        for (int x = -8; x <= 72; x++) for (int z = -8; z <= 8; z++)
            columns.add(new WallFinePlanner.FineColumn(x, z, 0, AnalyticTerrainOracle.dry(64)));
        var solved = WindowHeightEnvelope.solve(recipe, List.of(input), FineHeightIndex.of(columns, true)).getFirst();
        var projection = new local.plaincraft.greatwall.component.ImportedRibbonProjection(solved.stations().stations(), List.of());
        // 验最终逐列投影而非仅验站点；插入楼口后的短边也必须满足四格最多一级。
        for (int x = 0; x < 60; x++) assertTrue(Math.abs(projection.contract(x + 4, 0).walkY()
                - projection.contract(x, 0).walkY()) <= 1, "四格坡度失守 x=" + x);
    }
    @Test void continuousRiverKeepsWalkwayOverWaterAndFillsFromItsBed() {
        ImportedProductionPlanTest.bootstrap();
        var recipe = AdaptiveSiteSearchTest.recipe();
        var columns = new ArrayList<WallFinePlanner.FineColumn>();
        for (int x = -8; x <= 2056; x++) for (int z = -8; z <= 8; z++)
            columns.add(new WallFinePlanner.FineColumn(x, z, 0, x >= 1008 && x <= 1040
                    ? new ColumnSample(42, OptionalInt.of(94), OptionalInt.of(42), "minecraft:river", Quality.REFERENCE, false)
                    : AnalyticTerrainOracle.dry(64)));
        var fine = FineHeightIndex.of(columns,true);
        var result = WindowHeightEnvelope.solve(recipe,List.of(plan(recipe,0)),fine).getFirst();
        var provider = new PlanningColumnProvider(fine);
        var key = new CellKey(recipe.recipeHash(),recipe.dimension(),16);
        var window = new PlanWindow(List.of(result));
        var coverage = new local.plaincraft.greatwall.generation.CoveragePlanner(3,true).prepareOwners(window);
        var input = new local.plaincraft.greatwall.terrain.RealizationPlanner.Input(key,window,Map.of(),
                provider.columnsFor(coverage.forCell(key)),List.of(),Map.of(),0,H);
        var resolved = ImportedProductionPlanTest.planner(recipe).resolve(input);
        var wall = resolved.components().stream().filter(c->c.id().kind()==SiteKind.WALL).findFirst().orElseThrow();
        assertTrue(wall.voxels().stream().anyMatch(v->v.pos().equals(new Point3i(1025,42,0)) && v.part()==Part.FOUNDATION));
        assertEquals(94, result.stations().stations().stream().mapToInt(Station::walkY).max().orElseThrow(),
                "水面只约束净空，不能额外把全段抬高一层墙");
        assertTrue(wall.voxels().stream().anyMatch(v->v.pos().x()==1025 && v.pos().z()==0
                && v.pos().y()>=93 && v.walkable()));
        assertTrue(resolved.breaks().isEmpty());
    }

    @Test void aBuildingPlatformRisesAsOneLevelInsteadOfKeepingAnOldHardHeight() {
        var recipe = AdaptiveSiteSearchTest.recipe();
        var original = plan(recipe, 0);
        var site = new SiteScheduler(recipe).at(new SiteId(recipe.wallId(), SiteKind.ENEMY_TOWER, 1),
                1024L * 256, original.geometry(), 1024L * 256);
        var plan = new SegmentPlan(original.geometry(), original.prefix(), original.stations(), List.of(site), H);
        var columns = new ArrayList<WallFinePlanner.FineColumn>();
        for (int x = -8; x <= 2056; x++) for (int z = -8; z <= 8; z++)
            columns.add(new WallFinePlanner.FineColumn(x, z, 0, AnalyticTerrainOracle.dry(x == 1040 ? 120 : 64)));
        var raised = WindowHeightEnvelope.solve(recipe, List.of(plan), FineHeightIndex.of(columns, true)).getFirst();
        var platform = raised.stations().stations().stream().filter(s ->
                local.plaincraft.greatwall.component.ImportedSiteGeometry.levelAt(site, s.globalSQ8())).toList();
        assertTrue(platform.size() > 4);
        assertEquals(1, platform.stream().map(Station::walkY).distinct().count());
        assertTrue(platform.getFirst().walkY() >= 140);
        assertEquals(platform.getFirst().walkY(), raised.sites().getFirst().frame().walkY());
        assertEquals(SiteState.PLANNED, raised.sites().getFirst().state());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void raisedOrdinaryTowerCannotPierceTheRoofLimitOrAHighStructureButItsWallRemains(boolean structure) {
        var recipe = AdaptiveSiteSearchTest.recipe(); var original = plan(recipe, 0);
        var site = new SiteScheduler(recipe).at(new SiteId(recipe.wallId(), SiteKind.ENEMY_TOWER, 1),
                1024L * 256, original.geometry(), 1024L * 256);
        var input = new SegmentPlan(original.geometry(), original.prefix(), original.stations(), List.of(site), H);
        // 结构只在抬升后与楼体相交，单靠初始平台高度将遗漏它。
        var b = site.bounds(); int x = b.minX() + 1, z = b.minZ() + 1;
        var obstacle = new local.plaincraft.greatwall.generation.CorridorStructureScan.OwnedBox("test:roof",0L,
                new net.minecraft.world.level.levelgen.structure.BoundingBox(x,150,z,x,150,z));
        var index = structure ? StructureObstacleIndex.of(List.of(obstacle), 0) : StructureObstacleIndex.empty(0);
        assertFalse(index.intersects(b, b.minY(), b.maxYExclusive()));
        int peak = structure ? 120 : recipe.bounds().maxYExclusive() - 32;
        var columns = new ArrayList<WallFinePlanner.FineColumn>();
        for (int cx = -8; cx <= 2056; cx++) for (int cz = -8; cz <= 8; cz++)
            columns.add(new WallFinePlanner.FineColumn(cx, cz, 0, AnalyticTerrainOracle.dry(cx == 1040 ? peak : 64)));
        var solved = WindowHeightEnvelope.solve(recipe, List.of(input), FineHeightIndex.of(columns,true), index).getFirst();
        var finalSite = solved.sites().getFirst();
        assertEquals(SiteState.SUPPRESSED, finalSite.state());
        assertEquals(Optional.of(structure ? BreakReason.STRUCTURE_CONFLICT : BreakReason.WORLD_LIMIT), finalSite.reason());
        assertTrue(solved.stations().breaks().isEmpty());
        var projection = new local.plaincraft.greatwall.component.ImportedRibbonProjection(solved.stations().stations(), solved.sites());
        var wall = projection.contract(1024,0);
        assertNotNull(wall, "撤楼后原楼中心应有连续的可施工墙线");
        assertTrue(wall.walkY() >= peak + 20);
    }

    @Test void mountainNearASeamRaisesBothSegmentsAndNeverLowersTheSampledTerrainTarget() {
        var recipe = AdaptiveSiteSearchTest.recipe();
        var a = plan(recipe, 0);
        var b = plan(recipe, 1);
        var columns = new ArrayList<WallFinePlanner.FineColumn>();
        for (int x = -8; x <= 4104; x++) for (int z = -8; z <= 8; z++)
            columns.add(new WallFinePlanner.FineColumn(x, z, 0, AnalyticTerrainOracle.dry(x == 2176 ? 160 : 64)));
        var result = WindowHeightEnvelope.solve(recipe, List.of(b, a), FineHeightIndex.of(columns, true));
        assertEquals(2, result.size());
        var first = result.getFirst().stations().stations();
        var second = result.getLast().stations().stations();
        assertTrue(first.getLast().walkY() >= 148, "高点必须跨128格抬高段缝");
        assertEquals(first.getLast().walkY(), second.getFirst().walkY());
        assertTrue(second.stream().filter(s -> s.globalSQ8() == 2176L * 256).findFirst().orElseThrow().walkY() >= 180);
        for (var plan : result) {
            assertTrue(plan.stations().breaks().isEmpty());
            assertEquals(DeterministicHash.sha256(CanonicalCodec.stations(plan.stations())), plan.stations().hash());
            var stations = plan.stations().stations();
            for (int i = 1; i < stations.size(); i++)
                assertTrue(Math.abs(stations.get(i).walkY() - stations.get(i - 1).walkY()) <= 1);
        }
        assertEquals(result, WindowHeightEnvelope.solve(recipe, result, FineHeightIndex.of(columns, true)), "重复求解不能越抬越高");
    }
    static SegmentPlan plan(WorldRecipe recipe, int segment) {
        var stations = new ArrayList<Station>();
        for (int x = segment * 2048; x <= (segment + 1) * 2048; x += 4)
            stations.add(new Station(x * 256L, new Point2q(x * 256L + 128, 128), 84, 0));
        var geometry = new SegmentGeometry(new SegmentKey(recipe.recipeHash(), recipe.dimension(), segment),
                List.of(stations.getFirst().point(), stations.getLast().point()), List.of(0L, 2048L * 256), List.of(),
                Optional.empty(), new Boundary(segment, stations.getFirst().point(), 84, H),
                new Boundary(segment + 1, stations.getLast().point(), 84, H), 0, 0, H);
        var prefix = segment == 0 ? PrefixSummaryIndex.origin() : PrefixSummaryIndex.reduce(PrefixSummaryIndex.origin(), plan(recipe, 0).geometry());
        return new SegmentPlan(geometry, prefix, new StationTable(stations, List.of(), H), List.of(), H);
    }
}
