package local.plaincraft.greatwall.stress;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.diagnostics.RuntimeVersions;
import local.plaincraft.greatwall.diagnostics.WallMetrics;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.mixin.TerrainChunkAccess;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * S5 stress driver for the serverSmoke source set. It never ships and never
 * waits on a normal server tick: {@link #tick()} is the only scheduling entry
 * point and runs on the server main thread, issuing at most four outer chunk
 * demands per tick while in-flight demand stays under the configured concurrency.
 */
public final class StressController implements AutoCloseable {
    private static final com.google.gson.Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final TicketType<ChunkPos> TICKET = TicketType.create("pgw_s5_stress",
            Comparator.comparingInt((ChunkPos p) -> p.x).thenComparingInt(p -> p.z));
    private static final int MAX_TICK_SCHEDULE = 4;
    private static final int MAX_PENDING = 65_536;
    // How long the drain may take after the profile window closes.
    //
    // This is a grace period for work that is already running, not a bound on how fast the machine is.
    // The profile deliberately overloads the server, and chunk completions are delivered on the server
    // thread, so while the server is behind the drain makes little progress. A one-hour run at
    // concurrency 8 measured single chunks taking up to 10.3s with a 7.4/8GB heap; a 30s window
    // therefore expired with work still legitimately in flight and reported a completed profile as
    // failed. The window now scales with the requested duration so it cannot be shorter than the
    // workload it is draining.
    private static final long STOP_TIMEOUT_FLOOR_SECONDS = 120L;
    private static final long STOP_TIMEOUT_CEILING_SECONDS = 600L;
    private static final Set<Integer> ALLOWED_CONCURRENCY = Set.of(1, 2, 8, 16);

    public enum Mode { WALK_CORRIDOR, FAR_JUMP, CHUNKY, RELOAD, MIXED_24H }

    public record Config(long seed, Mode mode, long durationSeconds, int concurrency,
            List<Long> farSegments, String expectedRecipe, boolean coldCache) {
        public Config {
            Objects.requireNonNull(mode, "mode");
            farSegments = List.copyOf(Objects.requireNonNull(farSegments, "farSegments"));
        }
    }

    public record Checkpoint(long elapsedSeconds, long completedChunks, WallMetrics.Snapshot metrics,
            long usedHeapBytes, long afterGcHeapBytes, int threads, int openFiles, String hash) {}

    private final ServerLevel level;
    private final MinecraftServer server;
    private final Config config;
    private final Path reports;
    private final WallMetrics metrics = new WallMetrics();
    private final ExecutorService io;
    private final Deque<ChunkPos> pending = new ArrayDeque<>();
    private final Set<ChunkPos> inFlight = ConcurrentHashMap.newKeySet();
    private final List<CompletableFuture<?>> inFlightFutures = new CopyOnWriteArrayList<>();
    private final AtomicLong completedChunks = new AtomicLong();
    /** How many times the finite position list has been refilled to cover the requested window. */
    private final AtomicLong refills = new AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean flushed = new AtomicBoolean();
    private final CompletableFuture<Void> startedFuture = new CompletableFuture<>();
    private final CompletableFuture<Void> stoppedFuture = new CompletableFuture<>();
    private volatile long startedWallNanos;
    private volatile Throwable failure;
    private long sampleCount;
    private int reloadIssued;

    public StressController(ServerLevel level, Config config, Path reports) {
        this.level = Objects.requireNonNull(level, "level");
        this.config = Objects.requireNonNull(config, "config");
        this.server = level.getServer();
        validateConfig(config);
        this.reports = requireFreshReportDir(Objects.requireNonNull(reports, "reports"));
        this.io = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "pgw-s5-stress-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletionStage<Void> start() {
        if (!started.compareAndSet(false, true)) return startedFuture;
        try {
            if (level.getSeed() != config.seed()) {
                throw new IllegalStateException("actual world seed differs from configured stress seed");
            }
            writeHeader();
            pending.addAll(generatePositions());
            startedWallNanos = System.nanoTime();
            GreatWallMod.LOGGER.info("[GreatWall:S5] STRESS_START mode={} seed={} pending={} concurrency={} coldCache={}",
                    config.mode(), config.seed(), pending.size(), config.concurrency(), config.coldCache());
            startedFuture.complete(null);
        } catch (Throwable error) {
            startedFuture.completeExceptionally(error);
        }
        return startedFuture;
    }

    public void tick() {
        if (!started.get() || stopping.get() || closed.get()) return;
        if (failure != null) throw new IllegalStateException("stress run failed", failure);
        // The window is the contract, not the queue. The position list is finite (MAX_PENDING), so a
        // long profile would otherwise drain it and stop far short of its requested duration — a 24h
        // request was on course to end after roughly 4.5h. Both the S5 spec and this class document the
        // long mode as "2..24h rotating walk / far jump / concurrency / re-entry into already generated
        // regions", so once the list is exhausted it is refilled and revisited for as long as the run
        // still has time left. Refilling keeps the same deterministic order, so the workload stays
        // reproducible and the extra time is spent re-entering terrain rather than inventing new work.
        if (pending.isEmpty() && inFlight.isEmpty() && !windowElapsed()) {
            pending.addAll(generatePositions());
            // Logged because the refill is the only thing separating a real full-window profile from one
            // that quietly drained its queue and stopped hours early; it should be visible while the run
            // is still going, not only in the final report.
            long ordinal = refills.incrementAndGet();
            GreatWallMod.LOGGER.info("[GreatWall:S5] STRESS_REFILL ordinal={} completed={} elapsedSeconds={} "
                            + "remainingSeconds={}", ordinal, completedChunks.get(),
                    (System.nanoTime() - startedWallNanos) / 1_000_000_000L,
                    config.durationSeconds() - (System.nanoTime() - startedWallNanos) / 1_000_000_000L);
        }
        int scheduled = 0;
        while (scheduled < MAX_TICK_SCHEDULE && inFlight.size() < config.concurrency() && !pending.isEmpty()) {
            ChunkPos pos = pending.poll();
            if (!inFlight.add(pos)) continue;
            scheduleChunk(pos);
            scheduled++;
        }
        if (config.mode() == Mode.RELOAD && reloadIssued == 0) {
            reloadIssued = 1;
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
        }
        if (pending.isEmpty() && inFlight.isEmpty()) {
            stop();
        }
    }

    /** True once the requested profile window has been observed. */
    private boolean windowElapsed() {
        return (System.nanoTime() - startedWallNanos) / 1_000_000_000L >= config.durationSeconds();
    }

    public CompletionStage<Checkpoint> sample() {
        requireStarted();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return captureSample();
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, io);
    }

    public CompletionStage<Void> stop() {
        if (!started.get()) {
            if (!stoppedFuture.isDone()) {
                stoppedFuture.completeExceptionally(new IllegalStateException("start() was never called"));
            }
            return stoppedFuture;
        }
        stopping.set(true);
        pending.clear();
        CompletableFuture<?>[] snapshot = inFlightFutures.toArray(new CompletableFuture<?>[0]);
        // A short profile drains quickly; a long one is allowed proportionally longer, bounded on both
        // ends so the grace period is never zero and never hides a genuinely stuck future.
        long drainSeconds = Math.max(STOP_TIMEOUT_FLOOR_SECONDS,
                Math.min(STOP_TIMEOUT_CEILING_SECONDS, config.durationSeconds() / 6L + 60L));
        CompletableFuture.allOf(snapshot)
                .orTimeout(drainSeconds, TimeUnit.SECONDS)
                .whenCompleteAsync((value, error) -> {
                    if (failure != null) {
                        flushReport();
                        stoppedFuture.completeExceptionally(failure);
                    } else if (error != null) {
                        flushReport();
                        stoppedFuture.completeExceptionally(
                                new CancellationException("stress stop timed out waiting for in-flight chunks"));
                    } else {
                        flushReport();
                        stoppedFuture.complete(null);
                    }
                }, io);
        return stoppedFuture;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stopping.set(true);
        pending.clear();
        // Best-effort release of any tickets still outstanding; completion paths already remove them.
        for (ChunkPos pos : inFlight) {
            try {
                level.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
            } catch (RuntimeException ignored) {
                // Ticket already gone or level mid-teardown; nothing further to release.
            }
        }
        inFlight.clear();
        inFlightFutures.clear();
        if (!stoppedFuture.isDone()) {
            stoppedFuture.completeExceptionally(new CancellationException("stress controller closed"));
        }
        io.shutdownNow();
        // No player connections or run handles are ever created by this controller; there is
        // nothing else to release, and cancelling one stress run never cancels another user's work.
    }

    private void scheduleChunk(ChunkPos pos) {
        var source = level.getChunkSource();
        source.addRegionTicket(TICKET, pos, 0, pos);
        long requested = System.nanoTime();
        CompletableFuture<?> future = ((TerrainChunkAccess) source)
                .greatwall$request(pos.x, pos.z, ChunkStatus.FULL, true)
                .whenCompleteAsync((result, error) -> {
                    source.removeRegionTicket(TICKET, pos, 0, pos);
                    inFlight.remove(pos);
                    if (error != null) {
                        failed(error);
                        return;
                    }
                    try {
                        result.orElseThrow(() -> new IllegalStateException(
                                "FULL unavailable " + pos + ": " + result.getError()));
                        completedChunks.incrementAndGet();
                        metrics.time("worldgen.applyMs", System.nanoTime() - requested);
                    } catch (Throwable t) {
                        failed(t);
                    }
                }, server);
        // Only unsettled futures are retained, and each removes itself on completion.
        //
        // The list is drained by stop() to await in-flight work, so it must hold exactly the work that is
        // still running. Adding every future and never removing it leaked one entry per chunk forever:
        // harmless for a short run, but the long profile refills its position list and revisits terrain
        // for its whole window, which would have accumulated roughly a million retained futures and the
        // chunk references they hold. That is what pushed the heap to the 8G ceiling, and the resulting
        // GC pressure stalled a chunk-save tick past the 60s watchdog limit and killed the run.
        inFlightFutures.add(future);
        future.whenComplete((ignored, error) -> inFlightFutures.remove(future));
    }

    private Checkpoint captureSample() throws IOException {
        long elapsedSeconds = (System.nanoTime() - startedWallNanos) / 1_000_000_000L;
        WallMetrics.Snapshot snapshot = metrics.snapshot();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long usedHeap = memory.getHeapMemoryUsage().getUsed();
        long afterGcHeap = -1L;
        long afterGcPauseMs = -1L;
        boolean explicitGc = isFixedGcSample();
        if (explicitGc) {
            long before = System.nanoTime();
            System.gc();
            afterGcPauseMs = (System.nanoTime() - before) / 1_000_000L;
            afterGcHeap = memory.getHeapMemoryUsage().getUsed();
        }
        int threadCount = threads.getThreadCount();
        int openFiles = countOpenFiles();

        Map<String, Object> timings = new TreeMap<>();
        for (var entry : snapshot.timings().entrySet()) {
            var histogram = entry.getValue();
            timings.put(entry.getKey(), Map.of(
                    "count", histogram.count(),
                    "totalNanos", histogram.totalNanos(),
                    "maxNanos", histogram.maxNanos(),
                    "bins", histogram.bins()));
        }

        Map<String, Object> report = new TreeMap<>();
        report.put("elapsedSeconds", elapsedSeconds);
        report.put("completedChunks", completedChunks.get());
        report.put("usedHeapBytes", usedHeap);
        report.put("afterGcHeapBytes", afterGcHeap);
        report.put("afterGcExplicit", explicitGc);
        if (afterGcPauseMs >= 0L) report.put("afterGcPauseMs", afterGcPauseMs);
        report.put("threads", threadCount);
        report.put("openFiles", openFiles);
        report.put("counters", snapshot.counters());
        report.put("gauges", snapshot.gauges());
        report.put("timings", timings);

        String json = GSON.toJson(report);
        String hash = DeterministicHash.sha256(json.getBytes(StandardCharsets.UTF_8));
        Files.writeString(reports.resolve(String.format("sample-%06d.json", sampleCount)), json);
        sampleCount++;
        return new Checkpoint(elapsedSeconds, completedChunks.get(), snapshot,
                usedHeap, afterGcHeap, threadCount, openFiles, hash);
    }

    private boolean isFixedGcSample() {
        // Explicit GC belongs only to the long-run mode's fixed 30-minute snapshots (every 30th
        // sample at the 60s cadence). The measured pause is reported, never hidden from stats.
        if (config.mode() != Mode.MIXED_24H) return false;
        return (sampleCount % 30) == 29;
    }

    private static int countOpenFiles() {
        Path fd = Path.of("/proc/self/fd");
        if (!Files.isDirectory(fd)) return -1;
        try (var stream = Files.list(fd)) {
            return (int) stream.count();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private List<ChunkPos> generatePositions() {
        List<ChunkPos> out = new ArrayList<>();
        switch (config.mode()) {
            case WALK_CORRIDOR -> {
                for (int i = 0; i < MAX_PENDING; i++) out.add(new ChunkPos(i, 0));
            }
            case FAR_JUMP -> {
                for (long segment : config.farSegments()) {
                    int cx = (int) (Math.abs(segment) % 1_000_000L) * 64;
                    int cz = (int) ((segment * 7919L) % 96L) - 48;
                    for (int rep = 0; rep < 10 && out.size() < MAX_PENDING; rep++) {
                        out.add(new ChunkPos(cx + rep, cz));
                    }
                }
                if (out.isEmpty()) out.add(new ChunkPos(0, 0));
            }
            case CHUNKY -> {
                // u0..100000 with a 96-block-wide corridor: a bounded rectangle along the route,
                // not a full 100000-radius disc. lane count = 96 / 16 = 6.
                int lanes = 96 / 16;
                for (int u = 0; u < 100_000 && out.size() < MAX_PENDING; u += 16) {
                    for (int lane = 0; lane < lanes; lane++) out.add(new ChunkPos(u / 16, lane - lanes / 2));
                }
            }
            case RELOAD -> {
                for (int i = 0; i < MAX_PENDING; i++) out.add(new ChunkPos(i, 0));
            }
            case MIXED_24H -> {
                // Deterministic rotation across walk / far jump / chunky for the 24h run.
                for (int i = 0; i < MAX_PENDING; i++) {
                    switch (i % 3) {
                        case 0 -> out.add(new ChunkPos(i, 0));
                        case 1 -> out.add(new ChunkPos((i % 500) * 2048, 1));
                        default -> out.add(new ChunkPos(i % 6250, 2));
                    }
                }
            }
        }
        return out;
    }

    private void writeHeader() throws IOException {
        Files.createDirectories(reports);
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        Map<String, Object> header = new TreeMap<>();
        header.put("purpose", "S5 stress run; serverSmoke source set only, never shipped");
        header.put("seed", config.seed());
        header.put("mode", config.mode().name());
        header.put("durationSeconds", config.durationSeconds());
        header.put("concurrency", config.concurrency());
        header.put("farSegments", config.farSegments());
        header.put("expectedRecipe", config.expectedRecipe());
        header.put("coldCache", config.coldCache());
        header.put("os", Map.of("name", system("os.name"), "arch", system("os.arch"), "version", system("os.version")));
        header.put("jdk", Map.of("version", Runtime.version().toString(),
                "vendor", system("java.vendor"), "home", system("java.home")));
        header.put("cpu", Map.of("availableProcessors", Runtime.getRuntime().availableProcessors(),
                "systemLoadAverage", os.getSystemLoadAverage()));
        header.put("ramJvmMaxHeapBytes", Runtime.getRuntime().maxMemory());
        header.put("gpu", "not-observed-in-server-process");
        header.put("jvmFlags", ManagementFactory.getRuntimeMXBean().getInputArguments());
        header.put("mods", RuntimeVersions.capture().stream()
                .map(version -> version.id() + "=" + version.version()).toList());
        header.put("modSHA", modSetSha());
        header.put("worldSeed", level.getSeed());
        Files.writeString(reports.resolve("header.json"), GSON.toJson(header));
    }

    private void flushReport() {
        if (!flushed.compareAndSet(false, true)) return;
        try {
            Map<String, Object> summary = new TreeMap<>();
            summary.put("status", failure == null ? "COMPLETED" : "FAILED");
            summary.put("seed", config.seed());
            summary.put("mode", config.mode().name());
            summary.put("completedChunks", completedChunks.get());
            // Reported so a reader can tell a genuine full-window run from one that drained its queue
            // and stopped early; re-entry into already generated terrain is part of the long profile.
            summary.put("positionRefills", refills.get());
            summary.put("elapsedSeconds", (System.nanoTime() - startedWallNanos) / 1_000_000_000L);
            summary.put("failure", failure == null ? "" : failure.toString());
            Files.writeString(reports.resolve("summary.json"), GSON.toJson(summary));
            GreatWallMod.LOGGER.info("[GreatWall:S5] STRESS_STOP mode={} completed={} refills={} failure={}",
                    config.mode(), completedChunks.get(), refills.get(),
                    failure == null ? "none" : failure.toString());
        } catch (IOException e) {
            GreatWallMod.LOGGER.error("[GreatWall:S5] cannot flush stress report", e);
        }
    }

    private void failed(Throwable error) {
        if (failure == null) failure = error;
        stopping.set(true);
        pending.clear();
        if (!stoppedFuture.isDone()) stoppedFuture.completeExceptionally(error);
    }

    private void requireStarted() {
        if (!started.get()) throw new IllegalStateException("stress start() must be called first");
    }

    private static void validateConfig(Config config) {
        if (!ALLOWED_CONCURRENCY.contains(config.concurrency())) {
            throw new IllegalArgumentException("concurrency must be 1/2/8/16, got " + config.concurrency());
        }
        if (config.durationSeconds() <= 0) {
            throw new IllegalArgumentException("durationSeconds must be positive");
        }
        if (config.expectedRecipe() != null && !config.expectedRecipe().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedRecipe must be a 64-char lowercase hex hash or null");
        }
    }

    private static Path requireFreshReportDir(Path reports) {
        Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        if (!Files.isRegularFile(gameDir.resolve("greatwall_test_world"))) {
            throw new IllegalStateException("stress requires an isolated test-world marker at "
                    + gameDir.resolve("greatwall_test_world"));
        }
        Path abs = reports.toAbsolutePath().normalize();
        requireUnderBuildRun(abs);
        rejectReservedComponents(abs);
        rejectSymlinkEscape(abs);
        if (Files.exists(abs)) {
            throw new IllegalStateException("stress report directory must be fresh (not pre-existing): " + abs);
        }
        return abs;
    }

    private static void requireUnderBuildRun(Path path) {
        for (Path p = path; p != null; p = p.getParent()) {
            Path parent = p.getParent();
            if (parent != null && p.getFileName() != null && parent.getFileName() != null
                    && p.getFileName().toString().equals("run")
                    && parent.getFileName().toString().equals("build")) {
                return;
            }
        }
        throw new IllegalStateException("report directory must live under a project build/run path, got " + path);
    }

    private static void rejectReservedComponents(Path path) {
        for (Path component : path) {
            String name = component.toString();
            if (name.isEmpty()) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("mods") || lower.equals("save") || lower.equals("saves")) {
                throw new IllegalStateException("report path must not reference a real instance directory: " + name);
            }
            if (name.endsWith(".") || name.endsWith(" ") || name.endsWith(":")) {
                throw new IllegalStateException("report path component has a reserved trailing char: " + name);
            }
        }
    }

    private static void rejectSymlinkEscape(Path path) {
        Path probe = path;
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        for (Path p = probe; p != null; p = p.getParent()) {
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                var attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    throw new IllegalStateException("symlink/special file forbidden in report path: " + p);
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot inspect report path " + p, e);
            }
        }
    }

    private static String system(String key) {
        return System.getProperty(key, "unknown");
    }

    private static String modSetSha() {
        List<String> mods = RuntimeVersions.capture().stream()
                .map(version -> version.id() + "=" + version.version())
                .sorted()
                .toList();
        return DeterministicHash.sha256(String.join("\n", mods).getBytes(StandardCharsets.UTF_8));
    }
}
