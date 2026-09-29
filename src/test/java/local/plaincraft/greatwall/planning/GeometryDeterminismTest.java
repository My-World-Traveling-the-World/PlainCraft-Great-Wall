package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import local.plaincraft.greatwall.terrain.HydrologyClassifier;
import org.junit.jupiter.api.Test;

class GeometryDeterminismTest {
    @Test void completeGeometryBlobsMatchAcrossFourSeedsAndThreeOrders() {
        for (long seed : new long[] {0, 1, 9282026, -1}) {
            var expected = new HashMap<Integer, byte[]>();
            var order = new ArrayList<Integer>();
            for (int i = 0; i < 64; i++) order.add(i);
            for (int mode = 0; mode < 3; mode++) {
                if (mode == 1) Collections.reverse(order);
                if (mode == 2) Collections.shuffle(order, new Random(9282026));
                var routes = new HashMap<Long, RouteShape>();
                for (int i : order) {
                    byte[] actual = geometry(seed, i, mode == 0 ? 1 : mode == 1 ? 64 : 256, routes, mode != 0);
                    if (mode == 0) expected.put(i, actual);
                    else assertArrayEquals(expected.get(i), actual, "seed=" + seed + " segment=" + i + " mode=" + mode);
                }
            }
        }
    }

    private static byte[] geometry(long seed, int index, int slice, Map<Long, RouteShape> routes, boolean warm) {
        var center = routes.computeIfAbsent((long) index, i -> RidgePlannerTest.route(seed, i, 64, warm));
        var previous = index == 0 ? Optional.<RouteShape>empty() : Optional.of(routes.computeIfAbsent(index - 1L, i -> RidgePlannerTest.route(seed, i, 64, warm)));
        var next = routes.computeIfAbsent(index + 1L, i -> RidgePlannerTest.route(seed, i, 64, warm));
        var classifier = new HydrologyClassifier();
        try (var oracle = AnalyticTerrainOracle.ridge(warm)) {
            var cursor = classifier.begin(new RouteNeighborhood(center, previous, Optional.of(next)), oracle, 24000);
            while (classifier.advance(cursor, slice) != WorkState.DONE) { }
            var water = classifier.finish(cursor);
            var value = new SegmentGeometry(center.key(), center.points(), center.localSQ8(), water, Optional.empty(),
                    center.start(), center.end(), center.searchColumns() + center.boundaryColumns() + cursor.logicalColumns(),
                    center.degradation(), "0".repeat(64));
            assertTrue(value.logicalColumns() + 5998 <= 150000);
            return CanonicalCodec.geometry(value);
        }
    }
}
