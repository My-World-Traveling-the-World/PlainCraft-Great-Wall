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
