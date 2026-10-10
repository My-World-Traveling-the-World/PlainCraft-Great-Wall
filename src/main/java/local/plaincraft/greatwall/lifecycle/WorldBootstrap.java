package local.plaincraft.greatwall.lifecycle;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.persistence.WorldManifest;
import local.plaincraft.greatwall.planning.PlannerScheduler;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;
import local.plaincraft.greatwall.terrain.OracleLane;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Creation, root search and durable recipe are explicit asynchronous stages, never tick dependencies. */
public final class WorldBootstrap implements AutoCloseable {
    public record Frozen(DimensionBounds bounds, String terrainHash, String paletteHash, String templatesHash,
            Supplier<NaturalTerrainOracle> factory,
            BiPredicate<NaturalTerrainOracle, NaturalTerrainOracle.ColumnCursor> foundation,RootStructureScreen structures,
            local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance sanctuaryAvoidance) {
        public Frozen(DimensionBounds bounds,String terrainHash,String paletteHash,String templatesHash,
                Supplier<NaturalTerrainOracle> factory,BiPredicate<NaturalTerrainOracle,NaturalTerrainOracle.ColumnCursor> foundation){
            this(bounds,terrainHash,paletteHash,templatesHash,factory,foundation,null,
                    local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance.none());
        }
        public Frozen(DimensionBounds bounds,String terrainHash,String paletteHash,String templatesHash,
                Supplier<NaturalTerrainOracle> factory,BiPredicate<NaturalTerrainOracle,NaturalTerrainOracle.ColumnCursor> foundation,
                RootStructureScreen structures){
            this(bounds,terrainHash,paletteHash,templatesHash,factory,foundation,structures,
                    local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance.none());
        }
        public Frozen {
            Objects.requireNonNull(bounds); Objects.requireNonNull(factory); Objects.requireNonNull(foundation);
            local.plaincraft.greatwall.model.WallTypes.requireHash(terrainHash);
            local.plaincraft.greatwall.model.WallTypes.requireHash(paletteHash);
            local.plaincraft.greatwall.model.WallTypes.requireHash(templatesHash);
        }
    }
    private record Authorized(WorldManifest.Creation creation, Optional<WorldRecipe> saved) {}
    private record RootBatch(Optional<RootSearch.Selection> selected, RootSearch.Progress progress,
            List<RootStructureScreen.Candidate> screen) {}
    private final MinecraftServer server;
    private final WorldManifest manifest;
    private final Executor io;
    private final long seed;
    private final boolean originallyInitialized;
    private final Thread eventOwner = Thread.currentThread();
    private final SpawnDomain earlyDomain = new SpawnDomain();
    private final ConcurrentHashMap<String, Boolean> state = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Point3i> spawn = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Frozen> frozen = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<OracleLane>> laneSnapshot = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RootSearch.Progress> progress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<RootSearch.Audit>> rootAudit = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorldRecipe> recipes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlannerScheduler> transferred = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<CompletableFuture<?>>> ioFlights = new ConcurrentHashMap<>();
    private final CompletableFuture<Authorized> authorized;
    private final CompletableFuture<WorldRecipe> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final ScheduledThreadPoolExecutor rootPump;

    public WorldBootstrap(MinecraftServer server, WorldManifest manifest, Executor io) {
        this(server, manifest, io, server.getWorldData().worldGenOptions().seed(),
                server.getWorldData().overworldData().isInitialized());
    }

    WorldBootstrap(WorldManifest manifest, Executor io, long seed, boolean originallyInitialized) {
        this(null, manifest, io, seed, originallyInitialized);
    }

    private WorldBootstrap(MinecraftServer server, WorldManifest manifest, Executor io,
            long seed, boolean originallyInitialized) {
        this.server = server; this.manifest = Objects.requireNonNull(manifest);
        this.io = Objects.requireNonNull(io); this.seed = seed; this.originallyInitialized = originallyInitialized;
        rootPump = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "pgw-root-pump-" + UUID.randomUUID());
            thread.setDaemon(true); return thread;
        });
        rootPump.setRemoveOnCancelPolicy(true);
        // One shared timer for every bounded beforeRecipe wait: a per-chunk timer
        // would allocate a scheduled task per chunk load, and there can be tens of
        // thousands of those.
        recipeTimeoutTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "pgw-recipe-timeout-" + UUID.randomUUID());
            thread.setDaemon(true); return thread;
        });
        authorized = io(() -> new Authorized(manifest.authorize(originallyInitialized, seed,
                WorldManifest.configHash(defaultConfig())), manifest.read()));
        authorized.whenComplete((value, error) -> {
            if (error != null) fail(error); else requestStart();
        });
    }

    public static GenerationConfig defaultConfig() {
        // Measured on real terrain: loadCell() captures every owner of a cell
        // into one shared arena, and each successful capture keeps its pin
        // (finishCapture only closes retained pins on failure) while also
        // holding a 24 MiB scratch reservation. The 64 MiB terrain budget was
        // consumed to ~99% at roughly 130 owners per cell, so it is raised to
        // 256 MiB to cover a full 2048-block cell. The compiled budget is
        // raised for the same reason: componentBytes charges 256 bytes per
        // voxel and one chunk owner retains many cells. Segment stays at 64 MiB.
        //
        // Geometry in-flight is derived from the hardware instead of being the constant
        // four it used to be. That constant was the real limit on a startup prewarm: a
        // measured sixteen-core session ran sixteen native lanes but could never have
        // more than four geometries in flight, so twelve lanes sat idle while four
        // segments took twenty-two minutes. The byte budgets above remain the gate that
        // prevents over-commitment - this number only decides how much may be attempted.
        return new GenerationConfig(1, 1, 2048, 4, 150000, 384, 512,
                local.plaincraft.greatwall.terrain.ConcurrencyPlan.startupGeometryInflight(),
                64L << 20, 256L << 20, 256L << 20);
    }

    public void authorizeSpawnProbe(ChunkKey candidate, int nativeDependencyRadius) {
        owner(); active();
        if (originallyInitialized || state.containsKey("root-started") || recipes.containsKey("ready")) return;
        try { earlyDomain.authorize(candidate, nativeDependencyRadius); }
        catch (RuntimeException error) { fail(error); throw error; }
    }

    public boolean mayPassBeforeRecipe(ChunkKey requested) {
        active();
        return !originallyInitialized && requested.dimension().equals("minecraft:overworld")
                && earlyDomain.contains(requested);
    }

    /**
     * Upper bound on how long an ordinary chunk may wait for the recipe.
     *
     * <h2>Why this exists</h2>
     * A real session showed the whole world's chunk generation stopping for
     * seven minutes and forty-seven seconds while the root search sampled
     * foundations on a single lane. Every overworld chunk's FEATURES and
     * INITIALIZE_LIGHT step is gated on {@link #ready}, so an unbounded wait
     * here does not merely delay the wall — it stalls the world, and it turns
     * every unrelated mod (Chunky, RoadWeaver, Streams) into a victim of this
     * one stage.
     *
     * <p>The wall is an optional structure, not a correctness prerequisite for
     * terrain. Once this budget expires the chunk is released unprotected and
     * the wall simply is absent from it; the alternative — holding the entire
     * world hostage to one planner stage — is strictly worse.
     *
     * <p>Measured cost of the alternative: a normal root search completes in
     * seconds. A stall past this budget means something is wrong, and the
     * session records {@code PRE_RECIPE_TIMEOUT} so the cause is diagnosable
     * instead of presenting as an unexplained freeze.
     */
    private static final long PRE_RECIPE_TIMEOUT_SECONDS =
            Math.max(5L, Long.getLong("greatwall.beforeRecipe.timeoutSeconds", 90L));
    private final java.util.concurrent.ScheduledExecutorService recipeTimeoutTimer;
    private final java.util.concurrent.atomic.AtomicLong preRecipeTimeouts = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Authorization is checked BEFORE deciding to await ready, avoiding an early-spawn dependency cycle.
     *
     * <p>The wait is bounded. See {@link #PRE_RECIPE_TIMEOUT_SECONDS} for why an
     * unbounded wait here is a whole-world outage rather than a local delay.
     */
    public CompletableFuture<Void> beforeRecipe(ChunkKey requested) {
        if (!requested.dimension().equals("minecraft:overworld")) return CompletableFuture.completedFuture(null);
        return authorized.thenCompose(value -> {
            active();
            if (mayPassBeforeRecipe(requested)) return CompletableFuture.completedFuture(null);
            var gate = new CompletableFuture<Void>();
            // Whichever arrives first wins: the recipe, or the budget. `ready` is
            // shared, so this must not be completed by cancelling it.
            ready.whenComplete((recipe, error) -> {
                if (error != null) gate.completeExceptionally(error); else gate.complete(null);
            });
            try {
                recipeTimeoutTimer.schedule(() -> {
                    if (gate.complete(null)) {
                        long total = preRecipeTimeouts.incrementAndGet();
                        // Log the first occurrence and then powers of two, so a
                        // sustained stall is visible without flooding the log.
                        if (total == 1L || (total & (total - 1L)) == 0L) {
                            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PRE_RECIPE_TIMEOUT no={} budget={}s "
                                            + "chunk={},{} — releasing the chunk unprotected; the wall will be "
                                            + "absent there rather than stalling world generation",
                                    total, PRE_RECIPE_TIMEOUT_SECONDS, requested.x(), requested.z());
                        }
                    }
                }, PRE_RECIPE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException stopping) {
                // Session closing: fall back to the unbounded wait so a shutdown
                // cannot silently mark chunks as passed.
                return ready.thenApply(recipe -> (Void) null);
            }
            return gate;
        }).copy();
    }

    /** Chunks released by the bounded wait instead of by a ready recipe. */
    public long preRecipeTimeoutCount() { return preRecipeTimeouts.get(); }

    public void spawnChosen(ServerLevel level, Point3i chosen) {
        owner(); active();
        if (!level.dimension().location().toString().equals("minecraft:overworld")) return;
        spawnChosen(chosen);
    }

    void spawnChosen(Point3i chosen) {
        owner(); active();
        if (spawn.putIfAbsent("chosen", Objects.requireNonNull(chosen)) != null) {
            throw new IllegalStateException("initial spawn may only be recorded once");
        }
        state.put("spawn-chosen", true);
        requestStart();
    }

    /** WorldServices freezes actual finalized registries and adapters before calling this method. */
    public void prepareLevelsEntered(ServerLevel level) {
        try { prepareLevelsEntered(level, ProductionInputs.freeze(level).bootstrap()); }
        catch (IOException error) { throw new WallFailure(FailureCode.IO_FAILED, "production input freeze failed", error); }
    }

    public void prepareLevelsEntered(ServerLevel level, Frozen inputs) {
        owner();
        if (server != level.getServer() || !level.dimension().location().toString().equals("minecraft:overworld")) {
            throw new IllegalArgumentException("bootstrap belongs to another overworld");
        }
        prepareFrozen(inputs);
    }

    void prepareFrozen(Frozen inputs) {
        owner(); active();
        if (frozen.putIfAbsent("inputs", Objects.requireNonNull(inputs)) != null) {
            throw new IllegalStateException("prepareLevels inputs already frozen");
        }
        requestStart();
    }

    public CompletableFuture<WorldRecipe> ready() { return ready.copy(); }
    public CompletableFuture<Void> drain() { return drained.copy(); }
    public Map<String, RootSearch.Progress> progress() { return Map.copyOf(progress); }
    public Map<String, List<RootSearch.Audit>> rootAudit() { return Map.copyOf(rootAudit); }
    public List<ChunkKey> earlyChunks() { return earlyDomain.snapshot(); }
    public Map<String, Boolean> diagnostics() { return Map.copyOf(state); }

    /** Owns neither oracle nor cursor; successful construction hands the original lane handles to planning. */
    public PlannerScheduler transferToPlanner(SegmentCacheStore cache, Executor plannerIo) {
        active();
        var recipe = recipes.get("ready");
        if (recipe == null) throw new IllegalStateException("durable recipe is not ready");
        transferred.compute("planner", (key, previous) -> {
            active();
            if (previous != null) throw new IllegalStateException("bootstrap lanes already transferred");
            return new PlannerScheduler(recipe, laneSnapshot.get("lanes"), cache, plannerIo);
        });
        if (state.containsKey("closed")) transferred.get("planner").close();
        return transferred.get("planner");
    }

    private void requestStart() {
        if (state.containsKey("closed") || state.containsKey("failed")) return;
        try {
            rootPump.execute(() -> authorized.whenCompleteAsync((authorization, error) -> {
                if (state.containsKey("closed") || state.containsKey("failed")) return;
                if (error != null) { fail(error); return; }
                var inputs = frozen.get("inputs");
                if (inputs == null || authorization.saved().isEmpty() && !spawn.containsKey("chosen")) return;
                if (state.putIfAbsent("root-started", true) != null) return;
                // The root search is the wall's first visible stage: from here until the
                // first segment is planned it has no count to show, so the loading card
                // reports "choosing the root position" with an elapsed time rather than
                // disappearing for minutes and looking like a hang.
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.beginRootSearch(
                        local.plaincraft.greatwall.terrain.LanePolicy.resolve().startupLaneCount());
                try {
                    var lanes = laneSnapshot.compute("lanes", (key, previous) -> {
                        active();
                        if (previous != null) throw new IllegalStateException("bootstrap lane group already created");
                        // Root search always runs on lane 0 and stays serial; the remaining lanes
                        // only widen route/geometry parallelism once planning begins.
                        //
                        // Constructed serially, and that is not the bottleneck: construction only
                        // allocates the lane's bookkeeping. The expensive part is the native
                        // oracle, which OracleLane builds inside start() - and start() asserts it
                        // runs on that lane's own dedicated thread, so it cannot be parallelised
                        // from here at all. An earlier revision started these constructors on
                        // sixteen threads expecting to save the ~232s the initialization costs;
                        // it saved nothing, because the work is not in the constructor. Admission
                        // of start() is what controls that cost, and PlannerScheduler already
                        // bounds it with LanePolicy.MAX_CONCURRENT_INITIALIZATIONS.
                        int count = local.plaincraft.greatwall.terrain.LanePolicy.resolve().startupLaneCount();
                        var built = new java.util.ArrayList<OracleLane>(count);
                        for (int index = 0; index < count; index++) {
                            built.add(new OracleLane(authorization.creation().instance(), index,
                                    inputs.terrainHash(), inputs.factory()));
                        }
                        return List.copyOf(built);
                    });
                    active();
                    if (authorization.saved().isPresent()) {
                        var recipe = authorization.saved().get();
                        validateSaved(recipe, inputs);
                        publish(recipe);
                    } else {
                        if(server!=null&&inputs.structures()==null)throw new IllegalStateException("native bootstrap requires a structure screen");
                        // A world that has already been searched keeps its axis on disk.
                        // Reusing it skips the whole search: a measured session spent about
                        // thirteen of its twenty-two prewarm minutes here, and a session that
                        // failed later in construction paid that cost again on every retry.
                        var cachedAxis = readCachedAxis(authorization.creation(), inputs);
                        if (cachedAxis.isPresent()) {
                            rootCacheHits.incrementAndGet();
                            var cachedSelection = new RootSearch.Selection(
                                    RootSearchCache.axisOf(cachedAxis.get()),
                                    cachedAxis.get().radiusIndex(), cachedAxis.get().angleIndex(),
                                    cachedAxis.get().axisIndex(), 0L, 0, 0L, 0L);
                            GreatWallMod.LOGGER.info("[GreatWall:S1C] ROOT_CACHE_HIT axis={} radiusIndex={} "
                                            + "angleIndex={} axisIndex={}",
                                    cachedSelection.axis(), cachedSelection.radiusIndex(),
                                    cachedSelection.angleIndex(), cachedSelection.axisIndex());
                            publishRecipeFor(cachedSelection, authorization.creation(), inputs);
                        } else {
                            var search = new RootSearch(spawn.get("chosen"), inputs.bounds(), earlyDomain.snapshot(), inputs.foundation(),inputs.structures()!=null, inputs.sanctuaryAvoidance());
                            advance(search, lanes.getFirst(), authorization.creation(), inputs);
                        }
                    }
                } catch (Throwable failure) { fail(failure); }
            }, rootPump));
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            if (!state.containsKey("closed")) fail(stopped);
        }
    }

    /**
     * Distributes the footprint's remaining column samples across the idle lanes.
     *
     * <h2>Why this is the fix and not an optimisation</h2>
     * A real session showed the root search sampling foundations for seven minutes
     * and forty-seven seconds on a single lane while fifteen sat idle. During that
     * window every chunk in the overworld waits on the recipe, so the search's
     * wall-clock time is a whole-world outage. Parallelising it is therefore the
     * difference between "the wall takes a while to appear" and "the world does not
     * generate".
     *
     * <h2>Why the result is unchanged</h2>
     * Only phase 1 is enumerated, and only its coordinates — the accept/reject
     * decision, the candidate order and the axis choice all still run on lane 0 in
     * the same sequence. Each lane builds its oracle from the same frozen inputs and
     * verifies the same terrain fingerprint, so a column's sample is a pure
     * function of its coordinates. Lane 0 consumes the cached value if it is there
     * and samples it itself if it is not, which means a prefetch that loses the
     * race costs nothing and one that arrives late is simply dropped.
     *
     * <h2>Bounded by construction</h2>
     * Work is handed out in chunks so a rejected candidate does not leave fifteen
     * lanes sampling thousands of columns nobody will read. Failures here are
     * swallowed on purpose: prefetching is best-effort acceleration, and lane 0
     * remains fully able to finish the search alone.
     */
    private void schedulePrefetch(RootSearch search, OracleLane primary) {
        List<OracleLane> lanes;
        try {
            lanes = laneSnapshot.get("lanes");
        } catch (RuntimeException unavailable) {
            return;
        }
        if (lanes == null || lanes.size() < 2) return;
        List<RootSearch.ColumnKey> pending;
        try {
            // Coarse phase 0 is the longest serial stretch: 144 candidates each sampling an
            // 81-point grid on lane 0 alone. Its coordinates are pure arithmetic too, so widen
            // it across the idle lanes exactly like the footprint phase. Either call returns
            // an empty list for the phases it does not own.
            pending = search.pendingCoarseReads();
            if (pending.isEmpty()) pending = search.pendingFootprintReads();
        } catch (RuntimeException notOwnerOrWrongPhase) {
            return;
        }
        if (pending.isEmpty()) return;
        // One chunk per idle lane keeps a rejected candidate from wasting a full
        // sweep, and keeps the queue shallow when lane 0 is already ahead.
        int perLane = Math.max(64, (pending.size() + lanes.size() - 1) / lanes.size());
        int cursor = 0;
        for (var lane : lanes) {
            if (lane == primary) continue;
            if (cursor >= pending.size()) break;
            int end = Math.min(pending.size(), cursor + perLane);
            var slice = List.copyOf(pending.subList(cursor, end));
            cursor = end;
            try {
                lane.submit(oracle -> {
                    for (var key : slice) {
                        if (state.containsKey("closed") || state.containsKey("failed")) break;
                        search.prefetch(key, oracle);
                    }
                    return null;
                });
            } catch (RuntimeException laneRefused) {
                // A closed or saturated lane just does not help this round.
                prefetchLaneRefusals.incrementAndGet();
            }
        }
        prefetchBatches.incrementAndGet();
        prefetchLastSize = pending.size();
    }

    /** Best-effort prefetch counters; they never gate the search itself. */
    private final java.util.concurrent.atomic.AtomicLong prefetchBatches = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong prefetchLaneRefusals = new java.util.concurrent.atomic.AtomicLong();
    private volatile int prefetchLastSize;
    /** Set when a cached root axis was reused, so a session that skipped the search says so. */
    private final java.util.concurrent.atomic.AtomicLong rootCacheHits = new java.util.concurrent.atomic.AtomicLong();
    /** Last logged root candidate; logging only on advance keeps the log bounded. */
    private final java.util.concurrent.atomic.AtomicLong lastLoggedCandidate = new java.util.concurrent.atomic.AtomicLong(-1L);

    /**
     * The save directory, or null when this bootstrap has no server (tests and the
     * analytic fixtures). A null result disables the root-search cache rather than
     * failing, because the cache is an optimisation and must never gate a session.
     */
    private java.nio.file.Path worldDirectory() {
        if (server == null) return null;
        try {
            return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** Root-search cache activity, for diagnostics. */
    public Map<String, Long> rootCacheDiagnostics() {
        return Map.of("root-cache-hits", rootCacheHits.get());
    }

    /** Prefetch activity, for diagnostics and for measuring the parallel speedup. */
    public Map<String, Long> prefetchDiagnostics() {
        return Map.of("prefetch-batches", prefetchBatches.get(),
                "prefetch-lane-refusals", prefetchLaneRefusals.get(),
                "prefetch-last-size", (long) prefetchLastSize);
    }

    /**
     * Applies the structure votes on a lane, retrying a refused submit.
     *
     * <p>The structure-screen path previously called {@code lane.submit} bare inside a
     * {@code thenCompose}: a saturated lane throws {@link java.util.concurrent.RejectedExecutionException},
     * which became a failed future and hit {@code fail(...)}, ending the whole bootstrap on a
     * transient condition. The main advance chain already retries; this mirrors that so the
     * vote application is equally resilient.
     */
    private CompletableFuture<Void> submitVotesWithRetry(RootSearch search, OracleLane lane,
            WorldManifest.Creation creation, Frozen inputs, List<RootStructureScreen.Vote> votes) {
        CompletableFuture<Void> submitted;
        try {
            submitted = lane.submit(oracle -> {
                active();
                search.acceptStructureVotes(votes);
                progress.put("root", search.progress());
                rootAudit.put("root", search.audit());
                return (Void) null;
            });
        } catch (RuntimeException rejected) {
            if (state.containsKey("closed") || state.containsKey("failed")) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new java.util.concurrent.CancellationException("bootstrap closed"));
            }
            // A saturated lane is transient; retry shortly on the root pump rather than
            // failing the bootstrap. schedule() tolerates the pump being busy.
            var retry = new CompletableFuture<Void>();
            rootPump.schedule(() -> submitVotesWithRetry(search, lane, creation, inputs, votes)
                    .whenComplete((ignored, error) -> {
                        if (error != null) retry.completeExceptionally(error);
                        else retry.complete(null);
                    }), 50, java.util.concurrent.TimeUnit.MILLISECONDS);
            return retry;
        }
        return submitted;
    }

    private void advance(RootSearch search, OracleLane lane, WorldManifest.Creation creation, Frozen inputs) {
        active();
        // Widen the search across the idle lanes before asking lane 0 to continue.
        // The root search itself is a serial state machine and stays on lane 0; what
        // parallelises is the column sampling, which is a pure function of the
        // coordinates and therefore produces identical values on every lane.
        schedulePrefetch(search, lane);
        final CompletableFuture<RootBatch> submitted;
        try {
            submitted = lane.submit(oracle -> {
                try {
                    var status = search.advance(oracle, 64);
                    return new RootBatch(status == WorkState.DONE ? Optional.of(search.finish()) : Optional.empty(), search.progress(),
                            search.needsStructureScreen()?search.pendingStructureCandidates():List.of());
                } finally {
                    progress.put("root", search.progress());
                    rootAudit.put("root", search.audit());
                }
            });
        } catch (RuntimeException rejected) {
            // The serial advance chain is the search's only driver: if this submit is
            // refused and the exception is allowed to propagate, the recursive
            // whenCompleteAsync below never runs and the chain stops for good. A
            // measured session did exactly that - the root pump and oracle threads both
            // went idle with no queued work, the prewarm never began, and the road mod
            // waited on it forever. Re-queue on the pump instead of dying: a saturated
            // lane is a transient condition, and retrying keeps the search alive.
            if (state.containsKey("closed") || state.containsKey("failed")) return;
            rootPump.execute(() -> advance(search, lane, creation, inputs));
            return;
        }
        submitted.whenCompleteAsync((batch, error) -> {
            if (state.containsKey("closed") || state.containsKey("failed")) {
                GreatWallMod.LOGGER.warn("[GreatWall:DIAG] advance.whenComplete ALREADY_CLOSED thread={} state={}",
                    Thread.currentThread().getName(), Map.copyOf(state));
                return;
            }
            if (error != null) {
                GreatWallMod.LOGGER.error("[GreatWall:DIAG] advance.whenComplete ERROR thread={}",
                    Thread.currentThread().getName(), error);
                fail(error);
                return;
            }
            progress.put("root", batch.progress());
            // Publish the candidate index to the loading card, so the root search shows a
            // moving number rather than an indeterminate "searching" that reads as frozen.
            var now = batch.progress();
            PrewarmProgress.setRootProgress(now.candidate(), 144);
            PrewarmProgress.setRootPhase(now.phase());
            // Logged only when the candidate advances, and never more than the finite
            // candidate set (at most 144 of them).
            long lastCandidate = lastLoggedCandidate.get();
            if (now.candidate() != lastCandidate && now.candidate() < 200) {
                lastLoggedCandidate.set(now.candidate());
                GreatWallMod.LOGGER.info("[GreatWall:S1C] ROOT_SEARCH phase={} candidate={} shortlisted={} "
                                + "footprintColumn={} axis={} columns={}",
                        now.phase(), now.candidate(), now.shortlisted(),
                        now.footprintColumn(), now.axisIndex(), now.columns());
            }
            if(!batch.screen().isEmpty()){
                state.put("structure-screen-entered",true);
                GreatWallMod.LOGGER.info("[GreatWall:DIAG] advance STRUCTURE_SCREEN thread={} screenSize={}",
                    Thread.currentThread().getName(), batch.screen().size());
                try {
                    inputs.structures().inspect(batch.screen()).thenCompose(votes->submitVotesWithRetry(search, lane, creation, inputs, votes))
                            .whenCompleteAsync((ignored,failure)->{
                        if(state.containsKey("closed")||state.containsKey("failed"))return;
                        if(failure!=null){fail(failure);return;}
                        state.put("structure-screen-complete",true);advance(search,lane,creation,inputs);
                    },rootPump);
                }catch(Throwable failure){fail(failure);}
                return;
            }
            if (batch.selected().isEmpty()) {
                // Same reasoning as the submit guard above: advance() is the search's
                // only driver, and any throw here - from active(), schedulePrefetch or
                // an unexpected runtime failure - would run inside rootPump's task,
                // whose exception is swallowed with the queue, so the search would stop
                // with no log and no future work. Re-queueing on failure keeps it alive.
                try {
                    advance(search, lane, creation, inputs);
                } catch (RuntimeException failure) {
                    if (state.containsKey("closed") || state.containsKey("failed")) return;
                    rootPump.execute(() -> advance(search, lane, creation, inputs));
                }
                return;
            }
            var selection = batch.selected().get();
            GreatWallMod.LOGGER.info("[GreatWall:DIAG] advance SELECTED thread={} axis={}",
                Thread.currentThread().getName(), selection.axis());
            writeCachedAxis(creation, inputs, selection);
            publishRecipeFor(selection, creation, inputs);
        }, rootPump);
    }

    /**
     * Turns a selection into a frozen recipe and publishes it.
     *
     * <p>Shared by the searched and the cached paths so a cached axis produces exactly
     * the same recipe hash as a searched one — the recipe is derived from the axis, so
     * there is no second code path that could disagree.
     */
    private void publishRecipeFor(RootSearch.Selection selection, WorldManifest.Creation creation, Frozen inputs) {
        GreatWallMod.LOGGER.info("[GreatWall:DIAG] publishRecipeFor ENTER thread={} state={}", Thread.currentThread().getName(), Map.copyOf(state));
        var provisional = new WorldRecipe(creation.instance(), "0".repeat(64), seed, "minecraft:overworld",
                spawn.get("chosen"), selection.axis(), inputs.bounds(), defaultConfig(), inputs.terrainHash(),
                inputs.paletteHash(), inputs.templatesHash(), "0".repeat(64));
        String hash = DeterministicHash.sha256(CanonicalCodec.recipe(provisional));
        var recipe = new WorldRecipe(creation.instance(), hash, seed, provisional.dimension(), provisional.frozenSpawn(),
                provisional.axis(), provisional.bounds(), provisional.config(), inputs.terrainHash(),
                inputs.paletteHash(), inputs.templatesHash(), hash);
        GreatWallMod.LOGGER.info("[GreatWall:DIAG] publishRecipeFor BEFORE_IO thread={}", Thread.currentThread().getName());
        CompletableFuture<WorldRecipe> ioFuture;
        try {
            ioFuture = io(() -> { active(); manifest.freeze(recipe); return recipe; });
            GreatWallMod.LOGGER.info("[GreatWall:DIAG] publishRecipeFor AFTER_IO thread={}", Thread.currentThread().getName());
        } catch (Throwable ioError) {
            GreatWallMod.LOGGER.error("[GreatWall:DIAG] publishRecipeFor IO_THREW thread={}", Thread.currentThread().getName(), ioError);
            throw ioError;
        }
        ioFuture.whenComplete((saved, failure) -> {
            GreatWallMod.LOGGER.info("[GreatWall:DIAG] publishRecipeFor WHENCOMPLETE thread={} failure={}", Thread.currentThread().getName(), failure != null);
            if (failure != null) {
                GreatWallMod.LOGGER.error("[GreatWall:DIAG] publishRecipeFor FAILURE_BRANCH thread={}", Thread.currentThread().getName(), failure);
                fail(failure);
            } else if (!state.containsKey("closed") && !state.containsKey("failed")) {
                GreatWallMod.LOGGER.info("[GreatWall:DIAG] publishRecipeFor SUCCESS_BRANCH thread={}", Thread.currentThread().getName());
                publish(saved);
            } else {
                GreatWallMod.LOGGER.warn("[GreatWall:DIAG] publishRecipeFor ALREADY_CLOSED thread={} state={}", Thread.currentThread().getName(), Map.copyOf(state));
            }
        });
        GreatWallMod.LOGGER.info("[GreatWall:DIAG] publishRecipeFor EXIT thread={}", Thread.currentThread().getName());
    }

    /**
     * Reads the cached axis when it still matches this world's inputs.
     *
     * <p>Returns empty for every failure mode — absent file, unreadable file, identity
     * mismatch — so the caller simply searches instead. The cache shortens a launch; it
     * never decides whether one happens.
     */
    private Optional<RootSearchCache.Entry> readCachedAxis(WorldManifest.Creation creation, Frozen inputs) {
        var directory = worldDirectory();
        if (directory == null) return Optional.empty();
        var chosen = spawn.get("chosen");
        if (chosen == null) return Optional.empty();
        try {
            var identity = RootSearchCache.identityOf(seed, "minecraft:overworld", chosen,
                    inputs.terrainHash(), inputs.paletteHash(), inputs.templatesHash());
            var entry = RootSearchCache.read(directory, identity);
            if (entry.isPresent()) return entry;
            GreatWallMod.LOGGER.info("[GreatWall:S1C] ROOT_CACHE_MISS searching; no usable entry at {}",
                    RootSearchCache.file(directory));
            return Optional.empty();
        } catch (RuntimeException unusable) {
            return Optional.empty();
        }
    }

    /** Records the searched axis for the next launch of this world. */
    private void writeCachedAxis(WorldManifest.Creation creation, Frozen inputs, RootSearch.Selection selection) {
        var directory = worldDirectory();
        if (directory == null) return;
        var chosen = spawn.get("chosen");
        if (chosen == null) return;
        try {
            var identity = RootSearchCache.identityOf(seed, "minecraft:overworld", chosen,
                    inputs.terrainHash(), inputs.paletteHash(), inputs.templatesHash());
            RootSearchCache.write(directory, RootSearchCache.of(identity, selection.axis(),
                    selection.radiusIndex(), selection.angleIndex(), selection.axisIndex()));
            GreatWallMod.LOGGER.info("[GreatWall:S1C] ROOT_CACHE_STORED axis={} radiusIndex={} angleIndex={} "
                            + "axisIndex={}",
                    selection.axis(), selection.radiusIndex(), selection.angleIndex(), selection.axisIndex());
        } catch (RuntimeException unwritable) {
            // A cache write failure costs time on the next launch, never correctness now.
        }
    }

    private void validateSaved(WorldRecipe recipe, Frozen inputs) {
        if (recipe.seed() != seed || !recipe.bounds().equals(inputs.bounds())
                || !recipe.config().equals(defaultConfig()) || !recipe.terrainHash().equals(inputs.terrainHash())
                || !recipe.paletteHash().equals(inputs.paletteHash()) || !recipe.templatesHash().equals(inputs.templatesHash())) {
            throw new WallFailure(FailureCode.RECIPE_MISMATCH, "saved production recipe/settings no longer match; new world required");
        }
    }

    private void publish(WorldRecipe recipe) {
        active(); recipes.put("ready", recipe); state.put("durable-ready", true);
        ready.complete(recipe);
    }

    private void fail(Throwable error) {
        if (state.putIfAbsent("failed", true) != null) {
            GreatWallMod.LOGGER.warn("[GreatWall:DIAG] fail() ALREADY_FAILED thread={} newError={}",
                Thread.currentThread().getName(), error.toString());
            return;
        }
        GreatWallMod.LOGGER.error("[GreatWall:DIAG] fail() FIRST_FAILURE thread={} state={}",
            Thread.currentThread().getName(), Map.copyOf(state), error);
        ready.completeExceptionally(error);
        close();
    }

    private <T> CompletableFuture<T> io(IoAction<T> action) {
        GreatWallMod.LOGGER.info("[GreatWall:DIAG] io() ENTER thread={} state={}", Thread.currentThread().getName(), Map.copyOf(state));
        try {
            active();
            GreatWallMod.LOGGER.info("[GreatWall:DIAG] io() AFTER_ACTIVE thread={}", Thread.currentThread().getName());
        } catch (Throwable activeError) {
            GreatWallMod.LOGGER.error("[GreatWall:DIAG] io() ACTIVE_THREW thread={} state={}", Thread.currentThread().getName(), Map.copyOf(state), activeError);
            throw activeError;
        }
        var result = new CompletableFuture<T>();
        try {
            ioFlights.compute("admitted", (key, old) -> {
                active();
                var pending = old == null ? new java.util.ArrayList<CompletableFuture<?>>()
                        : new java.util.ArrayList<>(old);
                if (pending.size() >= 128) throw new WallFailure(FailureCode.CAPACITY, "bootstrap IO admission capacity");
                pending.add(result); return List.copyOf(pending);
            });
            GreatWallMod.LOGGER.info("[GreatWall:DIAG] io() AFTER_ADMIT thread={} admitted={}", Thread.currentThread().getName(), ioFlights.get("admitted").size());
        } catch (Throwable admitError) {
            GreatWallMod.LOGGER.error("[GreatWall:DIAG] io() ADMIT_THREW thread={}", Thread.currentThread().getName(), admitError);
            throw admitError;
        }
        result.whenComplete((value, error) -> ioFlights.computeIfPresent("admitted", (key, old) ->
                old.stream().filter(future -> future != result).toList()));
        try {
            GreatWallMod.LOGGER.info("[GreatWall:DIAG] io() BEFORE_EXECUTE thread={}", Thread.currentThread().getName());
            io.execute(() -> {
                GreatWallMod.LOGGER.info("[GreatWall:DIAG] io() TASK_START thread={}", Thread.currentThread().getName());
                try {
                    T value = action.run();
                    GreatWallMod.LOGGER.info("[GreatWall:DIAG] io() ACTION_SUCCESS thread={}", Thread.currentThread().getName());
                    result.complete(value);
                } catch (Throwable error) {
                    GreatWallMod.LOGGER.error("[GreatWall:DIAG] io() ACTION_THREW thread={}", Thread.currentThread().getName(), error);
                    result.completeExceptionally(error);
                }
            });
            GreatWallMod.LOGGER.info("[GreatWall:DIAG] io() AFTER_EXECUTE thread={}", Thread.currentThread().getName());
        } catch (RuntimeException error) {
            GreatWallMod.LOGGER.error("[GreatWall:DIAG] io() EXECUTE_REJECTED thread={}", Thread.currentThread().getName(), error);
            result.completeExceptionally(error);
        }
        return result;
    }
    @FunctionalInterface private interface IoAction<T> { T run() throws IOException; }
    private void owner() {
        if (Thread.currentThread() != eventOwner || server != null && !server.isSameThread()) {
            throw new IllegalStateException("bootstrap lifecycle input belongs to server owner");
        }
    }
    private void active() {
        if (state.containsKey("closed") || state.containsKey("failed")) {
            var trace = new RuntimeException("active() check failed - stack trace");
            GreatWallMod.LOGGER.error("[GreatWall:DIAG] active() FAILED thread={} closed={} failed={} failures={}",
                Thread.currentThread().getName(), state.containsKey("closed"), state.containsKey("failed"),
                Map.copyOf(state), trace);
            throw new WallFailure(FailureCode.CLOSED, "bootstrap is closed or failed");
        }
    }

    @Override public void close() {
        if (state.putIfAbsent("closed", true) != null) return;
        ready.completeExceptionally(new WallFailure(FailureCode.CLOSED, "bootstrap closed"));
        var planner = transferred.computeIfPresent("planner", (key, value) -> { value.close(); return value; });
        rootPump.shutdown();
        // Closing the session releases every bounded beforeRecipe wait through the
        // shared `ready` future above, so no chunk is left parked on the timer.
        recipeTimeoutTimer.shutdown();
        var inputs=frozen.get("inputs");
        if(inputs!=null&&inputs.structures()!=null)inputs.structures().close();
        var captured = new java.util.concurrent.atomic.AtomicReference<List<OracleLane>>(List.of());
        laneSnapshot.computeIfPresent("lanes", (key, lanes) -> {
            captured.set(lanes);
            if (planner == null) lanes.forEach(OracleLane::close);
            return lanes;
        });
        var lanes = captured.get();
        var done = planner == null ? CompletableFuture.allOf(lanes.stream().map(OracleLane::drain).toArray(CompletableFuture[]::new))
                : planner.drain();
        var ioSnapshot = new java.util.concurrent.atomic.AtomicReference<List<CompletableFuture<?>>>(List.of());
        ioFlights.computeIfPresent("admitted", (key, pending) -> { ioSnapshot.set(pending); return pending; });
        var pendingIo = CompletableFuture.allOf(ioSnapshot.get().stream()
                .map(value -> value.handle((result, error) -> null)).toArray(CompletableFuture[]::new));
        var structures=inputs==null||inputs.structures()==null?CompletableFuture.<Void>completedFuture(null):inputs.structures().drained();
        CompletableFuture.allOf(done, pendingIo,structures).whenComplete((value, error) -> {
            if (error == null) drained.complete(null); else drained.completeExceptionally(error);
        });
    }
}
