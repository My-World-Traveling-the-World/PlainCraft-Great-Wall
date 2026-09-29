package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import org.junit.jupiter.api.Test;

class HydrologyTest {
    private static RouteShape route(long segment, boolean diagonal) {
        var points = new ArrayList<Point2q>();
        for (int i = 0; i <= 2048; i++) {
            long x = segment * 2048 + i;
            points.add(new Point2q(x * 256 + 128, diagonal ? x * 256 + 128 : 128));
        }
        return new RouteShape(new SegmentKey(PlanningFixtures.HASH, "minecraft:overworld", segment), points,
                ArcLength.accumulate(points), new Boundary(segment, points.getFirst(), 84, "b" + segment),
                new Boundary(segment + 1, points.getLast(), 84, "b" + (segment + 1)), 0, 2, 0, PlanningFixtures.HASH);
    }

    private static List<WaterEvidence> classify(long segment, boolean diagonal, int slice,
            BiFunction<Integer, Integer, ColumnSample> terrain) {
        var center = route(segment, diagonal);
        var neighborhood = new RouteNeighborhood(center, segment == 0 ? Optional.empty() : Optional.of(route(segment - 1, diagonal)),
                Optional.of(route(segment + 1, diagonal)));
        var classifier = new HydrologyClassifier();
        try (var oracle = new AnalyticTerrainOracle(terrain, true)) {
            var cursor = classifier.begin(neighborhood, oracle, 24000);
            while (classifier.advance(cursor, slice) != WorkState.DONE) { }
            assertTrue(cursor.logicalColumns() <= 24000);
            return classifier.finish(cursor);
        }
    }

    private static BiFunction<Integer, Integer, ColumnSample> river(int start, int width, int bed) {
        return (x, z) -> x >= start && x < start + width ? AnalyticTerrainOracle.water(63, bed) : AnalyticTerrainOracle.dry(64);
    }

    @Test void width49IsNarrow50And51Terminate() {
        for (int width : new int[] {49, 50, 51}) {
            var water = classify(0, false, 64, river(100, width, 58));
            assertEquals(1, water.size());
            assertEquals(width * 256L, water.getFirst().channelWidthQ8());
            assertEquals(width == 49 ? WaterClass.NARROW : WaterClass.WIDE, water.getFirst().classification());
        }
    }

    @Test void diagonalSpanIsNotChannelWidth() {
        var evidence = classify(0, true, 64, river(100, 40, 58)).getFirst();
        assertEquals(WaterClass.NARROW, evidence.classification());
        assertEquals(40 * 256L, evidence.channelWidthQ8());
        assertTrue(evidence.crossingSpanQ8() > 50 * 256L);
    }

    @Test void roofedAquiferIsNotRiver() {
        assertTrue(classify(0, false, 64, (x, z) -> AnalyticTerrainOracle.dry(90)).isEmpty());
    }

    @Test void deepNarrowRiverMakesSupportGap() {
        var classifier = new HydrologyClassifier();
        var evidence = classify(0, false, 64, river(100, 49, 51)).getFirst();
        assertEquals(WaterClass.NARROW, evidence.classification());
        assertFalse(evidence.piers().isEmpty());
        assertEquals(WaterClass.SUPPORT_GAP, classifier.resolveSupport(evidence, p -> 84).classification());
        assertEquals(WaterClass.NARROW, classifier.resolveSupport(evidence, p -> 83).classification());
        assertEquals(evidence.crossing(), classifier.resolveSupport(evidence, p -> 84).crossing());
    }

    @Test void budgetExhaustionIsNotOcean() {
        var classifier = new HydrologyClassifier();
        try (var oracle = new AnalyticTerrainOracle(river(100, 51, 58), true)) {
            var cursor = classifier.begin(new RouteNeighborhood(route(0, false), Optional.empty(), Optional.empty()), oracle, 32);
            var failure = assertThrows(WallFailure.class, () -> { while (!cursor.done()) classifier.advance(cursor, 64); });
            assertEquals(FailureCode.HYDROLOGY_UNRESOLVED, failure.code());
            assertThrows(IllegalStateException.class, () -> classifier.finish(cursor));
            assertEquals(32, cursor.logicalColumns());
        }
    }

    @Test void oneBlockRiverBetweenCoarseSamplesIsFound() {
        var water = classify(0, false, 7, river(102, 1, 58));
        assertEquals(1, water.size());
        assertEquals(WaterClass.NARROW, water.getFirst().classification());
        assertEquals(256, water.getFirst().channelWidthQ8());
        assertEquals(256, water.getFirst().crossingSpanQ8());
    }

    @Test void seamRiverHasSameBanksAndOneGlobalCrossing() {
        var a = classify(0, false, 1, river(2030, 49, 58)).getFirst();
        var b = classify(1, false, 256, river(2030, 49, 58)).getFirst();
        assertEquals(a.banks(), b.banks());
        assertEquals(a.piers(), b.piers());
        assertEquals(a.inputHash(), b.inputHash());
        assertEquals(a.crossingSpanQ8(), b.crossingSpanQ8());
        assertEquals(a.crossing().fromQ8(), b.crossing().fromQ8() + 2048L * 256);
        assertEquals(a.crossing().toQ8(), b.crossing().toQ8() + 2048L * 256);
        assertTrue(a.crossing().fromQ8() >= 0 && b.crossing().fromQ8() < 0);
    }

    @Test void wetRootFailsExplicitly() {
        var failure = assertThrows(WallFailure.class, () -> classify(0, false, 64, river(-100, 200, 58)));
        assertEquals(FailureCode.ROOT_NOT_FOUND, failure.code());
    }

    @Test void missingBedOrCensoredReferenceIsNotCertifiedDry() {
        var failure = assertThrows(WallFailure.class, () -> classify(0, false, 64, (x, z) -> new ColumnSample(
                64, java.util.OptionalInt.of(70), java.util.OptionalInt.empty(), "minecraft:river", Quality.REFERENCE, false)));
        assertEquals(FailureCode.HYDROLOGY_UNRESOLVED, failure.code());
        assertThrows(WallFailure.class, () -> classify(0, false, 64, (x, z) -> new ColumnSample(
                64, java.util.OptionalInt.empty(), java.util.OptionalInt.empty(), "minecraft:plains", Quality.CENSORED, true)));
    }

    @Test void sliceAndCacheTemperatureDoNotAffectWaterEvidence() {
        var expected = classify(0, false, 1, river(100, 49, 58));
        for (int slice : new int[] {7, 64, 256}) assertEquals(expected, classify(0, false, slice, river(100, 49, 58)));
    }
}
