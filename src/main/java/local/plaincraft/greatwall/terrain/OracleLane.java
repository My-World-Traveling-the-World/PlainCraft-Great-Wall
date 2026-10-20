package local.plaincraft.greatwall.terrain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;

/** One session/lane owns one native producer. Public cancellation never substitutes for producer drain. */
public final class OracleLane implements AutoCloseable {
    private static final ThreadLocal<OracleLane> INITIALIZING = new ThreadLocal<>();
    /**
     * Queued batches a lane accepts before submitters start waiting.
     *
     * <p>64 was enough while every session was sited by the strict contract; the fallback site samples
     * more of the map, and the measured session filled a lane one second after the site was chosen.
     */
    private static final int MAX_QUEUED_BATCHES = 256;
    /** How long a submitter waits for room before the lane is declared stuck. */
    private static final long LANE_QUEUE_POLL_MILLIS = 5L;
    private final UUID session;
    private final int index;
    private final String fingerprint;
    private final Supplier<NaturalTerrainOracle> factory;
    private final ExecutorService executor;
    private final StageTrace trace;
    private final ConcurrentHashMap<String, Long> state = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    // The admission monitor protects submission ordering only, never initialization or sampling.
    private final Object admission = new Object();
    private NaturalTerrainOracle oracle;
    private Throwable producerFailure;

    public OracleLane(UUID session, int index, String fingerprint, Supplier<NaturalTerrainOracle> factory) {
        this.session = Objects.requireNonNull(session);
        if (index < 0 || index >= LanePolicy.MAX_LANES) {
            throw new IllegalArgumentException("oracle lane index 0.." + (LanePolicy.MAX_LANES - 1));
        }
        this.index = index; this.fingerprint = Objects.requireNonNull(fingerprint);
        this.factory = Objects.requireNonNull(factory);
        trace = new StageTrace(session);
        executor = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "pgw-oracle-" + index + "-" + session);
            thread.setDaemon(true); return thread;
        });
    }

    /** A detached view: cancelling it cannot cancel this lane's single initialization. */
    public CompletableFuture<Void> start() {
        trace.assertOutsideLock();
        synchronized (admission) {
            if (state.containsKey("closed")) return CompletableFuture.failedFuture(closedFailure());
            if (state.putIfAbsent("initializing", 1L) == null) executor.execute(this::initialize);
        }
        return ready.copy();
    }

    /** The mutable oracle never travels in a future. Actions and cursors remain on this lane. */
    public <T> CompletableFuture<T> submit(Function<NaturalTerrainOracle, T> action) {
        Objects.requireNonNull(action);
        trace.assertOutsideLock();
        var result = new CompletableFuture<T>();
        // Backpressure instead of an immediate failure. A full queue used to fail the whole session
        // ("oracle lane queue capacity"), which is how a fallback-sited session died one second after
        // the site was chosen: the fallback samples more of the map, the lanes fill, and a scheduling
        // hiccup becomes "no wall at all". The wait is bounded so a genuinely stuck lane still fails.
        long waitMillis = Long.getLong("greatwall.oracleLaneWaitMillis", 30_000L);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while (true) {
            synchronized (admission) {
                if (state.containsKey("closed")) return CompletableFuture.failedFuture(closedFailure());
                if (state.getOrDefault("queued", 0L) < MAX_QUEUED_BATCHES) {
                    start();
                    state.merge("queued", 1L, Long::sum);
                    executor.execute(() -> {
                        // Every heavy sampling unit in a session - the prewarm lanes and the fine sample
                        // lanes alike - passes through here, so this is the one place that can hold the
                        // whole startup to a single concurrency budget.
                        boolean slot = StageBudget.acquireSlot();
                        try {
                            if (state.containsKey("closed")) throw closedFailure();
                            if (producerFailure != null) throw new WallFailure(FailureCode.UNSUPPORTED_ADAPTER,
                                    "oracle lane producer failed", producerFailure);
                            if (oracle == null) throw new IllegalStateException("initialization did not publish an owner-local oracle");
                            trace.assertOutsideLock();
                            result.complete(action.apply(oracle));
                        } catch (Throwable failure) {
                            // A failed action may leave an unfinished cursor; this lane is never silently reused.
                            producerFailure = failure;
                            state.merge("action-failures", 1L, Long::sum);
                            result.completeExceptionally(failure);
                        } finally {
                            if (slot) StageBudget.releaseSlot();
                            state.compute("queued", (key, count) -> count - 1);
                        }
                    });
                    return result;
                }
            }
            if (System.nanoTime() >= deadline) {
                return CompletableFuture.failedFuture(new WallFailure(FailureCode.CAPACITY,
                        "oracle lane queue capacity (waited " + waitMillis + " ms)"));
            }
            try {
                Thread.sleep(LANE_QUEUE_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(new WallFailure(FailureCode.CAPACITY,
                        "interrupted while waiting for oracle lane capacity", interrupted));
            }
        }
    }

    public UUID session() { return session; }
    public int index() { return index; }
    public String fingerprint() { return fingerprint; }
    public Map<String, Long> diagnostics() {
        var result = new java.util.HashMap<>(state);
        trace.counts().forEach((key, value) -> result.put("sentinel-" + key, value));
        // Baked-chunk reuse: the fine sample only gets its benefit if hits dominate misses, and that is
        // only observable from a real session (a unit test cannot build a live NoiseChunk).
        if (oracle instanceof CpuTerrainOracle cpu) {
            result.put("chunk-cache-hits", cpu.chunkCacheHits());
            result.put("chunk-cache-misses", cpu.chunkCacheMisses());
            // Walked blocks and per-phase milliseconds: the only way to attribute the fine sample's
            // cost between the descent, the slice fill of a new chunk, the cell switch and the biome
            // lookup. Summed across lanes by PlannerScheduler.laneDiagnostic.
            result.putAll(cpu.costModel());
        }
        return Map.copyOf(result);
    }
    public CompletableFuture<Void> drain() { return drained.copy(); }

    /** Native constructors must run only in this executor's initialization action, outside all region locks. */
    public static void assertNativeInitialization() {
        var lane = INITIALIZING.get();
        if (lane == null) throw new IllegalStateException("native oracle initialization requires an explicit OracleLane future");
        lane.trace.assertOutsideLock();
        String thread = Thread.currentThread().getName();
        if (!thread.equals("pgw-oracle-" + lane.index + "-" + lane.session)) {
            lane.state.merge("forbidden-init-thread", 1L, Long::sum);
            throw new IllegalStateException("native initialization outside its dedicated lane");
        }
    }

    /** Consumed by the CPU constructor before allocating its private RandomState. Never renewable. */
    public static void claimNativeInitialization() {
        assertNativeInitialization();
        var lane = INITIALIZING.get();
        if (lane.state.containsKey("closed") || lane.state.putIfAbsent("native-constructions", 1L) != null) {
            throw new IllegalStateException("native construction permit is closed or already consumed on this lane");
        }
    }

    private void initialize() {
        long started = System.nanoTime();
        Throwable failure = null;
        INITIALIZING.set(this);
        try {
            assertNativeInitialization();
            if (state.containsKey("closed")) throw closedFailure();
            state.merge("factory-calls", 1L, Long::sum);
            oracle = Objects.requireNonNull(factory.get(), "oracle factory returned null");
            if (!fingerprint.equals(oracle.fingerprint())) {
                throw new WallFailure(FailureCode.RECIPE_MISMATCH, "lane oracle fingerprint mismatch");
            }
            if (state.containsKey("closed")) throw closedFailure();
            state.put("ready", 1L);
        } catch (Throwable error) {
            failure = error;
            producerFailure = error;
            state.merge("initialization-failures", 1L, Long::sum);
        } finally {
            state.put("initialization-nanos", System.nanoTime() - started);
            INITIALIZING.remove();
        }
        // CompletableFuture completion can execute public continuations inline on this thread.
        // Publish only AFTER revoking the constructor context, on both success and error paths.
        if (failure == null) ready.complete(null); else ready.completeExceptionally(failure);
    }

    @Override public void close() {
        synchronized (admission) {
            if (state.putIfAbsent("closed", 1L) != null) return;
            ready.completeExceptionally(closedFailure());
            Runnable drain = () -> {
                try {
                    if (oracle != null) { oracle.close(); oracle = null; state.merge("oracle-closes", 1L, Long::sum); }
                    state.put("drained", 1L);
                    drained.complete(null);
                } catch (Throwable failure) { drained.completeExceptionally(failure); }
            };
            // Queue AFTER the actual constructor/actions; no shutdownNow/cancellation masquerading as drain.
            // A rejected hand-off must still drain: PlannerScheduler.close() joins every lane's drained
            // future on the world-exit path, so a lane whose executor is already stopped would otherwise
            // hang the shutdown and leak its native oracle. Close the oracle inline instead - this thread
            // is the caller's, and the lane is already being torn down - and complete drained either way.
            try {
                executor.execute(drain);
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                state.merge("drain-rejected", 1L, Long::sum);
                drain.run();
            }
            executor.shutdown();
        }
    }

    private static WallFailure closedFailure() { return new WallFailure(FailureCode.CLOSED, "oracle lane session closed"); }
}
