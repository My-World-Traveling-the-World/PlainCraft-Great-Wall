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

    @Test void samplesFullWidthPerRoutePoint() {
        var recipe = PlanningFixtures.recipe(9282026);
        var route = route(recipe);
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var cursor = WallFinePlanner.begin(route, oracle);
            while (WallFinePlanner.advance(cursor, 1) != WorkState.DONE) { }
            var columns = WallFinePlanner.finish(cursor);
            // Each centre point yields up to 2*LATERAL+1 = 11 columns; straight runs deduplicate
            // overlapping neighbours, so the count is bounded by points * 11 and at least points.
            assertTrue(columns.size() >= route.points().size(),
                    "at least one column per centre point, got " + columns.size());
            assertTrue(columns.size() <= route.points().size() * (2 * WallFinePlanner.LATERAL + 1),
                    "at most full width per centre point");
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
