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
            local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance sanctuaryAvoidance, MacroRiverWater riverWater) {
        public Frozen(DimensionBounds bounds, String terrainHash, String paletteHash, String templatesHash,
                Supplier<NaturalTerrainOracle> factory,
                BiPredicate<NaturalTerrainOracle, NaturalTerrainOracle.ColumnCursor> foundation, RootStructureScreen structures,
                local.plaincraft.greatwall.compat.erdtree.SanctuaryAvoidance sanctuaryAvoidance) {
            this(bounds, terrainHash, paletteHash, templatesHash, factory, foundation, structures, sanctuaryAvoidance,
                    MacroRiverWater.NONE);
        }
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
            Objects.requireNonNull(bounds); Objects.requireNonNull(factory); Objects.requireNonNull(foundation); Objects.requireNonNull(riverWater);
            local.plaincraft.greatwall.model.WallTypes.requireHash(terrainHash);
            local.plaincraft.greatwall.model.WallTypes.requireHash(paletteHash);
            local.plaincraft.greatwall.model.WallTypes.requireHash(templatesHash);
        }
    }
    private record RootBatch(Optional<RootSearch.Selection> selected, RootSearch.Progress progress,
            List<RootStructureScreen.Candidate> screen) {}
    private final MinecraftServer server;
    private final WorldManifest manifest;
    private final Executor io;
    private final long seed;
    private final RootAxisWindow rootWindow = RootAxisWindow.current();
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
    private final CompletableFuture<WorldRecipeSelection> authorized;
    private final CompletableFuture<WorldRecipe> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final ScheduledThreadPoolExecutor rootPump;

    public WorldBootstrap(MinecraftServer server, WorldManifest manifest, Executor io) {
        this(server, manifest, io, server.getWorldData().worldGenOptions().seed(),
                server.getWorldData().overworldData().isInitialized(), ProductionRecipeVersions::newWorld, List.of());
    }

    WorldBootstrap(WorldManifest manifest, Executor io, long seed, boolean originallyInitialized) {
        this(null, manifest, io, seed, originallyInitialized, WorldBootstrap::defaultConfig, List.of());
    }

    WorldBootstrap(MinecraftServer server, WorldManifest manifest, Executor io, long seed, boolean originallyInitialized,
            Supplier<GenerationConfig> newWorld, List<GenerationConfig> readable) {
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
        // 授权后沿用所选配置，避免发布配方和读写根缓存各自重新取默认值。
        authorized = io(() -> WorldRecipeSelection.open(manifest, originallyInitialized, seed, newWorld.get(), readable));
        authorized.whenComplete((value, error) -> {
            if (error != null) fail(error); else requestStart();
        });
    }

    /**
     * The frozen {@link GenerationConfig} every world of this session is built with.
     *
     * <p>The final component is the digest of the generation parameters in effect. Reading
     * {@code GreatWallConfig.parameters()} here is what freezes them: this method is called first at
     * authorization time, before any planner runs, so a config edit made later cannot reach
     * generation. An edit made before the world loads changes the digest, and the world's creation
     * token then refuses with a parameter-identity mismatch rather than generating a different wall.
     */
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
                64L << 20, 256L << 20, 256L << 20,
                local.plaincraft.greatwall.config.GreatWallConfig.parameters().digest());
    }

    public void authorizeSpawnProbe(ChunkKey candidate, int nativeDependencyRadius) {
        owner(); active();
        if (originallyInitialized || state.containsKey("root-started") || recipes.containsKey("ready")) return;
        try { earlyDomain.authorize(candidate, nativeDependencyRadius); }
        catch (RuntimeException error) { fail(error); throw error; }
    }

    public boolean mayPassBeforeRecipe(ChunkKey requested) {
        // A predicate on the chunk pipeline must never throw. This is called from the feature stage of
        // every chunk in the early domain, so a failed session used to fail those chunks outright:
        // measured after ROOT_UNAVAILABLE, "Error upgrading chunk [-3,3] to minecraft:features" followed
        // by "Failed to load chunk -3,3". A wall that is absent is a reason to let the chunk through.
        if (state.containsKey("closed") || state.containsKey("failed")) return true;
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
        // Same rule as mayPassBeforeRecipe: a session that already failed must release the chunk rather
        // than complete this future exceptionally, because the future sits on the worldgen path.
        if (state.containsKey("closed") || state.containsKey("failed")) {
            return CompletableFuture.completedFuture(null);
        }
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
        try { prepareLevelsEntered(level, ProductionInputs.freeze(level, configuration().join().templateVersion()).bootstrap()); }
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
    /** 只等待凭证文件的版本选择，不依赖根选址；冻结资源时就必须使用该版本。 */
    public CompletableFuture<GenerationConfig> configuration() { return authorized.thenApply(WorldRecipeSelection::config); }
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
                        validateSaved(recipe, inputs, authorization.config());
                        publish(recipe);
                    } else {
                        if(server!=null&&inputs.structures()==null)throw new IllegalStateException("native bootstrap requires a structure screen");
                        // A world that has already been searched keeps its axis on disk.
                        // Reusing it skips the whole search: a measured session spent about
                        // thirteen of its twenty-two prewarm minutes here, and a session that
                        // failed later in construction paid that cost again on every retry.
                        var cachedAxis = readCachedAxis(authorization.config(), inputs);
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
                            publishRecipeFor(cachedSelection, authorization, inputs);
                        } else {
                            var search = new RootSearch(spawn.get("chosen"), inputs.bounds(), earlyDomain.snapshot(),
                                    inputs.foundation(), inputs.structures() != null, inputs.sanctuaryAvoidance(),
                                    local.plaincraft.greatwall.model.WallRecipeRules.landFirst(authorization.config()),
                                    rootWindow, inputs.riverWater());
                            rootSearchStartedAt = System.nanoTime();
                            advance(search, lanes.getFirst(), authorization, inputs);
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
        // sweep, and keeps the queue shallow when lane 0 is already ahead. The
        // per-lane slice is sized by the idle lanes only (lane 0 stays serial), so a
        // small pending window (81 points per coarse candidate) spreads across every
        // idle lane instead of piling 64 points on one lane and starving the rest.
        int idleLanes = Math.max(1, lanes.size() - 1);
        int perLane = Math.max(4, (pending.size() + idleLanes - 1) / idleLanes);
        int cursor = 0;
        for (var lane : lanes) {
            if (lane == primary) continue;
            if (cursor >= pending.size()) break;
            int end = Math.min(pending.size(), cursor + perLane);
            var slice = List.copyOf(pending.subList(cursor, end));
            cursor = end;
            // Claim what this lane will sample before handing it over. The pending list is rebuilt from
            // arithmetic on every advance, so without a claim the same column went to several lanes in
            // several rounds and was sampled repeatedly - only the last put survived, and the measured
            // session's search spent its twelve lanes to gain well under half of their throughput.
            var mine = new java.util.ArrayList<RootSearch.ColumnKey>(slice.size());
            for (var key : slice) {
                if (search.claimPrefetch(key)) mine.add(key);
            }
            if (mine.isEmpty()) continue;
            try {
                var accepted = lane.submit(oracle -> {
                    try {
                        for (var key : mine) {
                            if (state.containsKey("closed") || state.containsKey("failed")) break;
                            search.prefetch(key, oracle);
                        }
                    } finally {
                        // Whatever was not reached goes back to the pool of claimable columns.
                        search.releasePrefetch(mine);
                    }
                    return null;
                });
                if (accepted != null) {
                    accepted.whenComplete((ignored, failure) -> {
                        if (failure == null) return;
                        // A saturated lane answers with a failed future instead of a throw, which is why the
                        // refusal counter used to stay at zero: the catch below only ever saw closed lanes.
                        prefetchLaneRefusals.incrementAndGet();
                        search.releasePrefetch(mine);
                    });
                }
            } catch (RuntimeException laneRefused) {
                // A closed lane just does not help this round.
                prefetchLaneRefusals.incrementAndGet();
                search.releasePrefetch(mine);
            }
        }
        prefetchBatches.incrementAndGet();
        prefetchLastSize = pending.size();
    }

    /** Best-effort prefetch counters; they never gate the search itself. */
    private final java.util.concurrent.atomic.AtomicLong prefetchBatches = new java.util.concurrent.atomic.AtomicLong();
    /**
     * Real drive-loop rounds: one per {@code advance} entry, whether or not a prefetch was scheduled.
     *
     * <p>{@code prefetch-batches} only counts rounds whose pending list was non-empty, so it is a lower
     * bound on rounds and {@code wall / rounds} derived from it is an upper bound on per-round cost.
     * This one is the true count; the two together separate round-trip overhead from batch work.
     * Diagnostics only: nothing reads it.
     */
    private final java.util.concurrent.atomic.AtomicLong roundTrips = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong sumSchedulePrefetchNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong sumSubmitToStartNanos = new java.util.concurrent.atomic.AtomicLong();
    /**
     * How many {@code RootSearch.advance} batches one lane submission drives before the chain returns
     * to the root pump.
     *
     * <p>Each round trip costs a measured ~75 ms that is neither batch work nor scheduling
     * (lane finished -> pump callback -> next submit), and at 1,440 rounds that was 108 s of a 275 s
     * root search. Driving N batches per submission amortises that fixed cost by N while keeping
     * every batch on lane 0, which is what {@code RootSearch.claimOwner()} requires. Only the progress
     * card notices: it refreshes once per submission instead of once per batch.
     *
     * <p>1 is the exact previous behaviour - the loop below is skipped entirely - and is the fallback.
     */
    private static final int BATCHES_PER_SUBMIT = Math.max(1, Math.min(64,
            Integer.getInteger("greatwall.batchesPerSubmit", 8)));
    /**
     * Wall-clock cap on one lane submission, so a run of slow batches cannot hold lane 0 forever.
     *
     * <p>Eight nominal batches are ~890 ms; the cap only matters when individual batches overshoot
     * (measured maxBatchMs 675), and it keeps the worst case bounded at 2 s instead of 8 x 675 ms.
     */
    private static final long LANE_BUDGET_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            Math.max(100L, Long.getLong("greatwall.laneBudgetMillis", 2_000L)));
    /**
     * The part of a round that no other timer covered: lane work finished -&gt; the whenCompleteAsync
     * callback actually entered. That hop runs on rootPump, whose queue also carries the io
     * completions and the screen retries, so this is where a stalled pump or a busy queue shows up.
     * Diagnostics only.
     */
    private final java.util.concurrent.atomic.AtomicLong sumPumpHopNanos = new java.util.concurrent.atomic.AtomicLong();
    /** Callback body itself: progress/audit publication and the two card atomics, excluding the hop. */
    private final java.util.concurrent.atomic.AtomicLong sumCallbackNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong prefetchLaneRefusals = new java.util.concurrent.atomic.AtomicLong();
    private volatile int prefetchLastSize;
    /** Set when a cached root axis was reused, so a session that skipped the search says so. */
    private final java.util.concurrent.atomic.AtomicLong rootCacheHits = new java.util.concurrent.atomic.AtomicLong();
    /** Last logged root candidate; logging only on advance keeps the log bounded. */
    private final java.util.concurrent.atomic.AtomicLong lastLoggedCandidate = new java.util.concurrent.atomic.AtomicLong(-1L);
    /** Last root-search phase a progress line was printed for, so a phase change always logs. */
    private volatile int lastLoggedPhase = -1;

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
    /**
     * Screens one batch of candidates and applies its votes, trying the next batch when this one is
     * entirely blocked.
     *
     * <p>{@link RootSearch#acceptStructureVotes} throws when everything it screened conflicts, and
     * that must stay - it is the rule that stops the wall being sited through a village. What can
     * change is how many candidates are screened before giving up, so a blocked batch moves on to the
     * next one while any remain.
     */
    private CompletableFuture<Void> screenThenVote(RootSearch search, OracleLane lane,
            Frozen inputs, List<RootStructureScreen.Candidate> candidates) {
        CompletableFuture<List<RootStructureScreen.Vote>> inspected = candidates.isEmpty()
                ? CompletableFuture.completedFuture(List.of()) : inputs.structures().inspect(candidates);
        return inspected.thenCompose(votes -> submitVotesWithRetry(search, lane, votes))
                .thenCompose(result -> {
                    if (result.failure() != null) return CompletableFuture.failedFuture(result.failure());
                    if (result.next().isEmpty()) return CompletableFuture.completedFuture(null);
                    GreatWallMod.LOGGER.info("[GreatWall:S1C] ROOT_SCREEN_NEXT_BATCH 首批受保护，继续检查 {} 个候选", result.next().size());
                    return screenThenVote(search, lane, inputs, result.next());
                });
    }

    private record VoteResult(List<RootStructureScreen.Candidate> next, WallFailure failure) {}

    private CompletableFuture<VoteResult> submitVotesWithRetry(RootSearch search, OracleLane lane,
            List<RootStructureScreen.Vote> votes) {
        try {
            return lane.submit(oracle -> {
                active();
                try {
                    search.acceptStructureVotes(votes);
                } catch (WallFailure blocked) {
                    // 正常拒绝不能让采样线程永久报故障；候选列表和游标也只允许由所属线程读取。
                    if (blocked.code() != local.plaincraft.greatwall.model.WallTypes.FailureCode.ROOT_NOT_FOUND) throw blocked;
                    return search.screenNextStructureBatch()
                            ? new VoteResult(search.pendingStructureCandidates(), null)
                            : new VoteResult(List.of(), blocked);
                }
                progress.put("root", search.progress());
                rootAudit.put("root", search.audit());
                return new VoteResult(List.of(), null);
            });
        } catch (RuntimeException rejected) {
            if (state.containsKey("closed") || state.containsKey("failed")) {
                return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("bootstrap closed"));
            }
            var retry = new CompletableFuture<VoteResult>();
            rootPump.schedule(() -> submitVotesWithRetry(search, lane, votes)
                    .whenComplete((result, error) -> {
                        if (error != null) retry.completeExceptionally(error);
                        else retry.complete(result);
                    }), 50, java.util.concurrent.TimeUnit.MILLISECONDS);
            return retry;
        }
    }

    private void advance(RootSearch search, OracleLane lane, WorldRecipeSelection selection, Frozen inputs) {
        active();
        roundTrips.incrementAndGet();
        // Widen the search across the idle lanes before asking lane 0 to continue.
        // The root search itself is a serial state machine and stays on lane 0; what
        // parallelises is the column sampling, which is a pure function of the
        // coordinates and therefore produces identical values on every lane.
        long scheduleStarted = System.nanoTime();
        try {
            schedulePrefetch(search, lane);
        } finally {
            sumSchedulePrefetchNanos.addAndGet(System.nanoTime() - scheduleStarted);
        }
        final CompletableFuture<RootBatch> submitted;
        final long submittedAt = System.nanoTime();
        // Stamped as the very last thing the lane task does, so the callback can measure the hop.
        final long[] laneDoneAt = {0L};
        try {
            submitted = lane.submit(oracle -> {
                sumSubmitToStartNanos.addAndGet(System.nanoTime() - submittedAt);
                try {
                    var status = search.advance(oracle, 256);
                    // Only two things can end the drive: the search finished (DONE) or it needs the
                    // structure screen (phase 4), which must run on the pump. WorkState has no third
                    // value, so there is nothing else to break on.
                    if (BATCHES_PER_SUBMIT > 1) {
                        int batches = 1;
                        long laneDeadline = System.nanoTime() + LANE_BUDGET_NANOS;
                        while (status == WorkState.MORE && !search.needsStructureScreen()
                                && batches < BATCHES_PER_SUBMIT
                                && System.nanoTime() < laneDeadline) {
                            status = search.advance(oracle, 256);
                            batches++;
                        }
                    }
                    return new RootBatch(status == WorkState.DONE ? Optional.of(search.finish()) : Optional.empty(), search.progress(),
                            search.needsStructureScreen()?search.pendingStructureCandidates():List.of());
                } finally {
                    progress.put("root", search.progress());
                    rootAudit.put("root", search.audit());
                    laneDoneAt[0] = System.nanoTime();
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
            rootPump.execute(() -> advance(search, lane, selection, inputs));
            return;
        }
        submitted.whenCompleteAsync((batch, error) -> {
            final long callbackEntered = System.nanoTime();
            if (laneDoneAt[0] != 0L) sumPumpHopNanos.addAndGet(callbackEntered - laneDoneAt[0]);
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
            // A measured session died exactly here: a getter called below threw
            // IllegalStateException, the exception landed in the future this whenCompleteAsync
            // returns - which nobody holds - and the publish chain stopped with an empty log.
            // Everything from this point runs inside the try so a continuation failure is
            // reported and reaches the session's own failure channel instead of vanishing.
            // The body keeps its original indentation on purpose: wrapping it must not move a
            // single byte of the logic that publishes the recipe.
            try {
            progress.put("root", batch.progress());
            // Publish the candidate index to the loading card, so the root search shows a
            // moving number rather than an indeterminate "searching" that reads as frozen.
            var now = batch.progress();
            // 主进度和详情使用同一批次的单位；粗采按已判网格，复核按占地和轴向。
            PrewarmProgress.setRootDisplay(now.phase(), now.workDone(), now.workTotal());
            // One line per 25 candidates, plus every phase change. The ring is RADIUS_COUNT x 36 = 324
            // candidates, and a line per candidate produced 200 lines that carried nothing a reader could
            // act on - they buried the lines that do matter. Only the sampling changed, not the numbers.
            long lastCandidate = lastLoggedCandidate.get();
            boolean newPhase = now.phase() != lastLoggedPhase;
            if (newPhase || now.candidate() - lastCandidate >= 25) {
                lastLoggedCandidate.set(now.candidate());
                lastLoggedPhase = now.phase();
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
                    screenThenVote(search, lane, inputs, batch.screen())
                            .whenCompleteAsync((ignored,failure)->{
                        if(state.containsKey("closed")||state.containsKey("failed"))return;
                        if(failure!=null){fail(failure);return;}
                        state.put("structure-screen-complete",true);advance(search,lane,selection,inputs);
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
                    advance(search, lane, selection, inputs);
                } catch (RuntimeException failure) {
                    if (state.containsKey("closed") || state.containsKey("failed")) return;
                    rootPump.execute(() -> advance(search, lane, selection, inputs));
                }
                return;
            }
            var root = batch.selected().get();
            GreatWallMod.LOGGER.info("[GreatWall:DIAG] advance SELECTED thread={} axis={}",
                Thread.currentThread().getName(), root.axis());
            logRootSearchDone(search, batch.progress());
            writeCachedAxis(selection.config(), inputs, root);
            publishRecipeFor(root, selection, inputs);
            } catch (Throwable continuationFailure) {
                // Reported, not swallowed. fail(...) is this session's existing failure channel:
                // it sets the failed state, completes ready() exceptionally and closes the
                // bootstrap, so whoever waits on ready() learns that the recipe will not publish.
                GreatWallMod.LOGGER.error("[PGW-ROOT-CONTINUATION] root continuation failed, "
                        + "recipe will not publish thread={} state={}",
                        Thread.currentThread().getName(), Map.copyOf(state), continuationFailure);
                try {
                    fail(continuationFailure);
                } catch (Throwable reportingFailed) {
                    // Never lose the original evidence to a secondary failure in reporting it.
                    GreatWallMod.LOGGER.error("[PGW-ROOT-CONTINUATION] reporting the continuation "
                            + "failure failed too", reportingFailed);
                }
            } finally {
                sumCallbackNanos.addAndGet(System.nanoTime() - callbackEntered);
            }
        }, rootPump);
    }

    /** Wall-clock of the root search, for the cost line's parallelism ratio. */
    private volatile long rootSearchStartedAt;

    /**
     * Reports where the root search's terrain work actually went.
     *
     * <p>Two sessions' worth of analysis had to reconstruct this from the wall clock and a two-equation
     * model, because the search reported its <em>progress</em> and never its <em>cost</em> - while the oracle
     * lanes have carried a per-lane cost model all along. Every number below is a sum over the lanes, so
     * dividing the slice/block totals by the search's own wall clock yields the effective parallelism, which
     * is the one measurement that says whether the prefetch lanes are earning their keep.
     */
    private void logRootSearchDone(RootSearch search, RootSearch.Progress progress) {
        long elapsedNanos = rootSearchStartedAt == 0L ? 0L : System.nanoTime() - rootSearchStartedAt;
        long sliceMs = 0L, blockMs = 0L, cellMs = 0L, biomeMs = 0L, constructMs = 0L, surfaceMs = 0L;
        long hits = 0L, misses = 0L, walkedBlocks = 0L, leadRetries = 0L;
        List<local.plaincraft.greatwall.terrain.OracleLane> lanes;
        try {
            lanes = laneSnapshot.get("lanes");
        } catch (RuntimeException unavailable) {
            lanes = null;
        }
        if (lanes != null) {
            for (var lane : lanes) {
                if (lane == null) continue;
                Map<String, Long> diagnostic = lane.diagnostics();
                sliceMs += count(diagnostic, "slice-ms");
                blockMs += count(diagnostic, "block-ms");
                cellMs += count(diagnostic, "cell-ms");
                biomeMs += count(diagnostic, "biome-ms");
                constructMs += count(diagnostic, "construct-ms");
                surfaceMs += count(diagnostic, "surface-ms");
                hits += count(diagnostic, "chunk-cache-hits");
                misses += count(diagnostic, "chunk-cache-misses");
                walkedBlocks += count(diagnostic, "walked-blocks");
                leadRetries += count(diagnostic, "lead-retries");
            }
        }
        double elapsedMs = Math.max(1.0D, elapsedNanos / 1_000_000.0D);
        // The eight timers this line used to print were deleted with the walk that fed them, so
        // sliceMs/blockMs/cellMs/biomeMs/constructMs/surfaceMs/walkedBlocks/leadRetries were always
        // zero and effectiveParallelism was always 0.00 - a metric derived from them, and the reason a
        // measured session's scan looked idle while the lanes were answering 92% of its columns from
        // cache. What replaces them is measured: how much of the sampling did not happen on the pump.
        long hitsNow = search.prefetchHitCount();
        long inlineNow = search.inlineSampleCount();
        double offThreadPct = hitsNow + inlineNow == 0 ? 0.0
                : 100.0 * hitsNow / (hitsNow + inlineNow);
        GreatWallMod.LOGGER.info("[GreatWall:S1C] ROOT_SEARCH_DONE columns={} phase={} over40msBatches={} "
                        + "maxBatchMs={} elapsedMs={} prefetchOffThreadPct={} prefetchHits={} inlineSamples={} "
                        + "prefetchWaits={} discardedPrefetched={} distinctColumns={} columnReads={} "
                        + "roundTrips={} scheduleMs={} submitToStartMs={} batchMs={} "
                        + "pumpHopMs={} callbackMs={} {}",
                progress.columns(), progress.phase(), progress.over40msBatches(),
                Math.round(progress.maxBatchNanos() / 1_000_000.0D), Math.round(elapsedMs),
                String.format(java.util.Locale.ROOT, "%.1f", offThreadPct),
                hitsNow, inlineNow, search.prefetchWaitCount(), search.discardedPrefetchedCount(),
                search.distinctColumns(), search.columnReads(),
                roundTrips.get(),
                Math.round(sumSchedulePrefetchNanos.get() / 1_000_000.0D),
                Math.round(sumSubmitToStartNanos.get() / 1_000_000.0D),
                Math.round(search.batchNanosTotal() / 1_000_000.0D),
                Math.round(sumPumpHopNanos.get() / 1_000_000.0D),
                Math.round(sumCallbackNanos.get() / 1_000_000.0D),
                new java.util.TreeMap<>(prefetchDiagnostics()));
    }

    private static long count(Map<String, Long> diagnostics, String key) {
        Long value = diagnostics.get(key);
        return value == null ? 0L : value;
    }

    /**
     * Turns a selection into a frozen recipe and publishes it.
     *
     * <p>Shared by the searched and the cached paths so a cached axis produces exactly
     * the same recipe hash as a searched one — the recipe is derived from the axis, so
     * there is no second code path that could disagree.
     */
    private void publishRecipeFor(RootSearch.Selection selection, WorldRecipeSelection authorization, Frozen inputs) {
        GreatWallMod.LOGGER.info("[GreatWall:DIAG] publishRecipeFor ENTER thread={} state={}", Thread.currentThread().getName(), Map.copyOf(state));
        var instance = authorization.creation().instance();
        // 选址仍围绕出生点做，存进配方的轴首向反方向退半长。
        var axis = local.plaincraft.greatwall.model.WallRecipeRules.centerFiniteAxis(selection.axis(),
                rootWindow.wallLength(),
                authorization.config().templateVersion());
        var provisional = new WorldRecipe(instance, "0".repeat(64), seed, "minecraft:overworld",
                spawn.get("chosen"), axis, inputs.bounds(), authorization.config(), inputs.terrainHash(),
                inputs.paletteHash(), inputs.templatesHash(), "0".repeat(64));
        String hash = DeterministicHash.sha256(CanonicalCodec.recipe(provisional));
        var recipe = new WorldRecipe(instance, hash, seed, provisional.dimension(), provisional.frozenSpawn(),
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
    private Optional<RootSearchCache.Entry> readCachedAxis(GenerationConfig config, Frozen inputs) {
        var directory = worldDirectory();
        if (directory == null) return Optional.empty();
        var chosen = spawn.get("chosen");
        if (chosen == null) return Optional.empty();
        try {
            var identity = RootSearchCache.identityOf(seed, "minecraft:overworld", chosen,
                    inputs.terrainHash(), inputs.paletteHash(), inputs.templatesHash(),
                    rootAxisKey(config, inputs));
            var entry = RootSearchCache.read(directory, identity);
            // 已保存草案的轴仍须避让这次真实载入的出生保护与圣域；检查只是几何投影，不重扫地形。
            if (local.plaincraft.greatwall.model.WallRecipeRules.landFirst(config))
                entry = entry.filter(e -> RootEndpointSite.allowed(RootSearchCache.axisOf(e), rootWindow.wallLength(),
                        chosen, earlyDomain.snapshot(), inputs.bounds(), inputs.sanctuaryAvoidance()));
            if (entry.isPresent()) return entry;
            GreatWallMod.LOGGER.info("[GreatWall:S1C] ROOT_CACHE_MISS searching; no usable entry at {}",
                    RootSearchCache.file(directory));
            return Optional.empty();
        } catch (RuntimeException unusable) {
            return Optional.empty();
        }
    }

    private String rootAxisKey(GenerationConfig config, Frozen inputs) {
        return inputs.riverWater().rootCacheKey(ProductionRecipeVersions.rootAxisParameters(config, rootWindow));
    }

    /** Records the searched axis for the next launch of this world. */
    private void writeCachedAxis(GenerationConfig config, Frozen inputs, RootSearch.Selection selection) {
        var directory = worldDirectory();
        if (directory == null) return;
        var chosen = spawn.get("chosen");
        if (chosen == null) return;
        try {
            var identity = RootSearchCache.identityOf(seed, "minecraft:overworld", chosen,
                    inputs.terrainHash(), inputs.paletteHash(), inputs.templatesHash(),
                    rootAxisKey(config, inputs));
            RootSearchCache.write(directory, RootSearchCache.of(identity, selection.axis(),
                    selection.radiusIndex(), selection.angleIndex(), selection.axisIndex()));
            GreatWallMod.LOGGER.info("[GreatWall:S1C] ROOT_CACHE_STORED axis={} radiusIndex={} angleIndex={} "
                            + "axisIndex={}",
                    selection.axis(), selection.radiusIndex(), selection.angleIndex(), selection.axisIndex());
        } catch (RuntimeException unwritable) {
            // A cache write failure costs time on the next launch, never correctness now.
        }
    }

    private void validateSaved(WorldRecipe recipe, Frozen inputs, GenerationConfig current) {
        // Reported before the generic comparison because this is the one a player can trigger from the
        // config screen, and "generation parameters changed" is actionable in a way that a field-by-field
        // recipe diff is not. Both digests are printed so the log alone identifies which knob moved.
        if (!recipe.config().parametersHash().equals(current.parametersHash())) {
            GreatWallMod.LOGGER.error("[GreatWall:S1C] parameter identity drifted saved={} current={}",
                    recipe.config().parametersHash(), current.parametersHash());
            throw new WallFailure(FailureCode.RECIPE_MISMATCH,
                    "generation parameters changed; create a new world");
        }
        if (recipe.seed() != seed || !recipe.bounds().equals(inputs.bounds())
                || !recipe.config().equals(current) || !recipe.terrainHash().equals(inputs.terrainHash())
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
        GreatWallMod.LOGGER.debug("[GreatWall:DIAG] io() ENTER thread={} state={}", Thread.currentThread().getName(), Map.copyOf(state));
        try {
            active();
            GreatWallMod.LOGGER.debug("[GreatWall:DIAG] io() AFTER_ACTIVE thread={}", Thread.currentThread().getName());
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
            GreatWallMod.LOGGER.debug("[GreatWall:DIAG] io() AFTER_ADMIT thread={} admitted={}", Thread.currentThread().getName(), ioFlights.get("admitted").size());
        } catch (Throwable admitError) {
            GreatWallMod.LOGGER.error("[GreatWall:DIAG] io() ADMIT_THREW thread={}", Thread.currentThread().getName(), admitError);
            throw admitError;
        }
        result.whenComplete((value, error) -> ioFlights.computeIfPresent("admitted", (key, old) ->
                old.stream().filter(future -> future != result).toList()));
        try {
            GreatWallMod.LOGGER.debug("[GreatWall:DIAG] io() BEFORE_EXECUTE thread={}", Thread.currentThread().getName());
            io.execute(() -> {
                GreatWallMod.LOGGER.debug("[GreatWall:DIAG] io() TASK_START thread={}", Thread.currentThread().getName());
                try {
                    T value = action.run();
                    GreatWallMod.LOGGER.debug("[GreatWall:DIAG] io() ACTION_SUCCESS thread={}", Thread.currentThread().getName());
                    result.complete(value);
                } catch (Throwable error) {
                    GreatWallMod.LOGGER.error("[GreatWall:DIAG] io() ACTION_THREW thread={}", Thread.currentThread().getName(), error);
                    result.completeExceptionally(error);
                }
            });
            GreatWallMod.LOGGER.debug("[GreatWall:DIAG] io() AFTER_EXECUTE thread={}", Thread.currentThread().getName());
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
