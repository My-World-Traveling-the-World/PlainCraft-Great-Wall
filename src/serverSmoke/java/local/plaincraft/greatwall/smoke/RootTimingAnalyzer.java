package local.plaincraft.greatwall.smoke;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import jdk.jfr.consumer.*;

/** Offline test evidence join. Concurrent GC duration is never counted as stop-the-world time. */
public final class RootTimingAnalyzer {
    private RootTimingAnalyzer() {}
    public record Span(long from, long to) {
        public Span { if (to < from) throw new IllegalArgumentException("reversed interval"); }
    }
    private record Sample(long time, String stack) {}
    private record Anchor(long offset, long uncertainty, long thread, String label) {}

    public static long overlap(long from, long to, List<Span> intervals) {
        long total = 0, covered = from;
        for (var span : intervals.stream().sorted(Comparator.comparingLong(Span::from)).toList()) {
            if (span.from() >= to) break;
            long a = Math.max(Math.max(from, span.from()), covered), b = Math.min(to, span.to());
            if (b > a) { total += b - a; covered = b; }
        }
        return total;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("one completed diagnostic directory required");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        var timing = JsonParser.parseString(Files.readString(root.resolve("root-timing.json"))).getAsJsonObject();
        var gc = new ArrayList<Span>(); var safe = new ArrayList<Span>(); var samples = new ArrayList<Sample>();
        var anchors = new ArrayList<Anchor>(); var open = new HashMap<Long, Long>(); var ends = new HashMap<Long, Long>();
        long thread = timing.get("threadId").getAsLong(), concurrentGcEvents = 0;
        try (var recording = new RecordingFile(root.resolve("root.jfr"))) {
            while (recording.hasMoreEvents()) {
                var event = recording.readEvent();
                String name = event.getEventType().getName();
                long start = epoch(event.getStartTime()), end = epoch(event.getEndTime());
                switch (name) {
                    case "greatwall.RootClockAnchor" -> {
                        long a = event.getLong("monoBefore"), b = event.getLong("monoAfter");
                        anchors.add(new Anchor(start - (a + (b - a) / 2), b - a,
                                event.getLong("ownerThread"), event.getString("label")));
                    }
                    case "jdk.GCPhasePause" -> gc.add(new Span(start, end));
                    case "jdk.GarbageCollection" -> concurrentGcEvents++;
                    case "jdk.SafepointBegin" -> open.put(event.getLong("safepointId"), start);
                    case "jdk.SafepointEnd" -> ends.put(event.getLong("safepointId"), end);
                    case "jdk.ExecutionSample" -> {
                        var sampled = event.getThread("sampledThread");
                        if (sampled != null && sampled.getJavaThreadId() == thread && event.getStackTrace() != null) {
                            var stack = new StringBuilder();
                            for (var frame : event.getStackTrace().getFrames()) stack.append(frame.getMethod().getType().getName())
                                    .append('.').append(frame.getMethod().getName()).append('\n');
                            samples.add(new Sample(start, stack.toString()));
                        }
                    }
                    default -> { }
                }
            }
        }
        for (var entry : ends.entrySet()) {
            Long a = open.remove(entry.getKey());
            if (a != null) safe.add(new Span(a, entry.getValue()));
        }
        int gcEventCount = gc.size(), safeEventCount = safe.size();
        var gcUnion = union(gc); var safeUnion = union(safe);
        Anchor anchor = anchors.stream().filter(a -> a.thread() == thread && a.label().equals("ROOT_START"))
                .findFirst().orElseThrow(() -> new IllegalStateException("missing JFR/monotonic root clock anchor"));
        var evidence = new ArrayList<Map<String, Object>>();
        long maximum = 0, batches, ownOver = 0, outerOver = 0, cpuMaximum = 0;
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(
                Files.newInputStream(root.resolve("root-batches.bin.gz")), 64 * 1024), 64 * 1024));
             var csv = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                     Files.newOutputStream(root.resolve("root-batches.csv.gz")), 64 * 1024), StandardCharsets.UTF_8))) {
            if (in.readInt() != RootBatchLedger.MAGIC || in.readInt() != RootBatchLedger.SCHEMA) {
                throw new IllegalStateException("batch ledger schema mismatch");
            }
            batches = in.readLong();
            if (batches < 1 || batches > 2_000_000 || batches != timing.get("count").getAsLong()) {
                throw new IllegalStateException("batch evidence count mismatch");
            }
            csv.write("batch,startEpochNanos,endEpochNanos,wallNanos,cpuNanos,ownOver40Delta,gcPauseOverlapNanos,safepointOverlapNanos\n");
            for (long index = 0; index < batches; index++) {
                long a = in.readLong(), b = in.readLong(), cpu = in.readLong(), delta = in.readLong();
                long start = Math.addExact(anchor.offset(), a), end = Math.addExact(anchor.offset(), b);
                long wall = b - a;
                if (wall < 0 || cpu < 0 || delta < 0) throw new IllegalStateException("invalid batch clocks");
                ownOver += delta; maximum = Math.max(maximum, wall); cpuMaximum = Math.max(cpuMaximum, cpu);
                long gcNs = overlapSorted(start, end, gcUnion), safeNs = overlapSorted(start, end, safeUnion);
                csv.write(index + "," + start + "," + end + "," + wall + "," + cpu + "," + delta + "," + gcNs + "," + safeNs + "\n");
                if (wall > 40_000_000L) {
                    outerOver++;
                    var row = new TreeMap<String, Object>();
                    row.put("batch", index); row.put("startUtc", instant(start).toString()); row.put("endUtc", instant(end).toString());
                    row.put("wallNanos", wall); row.put("cpuNanos", cpu); row.put("ownOver40Delta", delta);
                    row.put("gcPauseOverlapNanos", gcNs); row.put("safepointOverlapNanos", safeNs);
                    row.put("nonSafepointWallNanos", Math.max(0, wall - safeNs));
                    row.put("sampleStacks", samples.stream().filter(s -> s.time() >= start && s.time() <= end)
                            .map(Sample::stack).distinct().toList());
                    evidence.add(Map.copyOf(row));
                }
            }
            if (in.read() != -1) throw new IllegalStateException("trailing batch data");
        }
        long expectedOwn = timing.getAsJsonObject("rootProgress").get("over40msBatches").getAsLong();
        if (ownOver != expectedOwn) throw new IllegalStateException("original root counter and ledger disagree");
        boolean allGc = ownOver > 0 && evidence.stream().filter(r -> (long) r.get("ownOver40Delta") > 0).allMatch(r ->
                (long) r.get("gcPauseOverlapNanos") > 0 && (long) r.get("nonSafepointWallNanos") + anchor.uncertainty() <= 40_000_000L
                        && (long) r.get("cpuNanos") <= 40_000_000L);
        boolean allNative = ownOver > 0 && evidence.stream().filter(r -> (long) r.get("ownOver40Delta") > 0).allMatch(r ->
                (long) r.get("cpuNanos") > 40_000_000L && (long) r.get("gcPauseOverlapNanos") < 1_000_000L
                        && ((List<?>) r.get("sampleStacks")).stream().anyMatch(s -> s.toString().contains("CpuTerrainOracle")));
        String verdict = ownOver == 0 ? "A_OBSERVED_NO_ROOT_OVERRUN" : allGc ? "B_GC_EXPLAINS_ALL_ROOT_OVERRUNS"
                : allNative ? "C_NATIVE_CPU_WITHOUT_GC" : "UNRESOLVED_OR_MIXED";
        var report = new TreeMap<String, Object>();
        report.put("verdict", verdict); report.put("batches", batches); report.put("rootOwnOver40", ownOver);
        report.put("outerOver40", outerOver); report.put("maximumOuterNanos", maximum); report.put("maximumBatchCpuNanos", cpuMaximum);
        report.put("clockAnchors", anchors); report.put("clockAnchorUncertaintyNanos", anchor.uncertainty());
        report.put("gcPauseEvents", gcEventCount); report.put("safepointEvents", safeEventCount); report.put("unpairedSafepoints", open.size());
        report.put("garbageCollectionEventsNotUsedAsPause", concurrentGcEvents); report.put("rootExecutionSamples", samples.size());
        report.put("overrunBatches", List.copyOf(evidence)); report.put("productionWiring", "OFF");
        report.put("scope", "per-run observational attribution, not a production fix or population estimate");
        Files.writeString(root.resolve("root-timing-analysis.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("ROOT_TIMING_ANALYSIS verdict=" + verdict + " batches=" + batches + " ownOver40=" + ownOver);
    }

    private static long overlapSorted(long from, long to, List<Span> intervals) {
        int lo = 0, hi = intervals.size();
        while (lo < hi) { int m = (lo + hi) >>> 1; if (intervals.get(m).to() <= from) lo = m + 1; else hi = m; }
        long result = 0;
        for (int i = lo; i < intervals.size() && intervals.get(i).from() < to; i++) {
            var span = intervals.get(i); result += Math.max(0, Math.min(to, span.to()) - Math.max(from, span.from()));
        }
        return result;
    }
    private static List<Span> union(List<Span> spans) {
        var sorted = spans.stream().sorted(Comparator.comparingLong(Span::from)).toList();
        var result = new ArrayList<Span>();
        for (var span : sorted) {
            if (!result.isEmpty() && span.from() <= result.getLast().to()) {
                var old = result.removeLast(); result.add(new Span(old.from(), Math.max(old.to(), span.to())));
            } else result.add(span);
        }
        return List.copyOf(result);
    }
    private static long epoch(Instant value) { return Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1_000_000_000L), value.getNano()); }
    private static Instant instant(long value) { return Instant.ofEpochSecond(Math.floorDiv(value, 1_000_000_000L), Math.floorMod(value, 1_000_000_000L)); }
}
