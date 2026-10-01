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
            BiPredicate<NaturalTerrainOracle, NaturalTerrainOracle.ColumnCursor> foundation) {
        public Frozen {
            Objects.requireNonNull(bounds); Objects.requireNonNull(factory); Objects.requireNonNull(foundation);
            local.plaincraft.greatwall.model.WallTypes.requireHash(terrainHash);
            local.plaincraft.greatwall.model.WallTypes.requireHash(paletteHash);
            local.plaincraft.greatwall.model.WallTypes.requireHash(templatesHash);
        }
    }
    private record Authorized(WorldManifest.Creation creation, Optional<WorldRecipe> saved) {}
    private record RootBatch(Optional<RootSearch.Selection> selected, RootSearch.Progress progress) {}
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
        return new GenerationConfig(1, 1, 2048, 4, 150000, 384, 192, 4,
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

    /** Authorization is checked BEFORE deciding to await ready, avoiding an early-spawn dependency cycle. */
    public CompletableFuture<Void> beforeRecipe(ChunkKey requested) {
        if (!requested.dimension().equals("minecraft:overworld")) return CompletableFuture.completedFuture(null);
        return authorized.thenCompose(value -> {
            active();
            if (mayPassBeforeRecipe(requested)) return CompletableFuture.completedFuture(null);
            return ready.thenApply(recipe -> (Void) null);
        }).copy();
    }

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
                try {
                    var lanes = laneSnapshot.compute("lanes", (key, previous) -> {
                        active();
                        if (previous != null) throw new IllegalStateException("bootstrap lane group already created");
                        return List.of(new OracleLane(authorization.creation().instance(), 0, inputs.terrainHash(), inputs.factory()),
                                new OracleLane(authorization.creation().instance(), 1, inputs.terrainHash(), inputs.factory()));
                    });
                    active();
                    if (authorization.saved().isPresent()) {
                        var recipe = authorization.saved().get();
                        validateSaved(recipe, inputs);
                        publish(recipe);
                    } else {
                        var search = new RootSearch(spawn.get("chosen"), inputs.bounds(), earlyDomain.snapshot(), inputs.foundation());
                        advance(search, lanes.getFirst(), authorization.creation(), inputs);
                    }
                } catch (Throwable failure) { fail(failure); }
            }, rootPump));
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            if (!state.containsKey("closed")) fail(stopped);
        }
    }

    private void advance(RootSearch search, OracleLane lane, WorldManifest.Creation creation, Frozen inputs) {
        active();
        lane.submit(oracle -> {
            try {
                var status = search.advance(oracle, 64);
                return new RootBatch(status == WorkState.DONE ? Optional.of(search.finish()) : Optional.empty(), search.progress());
            } finally {
                progress.put("root", search.progress());
                rootAudit.put("root", search.audit());
            }
        }).whenCompleteAsync((batch, error) -> {
            if (state.containsKey("closed") || state.containsKey("failed")) return;
            if (error != null) { fail(error); return; }
            progress.put("root", batch.progress());
            if (batch.selected().isEmpty()) { advance(search, lane, creation, inputs); return; }
            var selection = batch.selected().get();
            var provisional = new WorldRecipe(creation.instance(), "0".repeat(64), seed, "minecraft:overworld",
                    spawn.get("chosen"), selection.axis(), inputs.bounds(), defaultConfig(), inputs.terrainHash(),
                    inputs.paletteHash(), inputs.templatesHash(), "0".repeat(64));
            String hash = DeterministicHash.sha256(CanonicalCodec.recipe(provisional));
            var recipe = new WorldRecipe(creation.instance(), hash, seed, provisional.dimension(), provisional.frozenSpawn(),
                    provisional.axis(), provisional.bounds(), provisional.config(), inputs.terrainHash(),
                    inputs.paletteHash(), inputs.templatesHash(), hash);
            io(() -> { active(); manifest.freeze(recipe); return recipe; }).whenComplete((saved, failure) -> {
                if (failure != null) fail(failure);
                else if (!state.containsKey("closed") && !state.containsKey("failed")) publish(saved);
            });
        }, rootPump);
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
        if (state.putIfAbsent("failed", true) != null) return;
        ready.completeExceptionally(error);
        close();
    }

    private <T> CompletableFuture<T> io(IoAction<T> action) {
        active();
        var result = new CompletableFuture<T>();
        ioFlights.compute("admitted", (key, old) -> {
            active();
            var pending = old == null ? new java.util.ArrayList<CompletableFuture<?>>()
                    : new java.util.ArrayList<>(old);
            if (pending.size() >= 128) throw new WallFailure(FailureCode.CAPACITY, "bootstrap IO admission capacity");
            pending.add(result); return List.copyOf(pending);
        });
        result.whenComplete((value, error) -> ioFlights.computeIfPresent("admitted", (key, old) ->
                old.stream().filter(future -> future != result).toList()));
        try { io.execute(() -> {
            try { result.complete(action.run()); } catch (Throwable error) { result.completeExceptionally(error); }
        }); } catch (RuntimeException error) { result.completeExceptionally(error); }
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
            throw new WallFailure(FailureCode.CLOSED, "bootstrap is closed or failed");
        }
    }

    @Override public void close() {
        if (state.putIfAbsent("closed", true) != null) return;
        ready.completeExceptionally(new WallFailure(FailureCode.CLOSED, "bootstrap closed"));
        var planner = transferred.computeIfPresent("planner", (key, value) -> { value.close(); return value; });
        rootPump.shutdown();
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
        CompletableFuture.allOf(done, pendingIo).whenComplete((value, error) -> {
            if (error == null) drained.complete(null); else drained.completeExceptionally(error);
        });
    }
}
