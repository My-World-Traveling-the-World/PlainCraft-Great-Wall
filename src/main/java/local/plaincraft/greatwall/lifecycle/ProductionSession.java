package local.plaincraft.greatwall.lifecycle;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.component.S1TemplateSet;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.generation.*;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.persistence.WorldManifest;
import local.plaincraft.greatwall.planning.PlannerScheduler;
import local.plaincraft.greatwall.terrain.RealizationPlanner;
import local.plaincraft.greatwall.terrain.TerrainSnapshotService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.storage.LevelResource;

/** Private isolated-fixture integration. There is deliberately no enabled production-world switch. */
final class ProductionSession implements AutoCloseable {
    private record Installed(WorldRecipe recipe, PlannerScheduler planner, WallStageCoordinator barrier, ProductionInputs inputs) {}
    private final UUID runtime = UUID.randomUUID();
    private final MinecraftServer server;
    private final WorldServices services;
    private final Path root;
    private final StageTrace trace = new StageTrace(runtime);
    private final VegetationGuard vegetation = new VegetationGuard();
    private final ConcurrentHashMap<String, Boolean> state = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> failures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServerLevel> levels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProductionInputs> inputs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Installed> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkKey, FeatureSlot> featureSlots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> admissions = new ConcurrentHashMap<>();
    private final CompletableFuture<Installed> installed = new CompletableFuture<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final ThreadPoolExecutor io;
    private final WorldBootstrap bootstrap;

    ProductionSession(MinecraftServer server, WorldServices services) {
        this.server = server; this.services = services;
        var directory = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        if (!Boolean.getBoolean("greatwall.s1c.productionFixture") || Boolean.getBoolean("greatwall.production")
                || !Files.isRegularFile(directory.resolve("greatwall_s1c_fixture"))
                || !Files.isRegularFile(directory.resolve("greatwall_test_world"))) {
            throw new IllegalStateException("production remains OFF; S1C requires its separate isolated-fixture authorization");
        }
        rejectUncertifiedProfiles();
        root = server.getWorldPath(LevelResource.ROOT).resolve("greatwall");
        io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), task -> {
            var thread = new Thread(task, "pgw-production-io-" + runtime); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        bootstrap = new WorldBootstrap(server, new WorldManifest(root.resolve("manifest")), io);
        bootstrap.ready().whenComplete((recipe, error) -> server.execute(() -> {
            if (state.containsKey("closed")) return;
            if (error != null) { fail(error); return; }
            try {
                active();
                var level = levels.get(recipe.dimension());
                var frozen = inputs.get("frozen");
                if (level == null || frozen == null) throw new IllegalStateException("recipe published before prepareLevels freeze");
                var planner = bootstrap.transferToPlanner(new SegmentCacheStore(root.resolve("cache"), recipe), io);
                var store = ChunkEvidenceStore.production(root.resolve("evidence"));
                var terrain = new TerrainSnapshotService(store, io, frozen.blocks());
                var realization = new RealizationPlanner(recipe, new SurfaceProbe(), S1TemplateSet.create(recipe.seed()));
                var barrier = new WallStageCoordinator(level, services, recipe, planner, terrain, realization,
                        new ChunkPlanCompiler(), new ChunkWallWriter());
                var snapshot = new Installed(recipe, planner, barrier, frozen);
                snapshots.put("installed", snapshot);
                planner.startupPump();
                trace.record("PRODUCTION_RECIPE_INSTALLED", recipe.dimension(), 0, 0, "recipe-durable-before-generation", "none");
                installed.complete(snapshot);
                GreatWallMod.LOGGER.info("[GreatWall:S1C] FIXTURE_RECIPE_READY runtime={} wall={} spawn={} axis={} production=OFF",
                        runtime, recipe.wallId(), recipe.frozenSpawn(), recipe.axis());
            } catch (Throwable failure) { fail(failure); }
        }));
    }

    UUID runtime() { return runtime; }
    WorldBootstrap bootstrap() { return bootstrap; }
    VegetationGuard vegetation() { return vegetation; }
    CompletableFuture<WorldRecipe> recipeReady() { return installed.thenApply(Installed::recipe).copy(); }
    PlannerScheduler planning() { return requireInstalled().planner(); }
    WorldRecipe recipe() { return requireInstalled().recipe(); }
    TreeBoundsCatalog trees() {
        var value = inputs.get("frozen");
        if (value == null) throw new IllegalStateException("tree catalog not frozen");
        return value.trees();
    }
    WallStageCoordinator barrier(ServerLevel level) {
        var value = snapshots.get("installed");
        return value != null && value.recipe().dimension().equals(level.dimension().location().toString()) ? value.barrier() : null;
    }
    void openDimension(ServerLevel level) { levels.put(level.dimension().location().toString(), level); }
    void warming() {
        if (!server.isSameThread()) throw new IllegalStateException("prepareLevels must run on server owner");
        active();
        var level = levels.get("minecraft:overworld");
        if (level == null) throw new IllegalStateException("overworld LOAD has not been recorded");
        try {
            var frozen = ProductionInputs.freeze(level);
            inputs.put("frozen", frozen);
            if (server.getWorldData().overworldData().isInitialized() && !bootstrap.diagnostics().containsKey("spawn-chosen")) {
                // A self-created token can resume before world.json. Already frozen recipes ignore this new spawn.
                var pos = level.getSharedSpawnPos();
                bootstrap.spawnChosen(level, new Point3i(pos.getX(), pos.getY(), pos.getZ()));
            }
            bootstrap.prepareLevelsEntered(level, frozen.bootstrap());
            trace.record("PRODUCTION_PREPARE_LEVELS", "minecraft:overworld", 0, 0, "all-LOAD-complete", "none");
        } catch (Throwable error) { fail(error); throw new IllegalStateException("Production input freeze failed", error); }
    }
    void started() { state.put("server-started", true); }
    void tick() {
        if (state.containsKey("closed") || state.containsKey("failed")) return;
        var snapshot = snapshots.get("installed");
        if (snapshot == null) return;
        var diagnostics = snapshot.planner().diagnostics();
        if (diagnostics.containsKey("startup-failed")) { fail(new IllegalStateException("production prewarm failed")); return; }
        if (state.containsKey("server-started") && diagnostics.containsKey("startup-ready")
                && state.putIfAbsent("active", true) == null) {
            trace.record("PRODUCTION_ACTIVE", snapshot.recipe().dimension(), 0, 0, "server-started-and-prewarm-ready", "none");
        }
        snapshot.planner().tick(new SliceBudget(4096, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25)));
    }

    PlanLease<Void> featureLease(ChunkKey key) {
        active(); trace.assertOutsideLock();
        if (!key.dimension().equals("minecraft:overworld")) return emptyLease();
        UUID caller = UUID.randomUUID();
        var slot = featureSlots.compute(key, (position, previous) -> {
            active();
            if (previous == null) {
                admissions.compute("features", (name, old) -> {
                    int count = old == null ? 0 : old;
                    if (count >= 256) throw new WallFailure(FailureCode.CAPACITY, "FEATURES owner lease admission capacity");
                    return count + 1;
                });
                previous = new FeatureSlot(key);
            }
            previous.callers.put(caller, true);
            return previous;
        });
        var lease = new PlanLease<Void>(caller, slot.ready, () -> releaseFeature(key, slot, caller));
        if (slot.state.putIfAbsent("started", true) == null) slot.start();
        return lease;
    }
    private void releaseFeature(ChunkKey key, FeatureSlot expected, UUID caller) {
        featureSlots.computeIfPresent(key, (position, current) -> {
            if (current != expected) return current;
            current.callers.remove(caller);
            if (!current.callers.isEmpty()) return current;
            current.close();
            admissions.compute("features", (name, count) -> count - 1);
            return null;
        });
    }

    CompletableFuture<Void> beforeLight(ChunkKey key, ChunkAccess owner, TerrainEngine engine) {
        trace.assertOutsideLock();
        if (!key.dimension().equals("minecraft:overworld")) return CompletableFuture.completedFuture(null);
        return bootstrap.beforeRecipe(key).thenComposeAsync(ignored -> {
            active();
            if (bootstrap.mayPassBeforeRecipe(key)) return CompletableFuture.completedFuture(null);
            return installed.thenCompose(value -> value.barrier().beforeLight(key, owner, engine));
        }, io).whenComplete((value, error) -> { if (error != null && !state.containsKey("closed")) fail(error); });
    }

    void validateReload() {
        var snapshot = snapshots.get("installed");
        if (snapshot == null) { fail(new IllegalStateException("reload during incomplete production bootstrap")); return; }
        try {
            var next = ProductionInputs.freeze(levels.get("minecraft:overworld")).bootstrap();
            if (!snapshot.recipe().terrainHash().equals(next.terrainHash())
                    || !snapshot.recipe().paletteHash().equals(next.paletteHash())
                    || !snapshot.recipe().templatesHash().equals(next.templatesHash())) {
                throw new WallFailure(FailureCode.RECIPE_MISMATCH, "generation semantics changed; create a new world");
            }
            trace.record("PRODUCTION_RECIPE_ACCEPTED", "*", 0, 0, "reload", "none");
        } catch (Throwable error) { fail(error); }
    }
    void active() {
        if (state.containsKey("closed") || state.containsKey("failed")) throw new WallFailure(FailureCode.CLOSED, "production session is closed/failed");
    }
    void fail(Throwable error) {
        if (state.containsKey("closed") || failures.putIfAbsent("required", error.toString()) != null) return;
        state.put("failed", true);
        trace.record("PRODUCTION_FAILED", "*", 0, 0, "required-plan", "none");
        GreatWallMod.LOGGER.error("[GreatWall:S1C] FIXTURE_FAILED production remains OFF", error);
        installed.completeExceptionally(error);
        MinecraftServer.setFatalException(new IllegalStateException("GreatWall production fixture required dependency failed", error));
        close();
    }
    void trace(String event, String dimension, int x, int z, String stage, String token) { trace.record(event, dimension, x, z, stage, token); }
    Map<String, Long> counts() { return trace.counts(); }
    boolean completedStage(String dimension, int x, int z) {
        return trace.snapshot().stream().anyMatch(event -> event.event().equals("STAGE_EXIT")
                && event.dimension().equals(dimension) && event.chunkX() == x && event.chunkZ() == z);
    }
    boolean rejected() { return state.containsKey("failed"); }
    CompletableFuture<Void> drain() { return drained.copy(); }
    Map<String, Object> diagnostics() {
        var result = new TreeMap<String, Object>();
        result.put("runtime", runtime); result.put("state", Map.copyOf(state)); result.put("failures", Map.copyOf(failures));
        result.put("productionWiring", "OFF"); result.put("isolatedFixture", true);
        result.put("counts", counts()); result.put("root", bootstrap.progress()); result.put("bootstrap", bootstrap.diagnostics());
        result.put("rootAudit", bootstrap.rootAudit());
        result.put("featureLeases", featureSlots.size()); result.put("vegetationLeases", vegetation.leases());
        var snapshot = snapshots.get("installed");
        if (snapshot != null) {
            result.put("recipe", snapshot.recipe()); result.put("planner", snapshot.planner().diagnostics());
            result.put("plannerFailures", snapshot.planner().failures());
            result.put("plannerProgress", snapshot.planner().progress());
            result.put("barrier", snapshot.barrier().counts());
        }
        return Map.copyOf(result);
    }
    void export(String lifecycle) {
        try {
            Files.createDirectories(root.resolve("diagnostics"));
            var json = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(root.resolve("diagnostics/session-" + lifecycle + ".json"), json.toJson(diagnostics()));
            Files.writeString(root.resolve("diagnostics/events-" + lifecycle + ".json"), json.toJson(trace.snapshot()));
        } catch (IOException failure) { throw new IllegalStateException("Cannot persist production fixture diagnostics", failure); }
    }
    @Override public void close() {
        if (state.putIfAbsent("closed", true) != null) return;
        installed.completeExceptionally(new WallFailure(FailureCode.CLOSED, "production session closed"));
        for (var key : List.copyOf(featureSlots.keySet())) featureSlots.computeIfPresent(key, (position, slot) -> {
            slot.close(); admissions.compute("features", (name, count) -> count - 1); return null;
        });
        var snapshot = snapshots.get("installed");
        if (snapshot != null) snapshot.barrier().close();
        bootstrap.close();
        bootstrap.drain().whenComplete((value, error) -> {
            io.shutdown();
            if (error == null) drained.complete(null); else drained.completeExceptionally(error);
        });
    }
    private Installed requireInstalled() {
        active(); var snapshot = snapshots.get("installed");
        if (snapshot == null) throw new IllegalStateException("production recipe is not installed");
        return snapshot;
    }
    private static PlanLease<Void> emptyLease() {
        return new PlanLease<>(UUID.randomUUID(), CompletableFuture.completedFuture(null), () -> {});
    }
    private static void rejectUncertifiedProfiles() {
        var loader = FabricLoader.getInstance();
        for (String id : List.of("tanshugetrees", "plaincraft_erdtree_sanctuary", "plaincraft_erdtree_sapling", "ohthetreesyoullgrow")) {
            loader.getModContainer(id).ifPresent(mod -> {
                try {
                    throw new WallFailure(FailureCode.UNSUPPORTED_ADAPTER, "BLOCKED id=" + id + " version="
                            + mod.getMetadata().getVersion().getFriendlyString() + " SHA256=" + GenerationResourceFingerprint.sourceHash(mod)
                            + (id.equals("tanshugetrees") ? " post-generation tree writer not certified" : " S1C adapter/write-set certification NOT_RUN"));
                } catch (IOException error) { throw new WallFailure(FailureCode.IO_FAILED, "cannot audit blocked profile source", error); }
            });
        }
    }

    private final class FeatureSlot implements AutoCloseable {
        private final ChunkKey owner;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final ConcurrentHashMap<UUID, Boolean> callers = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, PlanLease<PlanWindow>> children = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Boolean> state = new ConcurrentHashMap<>();
        private FeatureSlot(ChunkKey owner) { this.owner = owner; }
        private void start() {
            bootstrap.beforeRecipe(owner).thenComposeAsync(ignored -> {
                active(); if (state.containsKey("closed")) throw new WallFailure(FailureCode.CLOSED, "FEATURES lease closed");
                if (bootstrap.mayPassBeforeRecipe(owner)) return CompletableFuture.completedFuture((Installed) null);
                return installed;
            }, io).thenComposeAsync(snapshot -> {
                if (snapshot == null) return CompletableFuture.completedFuture((PlanWindow) null);
                int halo = net.minecraft.world.level.chunk.status.ChunkPyramid.GENERATION_PYRAMID
                        .getStepTo(net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES).blockStateWriteRadius();
                if (halo < 0 || halo > 4) throw new WallFailure(FailureCode.UNSUPPORTED_ADAPTER, "unknown native FEATURES write halo");
                int margin = Math.addExact(snapshot.inputs().trees().maximumRadius(), 32 + halo * 16);
                int grid = margin <= 512 ? 0 : Math.toIntExact(Math.floorDiv((long) margin + 511, 512));
                if (grid > 5) throw new WallFailure(FailureCode.CAPACITY, "vegetation plan subdivision exceeds finite grid");
                CompletableFuture<Map<Long, SegmentPlan>> chain = CompletableFuture.completedFuture(Map.of());
                for (int dx = -grid; dx <= grid; dx++) for (int dz = -grid; dz <= grid; dz++) {
                    var query = new ChunkKey(owner.dimension(), Math.addExact(owner.x(), dx * 32), Math.addExact(owner.z(), dz * 32));
                    chain = chain.thenComposeAsync(prior -> {
                        active(); if (state.containsKey("closed")) throw new WallFailure(FailureCode.CLOSED, "FEATURES lease closed");
                        var child = snapshot.planner().requireWindow(query, Math.min(margin, 512), Priority.PLAYER);
                        children.put(child.id(), child);
                        if (state.containsKey("closed")) { children.remove(child.id(), child); child.close(); }
                        return child.future().thenApply(plans -> {
                            var merged = new TreeMap<>(prior);
                            for (var plan : plans.plans()) {
                                var previous = merged.putIfAbsent(plan.geometry().key().index(), plan);
                                if (previous != null && !previous.hash().equals(plan.hash())) throw new WallFailure(FailureCode.RECIPE_MISMATCH, "split vegetation plan differs");
                            }
                            if (merged.size() > 12) throw new WallFailure(FailureCode.CAPACITY, "combined vegetation window exceeds finite segment bound");
                            return Map.copyOf(merged);
                        });
                    }, io);
                }
                return chain.thenApply(plans -> new PlanWindow(plans.values().stream()
                        .sorted(java.util.Comparator.comparingLong(plan -> plan.geometry().key().index())).toList()));
            }, io).thenAcceptAsync(plans -> {
                active();
                featureSlots.computeIfPresent(owner, (position, current) -> {
                    if (current != this || state.containsKey("closed")) return current;
                    if (plans != null) vegetation.publish(owner, plans);
                    state.put("published", true);
                    return current;
                });
                if (!state.containsKey("published")) throw new WallFailure(FailureCode.CLOSED, "FEATURES lease lost before publication");
                ready.complete(null);
            }, io).whenComplete((value, error) -> {
                if (error != null) {
                    ready.completeExceptionally(error);
                    if (!state.containsKey("closed") && !ProductionSession.this.state.containsKey("closed")) fail(error);
                }
            });
        }
        @Override public void close() {
            if (state.putIfAbsent("closed", true) != null) return;
            ready.completeExceptionally(new WallFailure(FailureCode.CLOSED, "FEATURES callers released"));
            children.values().forEach(PlanLease::close); children.clear();
            vegetation.release(owner);
        }
    }
}
