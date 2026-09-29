package local.plaincraft.greatwall.generation;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.mixin.ChunkMapAccess;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.planning.ProbePlanner;
import local.plaincraft.greatwall.planning.PlannerScheduler;
import local.plaincraft.greatwall.planning.ArcPlanProvider;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.RealizationPlanner;
import local.plaincraft.greatwall.terrain.TerrainSnapshotService;
import local.plaincraft.greatwall.terrain.TerrainTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** S0 barrier prototype. Every mutation is restricted to the finite laboratory footprint. */
public final class WallStageCoordinator implements AutoCloseable {
    public record Result(int round, int x, int z, String t0Hash, String realizationHash, String finalHash) {}
    private record Captured(TerrainTile tile, boolean stored) {}
    private final ServerLevel level;
    private final WorldServices session;
    private final String recipe;
    private final Path root;
    private final ChunkEvidenceStore store;
    private final BarrierFixtureLayout layout;
    private final ProductionWallPipeline production;
    private final ArcPlanProvider arcPlans;
    private final StageTrace trace = new StageTrace(UUID.randomUUID());
    private final ConcurrentHashMap<TerrainTile.Key, CompletableFuture<TerrainTile>> tiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, CompletableFuture<Void>> applies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Result> results = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, TerrainEngine> engines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, FeaturesLease> leases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> delays = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, CompletableFuture<Void>> validations = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> crashPause = new CompletableFuture<>();
    private final ConcurrentHashMap<String, Boolean> state = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                var thread = new Thread(runnable, "greatwall-t0-io"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
        var thread = new Thread(runnable, "greatwall-t0-delay"); thread.setDaemon(true); return thread;
    });

    public WallStageCoordinator(ServerLevel level, WorldServices session, String recipe, Path root) {
        this.level = level; this.session = session; this.recipe = recipe; this.root = root;
        layout = new BarrierFixtureLayout(Integer.getInteger("greatwall.s0.terrainRounds", 1));
        store = new ChunkEvidenceStore(root.resolve("evidence"));
        production=null;arcPlans=null;
        timer.setRemoveOnCancelPolicy(true);
    }
    public WallStageCoordinator(ServerLevel level,WorldServices session,WorldRecipe recipe,PlannerScheduler planner,
            TerrainSnapshotService snapshots,RealizationPlanner realization,ChunkPlanCompiler compiler,ChunkWallWriter writer) {
        this.level=level;this.session=session;this.recipe=recipe.recipeHash();this.root=null;this.store=null;this.layout=null;
        this.arcPlans=new ArcPlanProvider(n->planner.requirePlan(n,Priority.PLAYER),io);
        this.production=new ProductionWallPipeline(recipe,new ProductionWallPipeline.Ports(
                (key,margin)->planner.requireWindow(key,margin,Priority.PLAYER),
                arcPlans::require,
                positions->{var lease=new FeaturesLease(level,positions,trace);
                    return new PlanLease<>(UUID.randomUUID(),lease.ready(),lease::close);},
                session::assertActiveSession,session::failRequiredPlan,io),snapshots,realization,compiler,writer);
    }
    public BarrierFixtureLayout layout() {
        if(production!=null)throw new IllegalStateException("production has no laboratory layout");
        return layout;
    }
    public CompletableFuture<Void> beforeFeatures(ChunkKey key) {
        if(production==null)throw new IllegalStateException("S0 strategy has no production plan");
        return production.beforeFeatures(key);
    }
    public CompletableFuture<Void> beforeLight(ChunkKey key,ChunkAccess owner,TerrainEngine engine) {
        if(production==null)throw new IllegalStateException("S0 strategy has no production plan");
        return production.beforeLight(key,owner,engine);
    }
    public Map<ChunkPos, Result> results() { return Map.copyOf(results); }
    public Map<String, Long> counts() { return production==null?trace.counts():production.diagnostics(); }
    private void active() {
        session.assertActiveSession();
        if (state.containsKey("closed")) throw new IllegalStateException("T0 session closed");
    }
    private void event(String event, ChunkPos pos, String stage, String lock) {
        trace.record(event, level.dimension().location().toString(), pos.x, pos.z, stage, lock);
    }
    public CompletableFuture<Void> delayFeatures(ChunkPos pos) {
        if(production!=null)return beforeFeatures(new ChunkKey(level.dimension().location().toString(),pos.x,pos.z));
        if (!layout.featureSource(pos)) return CompletableFuture.completedFuture(null);
        active();
        var future = new CompletableFuture<Void>();
        UUID id = UUID.randomUUID();
        delays.put(id, future);
        future.whenComplete((ignored, failure) -> delays.remove(id));
        int order = Integer.getInteger("greatwall.s0.terrainOrder", 1);
        long delay = Math.floorMod((long) pos.x * 31 + (long) pos.z * 17 + order * 47L, 40) + 1;
        event("FEATURE_DELAY_OUTSIDE_LOCK", pos, "features", "none");
        try { timer.schedule(() -> future.complete(null), delay, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.RejectedExecutionException stopped) { future.cancel(false); }
        return future;
    }
    public CompletableFuture<Void> beforeLight(ChunkPos owner, ChunkAccess chunk, TerrainEngine engine) {
        if(production!=null)return beforeLight(new ChunkKey(level.dimension().location().toString(),owner.x,owner.z),chunk,engine);
        if (!layout.owns(owner)) return CompletableFuture.completedFuture(null);
        active(); trace.assertOutsideLock();
        engines.putIfAbsent(owner, engine);
        var candidate = new CompletableFuture<Void>();
        var previous = applies.putIfAbsent(owner, candidate);
        if (previous != null) return previous.copy();
        try { active(); }
        catch (RuntimeException closed) { candidate.completeExceptionally(closed); return candidate.copy(); }
        var cell = layout.cell(owner);
        event("CELL_REQUEST", owner, "prepare:shared=" + cell.size(), "none");
        List<CompletableFuture<TerrainTile>> inputs = cell.stream().map(pos -> snapshot(pos, engine)).toList();
        CompletableFuture.allOf(inputs.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
            if (!Boolean.getBoolean("greatwall.s0.terrainPauseBeforeApply")) return CompletableFuture.completedFuture(null);
            event("CRASH_READY_AFTER_DURABLE", owner, "before-apply", "none");
            export();
            GreatWallMod.LOGGER.info("[GreatWall:S0-03] CRASH_READY_AFTER_DURABLE owner={}", owner);
            return crashPause.copy();
        }).thenCompose(ignored -> {
            active();
            List<TerrainTile> snapshots = inputs.stream().map(CompletableFuture::resultNow).toList();
            String realization = ProbePlanner.hash("s0-marker-v1\n" + snapshots.stream().map(TerrainTile::hash).toList());
            return engine.exclusive(owner, () -> {
                active();
                if (!chunk.getPos().equals(owner)) throw new IllegalStateException("OWNER_CHUNK_MISMATCH");
                TerrainTile own = snapshots.stream().filter(tile -> tile.key().chunkX() == owner.x
                        && tile.key().chunkZ() == owner.z).findFirst().orElseThrow();
                event("APPLY_ENTER", owner, "apply", engine.name());
                String attached = chunk.getAttached(TerrainMarkers.T0);
                if (attached != null && !attached.equals(own.hash())) throw new IllegalStateException("T0_MARKER_MISMATCH");
                String applied = chunk.getAttached(TerrainMarkers.APPLIED);
                if (applied != null) {
                    if (!applied.equals(realization) || attached == null) throw new IllegalStateException("T0_APPLIED_MARKER_MISMATCH");
                    results.put(owner, new Result(layout.round(owner), owner.x, owner.z, own.hash(), realization, capture(chunk, key(owner)).hash()));
                    event("APPLY_REPLAY_NOOP", owner, "apply", engine.name());
                    return (Void) null;
                }
                // The entire write set is checked before any marker or block changes.
                for (int x = 0; x < 16; x++) {
                    var pos = new BlockPos(owner.getMinBlockX() + x, BarrierFixtureLayout.MIN_Y, owner.getMinBlockZ() + 8);
                    OwnershipGuard.check(owner.x, owner.z, pos.getX(), pos.getY(), pos.getZ(),
                            own.key().minY(), own.key().maxY(), chunk.getBlockEntitiesPos().contains(pos),
                            token(chunk, pos), own.state(x, pos.getY(), 8), token(Blocks.GOLD_BLOCK.defaultBlockState(), false));
                    if (!own.state(x, pos.getY(), 8).equals(token(Blocks.STONE.defaultBlockState(), false))) {
                        throw new IllegalStateException("T0_NEIGHBOR_STRIPE_MISSING: " + owner + " x=" + x);
                    }
                }
                chunk.setAttached(TerrainMarkers.T0, own.hash());
                for (int x = 0; x < 16; x++) chunk.setBlockState(new BlockPos(owner.getMinBlockX() + x,
                        BarrierFixtureLayout.MIN_Y, owner.getMinBlockZ() + 8), Blocks.GOLD_BLOCK.defaultBlockState(), false);
                chunk.setAttached(TerrainMarkers.APPLIED, realization);
                String finalHash = capture(chunk, key(owner)).hash();
                results.put(owner, new Result(layout.round(owner), owner.x, owner.z, own.hash(), realization, finalHash));
                event("APPLY_EXIT", owner, "apply", engine.name());
                return (Void) null;
            });
        }).whenComplete((value, failure) -> {
            if (failure == null) candidate.complete(null);
            else { candidate.completeExceptionally(failure); session.failRequiredPlan(failure); }
        });
        return candidate.copy();
    }

    public CompletableFuture<TerrainTile> revisitAfterApply(ChunkPos owner) {
        if (!results.containsKey(owner)) return CompletableFuture.failedFuture(new IllegalStateException("Owner not yet applied"));
        return snapshot(owner, engines.get(owner)).thenApply(tile -> {
            if (!tile.hash().equals(results.get(owner).t0Hash())) throw new IllegalStateException("T0 changed after APPLY");
            event("T0_REUSED_AFTER_APPLY", owner, "late-neighbor-cell", "none");
            return tile;
        });
    }

    public CompletableFuture<TerrainTile> snapshot(ChunkPos pos, TerrainEngine engine) {
        active(); trace.assertOutsideLock();
        TerrainTile.Key key = key(pos);
        if (!layout.owns(pos)) return CompletableFuture.failedFuture(new IllegalArgumentException("Outside fixture"));
        var candidate = new CompletableFuture<TerrainTile>();
        var previous = tiles.putIfAbsent(key, candidate);
        if (previous != null) { event("T0_REUSED", pos, "capture", "none"); return previous.copy(); }
        try { active(); }
        catch (RuntimeException closed) { candidate.completeExceptionally(closed); return candidate.copy(); }
        int radius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES).blockStateWriteRadius();
        if (radius < 0 || radius > 4) {
            candidate.completeExceptionally(new IllegalStateException("Unsupported feature write halo: " + radius));
            return candidate.copy();
        }
        var halo = new ArrayList<ChunkPos>();
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) halo.add(new ChunkPos(pos.x + x, pos.z + z));
        UUID leaseId = UUID.randomUUID();
        var lease = new FeaturesLease(level, halo, trace);
        leases.put(leaseId, lease);
        lease.ready().thenCompose(chunks -> CompletableFuture.supplyAsync(() -> {
            active();
            try { return store.read(key, recipe); }
            catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, io).thenCompose(saved -> engine.exclusive(pos, () -> {
            active();
            event("CAPTURE_ENTER", pos, "capture", engine.name());
            ChunkAccess chunk = chunks.get(pos);
            String marker = chunk.getAttached(TerrainMarkers.T0);
            if (saved.isEmpty() && (marker != null || chunk.getAttached(TerrainMarkers.APPLIED) != null)) {
                throw new IllegalStateException("T0_EVIDENCE_MISSING: refusing to recapture modified chunk");
            }
            TerrainTile tile = saved.orElseGet(() -> capture(chunk, key));
            if (marker != null && !marker.equals(tile.hash())) throw new IllegalStateException("T0_MARKER_MISMATCH");
            event(saved.isPresent() ? "T0_DISK_REUSED" : "T0_CAPTURED", pos, "capture", engine.name());
            event("CAPTURE_EXIT", pos, "capture", engine.name());
            return new Captured(tile, saved.isPresent());
        }))).thenApplyAsync(captured -> {
            active();
            try {
                TerrainTile durable = captured.stored() ? captured.tile() : store.persistBeforeApply(captured.tile());
                event("T0_DURABLE", pos, "persist", "none");
                return durable;
            } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, io).whenComplete((tile, failure) -> {
            lease.close(); leases.remove(leaseId);
            if (failure == null) candidate.complete(tile); else candidate.completeExceptionally(failure);
        });
        return candidate.copy();
    }
    public TerrainTile.Key key(ChunkPos pos) {
        return new TerrainTile.Key(level.dimension().location().toString(), pos.x, pos.z,
                BarrierFixtureLayout.MIN_Y, BarrierFixtureLayout.MAX_Y);
    }
    public void verifyFinalWindow(ChunkAccess chunk) {
        Result expected = results.get(chunk.getPos());
        if (expected == null || !expected.finalHash().equals(capture(chunk, key(chunk.getPos())).hash())) {
            throw new IllegalStateException("POST_APPLY_TERRAIN_CHANGED");
        }
        if (expected.t0Hash().equals(expected.finalHash())) throw new IllegalStateException("No actual fixture blocks changed");
        event("OUTPUT_STABLE_AT_FULL", chunk.getPos(), "full-validation", "server-main-read");
    }
    public CompletableFuture<Void> verifyLoaded(ChunkAccess chunk) {
        if(production!=null)return production.verifyLoaded(chunk);
        ChunkPos owner = chunk.getPos();
        if (!layout.owns(owner)) return CompletableFuture.completedFuture(null);
        if (results.containsKey(owner)) return CompletableFuture.completedFuture(null);
        var candidate = new CompletableFuture<Void>();
        var previous = validations.putIfAbsent(owner, candidate);
        if (previous != null) return previous.copy();
        String marker = chunk.getAttached(TerrainMarkers.T0);
        String applied = chunk.getAttached(TerrainMarkers.APPLIED);
        CompletableFuture.supplyAsync(() -> {
            active();
            if (marker == null || applied == null) throw new IllegalStateException("T0_NATIVE_MARKER_MISSING_ON_FULL");
            var cell = new ArrayList<TerrainTile>();
            try {
                for (ChunkPos member : layout.cell(owner)) cell.add(store.read(key(member), recipe).orElseThrow(
                        () -> new IOException("T0_EVIDENCE_MISSING: applied owner must not be recaptured")));
            } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
            TerrainTile own = cell.stream().filter(tile -> tile.key().equals(key(owner))).findFirst().orElseThrow();
            String realization = ProbePlanner.hash("s0-marker-v1\n" + cell.stream().map(TerrainTile::hash).toList());
            if (!marker.equals(own.hash()) || !applied.equals(realization)) throw new IllegalStateException("T0_MARKER_MISMATCH");
            return own;
        }, io).thenAcceptAsync(tile -> {
            active();
            results.put(owner, new Result(layout.round(owner), owner.x, owner.z, tile.hash(), applied, capture(chunk, key(owner)).hash()));
            event("LOADED_EVIDENCE_VERIFIED", owner, "load-read-only", "chunk-main");
        }, ((ChunkMapAccess) level.getChunkSource().chunkMap).greatwall$chunkMainExecutor()).whenComplete((ignored, error) -> {
            if (error == null) candidate.complete(null);
            else { candidate.completeExceptionally(error); session.failRequiredPlan(error); }
        });
        return candidate.copy();
    }
    private TerrainTile capture(ChunkAccess chunk, TerrainTile.Key key) {
        if (key.minY() < chunk.getMinBuildHeight() || key.maxY() >= chunk.getMaxBuildHeight()) {
            throw new IllegalStateException("T0 fixture window outside actual dimension bounds");
        }
        var palette = new ArrayList<String>();
        var index = new java.util.HashMap<String, Integer>();
        var protectedBlocks = java.util.Set.copyOf(chunk.getBlockEntitiesPos());
        var tokens = new java.util.HashMap<BlockState, String>();
        int[] states = new int[key.volume()];
        int cursor = 0;
        var pos = new BlockPos.MutableBlockPos();
        for (int y = key.minY(); y <= key.maxY(); y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            pos.set(key.chunkX() * 16 + x, y, key.chunkZ() * 16 + z);
            BlockState block = chunk.getBlockState(pos);
            String state = protectedBlocks.contains(pos) ? token(block, true)
                    : tokens.computeIfAbsent(block, value -> token(value, false));
            Integer id = index.get(state);
            if (id == null) { id = palette.size(); palette.add(state); index.put(state, id); }
            states[cursor++] = id;
        }
        return new TerrainTile(key, recipe, palette, states);
    }
    public static String token(ChunkAccess chunk, BlockPos pos) {
        return token(chunk.getBlockState(pos), chunk.getBlockEntitiesPos().contains(pos));
    }
    private static String token(BlockState state, boolean protectedBlock) {
        var properties = new java.util.TreeMap<String, String>();
        state.getValues().forEach((property, value) -> properties.put(property.getName(), value.toString()));
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()) + properties.toString()
                + ":fluid=" + BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()) + ":be=" + protectedBlock;
    }
    public void export() {
        if(production!=null)return;
        try {
            Files.createDirectories(root);
            var gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(root.resolve("barrier-events.json"), gson.toJson(trace.snapshot()));
            Files.writeString(root.resolve("barrier-results.json"), gson.toJson(results.values().stream()
                    .sorted(java.util.Comparator.comparingInt(Result::round).thenComparingInt(Result::x).thenComparingInt(Result::z)).toList()));
            Files.writeString(root.resolve("barrier-session.json"), gson.toJson(Map.of("counts", trace.counts(),
                    "tiles", tiles.size(), "owners", applies.size(), "leases", leases.size(),
                    "closed", state.containsKey("closed"), "recipe", recipe, "delays", delays.size())));
        } catch (IOException failure) { throw new IllegalStateException("Cannot persist barrier diagnostics", failure); }
    }
    @Override public void close() {
        if (state.putIfAbsent("closed", true) != null) return;
        if(production!=null){production.close();arcPlans.close();timer.shutdownNow();io.shutdown();return;}
        tiles.values().forEach(future -> future.cancel(false));
        applies.values().forEach(future -> future.cancel(false));
        validations.values().forEach(future -> future.cancel(false));
        crashPause.cancel(false);
        leases.values().forEach(FeaturesLease::close); leases.clear();
        delays.values().forEach(future -> future.cancel(false)); delays.clear();
        timer.shutdownNow(); io.shutdown();
        export();
    }
}
