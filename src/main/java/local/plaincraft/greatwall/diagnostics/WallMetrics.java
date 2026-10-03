package local.plaincraft.greatwall.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Diagnostic observations only; neither clocks nor counters are planning inputs. */
public final class WallMetrics {
    private static final int BIN_COUNT = 64;
    private static final int GAUGE_LIMIT = 1024;
    private static final long SLICE_LIMIT_NANOS = 40_000_000L;
    private static final Set<String> METRICS = Set.of(
            "planner.logicalSamples", "planner.actualDensityEvals", "planner.cacheHitRate",
            "planner.sliceMs", "planner.queueWait", "planner.active", "planner.pending",
            "planner.syncComputeCount", "planner.syncComputeMaxMs", "planner.cancellationCount",
            "prefix.certifiedThrough", "prefix.sparseIntervals", "prefix.pendingGapRange",
            "prefix.terminal",
            "cache.segmentBytes", "cache.snapshotBytes", "cache.pinnedBytes",
            "cache.eviction", "cache.corruptBlobCount",
            "worldgen.waitPlanMs", "worldgen.captureMs", "worldgen.compileMs",
            "worldgen.applyMs", "worldgen.writeBlocks", "worldgen.forbiddenGetChunk",
            "worldgen.waitWhileLocked", "worldgen.crossOwnerWrite", "worldgen.staleSessionWrites",
            "geometry.seamMismatch", "geometry.unsupportedVoxel", "geometry.stairConnectivityFailures",
            "hydrology.knownNarrow", "hydrology.knownWide", "hydrology.unresolved",
            "hydrology.terrainMismatch",
            "roads.gatesByState", "roads.protectedGaps", "roads.apertureViolations", "roads.retryCount",
            "beacons.committedUnique", "beacons.pending", "beacons.duplicateRejected",
            "beacons.ignoredSource", "beacons.ioErrors",
            "garrison.loadedAlive", "garrison.reserved", "garrison.capRejections",
            "garrison.protectedSpawnRejections", "garrison.retiredOverflow",
            "history.pendingIo", "history.health", "history.sequence", "oracle.primitiveMaxMs");
    private static final Set<String> SLICE_TIMINGS = Set.of(
            "planner.sliceMs", "planner.syncComputeMaxMs", "worldgen.captureMs",
            "worldgen.compileMs", "worldgen.applyMs", "oracle.primitiveMaxMs");
    private static final Histogram EMPTY = new Histogram(0, 0, 0,
            Collections.nCopies(BIN_COUNT, 0L));

    public record Histogram(long count, long totalNanos, long maxNanos, List<Long> bins) {
        public Histogram {
            Objects.requireNonNull(bins, "bins");
            if (count < 0 || totalNanos < 0 || maxNanos < 0 || bins.size() != BIN_COUNT) {
                throw new IllegalArgumentException("nonnegative histogram with 64 bins required");
            }
            bins = List.copyOf(bins);
            long observations = 0;
            for (long bin : bins) {
                if (bin < 0) throw new IllegalArgumentException("nonnegative bin required");
                observations = Math.addExact(observations, bin);
            }
            if (observations != count || maxNanos > totalNanos
                    || (count == 0 && (totalNanos != 0 || maxNanos != 0))) {
                throw new IllegalArgumentException("inconsistent histogram");
            }
        }
    }

    public record Snapshot(Map<String, Long> counters, Map<String, Histogram> timings,
            Map<String, String> gauges, long observationNanos) {
        public Snapshot {
            counters = sortedCopy(counters);
            timings = sortedCopy(timings);
            gauges = sortedCopy(gauges);
            for (String value : gauges.values()) requireGauge(value);
        }
    }

    private final ConcurrentHashMap<String, Long> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Histogram> timings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> gauges = new ConcurrentHashMap<>();

    public WallMetrics() {}

    public void increment(String name, long delta) {
        requireMetric(name);
        counters.compute(name, (key, previous) ->
                Math.addExact(previous == null ? 0L : previous, delta));
    }

    public void time(String name, long elapsedNanos) {
        requireMetric(name);
        if (elapsedNanos < 0) throw new IllegalArgumentException("elapsedNanos >= 0 required");
        // Zero and one ns share bin 0; positive ns use floor(log2(ns)).
        int bin = elapsedNanos == 0 ? 0 : 63 - Long.numberOfLeadingZeros(elapsedNanos);
        timings.compute(name, (key, previous) -> {
            Histogram old = previous == null ? EMPTY : previous;
            var bins = new ArrayList<>(old.bins());
            bins.set(bin, Math.addExact(bins.get(bin), 1L));
            return new Histogram(Math.addExact(old.count(), 1L),
                    Math.addExact(old.totalNanos(), elapsedNanos),
                    Math.max(old.maxNanos(), elapsedNanos), bins);
        });
        if (elapsedNanos > SLICE_LIMIT_NANOS && SLICE_TIMINGS.contains(name)) {
            // A derived diagnostic key preserves an explicitly supplied numeric gauge.
            gauges.put(name + ".over40ms", slowCall(name, elapsedNanos));
        }
    }

    public void gauge(String name, String value) {
        requireMetric(name);
        requireGauge(value);
        gauges.put(name, value);
    }

    public Snapshot snapshot() {
        var counts = new TreeMap<>(counters);
        var histograms = new TreeMap<>(timings);
        var values = new TreeMap<>(gauges);
        for (String name : METRICS) {
            if (!counts.containsKey(name) && !histograms.containsKey(name)) {
                values.putIfAbsent(name, "N/A");
            }
        }
        // Each map is a detached observation, not a transaction across metric names.
        return new Snapshot(counts, histograms, values, System.nanoTime());
    }

    public void resetSession() {
        counters.clear();
        timings.clear();
        gauges.clear();
    }

    private static void requireMetric(String name) {
        Objects.requireNonNull(name, "name");
        if (!METRICS.contains(name)) throw new IllegalArgumentException("unknown metric: " + name);
    }

    private static void requireGauge(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() > GAUGE_LIMIT) throw new IllegalArgumentException("gauge exceeds 1024 chars");
    }

    private static <V> Map<String, V> sortedCopy(Map<String, V> source) {
        Objects.requireNonNull(source, "source");
        var copy = new TreeMap<String, V>();
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value")));
        return Collections.unmodifiableMap(copy);
    }

    private static String slowCall(String name, long elapsedNanos) {
        var summary = new StringBuilder("metric=").append(name)
                .append(" elapsedNanos=").append(elapsedNanos);
        boolean first = true;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().equals(Thread.class.getName())
                    || frame.getClassName().equals(WallMetrics.class.getName())) continue;
            if (first) {
                // Identify the recording call site, without arguments or player data.
                summary.append(" class=").append(frame.getClassName())
                        .append(" primitive=").append(frame.getMethodName()).append(" stack=");
                first = false;
            } else {
                summary.append(" <- ");
            }
            summary.append(frame);
            if (summary.length() >= GAUGE_LIMIT) break;
        }
        return summary.substring(0, Math.min(summary.length(), GAUGE_LIMIT));
    }
}
