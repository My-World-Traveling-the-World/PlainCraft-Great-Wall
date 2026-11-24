package local.plaincraft.greatwall.planning;

import java.util.*;
import local.plaincraft.greatwall.generation.CoveragePlanner;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 深河的每列地基都要来自真实精采，不能用中心桥墩或附近仍有墙冒充连续跨水。 */
class ContinuousRiverWallTest {
    @BeforeAll static void bootstrap() { ImportedProductionPlanTest.bootstrap(); }
    @Test void aDeepHundredBlockRiverHasAContinuousWalkwayAndSolidWallDownToItsBed() {
        var recipe = AdaptiveSiteSearchTest.recipe();
        String hash = PlanningFixtures.HASH;
        var stations = new ArrayList<Station>();
        for (int x = 0; x <= 256; x += 4)
            stations.add(new Station(x * 256L, new Point2q(x * 256L + 128, 128), 84, 0));
        var water = new WaterEvidence(new ArcInterval(80L * 256, 180L * 256), WaterClass.NARROW,
                100L * 256, 100L * 256, 63, List.of(), List.of(), hash);
        var geometry = new SegmentGeometry(new SegmentKey(recipe.recipeHash(), recipe.dimension(), 0),
                List.of(stations.getFirst().point(), stations.getLast().point()), List.of(0L, 256L * 256),
                List.of(water), Optional.empty(), new Boundary(0, stations.getFirst().point(), 84, hash),
                new Boundary(1, stations.getLast().point(), 84, hash), 0, 0, hash);
        var plan = new SegmentPlan(geometry, PrefixSummaryIndex.origin(),
                new StationTable(stations, List.of(), hash), List.of(), hash);
        var index = ImportedProductionBoundaryTest.exactIndex(plan, (x,z) -> x >= 80 && x < 180
                ? AnalyticTerrainOracle.water(63, 20) : AnalyticTerrainOracle.dry(64));
        var coverage = new CoveragePlanner(3, true);
        var blocks = new HashMap<Point3i, VoxelIntent>();
        for (int arc = 1; arc <= 3; arc++) {
            var cell = new CellKey(recipe.recipeHash(), recipe.dimension(), arc);
            var columns = new PlanningColumnProvider(index).columnsFor(coverage.realizationOwners(cell, new PlanWindow(List.of(plan))));
            var fixture = new ImportedProductionPlanTest.Fixture(recipe, plan, null, columns);
            var result = ImportedProductionPlanTest.planner(recipe).resolve(ImportedProductionPlanTest.input(fixture, arc, List.of()));
            assertTrue(result.breaks().isEmpty(), result.breaks().toString());
            assertFalse(result.components().isEmpty());
            for (var component : result.components()) {
                assertEquals(SiteKind.WALL, component.id().kind());
                component.voxels().forEach(v -> assertNull(blocks.put(v.pos(), v)));
            }
        }
        for (int x = 64; x < 256; x++) assertEquals(Part.FLOOR, blocks.get(new Point3i(x, 83, 0)).part());
        for (int x = 80; x < 180; x++) for (int z = -4; z <= 4; z++) for (int y = 20; y < 63; y++) {
            var voxel = blocks.get(new Point3i(x, y, z));
            assertNotNull(voxel, "水底到墙体不能留空: " + x + "," + y + "," + z);
            assertEquals(IntentKind.SOLID, voxel.kind());
        }
    }
}
