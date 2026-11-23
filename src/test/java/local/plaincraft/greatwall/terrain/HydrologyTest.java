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
        return classify(segment, diagonal, slice, terrain, false);
    }

    private static List<WaterEvidence> classify(long segment, boolean diagonal, int slice,
            BiFunction<Integer, Integer, ColumnSample> terrain, boolean routeSpan) {
        var center = route(segment, diagonal);
        var neighborhood = new RouteNeighborhood(center, segment == 0 ? Optional.empty() : Optional.of(route(segment - 1, diagonal)),
                Optional.of(route(segment + 1, diagonal)));
        var classifier = new HydrologyClassifier();
        try (var oracle = new AnalyticTerrainOracle(terrain, true)) {
            var cursor = classifier.begin(neighborhood, oracle, 24000, routeSpan);
            while (classifier.advance(cursor, slice) != WorkState.DONE) { }
            assertTrue(cursor.logicalColumns() <= 24000);
            return classifier.finish(cursor);
        }
    }

    @Test void certifiedShortWaterCrossesAndLongWaterDoesNotEraseLaterEvidence() {
        var water = classify(0, false, 7, (x, z) -> {
            if (x >= 100 && x < 149 || x >= 200 && x < 250 || x >= 400 && x < 408)
                return AnalyticTerrainOracle.water(63, 58);
            return AnalyticTerrainOracle.dry(64);
        }, true);
        assertEquals(List.of(WaterClass.NARROW, WaterClass.WIDE, WaterClass.NARROW),
                water.stream().map(WaterEvidence::classification).toList());
        assertEquals(49 * 256L, water.getFirst().crossingSpanQ8());
        assertEquals(2, water.getFirst().banks().size());
        assertTrue(water.getFirst().piers().size() > 5);
        assertEquals(50 * 256L, water.get(1).crossingSpanQ8());
        assertTrue(water.get(1).piers().isEmpty());
        assertEquals(400 * 256L, water.getLast().crossing().fromQ8());
    }

    @Test void openWaterAtTheEndNeverInventsTheOppositeBank() {
        var water = classify(0, false, 64, (x, z) -> x >= 0 && x < 12
                ? AnalyticTerrainOracle.water(63, 58) : AnalyticTerrainOracle.dry(64), true);
        assertEquals(1, water.size());
        assertEquals(WaterClass.WIDE, water.getFirst().classification());
        assertEquals(12 * 256L, water.getFirst().crossingSpanQ8());
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

    @Test void inconsistentWaterLevelTerminatesAsWide() {
        // 三分法：证不出宽窄（含水位不一致的碎岛）按宽水域收头入海，不再前置抛异常。
        var water = classify(0, false, 64,
                (x, z) -> x >= 100 && x < 140
                        ? AnalyticTerrainOracle.water(x < 120 ? 63 : 66, 58)
                        : AnalyticTerrainOracle.dry(64));
        assertEquals(1, water.size());
        assertEquals(WaterClass.WIDE, water.getFirst().classification());
    }

    @Test void formerUnequalLevelWitnessTerminatesWithoutFabricatedWidth() {
        // 三分法最终规则：水位不一致（碎岛）直接按宽水域收头入海。
        // 收头的水域不测横断宽度（宽度不影响收头结果），width=0 表示"未测"，
        // 不是伪造成 40/50。piers 必须为空（不收窄河 pier）。
        var evidence = classify(0, false, 1,
                (x, z) -> x >= 100 && x < 140
                        ? AnalyticTerrainOracle.water(x < 120 ? 63 : 66, 58)
                        : AnalyticTerrainOracle.dry(64)).getFirst();
        assertEquals(0L, evidence.channelWidthQ8(), "terminal crossing leaves width unmeasured, never fabricated");
        assertEquals(3, 66 - 63, "the original waterMismatch > 1 witness remains true");
        assertEquals(WaterClass.WIDE, evidence.classification(),
                "WIDE is the authorized termination policy for inconsistent water levels");
        assertTrue(evidence.piers().isEmpty(), "a terminal crossing must never reach narrow-river pier construction");
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
