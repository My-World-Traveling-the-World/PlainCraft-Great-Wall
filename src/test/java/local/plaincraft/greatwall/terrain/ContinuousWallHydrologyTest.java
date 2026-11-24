package local.plaincraft.greatwall.terrain;

import java.util.*;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 相同水宽分别用河流和原始海洋群系验证，不能只把旧阈值整体放大。 */
class ContinuousWallHydrologyTest {
    @Test void riverConnectedToAnOceanKeepsItsOwnWallAndBudgetCannotPretendToBeAnOcean() {
        var routes = new RouteNeighborhood(route(0), Optional.empty(), Optional.of(route(1)));
        try (var oracle = new AnalyticTerrainOracle((x,z) -> x >= 100 && x < 1200
                ? new ColumnSample(30, OptionalInt.of(63), OptionalInt.of(30),
                        x < 600 ? "minecraft:river" : "minecraft:ocean", Quality.REFERENCE, false)
                : AnalyticTerrainOracle.dry(64), true)) {
            var classifier = new HydrologyClassifier(512);
            var cursor = classifier.begin(routes, oracle, 24000, true);
            while (!cursor.done()) classifier.advance(cursor, 256);
            var water = classifier.finish(cursor);
            assertEquals(2, water.size());
            assertEquals(WaterClass.NARROW, water.getFirst().classification());
            assertEquals(600L * 256, water.getFirst().crossing().toQ8());
            assertEquals(WaterClass.WIDE, water.getLast().classification());
            var unfinished = classifier.begin(routes, oracle, 24000, true);
            assertThrows(local.plaincraft.greatwall.model.WallFailure.class, () -> classifier.forceComplete(unfinished));
        }
    }
    @Test void riversAndLakesRemainContinuousBeyondTheOldFiftyBlockLimit() {
        assertEquals(WaterClass.NARROW, classify(0, 600, "minecraft:river", 512).classification());
        assertEquals(WaterClass.NARROW, classify(0, 600, "example:lake", 512).classification());
    }
    @Test void oceanThresholdIncludesEqualityAndReadsTheConfiguredValue() {
        assertEquals(WaterClass.NARROW, classify(0, 511, "minecraft:ocean", 512).classification());
        assertEquals(WaterClass.WIDE, classify(0, 512, "minecraft:ocean", 512).classification());
        assertEquals(WaterClass.NARROW, classify(0, 512, "minecraft:ocean", 1024).classification());
    }
    @Test void aSeaAcrossTheSegmentSeamHasTheSameDecisionOnBothSides() {
        var a = classify(0, 600, "minecraft:ocean", 512);
        var b = classify(1, 600, "minecraft:ocean", 512);
        assertEquals(WaterClass.WIDE, a.classification());
        assertEquals(a.classification(), b.classification());
        assertEquals(a.crossing().fromQ8(), b.crossing().fromQ8() + 2048L * 256);
        assertEquals(a.crossing().toQ8(), b.crossing().toQ8() + 2048L * 256);
    }
    private static WaterEvidence classify(int segment, int width, String biome, int threshold) {
        var routes = new RouteNeighborhood(route(segment), segment == 0 ? Optional.empty() : Optional.of(route(segment - 1)),
                Optional.of(route(segment + 1)));
        int begin = width == 600 ? 1800 : 100;
        try (var oracle = new AnalyticTerrainOracle((x,z) -> x >= begin && x < begin + width
                ? new ColumnSample(30, OptionalInt.of(63), OptionalInt.of(30), biome, Quality.REFERENCE, false)
                : AnalyticTerrainOracle.dry(64), true)) {
            var classifier = new HydrologyClassifier(threshold);
            var cursor = classifier.begin(routes, oracle, 24000, true);
            while (!cursor.done()) classifier.advance(cursor, 256);
            var waters = classifier.finish(cursor);
            assertEquals(1, waters.size());
            return waters.getFirst();
        }
    }
    private static RouteShape route(int segment) {
        var points = new ArrayList<Point2q>();
        for (int i = 0; i <= 2048; i++) points.add(new Point2q((segment * 2048L + i) * 256 + 128, 128));
        String hash = "0".repeat(64);
        return new RouteShape(new SegmentKey(hash, "minecraft:overworld", segment), points, ArcLength.accumulate(points),
                new Boundary(segment, points.getFirst(), 84, hash), new Boundary(segment + 1, points.getLast(), 84, hash), 0, 0, 0, hash);
    }
}
