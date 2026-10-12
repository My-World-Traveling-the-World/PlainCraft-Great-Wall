package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
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

    @Test void samplesEveryRoutePointOnce() {
        var recipe = PlanningFixtures.recipe(9282026);
        var route = route(recipe);
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var cursor = WallFinePlanner.begin(route, oracle);
            while (WallFinePlanner.advance(cursor, 1) != WorkState.DONE) { }
            var columns = WallFinePlanner.finish(cursor);
            assertEquals(route.points().size(), columns.size(), "one fine column per route point");
        }
    }

    @Test void sampledCoordinatesMatchRouteFloor() {
        var recipe = PlanningFixtures.recipe(9282026);
        var route = route(recipe);
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var cursor = WallFinePlanner.begin(route, oracle);
            while (WallFinePlanner.advance(cursor, 64) != WorkState.DONE) { }
            var columns = WallFinePlanner.finish(cursor);
            for (int i = 0; i < columns.size(); i++) {
                var point = route.points().get(i);
                var column = columns.get(i);
                assertEquals(local.plaincraft.greatwall.geometry.FixedMath.floorBlock(point.xQ8()), column.x(),
                        "column x must match route point " + i);
                assertEquals(local.plaincraft.greatwall.geometry.FixedMath.floorBlock(point.zQ8()), column.z(),
                        "column z must match route point " + i);
            }
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
