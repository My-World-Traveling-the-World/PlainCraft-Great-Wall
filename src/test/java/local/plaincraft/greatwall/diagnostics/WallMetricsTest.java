package local.plaincraft.greatwall.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class WallMetricsTest {
    @Test
    void unobservedMetricsAreNotSyntheticZeroMeasurements() {
        var metrics = new WallMetrics();
        var snapshot = metrics.snapshot();
        assertTrue(snapshot.counters().isEmpty());
        assertTrue(snapshot.timings().isEmpty());
        assertTrue(snapshot.gauges().values().stream().allMatch("N/A"::equals));
        for (String name : List.of("planner.logicalSamples", "prefix.certifiedThrough",
                "cache.segmentBytes", "worldgen.applyMs", "geometry.seamMismatch",
                "hydrology.unresolved", "roads.protectedGaps", "beacons.committedUnique",
                "garrison.retiredOverflow", "history.pendingIo", "history.health",
                "history.sequence", "oracle.primitiveMaxMs")) {
            assertEquals("N/A", snapshot.gauges().get(name), name);
        }
        metrics.increment("worldgen.crossOwnerWrite", 0);
        metrics.time("worldgen.applyMs", 0);
        var measured = metrics.snapshot();
        assertEquals(0L, measured.counters().get("worldgen.crossOwnerWrite").longValue());
        assertEquals(1, measured.timings().get("worldgen.applyMs").count());
        assertFalse(measured.gauges().containsKey("worldgen.crossOwnerWrite"));
        assertFalse(measured.gauges().containsKey("worldgen.applyMs"));
    }

    @Test
    void whitelistCoversEveryFrozenNameAndRejectsDynamicKeys() {
        var names = List.of(
                "planner.logicalSamples", "planner.actualDensityEvals", "planner.cacheHitRate",
                "planner.sliceMs", "planner.queueWait", "planner.active", "planner.pending",
                "planner.syncComputeCount", "planner.syncComputeMaxMs", "planner.cancellationCount",
                "prefix.certifiedThrough", "prefix.sparseIntervals", "prefix.pendingGapRange",
                "prefix.terminal", "cache.segmentBytes", "cache.snapshotBytes", "cache.pinnedBytes",
                "cache.eviction", "cache.corruptBlobCount", "worldgen.waitPlanMs",
                "worldgen.captureMs", "worldgen.compileMs", "worldgen.applyMs", "worldgen.writeBlocks",
                "worldgen.forbiddenGetChunk", "worldgen.waitWhileLocked", "worldgen.crossOwnerWrite",
                "worldgen.staleSessionWrites", "geometry.seamMismatch", "geometry.unsupportedVoxel",
                "geometry.stairConnectivityFailures", "hydrology.knownNarrow", "hydrology.knownWide",
                "hydrology.unresolved", "hydrology.terrainMismatch", "roads.gatesByState",
                "roads.protectedGaps", "roads.apertureViolations", "roads.retryCount",
                "beacons.committedUnique", "beacons.pending", "beacons.duplicateRejected",
                "beacons.ignoredSource", "beacons.ioErrors", "garrison.loadedAlive",
                "garrison.reserved", "garrison.capRejections", "garrison.protectedSpawnRejections",
                "garrison.retiredOverflow", "history.pendingIo", "history.health",
                "history.sequence", "oracle.primitiveMaxMs");
        var metrics = new WallMetrics();
        for (String name : names) metrics.gauge(name, "observed");
        assertEquals(names.size(), metrics.snapshot().gauges().size());
        for (String unknown : List.of("", "planner", "worldgen.applyMs.player-name",
                "oracle.applyMs", "not.registered")) {
            assertThrows(IllegalArgumentException.class, () -> metrics.increment(unknown, 1));
            assertThrows(IllegalArgumentException.class, () -> metrics.time(unknown, 1));
            assertThrows(IllegalArgumentException.class, () -> metrics.gauge(unknown, "value"));
        }
        assertTrue(metrics.snapshot().counters().isEmpty());
        assertTrue(metrics.snapshot().timings().isEmpty());
    }

    @Test
    void counterOverflowAndUnderflowPreserveThePreviousValue() {
        var metrics = new WallMetrics();
        metrics.increment("worldgen.writeBlocks", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> metrics.increment("worldgen.writeBlocks", 1));
        assertEquals(Long.MAX_VALUE, metrics.snapshot().counters().get("worldgen.writeBlocks").longValue());
        metrics.increment("planner.active", Long.MIN_VALUE);
        assertThrows(ArithmeticException.class, () -> metrics.increment("planner.active", -1));
        assertEquals(Long.MIN_VALUE, metrics.snapshot().counters().get("planner.active").longValue());
    }

    @Test
    void timingsUseSixtyFourLog2BinsAndAnExactMaximum() {
        var metrics = new WallMetrics();
        for (long elapsed : new long[] {0, 1, 2, 3, 4, 7, 8, 15, 16}) {
            metrics.time("worldgen.applyMs", elapsed);
        }
        var histogram = metrics.snapshot().timings().get("worldgen.applyMs");
        assertEquals(9, histogram.count());
        assertEquals(56, histogram.totalNanos());
        assertEquals(16, histogram.maxNanos());
        assertEquals(64, histogram.bins().size());
        assertEquals(List.of(2L, 2L, 2L, 2L, 1L), histogram.bins().subList(0, 5));
        assertTrue(histogram.bins().subList(5, 64).stream().allMatch(bin -> bin == 0));
    }

    @Test
    void everyPositivePowerOfTwoHasItsOwnBin() {
        var metrics = new WallMetrics();
        for (int exponent = 0; exponent < 63; exponent++) {
            metrics.time("planner.queueWait", 1L << exponent);
        }
        var histogram = metrics.snapshot().timings().get("planner.queueWait");
        assertEquals(63, histogram.count());
        assertEquals(Long.MAX_VALUE, histogram.totalNanos());
        assertEquals(1L << 62, histogram.maxNanos());
        assertTrue(histogram.bins().subList(0, 63).stream().allMatch(bin -> bin == 1));
        assertEquals(0L, histogram.bins().get(63).longValue());
    }

    @Test
    void timingOverflowAndNegativeInputCannotPartiallyUpdateHistogram() {
        var metrics = new WallMetrics();
        metrics.time("oracle.primitiveMaxMs", Long.MAX_VALUE);
        var previous = metrics.snapshot().timings().get("oracle.primitiveMaxMs");
        assertThrows(ArithmeticException.class, () -> metrics.time("oracle.primitiveMaxMs", 1));
        assertThrows(IllegalArgumentException.class, () -> metrics.time("oracle.primitiveMaxMs", -1));
        assertEquals(previous, metrics.snapshot().timings().get("oracle.primitiveMaxMs"));
        assertEquals(1L, previous.bins().get(62).longValue());
        assertEquals(Long.MAX_VALUE, previous.maxNanos());
    }

    @Test
    void applyAndCpuOracleRemainSeparateObservations() {
        var metrics = new WallMetrics();
        metrics.time("worldgen.applyMs", 57_519_100L);
        metrics.time("oracle.primitiveMaxMs", 12_000_000L);
        var snapshot = metrics.snapshot();
        assertEquals(57_519_100L, snapshot.timings().get("worldgen.applyMs").maxNanos());
        assertEquals(12_000_000L, snapshot.timings().get("oracle.primitiveMaxMs").maxNanos());
        assertEquals(2, snapshot.timings().size());
        assertTrue(snapshot.gauges().containsKey("worldgen.applyMs.over40ms"));
        assertFalse(snapshot.gauges().containsKey("oracle.primitiveMaxMs.over40ms"));
        assertEquals("N/A", snapshot.gauges().get("planner.sliceMs"));
    }

    @Test
    void overFortyMillisecondsKeepsBoundedClassPrimitiveAndStackWithoutReplacingGauge() {
        var metrics = new WallMetrics();
        metrics.gauge("planner.sliceMs", "observed separately");
        metrics.time("planner.sliceMs", 40_000_000L);
        assertFalse(metrics.snapshot().gauges().containsKey("planner.sliceMs.over40ms"));
        metrics.time("planner.sliceMs", 40_000_001L);
        String sample = metrics.snapshot().gauges().get("planner.sliceMs.over40ms");
        assertNotNull(sample);
        assertTrue(sample.contains("metric=planner.sliceMs"));
        assertTrue(sample.contains("primitive=overFortyMillisecondsKeepsBoundedClassPrimitiveAndStackWithoutReplacingGauge"));
        assertTrue(sample.contains("elapsedNanos=40000001"));
        assertTrue(sample.contains("class=" + WallMetricsTest.class.getName()));
        assertTrue(sample.contains("stack="));
        assertTrue(sample.length() <= 1024);
        assertEquals("observed separately", metrics.snapshot().gauges().get("planner.sliceMs"));
        metrics.time("worldgen.waitPlanMs", 400_000_000L);
        assertFalse(metrics.snapshot().gauges().containsKey("worldgen.waitPlanMs.over40ms"));
    }

    @Test
    void snapshotsAreSortedDetachedAndDeeplyImmutable() {
        var metrics = new WallMetrics();
        metrics.increment("worldgen.writeBlocks", 3);
        metrics.increment("beacons.committedUnique", 2);
        metrics.time("worldgen.applyMs", 7);
        metrics.gauge("history.health", "READY");
        var previous = metrics.snapshot();
        metrics.increment("worldgen.writeBlocks", 1);
        metrics.time("worldgen.applyMs", 9);
        metrics.gauge("history.health", "PENDING_IO");
        assertEquals(3L, previous.counters().get("worldgen.writeBlocks").longValue());
        assertEquals(7, previous.timings().get("worldgen.applyMs").maxNanos());
        assertEquals("READY", previous.gauges().get("history.health"));
        assertSorted(previous.counters());
        assertSorted(previous.timings());
        assertSorted(previous.gauges());
        assertThrows(UnsupportedOperationException.class, () -> previous.counters().clear());
        assertThrows(UnsupportedOperationException.class, () -> previous.timings().clear());
        assertThrows(UnsupportedOperationException.class, () -> previous.gauges().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> previous.timings().get("worldgen.applyMs").bins().set(0, 100L));
    }

    @Test
    void publicRecordsCopyInputsAndValidateHistogramConsistency() {
        var bins = new ArrayList<>(Collections.nCopies(64, 0L));
        bins.set(2, 1L);
        var histogram = new WallMetrics.Histogram(1, 4, 4, bins);
        bins.set(2, 99L);
        assertEquals(1L, histogram.bins().get(2).longValue());
        var counters = new HashMap<>(Map.of("worldgen.writeBlocks", 1L));
        var timings = new HashMap<>(Map.of("worldgen.applyMs", histogram));
        var gauges = new HashMap<>(Map.of("history.health", "READY"));
        var snapshot = new WallMetrics.Snapshot(counters, timings, gauges, 123L);
        counters.clear();
        timings.clear();
        gauges.clear();
        assertEquals(1, snapshot.counters().size());
        assertEquals(1, snapshot.timings().size());
        assertEquals(1, snapshot.gauges().size());
        assertEquals(123, snapshot.observationNanos());
        assertThrows(IllegalArgumentException.class,
                () -> new WallMetrics.Histogram(0, 0, 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new WallMetrics.Histogram(2, 4, 4, histogram.bins()));
        assertThrows(IllegalArgumentException.class,
                () -> new WallMetrics.Histogram(1, 3, 4, histogram.bins()));
        assertThrows(IllegalArgumentException.class,
                () -> new WallMetrics.Histogram(0, 1, 1, Collections.nCopies(64, 0L)));
        assertThrows(IllegalArgumentException.class,
                () -> new WallMetrics.Histogram(-1, 0, 0, Collections.nCopies(64, 0L)));
        var invalid = new ArrayList<>(Collections.nCopies(64, 0L));
        invalid.set(0, -1L);
        assertThrows(IllegalArgumentException.class,
                () -> new WallMetrics.Histogram(0, 0, 0, invalid));
        invalid.set(0, Long.MAX_VALUE);
        invalid.set(1, 1L);
        assertThrows(ArithmeticException.class,
                () -> new WallMetrics.Histogram(Long.MAX_VALUE, Long.MAX_VALUE, 1, invalid));
    }

    @Test
    void gaugeLimitAndNullsDoNotErasePreviousObservations() {
        var metrics = new WallMetrics();
        metrics.gauge("history.health", "x".repeat(1024));
        assertThrows(IllegalArgumentException.class,
                () -> metrics.gauge("history.health", "x".repeat(1025)));
        assertThrows(NullPointerException.class, () -> metrics.gauge("history.health", null));
        assertThrows(NullPointerException.class, () -> metrics.gauge(null, "READY"));
        assertThrows(NullPointerException.class, () -> metrics.increment(null, 1));
        assertThrows(NullPointerException.class, () -> metrics.time(null, 1));
        assertEquals(1024, metrics.snapshot().gauges().get("history.health").length());
    }

    @Test
    void concurrentWritersLoseNeitherCountsNorHistogramObservations() {
        var metrics = new WallMetrics();
        var tasks = new CompletableFuture<?>[8];
        try (var workers = Executors.newFixedThreadPool(tasks.length)) {
            for (int i = 0; i < tasks.length; i++) {
                tasks[i] = CompletableFuture.runAsync(() -> {
                    for (int n = 0; n < 1000; n++) {
                        metrics.increment("worldgen.writeBlocks", 1);
                        metrics.time("worldgen.applyMs", 9);
                        if (n % 100 == 0) {
                            var observed = metrics.snapshot().timings().get("worldgen.applyMs");
                            assertEquals(observed.count(),
                                    observed.bins().stream().mapToLong(Long::longValue).sum());
                        }
                    }
                }, workers);
            }
        }
        var completed = CompletableFuture.allOf(tasks);
        assertTrue(completed.isDone());
        assertFalse(completed.isCompletedExceptionally());
        completed.resultNow();
        var snapshot = metrics.snapshot();
        assertEquals(8000L, snapshot.counters().get("worldgen.writeBlocks").longValue());
        var histogram = snapshot.timings().get("worldgen.applyMs");
        assertEquals(8000, histogram.count());
        assertEquals(72_000, histogram.totalNanos());
        assertEquals(9, histogram.maxNanos());
        assertEquals(8000L, histogram.bins().get(3).longValue());
    }

    @Test
    void resetOnlyClearsThisMetricsInstanceAndRetainedSnapshotsStayIntact() {
        var metrics = new WallMetrics();
        var other = new WallMetrics();
        other.increment("beacons.committedUnique", 7);
        metrics.increment("beacons.committedUnique", 3);
        metrics.time("worldgen.applyMs", 40_000_001L);
        metrics.gauge("history.sequence", "123");
        var previous = metrics.snapshot();
        metrics.resetSession();
        var reset = metrics.snapshot();
        assertTrue(reset.counters().isEmpty());
        assertTrue(reset.timings().isEmpty());
        assertTrue(reset.gauges().values().stream().allMatch("N/A"::equals));
        assertEquals(3L, previous.counters().get("beacons.committedUnique").longValue());
        assertEquals("123", previous.gauges().get("history.sequence"));
        assertEquals(7L, other.snapshot().counters().get("beacons.committedUnique").longValue());
        metrics.increment("beacons.committedUnique", 1);
        assertEquals(1L, metrics.snapshot().counters().get("beacons.committedUnique").longValue());
    }

    private static void assertSorted(Map<String, ?> values) {
        var keys = new ArrayList<>(values.keySet());
        assertEquals(keys.stream().sorted().toList(), keys);
    }
}
