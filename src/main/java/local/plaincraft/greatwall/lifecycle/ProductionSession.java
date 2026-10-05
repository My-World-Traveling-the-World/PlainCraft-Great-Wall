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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.command.DiagnosticExporter;
import local.plaincraft.greatwall.compat.road.NegotiatedGateService;
import local.plaincraft.greatwall.compat.road.RoadWeaverAdapter;
import local.plaincraft.greatwall.compat.road.WallArchBackfill;
import local.plaincraft.greatwall.component.S1TemplateSet;
import local.plaincraft.greatwall.decay.BiomePaletteIndex;
import local.plaincraft.greatwall.decay.PaletteResolver;
import local.plaincraft.greatwall.diagnostics.DebugService;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.gameplay.BeaconLedger;
import local.plaincraft.greatwall.gameplay.GameplayTypes;
import local.plaincraft.greatwall.gameplay.GarrisonController;
import local.plaincraft.greatwall.gameplay.IgnitionService;
import local.plaincraft.greatwall.gameplay.SiteRuntimeIndex;
import local.plaincraft.greatwall.gameplay.WallSpawnerGuard;
import local.plaincraft.greatwall.generation.*;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.persistence.GateLedger;
import local.plaincraft.greatwall.persistence.HistoryJournal;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.persistence.WallWorldState;
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
    private final ExecutorService historyIo;
    private final ConcurrentHashMap<String, Object> gameplay = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WallWorldState> worldState = new ConcurrentHashMap<>();

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
        // HistoryJournal.close() shuts down its ioOwner, so it must never share production.io.
        historyIo = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "pgw-history-io-" + runtime); thread.setDaemon(true); return thread;
        });
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
                var realization = buildRealization(level, recipe);
                var barrier = new WallStageCoordinator(level, services, recipe, planner, terrain, realization,
                        new ChunkPlanCompiler(), new ChunkWallWriter());
                var snapshot = new Installed(recipe, planner, barrier, frozen);
                snapshots.put("installed", snapshot);
                planner.startupPump();
                trace.record("PRODUCTION_RECIPE_INSTALLED", recipe.dimension(), 0, 0, "recipe-durable-before-generation", "none");
                // S3 gameplay service assembly. The journal is world-scoped (manifest.instance),
                // owns a dedicated IO executor (close() shuts it down), and recovers the beacon count
                // before any credit ledger is published. Recovery is async but all service objects
                // are constructed on the server owner.
                var journal = new HistoryJournal(root.resolve("history"), recipe.instance(), historyIo);
                gameplay.put("history", journal);
                journal.open().thenAcceptAsync(recovery -> {
                    active();
                    var sites = new SiteRuntimeIndex(recipe);
                    var beacons = new BeaconLedger(runtime, recipe.wallId(), journal, server,
                            new GameplayTypes.BeaconProgress(recovery.head().beaconCount(), 0L, 10L, false, true));
                    var ignition = new IgnitionService(runtime, sites, beacons);
                    var garrison = new GarrisonController(runtime, level, recipe, journal, sites);
                    var spawners = new WallSpawnerGuard(garrison, sites);
                    var gates = new GateLedger(journal, server);
                    var backfill = new WallArchBackfill(level, gates);
                    var negotiated = new NegotiatedGateService(services, planner, gates, backfill);
                    var debug = new DebugService(services);
                    var exporter = new DiagnosticExporter(
                            server.getWorldPath(LevelResource.ROOT).resolve("greatwall-exports"), planner, server);
                    var state = new WallWorldState(journal.state());
                    level.getDataStorage().set("plaincraft_great_wall", state);
                    gameplay.put("sites", sites);
                    gameplay.put("beacons", beacons);
                    gameplay.put("ignition", ignition);
                    gameplay.put("garrison", garrison);
                    gameplay.put("spawners", spawners);
                    gameplay.put("gates", gates);
                    gameplay.put("negotiated", negotiated);
                    gameplay.put("debug", debug);
                    gameplay.put("diagnosticExporter", exporter);
                    if (FabricLoader.getInstance().isModLoaded("roadweaver")) {
                        gameplay.put("roadWeaverAdapter", new RoadWeaverAdapter(services, gates, backfill));
                    }
                    worldState.put("world", state);
                    trace.record("PRODUCTION_GAMEPLAY_READY", recipe.dimension(), 0, 0, "history-recovered", "none");
                    GreatWallMod.LOGGER.info("[GreatWall:S1C] GAMEPLAY_READY beacons={} session={}",
                            recovery.head().beaconCount(), runtime);
                }, server).exceptionally(failure -> { fail(failure); return null; });
                installed.complete(snapshot);
                GreatWallMod.LOGGER.info("[GreatWall:S1C] FIXTURE_RECIPE_READY runtime={} wall={} spawn={} axis={} production=OFF",
                        runtime, recipe.wallId(), recipe.frozenSpawn(), recipe.axis());
                GreatWallMod.LOGGER.info("[GreatWall:S1C] LEASE_HALO_RADIUS={} (structure-reference aware, 9x9 lease bound)",
                        local.plaincraft.greatwall.generation.ProtectionSnapshot.leaseRadiusChunks());
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
    UUID session() { return runtime; }
    BeaconLedger beacons() { return requireGameplay("beacons"); }
    GateLedger gates() { return requireGameplay("gates"); }
    SiteRuntimeIndex sites() { return requireGameplay("sites"); }
    GarrisonController garrison() { return requireGameplay("garrison"); }
    IgnitionService ignition() { return requireGameplay("ignition"); }
    WallSpawnerGuard spawners() { return requireGameplay("spawners"); }
    HistoryJournal history() { return requireGameplay("history"); }
    NegotiatedGateService negotiated() { return requireGameplay("negotiated"); }
    DebugService debug() { return requireGameplay("debug"); }
    DiagnosticExporter diagnosticExporter() { return requireGameplay("diagnosticExporter"); }
    RoadWeaverAdapter roadWeaverAdapter() { return requireGameplay("roadWeaverAdapter"); }
    WallWorldState worldState() {
        var value = worldState.get("world");
        if (value == null) throw new IllegalStateException("wall history header is not recovered");
        return value;
    }
    /** Null-returning accessors for lifecycle events that may fire before gameplay assembly. */
    SiteRuntimeIndex sitesIfReady() { return (SiteRuntimeIndex) gameplay.get("sites"); }
    GarrisonController garrisonIfReady() { return (GarrisonController) gameplay.get("garrison"); }
    RoadWeaverAdapter roadWeaverAdapterIfReady() { return (RoadWeaverAdapter) gameplay.get("roadWeaverAdapter"); }
    private <T> T requireGameplay(String key) {
        @SuppressWarnings("unchecked")
        T value = (T) gameplay.get(key);
        if (value == null) throw new IllegalStateException("gameplay service not assembled: " + key);
        return value;
    }
    private RealizationPlanner buildRealization(ServerLevel level, WorldRecipe recipe) throws IOException {
        var catalog = PaletteResolver.load(server.getResourceManager());
        var palette = new PaletteResolver(recipe.seed(), catalog);
        var biome = new BiomePaletteIndex(BiomePaletteIndex.freeze(level.registryAccess(), catalog));
        return new RealizationPlanner(recipe, new SurfaceProbe(), biome, S1TemplateSet.create(recipe.seed(), palette));
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
        var garrison = gameplay.get("garrison");
        if (garrison instanceof GarrisonController controller) controller.tick();
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
        // Stop new gameplay mutations before flushing permanent history: garrison birth and
        // beacon credits must cease, then the journal flushes and shuts down its own executor.
        for (String key : List.of("garrison", "beacons", "sites", "negotiated", "debug", "roadWeaverAdapter")) {
            var service = gameplay.get(key);
            if (service instanceof AutoCloseable closable) {
                try { closable.close(); } catch (Exception ignored) {}
            }
        }
        var journal = (HistoryJournal) gameplay.get("history");
        bootstrap.close();
        bootstrap.drain().whenComplete((value, error) -> {
            if (journal != null) journal.close();
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
        // 已确认：这四个 mod 均无需长城拒绝（2026-10-05 用户拍板）。
        //   tanshugetrees            = 自家移植，RoadWeaver 已有精确占地绕行
        //   plaincraft_erdtree_sanctuary = 独立大群系，不抢长城地块
        //   plaincraft_erdtree_sapling   = 仅玩家手动种植，非世界生成阶段
        //   ohthetreesyoullgrow       = 客户端未安装
        // 移除 BLOCKED 拒绝，生产/S1C 启动不再因这些 mod 而失败。
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
