package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Random;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import org.junit.jupiter.api.Test;

class RidgePlannerTest {
    static RouteShape route(long seed, long segment, int slice, boolean warm) {
        var recipe = PlanningFixtures.recipe(seed);
        var planner = new RidgePlanner(recipe, new AnchorField(recipe));
        try (var oracle = AnalyticTerrainOracle.ridge(warm)) {
            var cursor = planner.begin(new SegmentKey(recipe.recipeHash(), recipe.dimension(), segment), oracle);
            while (planner.advance(cursor, slice) != WorkState.DONE) { }
            assertEquals(cursor.logicalColumns(), planner.finish(cursor).searchColumns() + 2);
            return planner.finish(cursor);
        }
    }

    @Test void forwardReverseRandomProduceSameBytes() {
        for (long seed : new long[] {0, 1, 9282026, -1}) {
            var expected = new HashMap<Integer, byte[]>();
            var indices = new ArrayList<Integer>();
            for (int i = 0; i < 64; i++) { indices.add(i); expected.put(i, CanonicalCodec.route(route(seed, i, 256, false))); }
            Collections.reverse(indices);
            for (int i : indices) assertArrayEquals(expected.get(i), CanonicalCodec.route(route(seed, i, 64, true)), "reverse " + seed + "/" + i);
            Collections.shuffle(indices, new Random(9282026));
            for (int i : indices) assertArrayEquals(expected.get(i), CanonicalCodec.route(route(seed, i, 7, false)), "random " + seed + "/" + i);
        }
    }

    @Test void sliceSizesDoNotChangePlan() {
        var baseline = route(9282026, 13, 256, false);
        for (int slice : new int[] {1, 7, 64, 256}) {
            assertArrayEquals(CanonicalCodec.route(baseline), CanonicalCodec.route(route(9282026, 13, slice, false)));
        }
    }

    @Test void cacheTemperatureDoesNotChangeBudget() {
        var cold = route(1, 0, 64, false); var warm = route(1, 0, 64, true);
        assertEquals(cold.searchColumns(), warm.searchColumns());
        assertArrayEquals(CanonicalCodec.route(cold), CanonicalCodec.route(warm));
    }

    @Test void adjacentAnchorsAndTangentsMatch() {
        var a = route(1, 0, 64, true); var b = route(1, 1, 64, true);
        assertEquals(a.end(), b.start());
        assertEquals(a.points().getLast(), b.points().getFirst());
        var anchor = new AnchorField(PlanningFixtures.recipe(1));
        assertEquals(anchor.carrier(0, 2047 * 256), a.points().get(2047));
        assertEquals(anchor.carrier(1, 256), b.points().get(1));
        for (int u = 1; u <= 16; u++) {
            assertEquals(anchor.carrier(0, (2048 - u) * 256), a.points().get(2048 - u));
            assertEquals(anchor.carrier(1, u * 256), b.points().get(u));
        }
    }

    @Test void budgetNeverExceeds150000() {
        var route = route(-1, 1000, 64, false);
        assertTrue(route.searchColumns() > 0 && route.searchColumns() <= 120000);
        assertEquals(2, route.boundaryColumns());
        assertTrue(route.searchColumns() + route.boundaryColumns() + 24000 + 5998 <= 150000);
        assertEquals(2049, route.points().size());
        assertTrue(route.localSQ8().getLast() >= 2048L * 256);
    }

    @Test void negativeProjectionDoesNotCreateSecondWall() {
        var recipe = PlanningFixtures.recipe(0);
        assertEquals(-1, Projection.segmentAt(Projection.axisUQ8(recipe.axis(), 999, 0)));
        assertThrows(IllegalArgumentException.class, () -> new SegmentKey(recipe.recipeHash(), recipe.dimension(), -1));
    }

    @Test void unfinishedAndClosedOracleFailRatherThanPublishingPartialRoute() {
        var recipe = PlanningFixtures.recipe(0); var planner = new RidgePlanner(recipe, new AnchorField(recipe));
        var oracle = AnalyticTerrainOracle.ridge(false);
        var cursor = planner.begin(new SegmentKey(recipe.recipeHash(), recipe.dimension(), 0), oracle);
        assertThrows(IllegalStateException.class, () -> planner.finish(cursor));
        assertThrows(IllegalArgumentException.class, () -> planner.advance(cursor, 0));
        oracle.close();
        assertThrows(IllegalStateException.class, () -> planner.advance(cursor, 1));
    }

    /**
     * 累计坡度权重必须真的接在评分上。0.1.0 早期这个旋钮只活在配置与摘要里（评分式里没有这一项），
     * 改它既不改变路线、却会把世界摘要换掉 ⇒ 白白让存档作废。权重一旦掉线，这条测试必然失败。
     */
    @Test void accumulatedSlopeWeightIsWiredIntoTheScore() {
        var config = local.plaincraft.greatwall.config.GreatWallConfig.get();
        int saved = config.ridgeAccumulatedSlopeWeight();
        try {
            assertEquals(-6, saved, "默认值参与摘要，改了它等于换世界");
            // 用 freezeParameters() 就地冻结内存里的值：reload() 会重读配置文件，
            // 在测试里既会撤销 setter、也会碰到玩家的真实配置。
            local.plaincraft.greatwall.config.GreatWallConfig.freezeParameters();
            var withKnob = new HashMap<Integer, java.util.List<?>>();
            for (int segment = 0; segment < 8; segment++) {
                withKnob.put(segment, route(9282026, segment, 256, true).points());
            }
            config.setRidgeAccumulatedSlopeWeight(0);
            local.plaincraft.greatwall.config.GreatWallConfig.freezeParameters();
            int changed = 0;
            for (int segment = 0; segment < 8; segment++) {
                if (!withKnob.get(segment).equals(route(9282026, segment, 256, true).points())) changed++;
            }
            assertTrue(changed > 0, "把累计坡度权重清零后路线逐点相同——这个权重没有接进评分");
        } finally {
            config.setRidgeAccumulatedSlopeWeight(saved);
            local.plaincraft.greatwall.config.GreatWallConfig.reload();
        }
    }
}
