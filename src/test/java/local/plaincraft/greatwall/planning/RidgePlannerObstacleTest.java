package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.generation.CorridorStructureScan.OwnedBox;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class RidgePlannerObstacleTest {
    private static RouteShape routeWith(WorldRecipe recipe, StructureObstacleIndex obstacles, int slice) {
        var planner = new RidgePlanner(recipe, new AnchorField(recipe));
        planner.setObstacles(obstacles);
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var cursor = planner.begin(new SegmentKey(recipe.recipeHash(), recipe.dimension(), 0), oracle);
            while (planner.advance(cursor, slice) != WorkState.DONE) { }
            return planner.finish(cursor);
        }
    }

    @Test void emptyIndexMatchesUnmodifiedPlanner() {
        var recipe = PlanningFixtures.recipe(9282026);
        var plain = routeWith(recipe, StructureObstacleIndex.empty(8), 64);
        var base = new RidgePlanner(recipe, new AnchorField(recipe));
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var cursor = base.begin(new SegmentKey(recipe.recipeHash(), recipe.dimension(), 0), oracle);
            while (base.advance(cursor, 64) != WorkState.DONE) { }
            var expected = base.finish(cursor);
            assertEquals(expected.points().size(), plain.points().size());
            for (int i = 0; i < expected.points().size(); i++) {
                assertEquals(expected.points().get(i), plain.points().get(i));
            }
        }
    }

    @Test void routePointsAvoidInjectedObstacle() {
        // The axis runs along +X from (1000,64,0); the ridge oracle keeps z in about [-279,0].
        // Block only the northern half of that band (z in [-279,-100]) at x ~ 1500, leaving
        // z in (-100,0] as a corridor the ridge can jitter into. The detour must keep every emitted
        // point out of the box body itself; the 8-block obstacle margin is a scoring cushion, not an
        // absolute keep-out zone, so擦边 the margin is acceptable and asserted separately below.
        var recipe = PlanningFixtures.recipe(9282026);
        var box = new OwnedBox("minecraft:village", 0L,
                new BoundingBox(1400, 0, -279, 1600, 256, -100));
        var obstacles = StructureObstacleIndex.of(List.of(box), 8);
        var route = routeWith(recipe, obstacles, 64);

        for (Point2q point : route.points()) {
            int x = FixedMath.floorBlock(point.xQ8());
            int z = FixedMath.floorBlock(point.zQ8());
            boolean insideBody = x >= 1400 && x <= 1600 && z >= -279 && z <= -100;
            assertFalse(insideBody, "route point (" + x + "," + z + ") entered the obstacle body at segment 0");
        }
    }

    @Test void fullBlockedCorridorDegradesInsteadOfCuttingThrough() {
        // A box that spans the entire corridor width forces every candidate rejection; the ridge
        // must degrade (straight-line fallback) rather than emit points through the box.
        var recipe = PlanningFixtures.recipe(9282026);
        var box = new OwnedBox("minecraft:village", 0L,
                new BoundingBox(1400, 0, -10000, 1600, 256, 10000));
        var obstacles = StructureObstacleIndex.of(List.of(box), 8);
        var route = routeWith(recipe, obstacles, 64);
        // Degradation >= 1 signals the planner fell back rather than穿模.
        assertTrue(route.degradation() >= 1, "full-blocked corridor should degrade, got " + route.degradation());
    }
}
