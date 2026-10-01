package local.plaincraft.greatwall.smoke;

import com.google.gson.GsonBuilder;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import local.plaincraft.greatwall.lifecycle.RootSearch;
import local.plaincraft.greatwall.model.WallTypes.WorkState;
import local.plaincraft.greatwall.terrain.CpuTerrainOracle;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;

/** Test-only primitive ledger; no file IO or per-batch object allocation inside root.advance. */
public final class RootBatchLedger {
    public static final int MAGIC = 0x50524754;
    public static final int SCHEMA = 1;
    private static final long LIMIT = 40_000_000L;
    private static final int CAPACITY = 2_000_000;
    private final Thread owner = Thread.currentThread();
    private final ThreadMXBean cpu = ManagementFactory.getThreadMXBean();
    private final long[] records;
    private final Path directory;
    private final List<Map<String, Object>> slow = new ArrayList<>();
    private final List<Clock> clocks = new ArrayList<>();
    private long count, previousOver40, maximum;
    private boolean written;

    public record Clock(String label, long monotonicBefore, long monotonicAfter, long uptimeMillis,
            long epochMillis, long threadId) {}

    @Name("greatwall.RootClockAnchor")
    @StackTrace(false)
    public static final class AnchorEvent extends Event {
        public String label;
        public long monoBefore;
        public long monoAfter;
        public long ownerThread;
    }

    public RootBatchLedger(Path directory) { this(directory, CAPACITY); }

    RootBatchLedger(Path directory, int capacity) {
        if (capacity < 1 || capacity > CAPACITY) throw new IllegalArgumentException("bounded batch capacity required");
        this.directory = directory;
        records = new long[Math.multiplyExact(capacity, 4)];
        if (!cpu.isCurrentThreadCpuTimeSupported()) throw new IllegalStateException("thread CPU clock unavailable");
        if (!cpu.isThreadCpuTimeEnabled()) cpu.setThreadCpuTimeEnabled(true);
        anchor("ROOT_START");
    }

    public WorkState advance(RootSearch search, NaturalTerrainOracle oracle) {
        check();
        if (count == records.length / 4) throw new IllegalStateException("root batch evidence capacity exhausted");
        long cpuBefore = cpu.getCurrentThreadCpuTime();
        long before = System.nanoTime();
        try { return search.advance(oracle, 64); }
        finally {
            long after = System.nanoTime();
            long cpuAfter = cpu.getCurrentThreadCpuTime();
            long ownDelta = 0;
            if (after - before > LIMIT) {
                var progress = search.progress();
                ownDelta = progress.over40msBatches() - previousOver40;
                previousOver40 = progress.over40msBatches();
                var record = new TreeMap<String, Object>();
                record.put("batch", count); record.put("startNano", before); record.put("endNano", after);
                record.put("cpuNanos", cpuAfter - cpuBefore); record.put("ownOver40Delta", ownDelta);
                record.put("rootProgress", progress);
                record.put("nativeTiming", ((CpuTerrainOracle) oracle).timings());
                slow.add(Map.copyOf(record));
            }
            append(before, after, cpuAfter - cpuBefore, ownDelta);
        }
    }

    void append(long start, long end, long cpuNanos, long ownDelta) {
        check();
        if (end < start || cpuNanos < 0 || ownDelta < 0) throw new IllegalArgumentException("invalid time evidence");
        int index = Math.toIntExact(Math.multiplyExact(count, 4));
        if (index + 3 >= records.length) throw new IllegalStateException("root batch evidence capacity exhausted");
        records[index] = start; records[index + 1] = end; records[index + 2] = cpuNanos; records[index + 3] = ownDelta;
        count++; maximum = Math.max(maximum, end - start);
    }

    public void persist(RootSearch.Progress root, CpuTerrainOracle.TimingSnapshot nativeTiming) throws IOException {
        check();
        if (written) throw new IllegalStateException("cannot overwrite root timing evidence");
        anchor("ROOT_END");
        Files.createDirectories(directory);
        Path payload = directory.resolve("root-batches.bin.gz");
        if (Files.exists(payload)) throw new IllegalStateException("timing payload already exists");
        try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(
                Files.newOutputStream(payload), 64 * 1024), 64 * 1024))) {
            out.writeInt(MAGIC); out.writeInt(SCHEMA); out.writeLong(count);
            for (int i = 0; i < count * 4; i++) out.writeLong(records[i]);
        }
        var summary = new TreeMap<String, Object>();
        summary.put("schema", SCHEMA); summary.put("count", count); summary.put("threadId", owner.getId());
        summary.put("threadName", owner.getName()); summary.put("maximumOuterNanos", maximum);
        summary.put("rootProgress", root); summary.put("nativeTiming", nativeTiming);
        summary.put("clocks", List.copyOf(clocks)); summary.put("slowBatches", List.copyOf(slow));
        summary.put("scope", "outer envelope around original root.advance64; original own counter is authoritative");
        summary.put("records", "big-endian startNano,endNano,cpuNanos,ownOver40Delta; initialization excluded");
        summary.put("logging", "preallocated primitive array; file encoding only after root loop");
        summary.put("productionWiring", "OFF");
        Files.writeString(directory.resolve("root-timing.json"), new GsonBuilder().setPrettyPrinting().create().toJson(summary));
        written = true;
    }

    private void anchor(String label) {
        var event = new AnchorEvent();
        event.label = label; event.ownerThread = owner.getId();
        event.monoBefore = System.nanoTime();
        event.begin();
        event.end();
        event.monoAfter = System.nanoTime();
        event.commit();
        clocks.add(new Clock(label, event.monoBefore, event.monoAfter,
                ManagementFactory.getRuntimeMXBean().getUptime(), System.currentTimeMillis(), owner.getId()));
    }

    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("root ledger is lane confined");
    }
}
