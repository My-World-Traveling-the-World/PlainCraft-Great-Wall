package local.plaincraft.greatwall.planning;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.config.GreatWallConfig;
import local.plaincraft.greatwall.diagnostics.BudgetCache;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.terrain.HydrologyClassifier;
import local.plaincraft.greatwall.terrain.LanePolicy;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;
import local.plaincraft.greatwall.terrain.OracleLane;

/** Pure S1A scheduler. Integration must acquire its futures before any FEATURES region lock. */
public final class PlannerScheduler implements AutoCloseable {
    public record FailureEvidence(String phase, long segment, long operations, FailureCode code,
            String exceptionType, String message, String originalStack) {}
    private static final long MIB = 1024L * 1024;
    /** How many times the fine-sample stage ran in this process; see the FINE_SAMPLE_CALL line. */
    private static final java.util.concurrent.atomic.AtomicLong FINE_SAMPLE_CALLS = new java.util.concurrent.atomic.AtomicLong();
    private final WorldRecipe recipe;
    private final ConcurrentHashMap<SegmentKey, CompletableFuture<RouteShape>> routeFlights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SegmentKey, CompletableFuture<SegmentGeometry>> geometryFlights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SegmentKey, CompletableFuture<SegmentPlan>> planFlights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, JobProgress> progress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlanLease<?>> callerLeases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<PlanLease<Object>>> acquisitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> state = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FailureEvidence> failures = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor pump;
    private final PrefixSummaryIndex prefixes;
    private final SegmentCacheStore cache;
    private final Executor io;
    private final AnchorField anchors;
    private final RidgePlanner ridge;
    private final HydrologyClassifier hydrology = new HydrologyClassifier();
    /**
     * Per-segment hydrology operation ceiling. A real-world segment needed
     * >25M operations before terminating, which exceeded the server watchdog
     * window. Beyond this ceiling the remaining unresolved water terminates
     * as WIDE, so a slow island can never stall the whole wall.
     */
    private static final long HYDROLOGY_OPERATION_CEILING = 4_000_000L;
    /** Opening segments pre-computed by the bulk prewarm, once the blocking startup peer is done. */
    private static final long STARTUP_PREWARM_SEGMENTS = 4;
    /** Thread-name prefixes owned by this scheduler; used to refuse self-blocking waits. */
    private static final String pumpThreadPrefix = "pgw-planner-";
    private static final String laneThreadPrefix = "pgw-oracle-";
    private final HeightContractPlanner heights;
    private final SiteScheduler sites;
    private final BudgetCache<CacheKey, Object> memory;
    private final List<OracleLane> lanes;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final List<Lane> laneState;
    private final PriorityQueue<Job<?>> pending = new PriorityQueue<>(Comparator.<Job<?>, Priority>comparing(j -> j.priority)
            .thenComparingLong(j -> j.sequence));
    private final ArrayDeque<Runnable> geometryQueue = new ArrayDeque<>();
    /**
     * Guards {@link #geometryQueue}, which caller threads enqueue onto while the pump drains it.
     *
     * <p>{@code ArrayDeque} is not thread safe, and this queue genuinely has two producers of a race:
     * {@code requireGeometry} runs on whatever thread asked (the server thread for a {@code /greatwall}
     * command, the worldgen thread for a plan request), while {@code drainGeometry} also runs from the
     * pump when a geometry flight completes. Only the queue operations are locked - the dequeued work
     * runs outside the lock, so a slow geometry job cannot serialise the admission path.
     */
    private final Object geometryLock = new Object();
    private final ArrayDeque<Runnable> ioQueue = new ArrayDeque<>();
    private final ArrayDeque<Runnable> planAdmissions = new ArrayDeque<>(), geometryAdmissions = new ArrayDeque<>();
    private final Map<UUID, Job<?>> activeJobs = new HashMap<>();
    private int activeGeometry, credits, activeIo, admittedPlans, admittedGeometries;
    /** Native lanes currently initializing; bounded by {@link LanePolicy#MAX_CONCURRENT_INITIALIZATIONS}. */
    private int initializing;
    private long deadline, sequence, background = 4;
    private ScheduledFuture<?> startupTimer;
    /**
     * Drives the bulk prewarm independently of the server tick.
     *
     * <p>Cancelled when the prewarm settles or the scheduler closes. See
     * {@link #afterRoadBulk()} for why the prewarm does not rely on ticks.
     */
    private ScheduledFuture<?> prewarmTimer;

    /**
     * The fine sample's own driver.
     *
     * <p>The prewarm has had a dedicated 1 ms driver for a while, but the fine sample runs <em>before</em>
     * the prewarm and therefore ran with no driver at all: the startup timer is cancelled by the first
     * server tick, so the stage was driven only by ticks (about 20 drives/s) plus the stall watchdog.
     * Each drive grants every idle lane {@link #LANE_BATCH_OPERATIONS} primitive operations, so a
     * tick-driven stage ran roughly two orders of magnitude below the lanes' capacity. A measured
     * session spent 27 minutes in the fine sample and never finished it, with 8 of 12 lanes parked at
     * any instant. This driver is the same shape as the prewarm's.
     */
    private ScheduledFuture<?> fineSampleTimer;

    /**
     * Primitive operations granted to one lane per drive.
     *
     * <p>64 was the historical value and the prewarm showed it workable only because the prewarm had a
     * 1 ms driver (12 lanes x 64 ops x 1000 drives/s). The fine sample received the same 64 ops at tick
     * rate instead.
     *
     * <p><b>4096 is a hard ceiling, not a tuning choice.</b> Both cursor contracts reject more:
     * {@code CpuTerrainOracle.advance} and {@code WallFinePlanner.advance} each throw on a primitive
     * limit outside 1..4096 (the legacy 256 ceiling was raised in 7cc3124f; the batch size is clamped
     * to the same window by {@code GreatWallConfig.laneBatchOperations}). A measured lane round with
     * 256 ops took about 10 ms, so the batch is not the limiter either: the levers on this stage are
     * doing less work per column and cutting the retry walks, not a larger batch.
     */
    // The batch size is GreatWallConfig.laneBatchOperations (default 256, clamped 1..4096); this class
    // used to keep a private copy that nothing read, so tuning it changed nothing.


    /** How often the fine sample reports progress while it runs. */
    private static final long FINE_SAMPLE_REPORT_MILLIS = 30_000L;

    /**
     * Live counters of the running fine sample, published by {@link #requireFineSamples} so the
     * driver can report progress.
     *
     * <p>The fine sample is the longest silent stretch of the startup: it walks a cross-section per
     * route point and logs nothing until the whole index is complete, so a slow run and a hung one
     * look identical from the outside - the loading card only shows a column count that the player
     * cannot tell is moving. These are the same counters the card reads.
     */
    private volatile java.util.concurrent.atomic.AtomicInteger fineSampleDone;
    private volatile java.util.concurrent.atomic.AtomicInteger fineSampleTotal;
    /** Published for the progress line: emitted-before-dedup, sections walked, wide sections, probes. */
    private volatile java.util.concurrent.atomic.AtomicInteger fineSampleRaw;
    private volatile java.util.concurrent.atomic.AtomicLong fineSampleSections;
    private volatile java.util.concurrent.atomic.AtomicLong fineSampleWideSections;
    private volatile java.util.concurrent.atomic.AtomicLong fineSampleProbed;
    private volatile long fineSampleStartNanos;
    private volatile long fineSampleLastLogNanos;
    /**
     * The fine sample's own completion, published so the runaway guard can publish the index early.
     *
     * <p>Null once the stage has finished or been abandoned, which makes the guard idempotent.
     */
    private volatile Runnable fineSampleAbandon;
    /** Set when the runaway guard published the index instead of waiting for the work to converge. */
    private volatile java.util.concurrent.atomic.AtomicBoolean fineSampleForced = new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * The columns this session has walked, kept so a close can still write them to disk.
     *
     * <p>It is the same list the completion callback persists, so there is one source of truth; it is
     * published only for the close path.
     */
    private volatile List<WallFinePlanner.FineColumn> fineSampleWalked;
    /**
     * How far past its own estimate the fine sample may run before it is treated as a runaway.
     *
     * <p>An estimate may be a little low; ten times is not an estimate problem. A measured session was
     * still climbing at 4.5M columns against a 498k estimate after 25 minutes, at a rate that was
     * <em>rising</em> (1.8k/s to 3.7k/s) with 85% chunk-cache hits - the signature of re-walking columns
     * the index already holds, not of sampling new terrain. Publishing what has been sampled keeps the
     * save buildable; waiting for convergence never does.
     */
    /** Fixed slice count per segment; see the comment where it is used. */
    private static final int FINE_SAMPLE_SLICES_PER_SEGMENT = 48;
    private static final long FINE_SAMPLE_RUNAWAY_NUMERATOR = 4L;
    /** How often the semantic runaway test samples the counters. */
    private static final long FINE_SAMPLE_RUNAWAY_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** Columns that must arrive in one window before "no new terrain" means anything. */
    private static final long FINE_SAMPLE_RUNAWAY_MIN_COLUMNS = 1_000L;
    /** Below this the denominator is still the seed value, so the ratio bound means nothing. */
    private static final long FINE_SAMPLE_RUNAWAY_MIN_EXPECTED = 1_000L;
    /** Consecutive no-new-terrain windows before the stage is declared a runaway (5 s each). */
    private static final int FINE_SAMPLE_RUNAWAY_IDLE_WINDOWS = 3;
    /**
     * Consecutive windows of "not one column moved while work is still outstanding and every lane is
     * idle" before the stage is abandoned (5 s each).
     *
     * <p>The other two tests both need columns to keep arriving: the ratio test divides by columns, and
     * the no-new-terrain test ignores a window whose column delta is below its floor. A stage that hangs
     * - a slice future that never completes, a lane that died holding a job - moves no columns at all, so
     * both tests zero their counters and wait forever. This one is the liveness test they were missing,
     * and it is phrased on idleness rather than on a column count so a slow-but-alive stage cannot trip
     * it: lanes busy means work is progressing even when the counters are flat.
     */
    private static final int FINE_SAMPLE_STALL_WINDOWS = 3;
    private long runawayLastColumns;
    private long runawayLastMisses;
    private long runawayLastCheckNanos;
    private int runawayIdleChecks;
    private int runawayStallChecks;
    /** The fine sample's outstanding counters, mirrored so the driver can judge a stall. */
    private volatile java.util.concurrent.atomic.AtomicInteger fineSampleSlicesPending;
    private volatile java.util.concurrent.atomic.AtomicInteger fineSampleRoutesPending;

    /**
     * The prewarm's segment leases, pinned until the planning compile has published them.
     *
     * <p>They must outlive the prewarm: closing them releases the cached {@link SegmentPlan}s back
     * to the memory budget, and the compile does not run until the server has started (minutes
     * later). A measured session closed them at prewarm completion and then re-planned the whole
     * opening window during the compile - 22 minutes of silent work that published nothing before
     * the run ended. Released by {@link #releasePrewarmLeases()} once the compile is done, and by
     * {@link #close()} on every other path.
     */
    private final java.util.List<PlanLease<SegmentPlan>> prewarmLeases =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private PlanLease<SegmentPlan> backgroundLease;

    /**
     * Runs once the bulk prewarm has settled successfully, on the prewarm's own completion thread.
     *
     * <p>Registered by the production session to post the planning compile onto the server queue.
     * Without it the compile can only be reached from the server tick loop, which a startup peer's
     * managedBlock holds shut - the peer would wait for a wall that can no longer finish.
     */
    private volatile Runnable bulkReadyHook;

    /** Registers the bulk-prewarm completion hook. Replaces any previous one. */
    public void onBulkReady(Runnable hook) {
        this.bulkReadyHook = hook;
    }

    /**
     * Columns an earlier session already sampled, handed in before {@link #requireFineSamples}.
     *
     * <p>The fine sample is the slowest stage of a cold startup and its result is a pure function of
     * the world, so a session that reuses the previous session's columns skips every terrain walk
     * the cache answers. The cache is off by default so tests and fixture runs sample everything.</p>
     */
    private volatile local.plaincraft.greatwall.terrain.FineSampleCache fineSampleCache =
            local.plaincraft.greatwall.terrain.FineSampleCache.none();

    /** Installs the fine-sample cache. Must be called before {@link #requireFineSamples}. */
    public void setFineSampleCache(local.plaincraft.greatwall.terrain.FineSampleCache cache) {
        this.fineSampleCache = java.util.Objects.requireNonNull(cache, "cache");
    }

    public PlannerScheduler(WorldRecipe recipe, Supplier<NaturalTerrainOracle> oracleFactory, SegmentCacheStore cache, Executor io) {
        this(recipe, Objects.requireNonNull(oracleFactory), null, cache, io);
    }

    /** Successful construction transfers both session-owned lanes from bootstrap to this scheduler. */
    public PlannerScheduler(WorldRecipe recipe, List<OracleLane> bootstrapLanes, SegmentCacheStore cache, Executor io) {
        this(recipe, null, List.copyOf(bootstrapLanes), cache, io);
    }

    private PlannerScheduler(WorldRecipe recipe, Supplier<NaturalTerrainOracle> oracleFactory,
            List<OracleLane> bootstrapLanes, SegmentCacheStore cache, Executor io) {
        this.recipe = Objects.requireNonNull(recipe);
        this.cache = Objects.requireNonNull(cache); this.io = Objects.requireNonNull(io);
        if (!"minecraft:overworld".equals(recipe.dimension())) {
            throw new WallFailure(FailureCode.INVALID_RESOURCE, "only the overworld may own a wall planner");
        }
        if (!DeterministicHash.sha256(CanonicalCodec.recipe(recipe)).equals(recipe.recipeHash())) {
            throw new WallFailure(FailureCode.RECIPE_MISMATCH, "recipe semantic hash is not canonical");
        }
        if (!cache.recipeHash().equals(recipe.recipeHash())) throw new IllegalArgumentException("cache recipe mismatch");
        if (bootstrapLanes != null) {
            if (bootstrapLanes.size() < LanePolicy.MIN_LANES) {
                throw new IllegalArgumentException("at least " + LanePolicy.MIN_LANES + " bootstrap lanes required");
            }
            for (int index = 0; index < bootstrapLanes.size(); index++) {
                var lane = bootstrapLanes.get(index);
                if (lane.index() != index || !lane.session().equals(recipe.instance())
                        || !lane.fingerprint().equals(recipe.terrainHash())
                        || lane.diagnostics().containsKey("closed")) {
                    throw new WallFailure(FailureCode.STALE_SESSION, "bootstrap lane identity/settings/owner mismatch");
                }
            }
        }
        this.anchors = new AnchorField(recipe); this.ridge = new RidgePlanner(recipe, anchors);
        this.heights = new HeightContractPlanner(recipe); this.sites = new SiteScheduler(recipe);
        this.pump = new Pump(factory("pgw-planner-" + recipe.instance()));
        pump.setRemoveOnCancelPolicy(true);
        this.lanes = bootstrapLanes != null ? List.copyOf(bootstrapLanes)
                : lanes(recipe, oracleFactory, LanePolicy.resolve().startupLaneCount());
        this.laneState = this.lanes.stream().map(ignored -> new Lane()).toList();
        this.memory = new BudgetCache<>(recipe.config().segmentBytes(), 256, 4096, pump, PlannerScheduler::retainedBytes);
        this.prefixes = new PrefixSummaryIndex(i -> requireGeometry(i, Priority.PLAYER_PREFETCH), pump, cache, this::submitIo);
    }

    private static List<OracleLane> lanes(WorldRecipe recipe, Supplier<NaturalTerrainOracle> oracleFactory,
                                          int count) {
        var built = new java.util.ArrayList<OracleLane>(count);
        for (int index = 0; index < count; index++) {
            built.add(new OracleLane(recipe.instance(), index, recipe.terrainHash(), oracleFactory));
        }
        return List.copyOf(built);
    }

    public PlanLease<RouteShape> requireRoute(long segment, Priority priority) {
        var key = key(segment);
        promote(segment, priority);
        return acquire(new CacheKey("route", segment), MIB, RouteShape.class, () -> {
            var result = flight(routeFlights, key);
            enqueue(new RouteJob(key, priority, result));
            return result.thenApply(v -> v);
        });
    }

    public PlanLease<SegmentGeometry> requireGeometry(long segment, Priority priority) {
        var key = key(segment);
        promote(segment, priority);
        return acquire(new CacheKey("geometry", segment), 2 * MIB, SegmentGeometry.class, () -> {
            var result = flight(geometryFlights, key);
            synchronized (geometryLock) { geometryQueue.add(() -> startGeometry(key, priority, result)); }
            drainGeometry();
            return result.thenApply(v -> v);
        });
    }

    public PlanLease<SegmentPlan> requirePlan(long segment, Priority priority) {
        var key = key(segment);
        promote(segment, priority);
        return acquire(new CacheKey("plan", segment), 4 * MIB, SegmentPlan.class, () -> {
            var result = flight(planFlights, key);
            var geometry = requireGeometry(segment, priority);
            var prefix = prefixes.requireBefore(segment);
            geometry.future().thenCombineAsync(prefix.future(), Pair::new, pump).whenCompleteAsync((inputs, error) -> {
                if (error != null) { geometry.close(); prefix.close(); result.completeExceptionally(error); return; }
                if (closed()) { geometry.close(); prefix.close(); result.completeExceptionally(closedFailure()); return; }
                if (inputs.prefix.terminal().isPresent() && inputs.prefix.terminal().get().segment() < segment) {
                    var table = new StationTable(List.of(), List.of(), "0".repeat(64));
                    table = new StationTable(List.of(), List.of(), DeterministicHash.sha256(CanonicalCodec.stations(table)));
                    result.complete(hashedPlan(inputs.geometry, inputs.prefix, table, List.of()));
                    geometry.close(); prefix.close(); return;
                }
                ioRead(() -> cache.readPlan(key)).whenCompleteAsync((cached, cacheError) -> {
                    if (closed()) { geometry.close(); prefix.close(); result.completeExceptionally(closedFailure()); return; }
                    if (cacheError == null && cached.isPresent() && cached.get().prefix().equals(inputs.prefix)
                            && cached.get().geometry().hash().equals(inputs.geometry.hash())) {
                        result.complete(cached.get()); geometry.close(); prefix.close(); return;
                    }
                    preparePlan(key, priority, inputs, result, List.of(geometry, prefix));
                }, pump);
            }, pump);
            return result.thenApply(v -> v);
        });
    }

    /**
     * Runs the full-width fine cross-section sampler over the opening segments and publishes the
     * aggregate {@link FineHeightIndex} once every segment's samples are resolved. The generation
     * stage reads this index instead of a per-block T0 snapshot, which is the last step of "planning
     * decides the terrain, landing only paints".
     */
    public CompletableFuture<FineHeightIndex> requireFineSamples(Priority priority) {
        if (closed()) return CompletableFuture.failedFuture(closedFailure());
        var aggregate = new CompletableFuture<FineHeightIndex>();
        var all = new ArrayList<WallFinePlanner.FineColumn>();
        /** The subset of {@code all} this run walked or read from the cache; what gets persisted. */
        var walked = new ArrayList<WallFinePlanner.FineColumn>();
        fineSampleWalked = walked;
        var sampledColumns = new java.util.concurrent.atomic.AtomicInteger();
        // Cross-section columns emitted before the cursor collapsed repeats. The gap between this and
        // sampledColumns is the duplicate factor the overlapping cross-sections produce, which is the
        // number that says whether the de-duplication is doing its job on this world.
        var rawColumns = new java.util.concurrent.atomic.AtomicInteger();
        // Sections walked, how many of them carried the full wide band, and how many REFERENCE walks
        // they cost. A measured run emitted 1.35M columns for a corridor of ~8190 blocks, which is far
        // more per section than the 33-column base band implies: this triple is what tells whether the
        // wide arcs cover most route points (base band never applied) or the sampling plan's flank
        // refinement degrades to full resolution. Both are pure "sample less" wins if confirmed.
        var walkedSections = new java.util.concurrent.atomic.AtomicLong();
        var wideSections = new java.util.concurrent.atomic.AtomicLong();
        var probedColumns = new java.util.concurrent.atomic.AtomicLong();
        // Prewarm already plans the opening segments; sample the same window so the two share the
        // lane budget and the fine samples land together with the plans.
        long target = prewarmTargetSegments();
        if (target <= 0) target = STARTUP_PREWARM_SEGMENTS;
        // Final copy for the per-segment estimate lambda: target is reassigned above, so it cannot be
        // captured directly.
        final int projectedSegments = (int) Math.max(1L, target);
        final int totalSegments = (int) Math.min(Integer.MAX_VALUE, target);
        // Fan each segment's point range across the native lanes. One segment is ~2048 points plus the
        // two end aprons, each point a full cross-section of 2 * WallFinePlanner.LATERAL + 1 columns
        // (~230k REFERENCE samples); a single job walked them serially on one lane while
        // every other lane sat idle. The slices are point ranges of the same route, so each lane's
        // oracle samples exactly the columns it would have sampled serially — the merged index is
        // identical, only computed concurrently.
        //
        // 4x the lane count so the queue holds fine-grained work units: a 12-lane session measured
        // 26% idle (9/12 RUNNABLE) because its 12 coarse slices per route (~8k columns each) left
        // the last lane's tail unabsorbed. Smaller slices let the queue work-steal that tail away.
        // A fixed slice count, not lanes * 4. The slice boundaries are where the session-wide claim set
        // hands a column from one cursor to the next, so making the count depend on the lane number made
        // the sampled set depend on how many cores the machine has: the same seed produced a different
        // fine index on a four-lane and a sixteen-lane session. Forty-eight slices still work-steal across
        // any lane count, so the parallel win is unchanged and the result no longer varies by machine.
        int slices = FINE_SAMPLE_SLICES_PER_SEGMENT;
        // Progress is reported in sampled columns. A slice is roughly eight thousand columns, so a
        // slice-counting meter only moves every few thousand columns and reads as a hang. The
        // denominator starts at 1 and grows as slices are enqueued (their point ranges are known
        // before they are sampled), and max() keeps the fraction monotone.
        var expectedColumns = new java.util.concurrent.atomic.AtomicInteger(1);
        // Seeded once from the first segment and projected across the route; see the comment where it is set.
        var expectedSeeded = new java.util.concurrent.atomic.AtomicBoolean();
        // The sampling grid decides how many columns this stage emits, so the meter has to count the
        // same way or the fraction runs away from one. Read from the frozen snapshot: gridStep = 1 -
        // the default - leaves both the sampler and the meter exactly as they were.
        final int gridStep = local.plaincraft.greatwall.config.GreatWallConfig.parameters()
                .fineSampleGridStep();
        // Publish the counters before the driver starts: the driver reports them every 30 s.
        fineSampleDone = sampledColumns;
        fineSampleTotal = expectedColumns;
        fineSampleRaw = rawColumns;
        fineSampleSections = walkedSections;
        fineSampleWideSections = wideSections;
        fineSampleProbed = probedColumns;
        fineSampleStartNanos = System.nanoTime();
        fineSampleLastLogNanos = 0L;
        // A per-call instance, not a field that lives as long as the scheduler: a forced publish from an
        // earlier call used to leave the flag set, and the next call then skipped the completion gates and
        // published an index built from an empty list. This call gets its own flag and its own counters.
        var forcedFlag = new java.util.concurrent.atomic.AtomicBoolean();
        fineSampleForced = forcedFlag;
        runawayLastColumns = 0L;
        runawayLastMisses = 0L;
        runawayLastCheckNanos = 0L;
        runawayIdleChecks = 0;
        // Drive this stage ourselves: without it the fine sample waits on server ticks, which is how
        // a measured session spent 27 minutes here without finishing a single slice.
        startFineSampleDriver();
        // How many times this stage runs in one session, and over how many points.
        //
        // Every counter below (`all`, `walks`, the meters) is method-local, so a second call would
        // re-walk every column from scratch with nothing to show for it - and a measured session walked
        // ~29k cross-sections where the estimate implied ~7.4k, a ratio of 3.9 that has no explanation
        // inside a single call (a cursor cannot walk more indices than its own range). This line is what
        // separates "the stage ran four times" from "one call enqueued four times the slices", because
        // the next run prints the call number next to the segment and slice counts that produced it.
        GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_CALL call={} segments={} slicesPerSegment={}",
                FINE_SAMPLE_CALLS.incrementAndGet(), (int) Math.min(Integer.MAX_VALUE, target),
                Math.max(4, Math.min(laneState.size(), 16) * 4));
        // One memo per session, shared by every slice and every segment: a world column this run has
        // already walked is answered from here instead of walking the terrain a second time. Slices of
        // one segment meet at their boundaries and neighbouring cross-sections round to the same
        // integer columns, so the same column is asked for more than once; the memo turns the repeats
        // into map hits. Concurrent because the slices run on different lanes, and it holds only
        // immutable samples.
        var walks = new java.util.concurrent.ConcurrentHashMap<Long,
                local.plaincraft.greatwall.model.WallTypes.ColumnSample>();
        // One claim per world column for the whole session - the way RoadWeaver dedupes its sample
        // requests before walking them (one key per column, first requester wins). Without it every
        // slice deduped only against itself, so the 64-block overlap between neighbouring slices, and
        // the shared ground between the four segments, was walked three to four times over.
        var claims = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
        var leases = new ArrayList<PlanLease<RouteShape>>();
        // Two counters, not one. A single counter seeded with the segment count completed the
        // index after the first few slices (it was decremented once per slice, not per segment),
        // so the compile received roughly a twelfth of the sampled columns.
        // Seeded at zero and incremented per *enqueued* segment, never with the configured target: the
        // loop below stops at the first segment the frozen envelope cannot hold, and a counter seeded
        // with the target would then never reach zero - the aggregate would never complete, the driver
        // below would never be stopped and the startup would sit in FINE_SAMPLE forever.
        var routesPending = new java.util.concurrent.atomic.AtomicInteger();
        var slicesPending = new java.util.concurrent.atomic.AtomicInteger();
        // Published so the driver's stall test can tell "nothing is running and work is outstanding"
        // from "everything is busy and merely slow".
        fineSampleRoutesPending = routesPending;
        fineSampleSlicesPending = slicesPending;
        runawayStallChecks = 0;
        // While the enqueue loop below is still running, the counters cannot speak for the segments
        // that have not been handed to a lane yet: the first route's callback may resolve (and
        // decrement routesPending to zero) before the loop reaches the second segment, and the index
        // would then be published missing the columns of every later segment.
        var enqueueing = new java.util.concurrent.atomic.AtomicBoolean(true);
        // The body below publishes an index, writes the cache file and prints the stage summary. Five
        // call sites drive this callback, and once the runaway guard forces a publish every later call
        // used to re-run all of it: a measured session printed six FINE_SAMPLE_DONE lines, rebuilt the
        // index five extra times and rewrote the cache file five extra times. After the gates, so an
        // early return from a half-drained state does not consume the one chance.
        var published = new java.util.concurrent.atomic.AtomicBoolean(false);
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.PREWARM, 0, 1);
        var completion = new Runnable() {
            @Override public void run() {
                // The runaway guard publishes the index while slices are still in flight, so it skips both
                // gates: what has been merged so far is a superset of the columns it needs (the excess work
                // is re-walking columns the index already holds), and waiting for `pending` to drain is
                // exactly what never happened in the measured session.
                // This call's own flag, not the field: a later call replaces the field, and a completion
                // reading the field would then see that call's force state instead of its own.
                if (!forcedFlag.get()) {
                    if (enqueueing.get()) return;
                    if (routesPending.get() > 0 || slicesPending.get() > 0) return;
                }
                if (!published.compareAndSet(false, true)) return;
                int done = sampledColumns.get();
                // The estimate, not max(done, estimate): clamping the total to the done count made the
                // fraction identically 100% for the whole tail of a run whose estimate ran low, so the
                // card read "435385/435385" for eleven minutes while the sampler passed 1.2M columns.
                // PrewarmProgress caps a running stage at 99% and only the stage transition reports 100.
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.FINE_SAMPLE, Math.min(done, Math.max(1, expectedColumns.get())), expectedColumns.get());
                // Recorded here as well as at PREWARM_COMPLETE: the fine sample is the stage whose
                // cost scales with the cross-section width, and its cache counters are what decide
                // whether a slow run is the terrain walk or the chunk cache.
                // Cost split, so "is the walk or the cache the bottleneck" is readable without dividing
                // by hand: this is the number the slice-on-demand decision (B2) waits on.
                long blockMs = laneDiagnostic("block-ms");
                long sliceMs = laneDiagnostic("slice-ms");
                long constructMs = laneDiagnostic("construct-ms");
                long cellMs = laneDiagnostic("cell-ms");
                long biomeMs = laneDiagnostic("biome-ms");
                long surfaceMs = laneDiagnostic("surface-ms");
                long walkMs = blockMs + sliceMs + constructMs + cellMs + biomeMs + surfaceMs;
                GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_DONE columns={} raw={} repeatedPct={} "
                                + "expectedColumns={} chunkCacheHits={} chunkCacheMisses={} lanes={} "
                                + "walkedBlocks={} blocksPerColumn={} leadRetries={} retryPct={} "
                                + "blockMs={} sliceMs={} constructMs={} cellMs={} biomeMs={} surfaceMs={} "
                                + "blockPct={} slicePct={} walkMs={} msPerColumn={} "
                                + "surfaceMemoHits={} sections={} wideSections={} widePct={} "
                                + "columnsPerSection={} probesPerSection={} probesPerColumn={}",
                        done, rawColumns.get(),
                        rawColumns.get() == 0 ? 0 : Math.round(100f * (rawColumns.get() - done) / rawColumns.get()),
                        expectedColumns.get(),
                        laneDiagnostic("chunk-cache-hits"), laneDiagnostic("chunk-cache-misses"),
                        laneState.size(),
                        laneDiagnostic("walked-blocks"),
                        done == 0 ? 0 : laneDiagnostic("walked-blocks") / done,
                        laneDiagnostic("lead-retries"),
                        done == 0 ? 0 : Math.round(100f * laneDiagnostic("lead-retries") / done),
                        blockMs, sliceMs, constructMs, cellMs, biomeMs, surfaceMs,
                        percentOf(blockMs, walkMs), percentOf(sliceMs, walkMs), walkMs,
                        done == 0 ? 0 : walkMs / done,
                        laneDiagnostic("surface-memo-hits"),
                        walkedSections.get(), wideSections.get(),
                        walkedSections.get() == 0 ? 0
                                : Math.round(100f * wideSections.get() / walkedSections.get()),
                        walkedSections.get() == 0 ? 0 : rawColumns.get() / walkedSections.get(),
                        walkedSections.get() == 0 ? 0 : probedColumns.get() / walkedSections.get(),
                        done == 0 ? 0 : probedColumns.get() / done);
                persistFineSamples(walked);
                synchronized (all) {
                    // Distinct (x, z) columns actually sampled, next to the emission count: the gap between
                    // this and `columns` is the real duplication factor, which `repeatedPct` cannot see
                    // (that one only compares a single cursor's emissions before and after its own collapse,
                    // so cross-slice repeats read as zero).
                    java.util.HashSet<Long> distinct = new java.util.HashSet<>(Math.max(16, all.size() * 2));
                    for (var column : all) {
                        distinct.add(((long) column.x() << 32) ^ (column.z() & 0xffffffffL));
                    }
                    GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_COLUMNS emitted={} distinct={} "
                                    + "duplicatePct={} sections={}",
                            all.size(), distinct.size(),
                            all.isEmpty() ? 0 : Math.round(100f * (all.size() - distinct.size()) / all.size()),
                            walkedSections.get());
                    aggregate.complete(FineHeightIndex.of(all));
                }
            }
        };
        // Published for the runaway guard; cleared on the way out so a guard that fires twice is a no-op.
        fineSampleAbandon = completion;
        try {
            for (long i = 0; i < target && safeSegment(i); i++) {
                var lease = requireRoute(i, priority);
                leases.add(lease);
                // Counted before the callback is registered, because only the segments this loop
                // actually hands to a lane can ever report back.
                routesPending.incrementAndGet();
                long segment = i;
                // Denominator, whole wall, before a single column is sampled.
                //
                // The meter used to add each segment's estimate only once that segment's *prefix* index
                // had resolved - and a prefix can take minutes. A four-segment wall therefore read
                // "columns=126638 expected=31860" (402%) for most of the stage: one segment's worth of
                // denominator against four segments of sampling, which looks exactly like a runaway and
                // is the reason this stage kept being reported as "sampling way too much". The route
                // point count, unlike the prefix, is known as soon as the route is, and the full lateral
                // band is an upper bound, so this total is complete from the first sample and can only
                // over-state - which the 99% cap in PrewarmProgress handles correctly.
                lease.future().thenAcceptAsync(route -> {
                    if (route == null) return;
                    // The denominator follows the truncated range too, otherwise the card would stop
                    // short of 100% on exactly the segments the cut applies to.
                    long points = Math.max(1L, terminalPointLimit(route, route.points().size())
                            + 2L * WallFinePlanner.APRON_BLOCKS);
                    // The number that explains this stage's cost: the route length this segment carries.
                    // Four segments of an 8,190-block wall are four windows of it, so their point counts
                    // should sum to the wall length. If every segment reports the whole wall, the wall is
                    // sampled once per segment and that multiplier is the thing to remove.
                    GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_SEGMENT segment={} routePoints={} "
                                    + "sectionsExpected={} columnsExpected={}",
                            segment, route.points().size(),
                            (int) (points / Math.max(1, gridStep)),
                            (int) Math.min(Integer.MAX_VALUE - 1L, (points / Math.max(1, gridStep))
                                    * (2L * (WallFinePlanner.LATERAL / Math.max(1, gridStep)) + 1L)));
                    // BASE_LATERAL, not LATERAL: only the fort and gate points carry the wide band, so
                    // seeding with the wide half-width made the total 2.1x too large and the card stalled
                    // near half at completion. The base band is what most cross-sections emit.
                    // Base band plus the share of sections that carry the wide band. Seeding with the
                    // base band alone undershot by 1.6x on a measured run (73,985 against 119,305 columns)
                    // because about a quarter of the sections are wide - 17 columns each against 53. The
                    // share is measured, not derived: widePct sat at 24-25% across every run so far.
                    long baseWidth = 2L * (WallFinePlanner.BASE_LATERAL / Math.max(1, gridStep)) + 1L;
                    long wideWidth = 2L * (WallFinePlanner.LATERAL / Math.max(1, gridStep)) + 1L;
                    long perPoint = (3L * baseWidth + wideWidth) / 4L;
                    long estimated = (points / Math.max(1, gridStep)) * perPoint;
                    // Project the whole route, do not accumulate it. Adding one segment at a time made
                    // the denominator climb while the bar was already moving - 28289, then 84865, then
                    // 113153 - which reads as the sample getting longer the longer it runs. Segments are
                    // the same length, so one segment's count times the number of segments is the right
                    // denominator from the first one; later segments only raise it if they genuinely are
                    // longer.
                    int projected = (int) Math.min(Integer.MAX_VALUE - 1L, estimated * projectedSegments);
                    if (expectedSeeded.compareAndSet(false, true)) {
                        expectedColumns.set(projected);
                    } else {
                        expectedColumns.updateAndGet(current -> Math.max(current, projected));
                    }
                }, pump);
                lease.future().whenCompleteAsync((route, error) -> {
                    if (error != null) {
                        aggregate.completeExceptionally(error);
                        if (routesPending.decrementAndGet() == 0) completion.run();
                        return;
                    }
                    if (closed()) {
                        aggregate.completeExceptionally(closedFailure());
                        if (routesPending.decrementAndGet() == 0) completion.run();
                        return;
                    }
                    // The wide arcs sit at absolute stations, so the segment's starting arc has to be
                    // resolved before its cross-sections can be sized. The prefix index answers from its
                    // own cache and its lease closes to a no-op.
                    // Sampling waits for this segment's geometry, because only the geometry knows where the
                    // wall stops: a measured run cut nothing at all (121,120 columns, unchanged) because
                    // the slices were created before any geometry existed and terminalPointLimit fell back
                    // to "sample the whole route" every time. The wait is bounded - a geometry that never
                    // completes must not stop the sample, so the timeout falls back to the full route.
                    CompletableFuture<Void> geometryReady = CompletableFuture.<Void>completedFuture(null);
                    var flight = geometryFlights.get(route.key());
                    if (flight != null) {
                        geometryReady = flight.thenApply(ignored -> (Void) null)
                                .exceptionally(failed -> null)
                                .completeOnTimeout(null, 120, java.util.concurrent.TimeUnit.SECONDS);
                    }
                    geometryReady.thenComposeAsync(ignored -> prefixes.requireBefore(segment).future(), pump)
                            .whenCompleteAsync((prefix, prefixError) -> {
                    try {
                        if (prefixError != null) { aggregate.completeExceptionally(prefixError); return; }
                        var wideAt = widePoints(route, prefix.lengthBeforeQ8());
                        int points = route.points().size();
                        // Past the terminal there is no wall, so sampling there buys nothing. The cut is
                        // applied only when the geometry has already settled for this segment - the
                        // sample is pipelined with the prewarm, so an early segment often has none yet -
                        // and a segment without a terminal is sampled whole, exactly as before.
                        points = terminalPointLimit(route, points);
                        int perSlice = Math.max(1, (points + slices - 1) / slices);
                        for (int s = 0; s < slices; s++) {
                            int from = s * perSlice;
                            if (from >= points) break;
                            int to = Math.min(points, from + perSlice);
                            slicesPending.incrementAndGet();
                            // The denominator is seeded once per segment in the route callback above, from
                            // the whole route's point count. Accumulating a second estimate here as well
                            // would double it, and accumulating *only* here is what made the card show one
                            // segment's total while four were sampling (402%). The per-point width split
                            // this used to apply (wide vs base band) is bounded by the LATERAL band the
                            // seed assumes, so the seed is an upper bound and never a lie about being done.
                            var job = new FineSampleJob(route, from, to, priority,
                                    new CompletableFuture<List<WallFinePlanner.FineColumn>>(), fineSampleCache,
                                    wideAt, walks, claims);
                            enqueue(job);
                            job.result.whenCompleteAsync((columns, sampleError) -> {
                                try {
                                    if (sampleError != null) { aggregate.completeExceptionally(sampleError); return; }
                                    synchronized (all) {
                                        all.addAll(columns);
                                        walked.addAll(job.probed());
                                        sampledColumns.addAndGet(columns.size());
                                        rawColumns.addAndGet(job.emittedRaw());
                                        walkedSections.addAndGet(job.sections());
                                        wideSections.addAndGet(job.wideSections());
                                        probedColumns.addAndGet(job.probedColumns());
                                    }
                                    int done = sampledColumns.get();
                                    local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                                            local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.FINE_SAMPLE,
                                            done, expectedColumns.get());
                                    slicesPending.decrementAndGet();
                                    completion.run();
                                } catch (Throwable t) { aggregate.completeExceptionally(t); }
                            }, pump);
                        }
                    } catch (Throwable t) {
                        aggregate.completeExceptionally(t);
                    } finally {
                        // The index may only complete once every route has resolved AND every slice it
                        // spawned has finished; either counter alone can hit zero too early.
                        if (routesPending.decrementAndGet() == 0) completion.run();
                    }
                    }, pump);
                }, pump);
            }
        } catch (Throwable t) {
            // Returning without a completed aggregate here would leave the driver from above ticking
            // against a queue that no longer receives fine-sample work: the same permanent FINE_SAMPLE
            // hang, reached by a different route.
            aggregate.completeExceptionally(t);
        } finally {
            enqueueing.set(false);
        }
        // Publishes the index when the loop enqueued nothing at all (no safe segment, an empty
        // window); otherwise the callbacks above decide, and this call returns early.
        completion.run();
        aggregate.whenComplete((v, e) -> {
            // Stop the driver first: the stage is over on both paths, and a timer that keeps granting
            // credits would spin against a queue that no longer holds fine-sample work.
            stopFineSampleDriver();
            leases.forEach(PlanLease::close);
        });
        return aggregate;
    }

    /**
     * Writes the columns this session resolved so the next session can skip the terrain walks. The
     * verdict is logged either way: the hit ratio is what separates a slow stage that is walking
     * terrain from one that is re-reading a file.
     */
    private void persistFineSamples(List<WallFinePlanner.FineColumn> walked) {
        var cache = fineSampleCache;
        GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_CACHE hits={} misses={} cached={} walked={} persistent={}",
                cache.hits(), cache.misses(), cache.size(), walked.size(), cache.persistent());
        if (!cache.persistent() || walked.isEmpty()) return;
        var entries = new ArrayList<local.plaincraft.greatwall.terrain.FineSampleCache.Entry>(walked.size());
        for (var column : walked) {
            entries.add(new local.plaincraft.greatwall.terrain.FineSampleCache.Entry(
                    column.x(), column.z(), column.sample()));
        }
        cache.store(entries);
    }

    public PlanLease<PlanWindow> requireWindow(ChunkKey chunk, int marginBlocks, Priority priority) {
        if (closed()) throw closedFailure();
        if (marginBlocks < 0 || marginBlocks > 512) throw new IllegalArgumentException("window margin 0..512");
        if (!chunk.dimension().equals(recipe.dimension())) {
            return new PlanLease<>(UUID.randomUUID(), CompletableFuture.completedFuture(new PlanWindow(List.of())), () -> {});
        }
        int x0 = Math.toIntExact(Math.multiplyExact((long) chunk.x(), 16));
        int z0 = Math.toIntExact(Math.multiplyExact((long) chunk.z(), 16));
        long minU = Long.MAX_VALUE, maxU = Long.MIN_VALUE, minB = Long.MAX_VALUE, maxB = Long.MIN_VALUE;
        for (int dx : new int[] {0, 15}) for (int dz : new int[] {0, 15}) {
            int x = Math.addExact(x0, dx), z = Math.addExact(z0, dz);
            long u = Projection.axisUQ8(recipe.axis(), x, z);
            minU = Math.min(minU, u); maxU = Math.max(maxU, u);
            long side = Math.addExact(-Math.multiplyExact((long) x - recipe.axis().origin().x(), recipe.axis().ezQ30()),
                    Math.multiplyExact((long) z - recipe.axis().origin().z(), recipe.axis().exQ30()));
            long b = FixedMath.roundDiv(side, 1L << 22);
            minB = Math.min(minB, b); maxB = Math.max(maxB, b);
        }
        long lateral = (512L + 512 + 96 + marginBlocks) * 256;
        if (minB > lateral || maxB < -lateral || maxU + (96L + marginBlocks) * 256 < 0) {
            return new PlanLease<>(UUID.randomUUID(), CompletableFuture.completedFuture(new PlanWindow(List.of())), () -> {});
        }
        long first = Math.max(0, Projection.segmentAt(minU - (96L + marginBlocks) * 256));
        long last = Math.max(0, Projection.segmentAt(maxU + (96L + marginBlocks) * 256));
        if (last - first >= 5) throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "window requires more than five segments");
        var leases = new ArrayList<PlanLease<SegmentPlan>>();
        CompletableFuture<List<SegmentPlan>> chain = CompletableFuture.completedFuture(List.of());
        try {
            for (long i = first; i <= last; i++) {
                if (!safeSegment(i)) break;
                var lease = requirePlan(i, priority); leases.add(lease);
                chain = chain.thenCombineAsync(lease.future(), (list, plan) -> {
                    var copy = new ArrayList<>(list); copy.add(plan); return List.copyOf(copy);
                }, pump);
            }
        } catch (Throwable failure) {
            leases.forEach(PlanLease::close);
            return new PlanLease<>(UUID.randomUUID(), CompletableFuture.failedFuture(failure), () -> {});
        }
        return outer(chain.thenApply(list -> new PlanWindow(list.stream().filter(p -> p.prefix().terminal().isEmpty()
                || p.prefix().terminal().get().segment() >= p.geometry().key().index()).toList())), () -> leases.forEach(PlanLease::close));
    }

    /**
     * Starts the scheduling pump in <em>demand-only</em> mode: the pump runs so that on-demand
     * planning (which optional integrations depend on) makes progress, but the bulk prewarm of the
     * first segments is deliberately not enqueued.
     *
     * <p>This split exists to break a startup cycle. RoadWeaver blocks inside
     * {@code MinecraftServer.prepareLevels} and asks the wall for its recipe and plans while it does
     * so. If the wall were to spend that window running its own bulk prewarm, both sides would wait
     * on each other. Demand-only startup satisfies every dependency the other side needs without
     * competing for the same native lanes, and {@link #afterRoadBulk()} starts the bulk once the
     * other side has finished.
     */
    public void startupPump() {
        if (closed()) throw closedFailure();
        if (state.putIfAbsent("startup", 1L) != null) return;
        state.put("demand-only", 1L);
        pump.execute(() -> {
            startupTimer = pump.scheduleWithFixedDelay(() -> {
                state.merge("startup-pump-iterations", 1L, Long::sum);
                credits = 4096; deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25); dispatch();
            }, 0, 1, TimeUnit.MILLISECONDS);
            // Demand-only: readiness means "the pump is running", not "the first segments are done".
            // Spawn preparation can still require a segment, and it is served from this pump.
            state.put("startup-ready", 1L);
            // Take over as a self-driving pump the moment server ticks stop arriving,
            // so the server thread and this scheduler can never starve each other.
            startWatchdog();
        });
    }

    /**
     * Submits to the pump, reporting a rejection instead of throwing it at the caller.
     *
     * <p>{@code afterRoadBulk} checks {@code closed()} and then submits, and those two steps are not atomic:
     * a close in between raises {@link java.util.concurrent.RejectedExecutionException}. Its only remaining
     * caller is a {@code whenComplete} whose returned future is discarded, where that exception vanishes and
     * leaves the startup stage waiting for a prewarm that was never queued - the exact shape of a measured
     * session that logged no {@code PREWARM_BEGIN} at all and then parked the world for thirty minutes.
     * Every other pump submission in this class already maps a rejection onto a failed job; this one now
     * does the same.
     */
    private boolean pumpSubmit(Runnable task) {
        try {
            pump.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            GreatWallMod.LOGGER.error("[GreatWall:S1C] PREWARM_NOT_QUEUED reason=pump-closed; the wall does "
                    + "not prewarm in this session and the startup stage is released");
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
            return false;
        }
    }

    /**
     * Enqueues the bulk prewarm of the opening segments. Call only after any blocking startup peer
     * has finished, so the two never contend for the same native lanes.
     *
     * <p>The count comes from {@link GreatWallConfig#prewarmSegmentsTarget()}: an unbounded wall
     * prewarms only its opening segments, while a bounded wall short enough to finish prewarms
     * every segment it will ever build. That is what makes "start the game with the wall already
     * planned" achievable without ever waiting on an unbounded amount of work.
     */
    public void afterRoadBulk() {
        if (closed()) {
            // A planning failure closed this session earlier. This entry point is reached from the
            // server tick (MinecraftServer.prepareLevels TAIL -> WorldServices.afterRoadReached), and
            // throwing here took the whole game down with a failure it had already reported and
            // handled: a measured session crashed at 23:43 with
            // "PlannerScheduler.closedFailure <- afterRoadBulk <- ProductionSession.afterRoadReached".
            // The wall simply does not get its bulk prewarm; the loading overlay is told so that
            // nothing waits for a prewarm that will never start, and the worldgen gate opens because a
            // wall that cannot paint must not hold the roads behind it.
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PREWARM_SKIPPED reason=planner-closed");
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
            return;
        }
        if (state.putIfAbsent("after-road-bulk", 1L) != null) return;
        pumpSubmit(() -> {
            // This body owns the startup gates: only a failure originating here may release them.
            ((Pump) pump).enterPrewarmBody();
            long target = prewarmTargetSegments();
            long started = System.nanoTime();
            state.put("prewarm-target-segments", target);
            state.put("prewarm-start-nanos", started);
            // Published for two consumers: the loading overlay, which draws this as a card
            // during level loading, and a startup peer (RoadWeaver) that waits for the
            // prewarm to finish before it begins its own road generation.
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.begin(
                    (int) Math.min(Integer.MAX_VALUE, target), laneState.size());
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.enterPlanning();
            var completedSegments = new java.util.concurrent.atomic.AtomicInteger();
            var prewarm = new ArrayList<PlanLease<SegmentPlan>>();
            for (long i = 0; i < target && safeSegment(i); i++) {
                // Per-segment timing is logged individually: an operator measuring
                // how long one segment takes needs the segments separated, and a
                // single total would hide whether the cost is front-loaded.
                long segmentStarted = System.nanoTime();
                var lease = requirePlan(i, Priority.PLAYER_PREFETCH);
                long segment = i;
                lease.future().whenCompleteAsync((plan, error) -> {
                    long elapsedMs = Math.round((System.nanoTime() - segmentStarted) / 1_000_000.0D);
                    if (error == null) {
                        // Counted even when a segment fails: the card shows how much of the
                        // prewarm is behind us, and a failed segment is behind us.
                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setCompleted(
                                completedSegments.incrementAndGet());
                        // stations/terminal are the two numbers that decide whether the fine
                        // sample and the compile coverage agree. Without them a session cannot
                        // tell "the plan stopped at the coast" from "the plan covers the whole
                        // route", and those need opposite fixes.
                        var segmentPlan = plan;
                        long terminalSQ8 = segmentPlan == null ? -1L
                                : segmentPlan.geometry().terminal().map(terminal -> terminal.localSQ8()).orElse(-1L);
                        int stationCount = segmentPlan == null ? 0 : segmentPlan.stations().stations().size();
                        int siteCount = segmentPlan == null ? 0 : segmentPlan.sites().size();
                        GreatWallMod.LOGGER.info("[GreatWall:S1C] PREWARM_SEGMENT segment={} elapsedMs={} "
                                        + "stations={} sites={} terminalSQ8={} routeSQ8={}",
                                segment, elapsedMs, stationCount, siteCount, terminalSQ8,
                                segmentPlan == null ? -1L : segmentPlan.geometry().localSQ8().getLast());
                    } else {
                        GreatWallMod.LOGGER.warn("[GreatWall:S1C] PREWARM_SEGMENT_FAILED segment={} elapsedMs={} reason={}",
                                segment, elapsedMs, error.toString());
                    }
                }, pump);
                prewarm.add(lease);
            }
            state.put("prewarm-requested-segments", (long) prewarm.size());
            GreatWallMod.LOGGER.info("[GreatWall:S1C] PREWARM_BEGIN requested={} target={}",
                    prewarm.size(), target);
            // A dedicated driver for the duration of the prewarm.
            //
            // Until now the only drives were the server tick and the stall watchdog. The
            // startup timer that originally filled this role is cancelled by the first tick
            // (see tick()), so once the server is ticking the prewarm advances only as often
            // as ticks arrive - and a measured session recorded 257 watchdog rescues, meaning
            // the tick stopped arriving for three seconds at a time, repeatedly, while a
            // startup peer held the server thread.
            //
            // The prewarm is bounded work with a known end, and nothing else in this mod
            // competes for the native lanes while it runs, so it can drive itself. The timer
            // is cancelled when the prewarm settles, on either outcome.
            prewarmTimer = pump.scheduleWithFixedDelay(() -> {
                if (closed() || state.containsKey("bulk-ready") || state.containsKey("bulk-failed")) return;
                state.merge("prewarm-driver-iterations", 1L, Long::sum);
                credits = 4096;
                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25);
                dispatch();
            }, 0, 1, TimeUnit.MILLISECONDS);
            CompletableFuture.allOf(prewarm.stream().map(PlanLease::future).toArray(CompletableFuture[]::new))
                    .whenCompleteAsync((value, error) -> {
                        long totalMs = Math.round((System.nanoTime() - started) / 1_000_000.0D);
                        // Stop the dedicated driver first: the prewarm is over on both paths,
                        // and a timer that keeps granting credits would spin against a queue
                        // that no longer has prewarm work in it.
                        stopPrewarmTimer();
                        // The bulk prewarm (segment sampling) is done. Release the startup-peer
                        // latch NOW: blockAndPumpUntilPrewarmSettled parks the server thread on
                        // this latch inside managedBlock, and the chunk compile that still follows
                        // runs from the server tick loop - which cannot fire until that managedBlock
                        // returns. Signalling only at publishPlanningWindow (the previous shape)
                        // therefore deadlocked: managedBlock waits for the latch, the latch waits
                        // for the compile, and the compile waits for a tick that managedBlock is
                        // blocking. signalReady() releases the latch WITHOUT closing the loading
                        // card, so the compile runs on the first post-startup ticks while the card
                        // stays open; publishPlanningWindow later closes it via finish().
                        // Do NOT call PrewarmProgress.finish here - that would close the card at
                        // 100% while the compile is still running.
                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.signalReady();
                        if (error == null) {
                            // Pinned, not closed: the compile reuses these very plans, and closing
                            // them here would let the memory budget evict them long before the
                            // compile runs. Released by releasePrewarmLeases()/close().
                            prewarmLeases.addAll(prewarm);
                        } else {
                            prewarm.forEach(PlanLease::close);
                        }
                        state.put(error == null ? "bulk-ready" : "bulk-failed", 1L);
                        state.put("prewarm-total-millis", totalMs);
                        // The compile must be able to run while somebody else holds the server
                        // thread. A startup peer (RoadWeaver) parks that thread in its own
                        // managedBlock waiting for this wall to publish, and the tick loop cannot
                        // fire while it is parked - so a compile that only waits for a tick would
                        // wait for the peer, and the peer would wait for the compile. The session
                        // registers a hook here that posts the compile onto the server queue; the
                        // peer's managedBlock pump executes exactly those tasks.
                        if (error == null) {
                            var hook = bulkReadyHook;
                            if (hook != null) {
                                try {
                                    hook.run();
                                } catch (Throwable hookFailure) {
                                    GreatWallMod.LOGGER.warn("[GreatWall:S1C] BULK_READY_HOOK_FAILED",
                                            hookFailure);
                                }
                            }
                        }
                        if (error == null) {
                            // The concurrency numbers are included so one session answers whether
                            // the hardware-derived limits actually took effect. Comparing this
                            // line against the previous 22.2-minute run is the point of the
                            // change, and without the limits recorded here a slower run would
                            // not say whether the scheduler or the terrain was responsible.
                            GreatWallMod.LOGGER.info("[GreatWall:S1C] PREWARM_COMPLETE segments={} totalMs={} "
                                            + "avgMsPerSegment={} geometryInflight={} lanes={} "
                                            + "chunkCacheHits={} chunkCacheMisses={}",
                                    prewarm.size(), totalMs,
                                    prewarm.isEmpty() ? 0L : totalMs / prewarm.size(),
                                    recipe.config().maxGeometryInflight(), laneState.size(),
                                    laneDiagnostic("chunk-cache-hits"), laneDiagnostic("chunk-cache-misses"));
                        } else {
                            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PREWARM_FAILED segments={} totalMs={} reason={}",
                                    prewarm.size(), totalMs, error.toString());
                        }
                        if (error != null && startupTimer != null) startupTimer.cancel(false);
                    }, pump);
        });
    }

    /**
     * Stops the prewarm's dedicated driver, if one is running.
     *
     * <p>Separate from the prewarm's own completion callback so {@link #close()} also
     * releases it: a session that ends mid-prewarm must not leave a timer holding the
     * scheduler's executor open.
     */
    private void stopPrewarmTimer() {
        var timer = prewarmTimer;
        prewarmTimer = null;
        if (timer != null) timer.cancel(false);
    }

    /**
     * The scheduler's pump, with a safety net for tasks whose failure would otherwise vanish.
     *
     * <h2>Why this exists</h2>
     * Every task handed to {@code execute} is wrapped in a {@link java.util.concurrent.FutureTask},
     * and {@code FutureTask.run()} hands a Throwable to {@code setException} instead of letting it
     * reach the thread's uncaught-exception handler - deliberately, and nothing in this class ever
     * reads that result. A task that throws therefore leaves whatever it was supposed to release
     * unreleased. The bulk prewarm body is the one that matters: it starts the loading card, drives
     * the prewarm and signals the startup peer, so a throw before its release chain is registered
     * leaves {@code PrewarmProgress} open forever - the card never closes, the worldgen gate never
     * opens, and a peer that joins on that gate without a timeout (RoadWeaver's
     * {@code InitialGenManager.awaitGreatWallPrewarm}) parks the server thread on the loading screen
     * with no failure and no log line. Reading the task's result here turns that silent hang into a
     * named release.
     *
     * <p>Only {@code afterExecute} is overridden, and only the already-done future is read, so this
     * costs one instanceof and one non-blocking {@code get()} per task.
     */
    private static final class Pump extends ScheduledThreadPoolExecutor {
        /**
         * Whether the task that just ran is the one that holds the startup gates.
         *
         * <p>The pump is single-threaded and {@code afterExecute} runs on that same thread straight after
         * the task, so a flag set by the task body is still visible here - and still visible when the body
         * threw, which is exactly the case that matters. It is removed in {@code afterExecute} so the next
         * task never inherits it.
         */
        private final ThreadLocal<Boolean> inPrewarmBody = ThreadLocal.withInitial(() -> Boolean.FALSE);

        Pump(ThreadFactory factory) { super(1, factory); }

        /** Marks the current pump task as the startup-gate holder. First statement of that body. */
        void enterPrewarmBody() { inPrewarmBody.set(Boolean.TRUE); }

        @Override protected void afterExecute(Runnable task, Throwable failure) {
            super.afterExecute(task, failure);
            boolean gateHolder = Boolean.TRUE.equals(inPrewarmBody.get());
            inPrewarmBody.remove();
            Throwable actual = failure;
            if (actual == null && task instanceof java.util.concurrent.FutureTask<?> future && future.isDone()) {
                try {
                    future.get();
                } catch (java.util.concurrent.CancellationException expected) {
                    return;
                } catch (java.util.concurrent.ExecutionException failed) {
                    actual = failed.getCause();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (actual == null) return;
            if (!gateHolder) {
                // A bookkeeping task failed - a priority promotion, an admission, a progress report. The
                // prewarm it was bookkeeping for is still running and can still finish, so releasing the
                // gates here is what let RoadWeaver start laying roads while the wall had sampled 7.8% of
                // its columns. Count it and carry on; the gate has exactly one owner (below).
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] PUMP_TASK_FAILED_GATE_KEPT reason={} - the startup "
                        + "gates stay closed; this task does not own them.", actual.toString());
                return;
            }
            GreatWallMod.LOGGER.error("[GreatWall:S1C] PUMP_TASK_FAILED the wall releases its startup gates; "
                    + "reason={}", actual.toString(), actual);
            // Idempotent, and the only release that every waiter on this session hangs off: the loading
            // card, the startup peer's latch and the worldgen gate all clear here.
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
        }
    }

    /**
     * Starts the fine sample's dedicated driver. Idempotent: the fine sample is requested once per
     * session, but a retry must not stack timers.
     */
    private void startFineSampleDriver() {
        if (fineSampleTimer != null || closed()) return;
        fineSampleTimer = pump.scheduleWithFixedDelay(() -> {
            if (closed() || fineSampleTimer == null) return;
            reportFineSampleProgress();
            credits = 4096;
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25);
            dispatch();
        }, 0, GreatWallConfig.get().fineSampleDriverIntervalMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * Publishes the index early when the fine sample runs wildly past its own estimate.
     *
     * <p>Called once per driver tick (about a millisecond), so it reads two atomics and returns.
     * {@return true when the stage was abandoned, in which case the caller stops reporting}
     */
    private boolean checkFineSampleRunaway() {
        var abandon = fineSampleAbandon;
        var done = fineSampleDone;
        var total = fineSampleTotal;
        if (abandon == null || done == null || total == null) return false;
        long expected = total.get();
        long columns = done.get();
        // expected starts at 1 while the first slices are still being enqueued; comparing against it
        // would fire on the first thousand columns of every run.
        if (expected >= FINE_SAMPLE_RUNAWAY_MIN_EXPECTED
                && columns > FINE_SAMPLE_RUNAWAY_NUMERATOR * expected) {
            return abandonFineSample(abandon, columns, expected, "ratio");
        }
        // The semantic test, and the one that actually catches this: a runaway keeps producing columns
        // while it stops producing terrain. Every column in a healthy pass either builds a chunk cell
        // this run has not sampled yet or is answered by one; once no NEW cell appears for several
        // windows in a row while columns keep pouring in, every one of them is being answered from a
        // cell already in the cache - the stage is re-walking its own work, and no amount of waiting
        // finishes it. The measured session ran at 4.3k columns/s with a chunk-cache hit rate above 85%.
        long now = System.nanoTime();
        if (now - runawayLastCheckNanos < FINE_SAMPLE_RUNAWAY_WINDOW_NANOS) return false;
        runawayLastCheckNanos = now;
        long misses = laneDiagnostic("chunk-cache-misses");
        long hits = laneDiagnostic("chunk-cache-hits");
        if (hits + misses == 0L) {
            // No oracle lane published cache counters, so "no new terrain" cannot be judged from them.
            // Disabling the test is the honest answer: it is better to fall back to the ratio bound than
            // to declare a runaway because the counter happened to read zero.
            runawayIdleChecks = 0;
            return false;
        }
        long columnDelta = columns - runawayLastColumns;
        long missDelta = Math.max(0L, misses - runawayLastMisses);
        runawayLastColumns = columns;
        runawayLastMisses = misses;
        // Liveness first: if nothing is running, nothing is outstanding and no column moved, the stage
        // is hung, not slow. Judged on lane idleness rather than on the counter, because a healthy
        // stage can also spend a window without producing a column.
        if (columnDelta == 0L && outstandingFineSampleWork() && lanesIdle()) {
            if (++runawayStallChecks >= FINE_SAMPLE_STALL_WINDOWS) {
                return abandonFineSample(abandon, columns, expected, "stalled=" + runawayStallChecks);
            }
        } else {
            runawayStallChecks = 0;
        }
        if (columnDelta < FINE_SAMPLE_RUNAWAY_MIN_COLUMNS || missDelta > columnDelta / 50L) {
            runawayIdleChecks = 0;   // real terrain is still being discovered
            return false;
        }
        if (++runawayIdleChecks < FINE_SAMPLE_RUNAWAY_IDLE_WINDOWS) return false;
        return abandonFineSample(abandon, columns, expected, "no-new-terrain=" + runawayIdleChecks);
    }

    /**
     * Publishes the fine-sample index early and stops the stage.
     *
     * @param why which test fired, for the log
     * @return always true, so the caller can return it directly
     */
    private boolean abandonFineSample(Runnable abandon, long columns, long expected, String why) {
        // Cleared first: two driver ticks cannot both publish, and a second call reads null and returns.
        fineSampleAbandon = null;
        if (fineSampleForced != null) fineSampleForced.set(true);
        stopFineSampleDriver();
        GreatWallMod.LOGGER.warn("[GreatWall:S1C] FINE_SAMPLE_RUNAWAY reason={} columns={} expected={} ratio={} "
                        + "sections={} - the stage is re-walking columns the index already holds, so the "
                        + "index is published as it stands instead of waiting for a convergence that never comes.",
                why, columns, expected, expected <= 0 ? 0 : columns / expected,
                fineSampleSections == null ? 0L : fineSampleSections.get());
        try {
            abandon.run();
        } catch (RuntimeException alreadyPublished) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] FINE_SAMPLE_RUNAWAY publish failed", alreadyPublished);
        }
        return true;
    }

    /**
     * Logs one fine-sample progress line every {@link #FINE_SAMPLE_REPORT_MILLIS} ms while the stage
     * runs: columns sampled, the stage's own denominator, the rate and an ETA. Called from the fine
     * sample's own driver (once per millisecond), so it must stay cheap when it does not log.
     */
    private void reportFineSampleProgress() {
        if (checkFineSampleRunaway()) return;
        var done = fineSampleDone;
        var total = fineSampleTotal;
        if (done == null || total == null) return;
        long start = fineSampleStartNanos;
        if (start == 0L) return;
        long now = System.nanoTime();
        long last = fineSampleLastLogNanos;
        if (last != 0L && now - last < TimeUnit.MILLISECONDS.toNanos(FINE_SAMPLE_REPORT_MILLIS)) return;
        fineSampleLastLogNanos = now;
        int columns = done.get();
        int expected = total.get();
        var raw = fineSampleRaw;
        var sectionCounter = fineSampleSections;
        var wideCounter = fineSampleWideSections;
        var probeCounter = fineSampleProbed;
        long rawColumns = raw == null ? 0L : raw.get();
        long sections = sectionCounter == null ? 0L : sectionCounter.get();
        long wideSections = wideCounter == null ? 0L : wideCounter.get();
        long probes = probeCounter == null ? 0L : probeCounter.get();
        double seconds = Math.max(0.001, (now - start) / 1_000_000_000.0);
        double rate = columns / seconds;
        String eta = expected > columns && rate > 0.0
                ? String.format(java.util.Locale.ROOT, "%.0f", (expected - columns) / rate)
                : "?";
        GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_PROGRESS columns={} expected={} rate={}/s "
                        + "eta={}s raw={} repeatedPct={} expectedSections={} lanes={} chunkCacheHits={} "
                        + "chunkCacheMisses={} walkedBlocks={} "
                        + "blocksPerColumn={} leadRetries={} retryPct={} blockMs={} sliceMs={} constructMs={} "
                        + "cellMs={} biomeMs={} surfaceMs={} surfaceMemoHits={} sections={} wideSections={} "
                        + "widePct={} columnsPerSection={} probesPerSection={}",
                columns, expected, String.format(java.util.Locale.ROOT, "%.0f", rate), eta,
                // raw vs columns is the duplicate factor the overlapping cross-sections produce, and
                // expectedSections is the meter's own estimate divided by the width it observed: comparing
                // it with the walked `sections` is what tells whether the sampler walks the cross-sections
                // the meter counted, without waiting for the stage to finish.
                rawColumns,
                rawColumns == 0 ? 0 : Math.round(100f * (rawColumns - columns) / rawColumns),
                sections == 0 ? 0 : expected / Math.max(1, rawColumns / sections),
                laneState.size(), laneDiagnostic("chunk-cache-hits"), laneDiagnostic("chunk-cache-misses"),
                laneDiagnostic("walked-blocks"),
                columns == 0 ? 0 : laneDiagnostic("walked-blocks") / columns,
                laneDiagnostic("lead-retries"),
                columns == 0 ? 0 : Math.round(100f * laneDiagnostic("lead-retries") / columns),
                laneDiagnostic("block-ms"), laneDiagnostic("slice-ms"), laneDiagnostic("construct-ms"),
                laneDiagnostic("cell-ms"), laneDiagnostic("biome-ms"), laneDiagnostic("surface-ms"),
                laneDiagnostic("surface-memo-hits"),
                sections, wideSections,
                sections == 0 ? 0 : Math.round(100f * wideSections / sections),
                sections == 0 ? 0 : rawColumns / sections,
                sections == 0 ? 0 : probes / sections);
    }

    /** Stops the fine sample's driver. Idempotent, and safe to call from any completion path. */
    private void stopFineSampleDriver() {
        var timer = fineSampleTimer;
        fineSampleTimer = null;
        if (timer != null) timer.cancel(false);
    }

    /** {@return the share of {@code whole} that {@code part} accounts for, as a whole percent} */
    private static int percentOf(long part, long whole) {
        return whole <= 0L ? 0 : (int) Math.round(100.0D * part / whole);
    }

    /** {@return whether any fine-sample work is still outstanding} */
    private boolean outstandingFineSampleWork() {
        var slices = fineSampleSlicesPending;
        var routes = fineSampleRoutesPending;
        if (slices == null) return false;
        return slices.get() > 0 || (routes != null && routes.get() > 0);
    }

    /** {@return whether every planning lane is idle right now} */
    private boolean lanesIdle() {
        for (var lane : laneState) {
            if (lane.busy || lane.initializing) return false;
        }
        return true;
    }

    /** Sum of one per-lane diagnostic counter across the native lanes. */
    private long laneDiagnostic(String key) {        long total = 0L;
        for (var lane : lanes) total += lane.diagnostics().getOrDefault(key, 0L);
        return total;
    }

    /**
     * The prewarm's leases, still pinned. Empty until the bulk prewarm settles - and empty forever
     * if it failed. The compile consumes these instead of re-requesting the plans, so the work the
     * prewarm already did is the work the compile publishes.
     */
    public java.util.List<PlanLease<SegmentPlan>> prewarmLeases() {
        synchronized (prewarmLeases) {
            return java.util.List.copyOf(prewarmLeases);
        }
    }

    /** Releases the pinned prewarm leases. Idempotent. */
    public void releasePrewarmLeases() {
        java.util.List<PlanLease<SegmentPlan>> copy;
        synchronized (prewarmLeases) {
            copy = java.util.List.copyOf(prewarmLeases);
            prewarmLeases.clear();
        }
        copy.forEach(PlanLease::close);
    }

    /**
     * Segments to prewarm.
     *
     * <p>The configured value wins when set; otherwise a bounded wall at or below
     * the full-prewarm threshold is prewarmed completely and everything else keeps
     * the historical opening-segment count. The config is read here rather than at
     * construction so a Mod Menu edit applies to the next world without a restart.
     */
    /**
     * Cuts a segment's sample range at its terminal.
     *
     * <p>Returns {@code points} unchanged whenever the geometry is not settled yet or the segment has no
     * terminal, so the worst case is the old behaviour - sample the whole route. That is deliberate:
     * over-sampling costs time, under-covering costs a broken compile.
     */
    /** One line per distinct outcome, so a silent "no cut" cannot hide for a whole release again. */
    private static final java.util.Set<String> TERMINAL_CUT_NOTES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void noteTerminalCut(String outcome, long cap, int before, int after) {
        if (!TERMINAL_CUT_NOTES.add(outcome + ":" + (cap < 0 ? "-" : String.valueOf(cap / 256)))) return;
        GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_TERMINAL outcome={} terminalBlock={} pointsBefore={} pointsAfter={}",
                outcome, cap < 0 ? -1 : cap / 256, before, after);
    }

    private int terminalPointLimit(local.plaincraft.greatwall.model.WallTypes.RouteShape route, int points) {
        try {
            var flight = geometryFlights.get(route.key());
            if (flight == null) { noteTerminalCut("no-flight", -1L, points, points); return points; }
            if (!flight.isDone()) { noteTerminalCut("flight-pending", -1L, points, points); return points; }
            if (flight.isCompletedExceptionally()) { noteTerminalCut("flight-failed", -1L, points, points); return points; }
            var geometry = flight.getNow(null);
            if (geometry == null || geometry.terminal().isEmpty()) return points;
            long cap = geometry.terminal().get().localSQ8() + 32L * 256;
            var arcs = route.localSQ8();
            int lo = 0, hi = arcs.size() - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (arcs.get(mid) <= cap) lo = mid; else hi = mid - 1;
            }
            int cut = Math.max(1, Math.min(points, lo + 1));
            noteTerminalCut("cut", cap, points, cut);
            return cut;
        } catch (RuntimeException unsettled) {
            return points;
        }
    }

    private long prewarmTargetSegments() {
        try {
            long configured = GreatWallConfig.parameters().prewarmSegmentsTarget();
            return configured > 0L ? configured : STARTUP_PREWARM_SEGMENTS;
        } catch (RuntimeException | LinkageError unavailable) {
            // A broken client config must not stop planning; fall back to the opening segments.
            state.put("prewarm-config-unavailable", 1L);
            return STARTUP_PREWARM_SEGMENTS;
        }
    }

    public void tick(SliceBudget budget) {
        Objects.requireNonNull(budget);
        if (closed()) return;
        // Only the most recent server budget is retained; a stalled pump cannot queue unbounded ticks.
        state.put("tick-operations", (long) budget.maxOperations()); state.put("tick-deadline", budget.deadlineNanos());
        state.put("last-tick-nanos", System.nanoTime());
        if (state.putIfAbsent("tick-enqueued", 1L) != null) return;
        pump.execute(() -> {
            state.remove("tick-enqueued");
            if (startupTimer != null) {
                startupTimer.cancel(false);
                startupTimer = null;
                state.put("startup-handoff", 1L);
            }
            credits = Math.toIntExact(Math.min(65536, state.getOrDefault("tick-operations", 0L)));
            deadline = state.getOrDefault("tick-deadline", 0L);
            if (state.containsKey("bulk-ready") && !state.containsKey("background-terminal") && backgroundLease == null && safeSegment(background)) {
                backgroundLease = requirePlan(background++, Priority.BACKGROUND);
                var lease = backgroundLease;
                lease.future().whenCompleteAsync((v, e) -> {
                    if (v != null && (v.geometry().terminal().isPresent() || v.prefix().terminal().isPresent())) state.put("background-terminal", 1L);
                    lease.close(); backgroundLease = null;
                }, pump);
            }
            dispatch();
        });
    }

    /**
     * Upper bound on how long the scheduler may go without a server tick before
     * it re-arms its own timer.
     *
     * <h2>Why this exists</h2>
     * {@link #tick} cancels {@code startupTimer} on its first call and takes over
     * as the only source of {@code credits}/{@code deadline}. That is fine while
     * the server thread keeps ticking, but the server thread is also the thread
     * that blocks on a synchronous chunk load. A real session showed the two
     * starving each other: the server waited for a chunk whose features step was
     * parked on this scheduler, while this scheduler waited for a server tick
     * that could not arrive until that chunk resolved. Neither side has a
     * timeout, so the pair stayed stuck for as long as the session lived.
     *
     * <p>The watchdog restores the self-driving pump that {@code startupTimer}
     * originally provided, but only while ticks have actually stopped, so the
     * normal case keeps using the server's own budget rather than competing with
     * it for the native lanes.
     */
    private static final long WATCHDOG_STALL_NANOS =
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                    Math.max(500L, Long.getLong("greatwall.planner.watchdogStallMs", 3_000L)));
    private ScheduledFuture<?> watchdogTimer;

    /**
     * Starts the stall watchdog. Called once the scheduler is installed.
     *
     * <p>Cheap by construction: the task only inspects a timestamp and returns
     * unless ticks have genuinely stopped.
     */
    public void startWatchdog() {
        if (closed()) throw closedFailure();
        if (state.putIfAbsent("watchdog", 1L) != null) return;
        pump.execute(() -> {
            try {
                watchdogTimer = pump.scheduleWithFixedDelay(this::watchdogCheck, 1, 1,
                        java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException stopped) {
                if (!closed()) state.put("watchdog-rejected", 1L);
            }
        });
    }

    private void watchdogCheck() {
        if (closed()) return;
        long last = state.getOrDefault("last-tick-nanos", 0L);
        // Before the first tick the startup timer is still the driver, so there is
        // nothing to rescue yet.
        if (last == 0L) return;
        if (System.nanoTime() - last < WATCHDOG_STALL_NANOS) return;
        // Ticks have stopped. Supply the budget ourselves until they resume.
        state.merge("watchdog-rescues", 1L, Long::sum);
        credits = 4096;
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25);
        dispatch();
    }

    public Map<UUID, JobProgress> progress() { return Map.copyOf(progress); }
    public Map<String, FailureEvidence> failures() { return Map.copyOf(failures); }
    public Map<String, Long> diagnostics() {
        var result = new HashMap<>(state);
        for (var lane : lanes) lane.diagnostics().forEach((key, value) -> result.put("lane-" + lane.index() + "-" + key, value));
        return Map.copyOf(result);
    }
    public CompletableFuture<Void> drain() { return drained.copy(); }

    /**
     * Publishes the resolved corridor-structure obstacle index to the ridge planner. Must be called
     * after the planning-time structure scan settles and before {@link #afterRoadBulk()} starts the
     * bulk prewarm, so the detour stage observes the final index.
     */
    public void setObstacles(StructureObstacleIndex index) {
        ridge.setObstacles(index);
    }

    public SegmentGeometry computeGeometryNow(long segment, NaturalTerrainOracle diagnosticOracle) {
        key(segment);
        if (!diagnosticOracle.supportsSynchronousDiagnostics()) {
            throw new IllegalStateException("synchronous geometry is restricted to explicitly supplied analytic fixtures");
        }
        String thread = Thread.currentThread().getName().toLowerCase(java.util.Locale.ROOT);
        if (thread.contains("worldgen") || thread.contains("c2me")) {
            throw new IllegalStateException("synchronous diagnostic computation is forbidden on worldgen threads");
        }
        long started = System.nanoTime();
        try (var oracle = diagnosticOracle) {
            var center = computeRoute(segment, oracle);
            var previous = segment == 0 ? Optional.<RouteShape>empty() : Optional.of(computeRoute(segment - 1, oracle));
            var next = safeSegment(segment + 1) ? Optional.of(computeRoute(segment + 1, oracle)) : Optional.<RouteShape>empty();
            var cursor = hydrology.begin(new RouteNeighborhood(center, previous, next), oracle, 96000);
            while (hydrology.advance(cursor, 64) != WorkState.DONE) if (closed()) throw closedFailure();
            return geometry(center, hydrology.finish(cursor), cursor.logicalColumns(), next.isEmpty(),
                    oceanRuns(center, oracle));
        } finally {
            state.merge("synchronous-count", 1L, Long::sum);
            state.merge("synchronous-max-nanos", System.nanoTime() - started, Math::max);
        }
    }

    private RouteShape computeRoute(long segment, NaturalTerrainOracle oracle) {
        var c = ridge.begin(key(segment), oracle);
        while (ridge.advance(c, 64) != WorkState.DONE) if (closed()) throw closedFailure();
        return ridge.finish(c);
    }

    private void startGeometry(SegmentKey key, Priority priority, CompletableFuture<SegmentGeometry> result) {
        activeGeometry++;
        result.whenCompleteAsync((v, error) -> { activeGeometry--; drainGeometry(); }, pump);
        ioRead(() -> cache.readGeometry(key)).whenCompleteAsync((cached, error) -> {
            if (closed()) { result.completeExceptionally(closedFailure()); return; }
            if (error == null && cached.isPresent()) { result.complete(cached.get()); return; }
            var center = requireRoute(key.index(), priority);
            var previous = key.index() == 0 ? null : requireRoute(key.index() - 1, priority);
            var next = safeSegment(key.index() + 1) ? requireRoute(key.index() + 1, priority) : null;
            var previousFuture = previous == null ? CompletableFuture.<RouteShape>completedFuture(null) : previous.future();
            var nextFuture = next == null ? CompletableFuture.<RouteShape>completedFuture(null) : next.future();
            var owned = new ArrayList<PlanLease<?>>(); owned.add(center); if (previous != null) owned.add(previous); if (next != null) owned.add(next);
            center.future().thenCombineAsync(previousFuture, (a, b) -> new RouteNeighborhood(a, Optional.ofNullable(b), Optional.empty()), pump)
                    .thenCombineAsync(nextFuture, (a, b) -> new RouteNeighborhood(a.center(), a.previous(), Optional.ofNullable(b)), pump)
                    .whenCompleteAsync((routes, failure) -> {
                        if (failure != null) { owned.forEach(PlanLease::close); result.completeExceptionally(failure); return; }
                        var job = new HydroJob(routes, priority, result);
                        result.whenComplete((v, e) -> owned.forEach(PlanLease::close));
                        enqueue(job);
                    }, pump);
        }, pump);
    }

    private void drainGeometry() {
        while (!closed() && activeGeometry < recipe.config().maxGeometryInflight()) {
            Runnable next;
            // Dequeue under the lock, run outside it: the admission gate may be entered from the server
            // thread while the pump is completing a flight, and running a geometry job inside the lock
            // would serialise every later admission behind it.
            synchronized (geometryLock) { next = geometryQueue.pollFirst(); }
            if (next == null) return;
            next.run();
        }
    }

    private void preparePlan(SegmentKey key, Priority priority, Pair inputs, CompletableFuture<SegmentPlan> result, List<PlanLease<?>> initial) {
        var owned = new ArrayList<PlanLease<?>>(initial);
        CompletableFuture<GeometryWindow> window = CompletableFuture.completedFuture(new GeometryWindow(List.of(inputs.geometry), List.of(inputs.prefix)));
        for (long neighbor : new long[] {key.index() - 1, key.index() + 1}) {
            if (neighbor < 0 || !safeSegment(neighbor) || inputs.geometry.terminal().isPresent() && neighbor > key.index()) continue;
            var geometry = requireGeometry(neighbor, priority); var prefix = prefixes.requireBefore(neighbor);
            owned.add(geometry); owned.add(prefix);
            var pair = geometry.future().thenCombineAsync(prefix.future(), Pair::new, pump);
            window = window.thenCombineAsync(pair, (w, p) -> {
                if (p.prefix.beforeSegment() != p.geometry.key().index()) return w;
                var gs = new ArrayList<>(w.segments()); gs.add(p.geometry);
                var ps = new ArrayList<>(w.prefixes()); ps.add(p.prefix);
                return new GeometryWindow(gs, ps);
            }, pump);
        }
        result.whenComplete((v, e) -> owned.forEach(PlanLease::close));
        window.whenCompleteAsync((w, error) -> {
            if (error != null) { result.completeExceptionally(error); return; }
            try {
                long from = inputs.prefix.lengthBeforeQ8(), to = from + inputs.geometry.localSQ8().getLast();
                var provisional = sites.schedule(w, from, to);
                var points = heights.samplePoints(inputs.geometry, inputs.prefix, provisional);
                enqueue(new ContractJob(inputs, provisional, points, priority, result));
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        }, pump);
    }

    static java.util.List<local.plaincraft.greatwall.planning.OceanRouteScan.OceanRun> oceanRuns(
            local.plaincraft.greatwall.model.WallTypes.RouteShape route, NaturalTerrainOracle source) {
        if (source == null || route == null) return java.util.List.of();
        var points = route.points();
        var arcs = route.localSQ8();
        if (points.isEmpty() || arcs.size() != points.size()) return java.util.List.of();
        var keys = new java.util.ArrayList<String>(points.size());
        for (var point : points) {
            keys.add(source.biomeKeyAt(local.plaincraft.greatwall.geometry.FixedMath.floorBlock(point.xQ8()),
                    local.plaincraft.greatwall.geometry.FixedMath.floorBlock(point.zQ8())));
        }
        return local.plaincraft.greatwall.planning.OceanRouteScan.scan(keys, arcs);
    }

    private SegmentGeometry geometry(RouteShape route, List<WaterEvidence> water, int hydroColumns, boolean last,
            List<local.plaincraft.greatwall.planning.OceanRouteScan.OceanRun> seas) {
        // The sea is decided from biomes first. It costs one lookup per route point and it is the
        // same coastline the hydrology stage would have found by measuring every column of water -
        // including the deep ones, which are the most expensive columns this stage can ask for.
        Optional<Terminal> terminal = local.plaincraft.greatwall.planning.OceanRouteScan
                .firstTerminating(seas)
                .map(run -> new Terminal(route.key().index(),
                        route.localSQ8().get(Math.min(route.localSQ8().size() - 1,
                                local.plaincraft.greatwall.planning.OceanRouteScan.shoreIndex(run))),
                        TerminalKind.OCEAN));
        if (terminal.isPresent()) {
            local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                    "[GreatWall:S1C] OCEAN_ROUTE_SCAN segment={} seas={} terminal=biome localSQ8={}",
                    route.key().index(), seas.size(), terminal.get().localSQ8());
        }
        if (terminal.isEmpty()) terminal = water.stream().filter(w -> w.classification() == WaterClass.WIDE
                && w.crossing().fromQ8() >= 0 && w.crossing().fromQ8() < route.localSQ8().getLast())
                .map(w -> new Terminal(route.key().index(), w.crossing().fromQ8(), TerminalKind.OCEAN))
                .min(Comparator.comparingLong(Terminal::localSQ8));
        if (terminal.isEmpty() && last) terminal = Optional.of(new Terminal(route.key().index(), route.localSQ8().getLast(), TerminalKind.WORLD_LIMIT));
        var g = new SegmentGeometry(route.key(), route.points(), route.localSQ8(), water, terminal, route.start(), route.end(),
                route.searchColumns() + route.boundaryColumns() + hydroColumns, route.degradation(), "0".repeat(64));
        return new SegmentGeometry(g.key(), g.points(), g.localSQ8(), g.water(), g.terminal(), g.start(), g.end(), g.logicalColumns(), g.degradation(),
                DeterministicHash.sha256(CanonicalCodec.geometry(g)));
    }

    private static SegmentPlan hashedPlan(SegmentGeometry g, PrefixSummary p, StationTable t, List<SiteRecord> sites) {
        var plan = new SegmentPlan(g, p, t, sites, "0".repeat(64));
        return new SegmentPlan(g, p, t, sites, DeterministicHash.sha256(CanonicalCodec.plan(plan)));
    }

    private void enqueue(Job<?> job) {
        if (closed()) { job.result.completeExceptionally(closedFailure()); return; }
        pending.add(job); activeJobs.put(job.id, job);
        progress.put(job.id, new JobProgress(job.id, job.phase, 0, 1, true));
        dispatch();
    }

    private void promote(long segment, Priority priority) {
        Objects.requireNonNull(priority);
        pump.execute(() -> {
            for (var job : activeJobs.values()) {
                long index = switch (job) {
                    case RouteJob route -> route.key.index();
                    case HydroJob hydro -> hydro.routes.center().key().index();
                    case ContractJob contract -> contract.inputs.geometry.key().index();
                    // A fine-sample job belongs to the segment whose route it walks. Leaving it out of this
                    // switch is what took the whole pump down: a promotion that landed on a segment with a
                    // fine sample in flight threw "unknown job", the pump reported PUMP_TASK_FAILED, and that
                    // path releases every startup gate - so RoadWeaver started laying roads while the wall
                    // was still sampling. A bookkeeping lookup must never be able to do that.
                    case FineSampleJob fine -> fine.route.key().index();
                    default -> -1L;
                };
                if (index < 0L) continue;
                if (index == segment && priority.compareTo(job.priority) < 0) {
                    boolean queued = pending.remove(job);
                    job.priority = priority;
                    if (queued) pending.add(job);
                }
            }
        });
    }

    private void dispatch() {
        if (closed()) return;
        for (int lane = 0; lane < laneState.size() && credits > 0 && System.nanoTime() < deadline; lane++) {
            var slot = laneState.get(lane);
            if (slot.busy) continue;
            var nativeLane = lanes.get(lane);
            if (!slot.ready) {
                // Admission for native oracle construction.
                //
                // This used to be a constant of four, on the theory that it kept N native
                // RandomState instances from peaking together. It did not: every lane retains
                // its oracle for the life of the session, so all N exist at steady state
                // regardless, and the cap only spread their creation across batches - measured
                // at about 232 seconds of a startup prewarm on sixteen cores spent before any
                // planning began. The bound is now the lane ceiling, so all lanes initialize
                // together and the cost is paid once, concurrently.
                if (!slot.initializing && initializing < LanePolicy.MAX_CONCURRENT_INITIALIZATIONS) {
                    slot.initializing = true;
                    initializing++;
                    nativeLane.start().whenCompleteAsync((value, error) -> {
                        initializing--;
                        if (error != null) {
                            state.merge("lane-initialization-failures", 1L, Long::sum);
                            recordFailure(error, "NATIVE_INITIALIZATION", -1, 0);
                            routeFlights.forEach((key, future) -> future.completeExceptionally(error));
                            geometryFlights.forEach((key, future) -> future.completeExceptionally(error));
                            planFlights.forEach((key, future) -> future.completeExceptionally(error));
                            close();
                        } else { slot.ready = true; dispatch(); }
                    }, pump);
                }
                continue;
            }
            if (slot.job == null) slot.job = pending.poll();
            if (slot.job == null) continue;
            int operations = Math.min(GreatWallConfig.get().laneBatchOperations(), credits); credits -= operations;
            var job = slot.job; slot.busy = true;
            nativeLane.submit(oracle -> {
                Throwable error = null; boolean done = false; Object value = null;
                long started = System.nanoTime();
                try {
                    if (closed()) throw closedFailure();
                    // Hand the whole batch budget to one advance call rather than looping
                    // with a limit of one primitive at a time.
                    //
                    // The loop this replaces issued `advance(oracle, 1)` up to 64 times, and
                    // the difference is not a micro-optimisation. A job's advance is a
                    // resumable state machine, and several of its steps need a terrain column
                    // sampled before the state machine can progress at all: RidgePlanner.sample
                    // issues begin, then returns, then needs one advance per inner step, then
                    // finish - three or more separate calls, each of which had to be its own
                    // batch. With a limit of one, every one of those calls ended the loop body
                    // having done no recoverable work, so the batch budget was spent on
                    // bookkeeping between steps rather than on the steps themselves.
                    //
                    // A measured session ran 1,337,565 batches at 0.995 ms each - 22.2 minutes,
                    // matching its wall clock almost exactly - for work that the same code
                    // performs directly in computeGeometryNow via advance(cursor, 64). Passing
                    // the batch's own operation count lets a column sampling sequence complete
                    // inside a single batch instead of spanning three.
                    //
                    // The 25 ms target still applies, just coarser: it is now checked once per
                    // batch rather than once per primitive, so an unusually slow primitive can
                    // overrun it. That is already the case - the same session recorded a
                    // 108.6 ms batch and only 9 batches over 40 ms out of 1.34 million - and the
                    // deadline exists to keep a batch from monopolising a lane, not to bound a
                    // single primitive.
                    done = job.advance(oracle, operations);
                    if (done) value = job.value();
                } catch (Throwable failure) { error = failure; done = true; }
                long duration = System.nanoTime() - started;
                state.merge("max-batch-nanos", duration, Math::max);
                state.merge("batch-count", 1L, Long::sum);
                if (duration > TimeUnit.MILLISECONDS.toNanos(40)) state.merge("over-40ms-batches", 1L, Long::sum);
                return new Batch(done, error, value);
            }).whenComplete((batch, submitError) -> {
                try { pump.execute(() -> {
                    slot.busy = false;
                    if (closed()) { job.result.completeExceptionally(closedFailure()); return; }
                    // A failed job may still own an unfinished native cursor. Never reuse that lane.
                    Throwable failure = submitError != null ? submitError : batch.failure();
                    if (failure != null) {
                        recordFailure(failure, job.phase, segmentOf(job), job.operations);
                        job.result.completeExceptionally(failure);
                        // Declare the prewarm over before the session winds down: closing the planner
                        // fails every future, and the loading side would otherwise keep waiting for a
                        // prewarm that can never finish. A measured session sat on the loading card for
                        // the full 30 minute peer timeout after its hydrology job failed. finish(false)
                        // records the failure on the card and opens the peer/worldgen gates, which is
                        // exactly the policy already applied when the bulk is skipped on a closed
                        // planner: a wall that cannot paint must not hold the roads behind it.
                        GreatWallMod.LOGGER.warn("[GreatWall:S1C] PREWARM_ABORTED phase={} segment={} reason={}",
                                job.phase, segmentOf(job), failure.toString());
                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
                        close();
                        return;
                    }
                    boolean finished = batch.finished();
                    if (finished) {
                        slot.job = null; activeJobs.remove(job.id); progress.remove(job.id);
                        job.complete(batch.value());
                    } else {
                        progress.put(job.id, new JobProgress(job.id, job.phase, job.operations, Long.MAX_VALUE, true));
                    }
                    dispatch();
                }); } catch (RejectedExecutionException stopped) { job.result.completeExceptionally(closedFailure()); }
            });
        }
    }

    private <T> CompletableFuture<T> flight(ConcurrentHashMap<SegmentKey, CompletableFuture<T>> flights, SegmentKey key) {
        var future = new CompletableFuture<T>();
        if (flights.putIfAbsent(key, future) != null) throw new IllegalStateException("cache started duplicate loader");
        future.whenComplete((v, error) -> flights.remove(key, future)); return future;
    }

    private <T> PlanLease<T> acquire(CacheKey key, long upper, Class<T> type, Supplier<CompletionStage<Object>> loader) {
        if (closed()) return new PlanLease<>(UUID.randomUUID(), CompletableFuture.failedFuture(closedFailure()), () -> {});
        var acquired = new CompletableFuture<PlanLease<Object>>();
        UUID id = UUID.randomUUID();
        boolean[] accepted = {false};
        state.compute("acquisitions", (k, old) -> {
            long count = old == null ? 0 : old;
            if (count >= 4096) return count;
            accepted[0] = true; return count + 1;
        });
        if (!accepted[0]) return new PlanLease<>(id, CompletableFuture.failedFuture(
                new WallFailure(FailureCode.CAPACITY, "planner acquisition capacity")), () -> {});
        acquisitions.put(id, acquired);
        acquired.whenComplete((v, e) -> {
            acquisitions.remove(id); state.compute("acquisitions", (k, old) -> old - 1);
        });
        pump.execute(() -> {
            if (closed()) { acquired.completeExceptionally(closedFailure()); return; }
            Runnable start = () -> startAcquire(key, upper, loader, acquired);
            if (key.kind.equals("plan")) planAdmissions.add(start);
            else if (key.kind.equals("geometry")) geometryAdmissions.add(start);
            else start.run();
            drainAdmissions();
        });
        CompletableFuture<T> view = acquired.thenCompose(lease -> lease.future().thenApply(type::cast));
        return outer(view, () -> acquired.thenAccept(PlanLease::close));
    }

    private void startAcquire(CacheKey key, long upper, Supplier<CompletionStage<Object>> loader,
            CompletableFuture<PlanLease<Object>> result) {
        if (key.kind.equals("plan")) admittedPlans++;
        if (key.kind.equals("geometry")) admittedGeometries++;
        memory.acquire(key, upper, loader).whenCompleteAsync((lease, failure) -> {
            if (key.kind.equals("plan")) admittedPlans--;
            if (key.kind.equals("geometry")) admittedGeometries--;
            if (failure != null) result.completeExceptionally(failure);
            else if (!result.complete(lease)) lease.close();
            drainAdmissions();
        }, pump);
    }

    private void drainAdmissions() {
        // Reserve dependency headroom before allocating a parent plan/geometry reservation.
        while (!closed() && admittedGeometries < 4 && !geometryAdmissions.isEmpty()) geometryAdmissions.removeFirst().run();
        while (!closed() && admittedPlans < 4 && !planAdmissions.isEmpty()) planAdmissions.removeFirst().run();
    }

    private <T> PlanLease<T> outer(CompletionStage<T> shared, Runnable release) {
        UUID id = UUID.randomUUID();
        boolean[] admitted = {false};
        state.compute("leases", (k, old) -> {
            long count = old == null ? 0 : old;
            if (closed() || count >= 4096) return count;
            admitted[0] = true; return count + 1;
        });
        if (!admitted[0]) {
            release.run();
            return new PlanLease<>(id, CompletableFuture.failedFuture(closed() ? closedFailure()
                    : new WallFailure(FailureCode.CAPACITY, "planner lease limit")), () -> {});
        }
        var lease = new PlanLease<T>(id, shared, () -> {
            callerLeases.remove(id); state.compute("leases", (k, old) -> old - 1); release.run();
        });
        callerLeases.put(id, lease);
        if (closed()) lease.close();
        return lease;
    }

    private SegmentKey key(long segment) {
        if (closed()) throw closedFailure();
        if (!safeSegment(segment)) throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "segment exceeds complete frozen envelope");
        return new SegmentKey(recipe.recipeHash(), recipe.dimension(), segment);
    }

    /**
     * Whether a segment index is inside the region this world may build.
     *
     * <h2>Two limits, both required</h2>
     * The frozen envelope is a generation-semantics boundary: past it the
     * projection and carrier maths would leave the dimension's horizontal limit,
     * so it can never be relaxed.
     *
     * <p>The configured length is a player choice, applied here because this
     * method is the single gate every segment access passes through — planning,
     * background prewarm, neighbour lookups and the terminal check all call it.
     * Enforcing the length anywhere else would leave paths that bypass it.
     *
     * <p>An unconfigured wall keeps the historical behaviour exactly: the whole
     * frozen envelope is buildable, and no configured terminal is ever produced.
     */
    private boolean safeSegment(long segment) {
        if (segment < 0) return false;
        if (!withinConfiguredLength(segment)) return false;
        try {
            // 512 carrier + 512 ridge + 96 component + 48 reference side-column envelope.
            long radius = 1168L * 256, limit = recipe.bounds().horizontalLimit() * 256L;
            for (long index : new long[] {segment, Math.addExact(segment, 1)}) {
                long u = Math.multiplyExact(index, 2048L * 256);
                long x = Math.addExact(recipe.axis().origin().x() * 256L, FixedMath.mulDiv(u, recipe.axis().exQ30(), 1L << 30));
                long z = Math.addExact(recipe.axis().origin().z() * 256L, FixedMath.mulDiv(u, recipe.axis().ezQ30(), 1L << 30));
                if (x - radius < -limit || x + radius > limit || z - radius < -limit || z + radius > limit) return false;
            }
            return true;
        } catch (ArithmeticException overflow) { return false; }
    }

    /**
     * Configured length gate.
     *
     * <p>{@code configuredSegments} is the number of whole 2048-block segments the
     * requested length spans, so segment {@code n} starts at {@code n * 2048}
     * blocks along the axis. Segment {@code configuredSegments} is therefore the
     * first one entirely past the requested length and is refused.
     *
     * <p>A configuration that cannot be read leaves the wall unbounded rather
     * than silently truncating it: a missing file must never shorten a world.
     */
    private boolean withinConfiguredLength(long segment) {
        long allowed = configuredSegmentLimit();
        return allowed <= 0L || segment < allowed;
    }

    /** Cached because this is called for every segment access on the planner's hot path. */
    private volatile long configuredSegmentLimitCache = Long.MIN_VALUE;

    private long configuredSegmentLimit() {
        long cached = configuredSegmentLimitCache;
        if (cached != Long.MIN_VALUE) return cached;
        long limit;
        try {
            limit = GreatWallConfig.parameters().configuredSegments();
        } catch (RuntimeException | LinkageError unavailable) {
            // Fail open: an unreadable config must not shorten an existing wall.
            state.put("wall-length-config-unavailable", 1L);
            limit = 0L;
        }
        configuredSegmentLimitCache = limit;
        return limit;
    }

    /** Segments this wall will build; {@code 0} when unbounded. Exposed for diagnostics. */
    public long configuredSegmentLimitForDiagnostics() { return configuredSegmentLimit(); }

    private <T> CompletableFuture<Optional<T>> ioRead(IoRead<T> operation) {
        if (state.containsKey("cache-disabled")) return CompletableFuture.completedFuture(Optional.empty());
        var result = new CompletableFuture<Optional<T>>();
        try {
            submitIo(() -> {
                try { result.complete(operation.read()); }
                catch (IOException error) { state.put("cache-disabled", 1L); result.complete(Optional.empty()); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException full) {
            // Capacity, not failure: one burst must not switch the cache off for the whole session. Only a
            // closed planner does that, because then nothing will ever drain the queue.
            if (closed()) state.put("cache-disabled", 1L);
            else state.compute("cache-backpressure", (k, old) -> old == null ? 1L : old + 1L);
            result.complete(Optional.empty());
        }
        return result;
    }

    private void submitIo(Runnable task) {
        if (closed()) throw new RejectedExecutionException("planner closed");
        boolean[] admitted = {false};
        state.compute("io-pending", (k, old) -> {
            long count = old == null ? 0 : old;
            if (count >= 128) return count;
            admitted[0] = true; return count + 1;
        });
        if (!admitted[0]) throw new RejectedExecutionException("planner cache IO queue capacity");
        try {
            pump.execute(() -> {
                ioQueue.add(() -> { try { task.run(); } finally { state.compute("io-pending", (k, old) -> old - 1); } });
                drainIo();
            });
        } catch (RuntimeException rejected) { state.compute("io-pending", (k, old) -> old - 1); throw rejected; }
    }

    private void drainIo() {
        if (activeIo != 0 || ioQueue.isEmpty() || closed()) return;
        activeIo = 1; Runnable next = ioQueue.removeFirst();
        try { io.execute(() -> {
            try { next.run(); }
            finally {
                try { pump.execute(() -> { activeIo = 0; drainIo(); }); }
                catch (RejectedExecutionException ignored) { /* Closing already completed public promises. */ }
            }
        }); } catch (RuntimeException rejected) {
            activeIo = 0; state.put("cache-disabled", 1L);
            var failure = new WallFailure(FailureCode.IO_FAILED, "IO executor rejected a required continuation", rejected);
            recordFailure(failure, "CACHE_IO", -1, 0);
            routeFlights.forEach((key, future) -> future.completeExceptionally(failure));
            geometryFlights.forEach((key, future) -> future.completeExceptionally(failure));
            planFlights.forEach((key, future) -> future.completeExceptionally(failure));
            close();
        }
    }

    private void persist(SegmentGeometry value) {
        if (!state.containsKey("cache-disabled") && !closed()) try {
            submitIo(() -> { try { cache.writeGeometry(value); } catch (IOException error) { state.put("cache-disabled", 1L); } });
        } catch (RejectedExecutionException full) {
                    // Capacity, not failure: the cache stays enabled so the next write can still land.
                    if (closed()) state.put("cache-disabled", 1L);
                    else state.compute("cache-backpressure", (k, old) -> old == null ? 1L : old + 1L);
                }
    }
    private void persist(SegmentPlan value) {
        if (!state.containsKey("cache-disabled") && !closed()) try {
            submitIo(() -> { try { cache.writePlan(value); } catch (IOException error) { state.put("cache-disabled", 1L); } });
        } catch (RejectedExecutionException full) {
                    // Capacity, not failure: the cache stays enabled so the next write can still land.
                    if (closed()) state.put("cache-disabled", 1L);
                    else state.compute("cache-backpressure", (k, old) -> old == null ? 1L : old + 1L);
                }
    }

    private static long retainedBytes(Object value) {
        if (value instanceof RouteShape r) return 4096L + 80L * r.points().size();
        if (value instanceof SegmentGeometry g) {
            long result = 4096L + 80L * g.points().size();
            for (var w : g.water()) result += 512L + 48L * (w.banks().size() + w.piers().size());
            return result;
        }
        if (value instanceof SegmentPlan p) return retainedBytes(p.geometry()) + 4096L + 96L * p.stations().stations().size()
                + 128L * p.stations().breaks().size() + 2048L * p.sites().size();
        throw new IllegalArgumentException("unknown cached object");
    }

    private boolean closed() { return state.containsKey("closed"); }
    private void recordFailure(Throwable error, String phase, long segment, long operations) {
        Throwable root = error;
        FailureCode code = null;
        while (true) {
            if (code == null && root instanceof WallFailure failure) code = failure.code();
            if (root.getCause() == null) break;
            root = root.getCause();
        }
        var text = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(text));
        failures.putIfAbsent("first", new FailureEvidence(phase, segment, operations,
                code == null ? FailureCode.INVALID_RESOURCE : code,
                root.getClass().getName(), String.valueOf(root.getMessage()), text.toString()));
    }
    private static long segmentOf(Job<?> job) {
        return switch (job) {
            case PlannerScheduler.RouteJob route -> route.key.index();
            case PlannerScheduler.HydroJob hydro -> hydro.routes.center().key().index();
            case PlannerScheduler.ContractJob contract -> contract.inputs.geometry.key().index();
            case PlannerScheduler.FineSampleJob fine -> fine.route.key().index();
            // Never fatal: this is a label for diagnostics, and a missing label used to fail the pump.
            default -> -1L;
        };
    }
    private WallFailure closedFailure() {
        var first = failures.get("first");
        return first == null ? new WallFailure(FailureCode.CLOSED, "planner session closed")
                : new WallFailure(first.code(), "planner failed in " + first.phase() + " segment="
                        + first.segment() + ": " + first.exceptionType() + ": " + first.message());
    }
    /**
     * Refuses to run on a thread that the planner itself owns.
     *
     * <p>Used by callers that block on planner futures. Waiting on this scheduler from one of its own
     * threads cannot succeed — the work being awaited can only progress on the pump that is blocked —
     * so the hazard is converted into an immediate, named failure.
     */
    public void assertOutsidePlanningThread(String purpose) {
        String thread = Thread.currentThread().getName();
        if (thread.startsWith(pumpThreadPrefix) || thread.startsWith(laneThreadPrefix)) {
            throw new IllegalStateException(purpose + " must not block on the planner from a planner-owned thread: " + thread);
        }
    }

    private static ThreadFactory factory(String name) {
        return runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; };
    }

    @Override public void close() {
        if (state.putIfAbsent("closed", 1L) != null) return;
        routeFlights.forEach((key, future) -> future.completeExceptionally(closedFailure()));
        geometryFlights.forEach((key, future) -> future.completeExceptionally(closedFailure()));
        planFlights.forEach((key, future) -> future.completeExceptionally(closedFailure()));
        acquisitions.forEach((id, future) -> future.completeExceptionally(closedFailure()));
        prefixes.close(); callerLeases.forEach((id, lease) -> lease.close());
        // The lease map is a weakly consistent view: an entry inserted while that loop ran would keep its
        // lease - and its memory pin - open forever. close() is idempotent, so sweep again now that the
        // closed flag is up and every new acquisition is refused.
        callerLeases.forEach((id, lease) -> lease.close());
        // A session that ends before the compile must not leave the prewarm's leases holding the
        // memory budget open.
        releasePrewarmLeases();
        // Write the columns this session walked before the session is torn down. The cache used to be
        // written only from the completion callback, so a session that ended early - the measured one
        // stopped after 50,937 of 230,657 columns - kept nothing and the next session walked them again.
        var sampled = fineSampleWalked;
        if (sampled != null && !sampled.isEmpty()) {
            try {
                persistFineSamples(sampled);
            } catch (RuntimeException cannotPersist) {
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] FINE_SAMPLE_CACHE_CLOSE_FAILED reason={}",
                        cannotPersist.toString());
            }
        }
        memory.close();
        pump.execute(() -> {
            if (startupTimer != null) startupTimer.cancel(false);
            if (watchdogTimer != null) watchdogTimer.cancel(false);
            if (prewarmTimer != null) prewarmTimer.cancel(false);
            if (fineSampleTimer != null) fineSampleTimer.cancel(false);
            pending.forEach(job -> job.result.completeExceptionally(closedFailure()));
            // In-flight jobs are not in pending - dispatch() already polled them onto a lane - so they
            // would otherwise only be completed by their lane callback, which the shutdown below can
            // refuse. completeExceptionally is idempotent, so completing them twice is harmless.
            activeJobs.values().forEach(job -> job.result.completeExceptionally(closedFailure()));
            pending.clear(); synchronized (geometryLock) { geometryQueue.clear(); } ioQueue.clear();
            planAdmissions.clear(); geometryAdmissions.clear();
            activeJobs.clear(); progress.clear();
            lanes.forEach(OracleLane::close);
            CompletableFuture.allOf(lanes.stream().map(OracleLane::drain).toArray(CompletableFuture[]::new))
                    .whenComplete((value, error) -> {
                        if (error == null) drained.complete(null); else drained.completeExceptionally(error);
                    });
            pump.shutdown();
        });
    }

    private record CacheKey(String kind, long segment) {}
    private record Pair(SegmentGeometry geometry, PrefixSummary prefix) {}
    @FunctionalInterface private interface IoRead<T> { Optional<T> read() throws IOException; }
    private record Batch(boolean finished, Throwable failure, Object value) {}
    private static final class Lane { private Job<?> job; private boolean busy, initializing, ready; }

    private abstract class Job<T> {
        private final UUID id = UUID.randomUUID();
        private Priority priority;
        private final long sequence = PlannerScheduler.this.sequence++;
        private final String phase;
        final CompletableFuture<T> result;
        long operations;
        private Job(Priority priority, String phase, CompletableFuture<T> result) {
            this.priority = Objects.requireNonNull(priority); this.phase = phase; this.result = result;
        }
        abstract boolean advance(NaturalTerrainOracle oracle, int limit);
        abstract T value();
        @SuppressWarnings("unchecked") private void complete(Object value) { result.complete((T) value); }
    }

    private final class RouteJob extends Job<RouteShape> {
        private final SegmentKey key;
        private RidgePlanner.Cursor cursor;
        private RouteJob(SegmentKey key, Priority priority, CompletableFuture<RouteShape> result) {
            super(priority, "ROUTE", result); this.key = key;
        }
        @Override boolean advance(NaturalTerrainOracle oracle, int limit) {
            if (cursor == null) cursor = ridge.begin(key, oracle);
            operations += limit; return ridge.advance(cursor, limit) == WorkState.DONE;
        }
        @Override RouteShape value() { return ridge.finish(cursor); }
    }

    private final class FineSampleJob extends Job<List<WallFinePlanner.FineColumn>> {
        private final RouteShape route;
        /** Point sub-range this job samples: {@code [fromPoint, toPoint)} of the route. */
        private final int fromPoint, toPoint;
        private final local.plaincraft.greatwall.terrain.FineSampleCache cache;
        /** Points of this route that must carry the full band, because a component sits on them. */
        private final java.util.function.IntPredicate wideAt;
        /** Session-wide memo of already-walked columns; see {@link WallFinePlanner#begin}. */
        private final java.util.Map<Long, local.plaincraft.greatwall.model.WallTypes.ColumnSample> walks;
        /** Columns already claimed by another slice of this session; see WallFinePlanner.begin. */
        private final java.util.Set<Long> claims;
        private WallFinePlanner.Cursor cursor;
        private FineSampleJob(RouteShape route, int fromPoint, int toPoint, Priority priority,
                              CompletableFuture<List<WallFinePlanner.FineColumn>> result,
                              local.plaincraft.greatwall.terrain.FineSampleCache cache,
                              java.util.function.IntPredicate wideAt,
                              java.util.Map<Long, local.plaincraft.greatwall.model.WallTypes.ColumnSample> walks,
                              java.util.Set<Long> claims) {
            super(priority, "FINE_SAMPLE", result);
            this.route = route;
            this.fromPoint = Math.max(0, fromPoint);
            this.toPoint = Math.min(route.points().size(), Math.max(this.fromPoint, toPoint));
            this.cache = cache;
            this.wideAt = wideAt;
            this.walks = walks;
            this.claims = claims;
        }
        @Override boolean advance(NaturalTerrainOracle oracle, int limit) {
            if (cursor == null) {
                cursor = WallFinePlanner.begin(route, oracle, fromPoint, toPoint, cache, wideAt, walks, claims);
            }
            operations += limit;
            return WallFinePlanner.advance(cursor, limit) == WorkState.DONE;
        }
        @Override List<WallFinePlanner.FineColumn> value() { return WallFinePlanner.finish(cursor); }
        /** Columns this slice resolved itself, for the session's cache write. */
        List<WallFinePlanner.FineColumn> probed() { return WallFinePlanner.probed(cursor); }
        /** Cross-section columns this slice emitted before repeats were collapsed, for the log. */
        int emittedRaw() { return cursor == null ? 0 : WallFinePlanner.emittedRaw(cursor); }
        /** Cross-sections this slice walked, and how many carried the full wide band. */
        long sections() { return cursor == null ? 0 : WallFinePlanner.sections(cursor); }
        long wideSections() { return cursor == null ? 0 : WallFinePlanner.wideSections(cursor); }
        /** REFERENCE walks this slice started, i.e. columns no cache or memo answered. */
        long probedColumns() { return cursor == null ? 0 : WallFinePlanner.probedColumns(cursor); }
    }

    /**
     * The route points whose cross-section must carry the full {@link WallFinePlanner#LATERAL} band.
     *
     * <p>Only forts and gates read past the base width: their axis-aligned box reaches roughly 48
     * blocks laterally once their frame is rotated. Their stations are a pure function of the absolute
     * arc - a gate every 800 blocks with a deterministic jitter of ±100, a fort every 4000 with ±500
     * (see {@link SiteScheduler}) - so the arcs are known before a single column is sampled. Widening a
     * window no component ends up using costs a few extra columns; missing one would leave the contract
     * without the columns it requires.</p>
     */
    private java.util.function.IntPredicate widePoints(RouteShape route, long fromQ8) {
        var arcs = route.localSQ8();
        int points = arcs.size();
        var wide = new java.util.BitSet(points);
        long toQ8 = fromQ8 + arcs.get(points - 1);
        // A component's box reaches at most ~48 blocks along the wall; 96 blocks is slack for the
        // station's own jitter and for the sampled route drifting off the planned one.
        long marginQ8 = 96L * 256L;
        for (long ordinal = Math.max(0L, (fromQ8 - marginQ8) / (800L * 256L) - 1L); ; ordinal++) {
            long site = sites.gatePositionQ8(ordinal);
            if (site - marginQ8 > toQ8) break;
            markWide(wide, arcs, fromQ8, site, marginQ8);
        }
        for (long ordinal = Math.max(0L, (fromQ8 - marginQ8) / (4000L * 256L) - 1L); ; ordinal++) {
            long site = sites.fortPositionQ8(ordinal);
            if (site - marginQ8 > toQ8) break;
            markWide(wide, arcs, fromQ8, site, marginQ8);
        }
        return wide::get;
    }

    /** Marks every route point whose arc lies within {@code marginQ8} of the absolute {@code siteQ8}. */
    private static void markWide(java.util.BitSet wide, List<Long> arcs, long fromQ8, long siteQ8, long marginQ8) {
        long lowArc = siteQ8 - marginQ8 - fromQ8;
        long highArc = siteQ8 + marginQ8 - fromQ8;
        int points = arcs.size();
        int lo = 0, hi = points;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arcs.get(mid) < lowArc) lo = mid + 1; else hi = mid;
        }
        for (int i = lo; i < points && arcs.get(i) <= highArc; i++) wide.set(i);
    }

    /** Columns the {@code [from, to)} cross-sections emit, the wide points and the sampling grid included. */
    private static int sliceColumns(List<Long> localSQ8, int from, int to,
                                    java.util.function.IntPredicate wideAt, int gridStep) {
        int total = 0;
        for (int i = from; i < to && i < localSQ8.size(); i++) {
            // The same rule WallFinePlanner.step applies to a route point index, so the meter counts
            // the cross-sections the cursor will actually walk.
            if (gridStep > 1 && Math.floorMod(i, gridStep) != 0) continue;
            total += gridWidth(wideAt.test(i) ? WallFinePlanner.LATERAL : WallFinePlanner.BASE_LATERAL, gridStep);
        }
        return total;
    }

    /**
     * Columns one cross-section of half width {@code halfWidth} keeps under the sampling grid.
     *
     * <p>Exactly the laterals {@link WallFinePlanner} emits: it keeps the index {@code 0} and every
     * {@code gridStep}-th lateral either side of it. Only the progress meter uses this, so it has to
     * track the sampler rather than be exact to the column.
     */
    private static int gridWidth(int halfWidth, int gridStep) {
        if (gridStep <= 1) return 2 * halfWidth + 1;
        return 2 * (halfWidth / gridStep) + 1;
    }

    private final class HydroJob extends Job<SegmentGeometry> {
        private final RouteNeighborhood routes;
        private HydrologyClassifier.Cursor cursor;
        /** Kept from advance() so value() can ask for biomes without a fresh oracle. */
        private NaturalTerrainOracle oracle;
        private HydroJob(RouteNeighborhood routes, Priority priority, CompletableFuture<SegmentGeometry> result) {
            super(priority, "HYDROLOGY", result); this.routes = routes;
            result.thenAcceptAsync(PlannerScheduler.this::persist, pump);
        }
        @Override boolean advance(NaturalTerrainOracle oracle, int limit) {
            if (cursor == null) cursor = hydrology.begin(routes, oracle, 96000);
            this.oracle = oracle;
            operations += limit;
            try {
                // A segment whose hydrology exceeds this operation ceiling terminates
                // its remaining unresolved water as WIDE instead of burning unbounded
                // time certifying an island that terminates either way. Already
                // certified NARROW/WIDE/SUPPORT_GAP evidence is preserved.
                if (operations > HYDROLOGY_OPERATION_CEILING && !cursor.done()) {
                    hydrology.forceComplete(cursor);
                    return true;
                }
                return hydrology.advance(cursor, limit) == WorkState.DONE;
            } catch (WallFailure unresolved) {
                // A column the terrain oracle cannot certify used to end the segment, and through the
                // scheduler's failure policy the whole session with it: a measured world died with
                // "HYDROLOGY_UNRESOLVED: reference column cannot certify ground or river bed", and the
                // fine sample and compile that depended on the same planner died with it. The
                // classifier already has an authorised answer for water it cannot measure - WIDE, the
                // same terminate-into-sea policy the operation ceiling uses - so take it here instead.
                // Safe on this lane: the classifier only refuses after its oracle column has finished,
                // and forceComplete drains any column still open, so the lane keeps a clean oracle.
                if (unresolved.code() != FailureCode.HYDROLOGY_UNRESOLVED || cursor.done()) throw unresolved;
                GreatWallMod.LOGGER.warn("[GreatWall:hydro] HYDROLOGY_FORCED_COMPLETE segment={} reason={}",
                        routes.center().key().index(), unresolved.getMessage());
                hydrology.forceComplete(cursor);
                return true;
            }
        }
        @Override SegmentGeometry value() {
            return geometry(routes.center(), hydrology.finish(cursor), cursor.logicalColumns(),
                    routes.next().isEmpty(), oceanRuns(routes.center(), oracle));
        }

        /**
         * The seas along this segment, from biome data alone.
         *
         * <p>One biome lookup per route point costs about a thousandth of a column sample, and it
         * answers the question the hydrology stage used to answer by measuring: where is the water,
         * and is it wide enough to end the wall. Returns an empty list whenever the oracle cannot
         * answer, which leaves the old water-based decision in place.
         */

    }

    private final class ContractJob extends Job<SegmentPlan> {
        private final Pair inputs;
        private final List<SiteRecord> provisional;
        private final List<Point2q> points;
        private final Map<Point2q, ColumnSample> columns = new HashMap<>();
        private int index;
        private NaturalTerrainOracle.ColumnCursor column;
        private ContractJob(Pair inputs, List<SiteRecord> provisional, List<Point2q> points, Priority priority, CompletableFuture<SegmentPlan> result) {
            super(priority, "HEIGHT_CONTRACT", result); this.inputs = inputs; this.provisional = provisional; this.points = points;
            result.thenAcceptAsync(PlannerScheduler.this::persist, pump);
        }
        @Override boolean advance(NaturalTerrainOracle oracle, int limit) {
            for (int i = 0; i < limit && index < points.size(); i++) {
                if (closed()) throw closedFailure();
                var p = points.get(index); operations++;
                if (column == null) column = oracle.begin(FixedMath.floorBlock(p.xQ8()), FixedMath.floorBlock(p.zQ8()), SampleKind.REFERENCE);
                else if (!column.done()) oracle.advance(column, 1);
                else { columns.put(p, oracle.finish(column)); column = null; index++; }
            }
            return index == points.size();
        }
        @Override SegmentPlan value() {
            var table = heights.solve(inputs.geometry, inputs.prefix, provisional, columns);
            return hashedPlan(inputs.geometry, inputs.prefix, table, sites.bindHeights(provisional, table));
        }
    }
}
