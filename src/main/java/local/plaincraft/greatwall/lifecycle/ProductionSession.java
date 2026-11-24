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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.command.DiagnosticExporter;
import local.plaincraft.greatwall.compat.road.NegotiatedGateService;
import local.plaincraft.greatwall.compat.road.WallArchBackfill;
import local.plaincraft.greatwall.component.S1TemplateSet;
import local.plaincraft.greatwall.component.S2TemplateSet;
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
import local.plaincraft.greatwall.planning.FineHeightIndex;
import local.plaincraft.greatwall.planning.PlannerScheduler;
import local.plaincraft.greatwall.planning.StructureObstacleIndex;
import local.plaincraft.greatwall.terrain.RealizationPlanner;
import local.plaincraft.greatwall.terrain.FineSampleCache;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.LevelResource;

/** Private isolated-fixture integration. There is deliberately no enabled production-world switch. */
final class ProductionSession implements AutoCloseable {
    private record Installed(WorldRecipe recipe, PlannerScheduler planner, ProductionInputs inputs) {}
    private final UUID runtime = UUID.randomUUID();
    private final MinecraftServer server;
    private final WorldServices services;
    private final Path root;
    private final StageTrace trace = new StageTrace(runtime);
    private final VegetationGuard vegetation = new VegetationGuard();
    // 仅保存本世界已编译占地，禁止跨存档复用结构屏蔽范围。
    private final StructurePlacementGuard structures = new StructurePlacementGuard();
    /** Null until the recipe hash is known; the dedicated biome identity is opt-in per run. */
    private volatile local.plaincraft.greatwall.compat.biome.GreatWallBiomeRuntime biomeRuntime;
    /**
     * Registry epoch for the biome identity. V1 never hot-swaps an active registry, so the epoch is
     * a constant: a new registry means a new world, which means a new session and a new runtime.
     */
    private static final long BIOME_REGISTRY_EPOCH = 1L;
    /**
     * Completes only after every gameplay service has been constructed and published.
     *
     * <p>{@code installed} becomes ready as soon as the recipe is durable, which is well before the
     * historical journal has been recovered and the gameplay objects exist. Callers that need those
     * objects must await this instead, otherwise they can observe a session that looks active while
     * the service they ask for does not exist yet.
     */
    private final CompletableFuture<GameplayStamp> gameplayReady = new CompletableFuture<>();
    /** Marker type; carries no data, its completion is the signal. */
    private enum GameplayStamp { INSTANCE }
    private final ConcurrentHashMap<String, Boolean> state = new ConcurrentHashMap<>();
    /** 回调与编译交接一次精采样，避免开局后还由会话持有整条路线的地形列。 */
    private final StartupSampleHandoff startupSamples = new StartupSampleHandoff();
    /**
     * Whether startup stages run one at a time.
     *
     * <p><b>False (overlapped) by default as of 0.7.119.</b> This used to default to serial on the
     * argument that the stages are individually parallel and share one CPU, so overlapping them
     * makes each wait for the other's threads. Two same-seed 16-core runs measured the opposite:
     * the overlap run came in at {@code totalMs=869,011} against {@code 981,497} serial — the scan
     * (291 s) is hidden behind the fine sample almost for free, because the scan is dominated by
     * chunk loading rather than by arithmetic. Overlapping costs the fine sample itself ~145 s
     * (545 s overlapped vs 400 s alone) but hides 291 s of scan, so the wall still wins ~112 s.
     *
     * <p>The accounting hides this: while overlapped, the FINE_SAMPLE stage timer only starts when
     * the scan callback runs, so {@code fineMs} reports the tail (254 s) rather than the real
     * duration (545 s). Compare {@code totalMs}, not the stage rows, when judging this flag.
     *
     * <p>{@code -Dgreatwall.startup.serialStages=true} forces the old serial shape for an A/B run.
     */
    private static final boolean SERIAL_STAGES = Boolean.parseBoolean(
            System.getProperty("greatwall.startup.serialStages", "false"));
    /** Set when a compile attempt deferred for want of the fine sample, so the callback re-triggers it. */
    private final java.util.concurrent.atomic.AtomicBoolean compileDeferred =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final ConcurrentHashMap<String, String> failures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServerLevel> levels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProductionInputs> inputs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Installed> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> admissions = new ConcurrentHashMap<>();
    private final CompletableFuture<Installed> installed = new CompletableFuture<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final ThreadPoolExecutor io;
    private final WorldBootstrap bootstrap;
    private final ExecutorService historyIo;
    private final ConcurrentHashMap<String, Object> gameplay = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WallWorldState> worldState = new ConcurrentHashMap<>();
    /** Planning-time corridor structure scan; constructed once the overworld is known and closed on
     *  session shutdown. */
    private volatile local.plaincraft.greatwall.generation.CorridorStructureScanner structureScanner;
    // 批量规划和发布共用同一个扫描门，避免晚到的预热通知绕过避让。
    private final StartupStructureGate structureGate = new StartupStructureGate();
    private volatile java.util.List<local.plaincraft.greatwall.model.WallTypes.Bounds> planningStructures = java.util.List.of();
    /** Realization + chunk-plan compiler, held for the planning-phase compilation. */
    private volatile local.plaincraft.greatwall.terrain.RealizationPlanner realization;
    private volatile local.plaincraft.greatwall.generation.ChunkPlanCompiler chunkCompiler;
    // 仅本会话拥有累计观测，换世界不能继承旧峰值或把仍运行窗口伪装为已释放。
    private final local.plaincraft.greatwall.diagnostics.CompileWindowMemory compileMemory =
            new local.plaincraft.greatwall.diagnostics.CompileWindowMemory();

    ProductionSession(MinecraftServer server, WorldServices services) {
        this.server = server; this.services = services;
        var directory = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        // Production by default. This used to require the S1C fixture flag and two marker files, and
        // threw when -Dgreatwall.production was set - a shipped jar that refused to be the product.
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
        bootstrap.ready().whenComplete((recipe, error) -> {
            GreatWallMod.LOGGER.info("[GreatWall:DIAG] bootstrap.ready CALLBACK thread={} error={} closed={}",
                Thread.currentThread().getName(), error != null, state.containsKey("closed"));
            server.execute(() -> {
                GreatWallMod.LOGGER.info("[GreatWall:DIAG] bootstrap.ready SERVER_EXECUTE thread={} closed={}",
                    Thread.currentThread().getName(), state.containsKey("closed"));
                if (state.containsKey("closed")) return;
                if (error != null) {
                    GreatWallMod.LOGGER.error("[GreatWall:DIAG] bootstrap.ready ERROR_BRANCH", error);
                    fail(error);
                    return;
                }
                try {
                    GreatWallMod.LOGGER.info("[GreatWall:DIAG] bootstrap.ready INSTALL_START wallId={}", recipe.wallId());
                    active();
                var level = levels.get(recipe.dimension());
                var frozen = inputs.get("frozen");
                if (level == null || frozen == null) throw new IllegalStateException("recipe published before prepareLevels freeze");
                var planner = bootstrap.transferToPlanner(new SegmentCacheStore(root.resolve("cache"), recipe), io);
                var store = ChunkEvidenceStore.production(root.resolve("evidence"));
                var realization = buildRealization(level, recipe);
                this.realization = realization;
                this.chunkCompiler = new ChunkPlanCompiler();
                var snapshot = new Installed(recipe, planner, frozen);
                snapshots.put("installed", snapshot);
                installBiomeRuntime(level, recipe);
                // 先让精采样可并行取路线；批量规划必须等结构避让索引装好。
                planner.startupPump();
                startStructureScanAndPrewarm(level, recipe, planner);
                trace.record("PRODUCTION_RIVERS_BULK", recipe.dimension(), 0, 0, "recipe-installed-unconditional-bulk", "none");
                trace.record("PRODUCTION_RECIPE_INSTALLED", recipe.dimension(), 0, 0, "recipe-durable-before-generation", "none");
                // S3 gameplay service assembly. The journal is world-scoped (manifest.instance),
                // owns a dedicated IO executor (close() shuts it down), and recovers the beacon count
                // before any credit ledger is published. Recovery is async but all service objects
                // are constructed on the server owner.
                // HistoryJournal derives both its own `history/` subtree and the sibling `manifest/`
                // directory from the root it is given, so it must receive the greatwall root itself.
                // Passing `root/history` made it look for `root/history/manifest/creation.json`, which
                // never exists, so every real-world run failed recovery with "creation token required
                // for history" while the unit tests (which pass the correct root) stayed green.
                var journal = new HistoryJournal(root, recipe.instance(), historyIo);
                gameplay.put("history", journal);
                journal.open().thenAcceptAsync(recovery -> {
                    active();
                    var sites = new SiteRuntimeIndex(recipe);
                    // Use the post-replay count: a WAL tail can carry beacon records that are not yet
                    // checkpointed, and seeding the ledger from the checkpoint alone would undercount
                    // beacons that the verified log already contains.
                    var beacons = new BeaconLedger(runtime, recipe.wallId(), journal, server,
                            new GameplayTypes.BeaconProgress(recovery.replayedBeaconCount(), 0L, 10L, false, true));
                    var ignition = new IgnitionService(runtime, sites, beacons);
                    var garrison = new GarrisonController(runtime, level, recipe, journal, sites);
                    var spawners = new WallSpawnerGuard(garrison, sites);
                    var gates = new GateLedger(journal, server);
                    // 门洞回填只读取墙生成时已持久化的真实写集（build-proof），
                    // 不在缺失时伪造空集；读取走 IO 线程，校验与写入留在 server owner。
                    var backfill = new WallArchBackfill(level, gates, io, owner -> {
                        try {
                            return store.readPlan(owner);
                        } catch (java.io.IOException failure) {
                            throw new java.util.concurrent.CompletionException(failure);
                        }
                    });
                    var negotiated = new NegotiatedGateService(services, planner, gates, backfill);
                    // The wall body backfill reads the same durable build-proofs the arch backfill does,
                    // so a plan published in an earlier session can still paint a chunk loaded today.
                    local.plaincraft.greatwall.generation.WallBodyBackfill.install(
                            new local.plaincraft.greatwall.generation.WallBodyBackfill.PlanStore() {
                                @Override public java.util.Optional<local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan>
                                        read(local.plaincraft.greatwall.model.WallTypes.ChunkKey owner)
                                        throws java.io.IOException {
                                    return store.readPlan(owner);
                                }

                                @Override public void write(local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan plan)
                                        throws java.io.IOException {
                                    store.persistPlan(plan);
                                }
                            });
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
                    gameplay.put("backfill", backfill);
                    gameplay.put("negotiated", negotiated);
                    gameplay.put("debug", debug);
                    gameplay.put("diagnosticExporter", exporter);
                    // RoadWeaver interop is disabled: the wall and the roads generate independently.
                    // No wall obstacle provider, no gate-height agreement and no ABI allowlist are
                    // registered, so RoadWeaver takes its plain no-wall path exactly as it did
                    // before the integration existed. The wall still owns the main thread for its
                    // own prewarm (see MinecraftServerProbeMixin), so startup stays serial.
                    worldState.put("world", state);
                    trace.record("PRODUCTION_GAMEPLAY_READY", recipe.dimension(), 0, 0, "history-recovered", "none");
                    GreatWallMod.LOGGER.info("[GreatWall:S1C] GAMEPLAY_READY beacons={} replayedBeyondHead={} session={}",
                            recovery.replayedBeaconCount(), recovery.replayedBeyondHead(), runtime);
                    // Published last: until this completes, gameplay services are absent and no
                    // caller may be told the session is fully assembled.
                    gameplayReady.complete(GameplayStamp.INSTANCE);
                }, server).exceptionally(failure -> {
                    // Assembly failed, so no caller can ever be admitted through the gameplay gate.
                    gameplayReady.completeExceptionally(failure);
                    fail(failure);
                    return null;
                });
                installed.complete(snapshot);
                GreatWallMod.LOGGER.info("[GreatWall:S1C] FIXTURE_RECIPE_READY runtime={} wall={} spawn={} axis={} production=OFF",
                        runtime, recipe.wallId(), recipe.frozenSpawn(), recipe.axis());
                GreatWallMod.LOGGER.info("[GreatWall:S1C] LEASE_HALO_RADIUS={} (structure-reference aware, 9x9 lease bound)",
                        local.plaincraft.greatwall.generation.ProtectionSnapshot.leaseRadiusChunks());
            } catch (Throwable failure) {
                GreatWallMod.LOGGER.error("[GreatWall:DIAG] bootstrap.ready INSTALL_THREW", failure);
                fail(failure);
            }
        });
        });
    }

    UUID runtime() { return runtime; }
    WorldBootstrap bootstrap() { return bootstrap; }
    VegetationGuard vegetation() { return vegetation; }
    StructurePlacementGuard structures() { return structures; }
    /** The dedicated biome runtime for this run, or null when the biome identity mode is off. */
    local.plaincraft.greatwall.compat.biome.GreatWallBiomeRuntime biomeRuntime() { return biomeRuntime; }

    /**
     * Builds the Great Wall biome runtime and binds its identity, if and only if the bundled biome
     * data pack is actually loaded and the biome key resolves.
     *
     * <p>Failing to resolve is not fatal to the run: the wall still builds exactly as before, it
     * simply has no dedicated biome identity. That keeps this feature strictly additive, so a
     * missing or disabled pack can never block world generation.
     */
    private void installBiomeRuntime(ServerLevel level, WorldRecipe recipe) {
        var runtimeId = local.plaincraft.greatwall.compat.biome.GreatWallBiomeRuntime.BIOME_ID;
        var biomeKey = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.BIOME, runtimeId);
        var lookup = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        var resolved = lookup.getHolder(biomeKey);
        if (resolved.isEmpty()) {
            state.put("biome-identity-absent", true);
            GreatWallMod.LOGGER.info("[GreatWall:biome] identity {} is not present; dedicated biome mode OFF",
                    runtimeId);
            return;
        }
        var candidate = new local.plaincraft.greatwall.compat.biome.GreatWallBiomeRuntime(
                runtime, level.dimension().location().toString(), recipe.recipeHash(), BIOME_REGISTRY_EPOCH, server);
        candidate.markPackEnabled(true);
        candidate.bindIdentity(resolved.get());
        biomeRuntime = candidate;
        GreatWallMod.LOGGER.info("[GreatWall:biome] identity {} bound; dedicated biome mode ON session={}",
                runtimeId, runtime);
    }
    CompletableFuture<WorldRecipe> recipeReady() { return installed.thenApply(Installed::recipe).copy(); }
    PlannerScheduler planning() { return requireInstalled().planner(); }
    WorldRecipe recipe() { return requireInstalled().recipe(); }
    TreeBoundsCatalog trees() {
        var value = inputs.get("frozen");
        if (value == null) throw new IllegalStateException("tree catalog not frozen");
        return value.trees();
    }
    UUID session() { return runtime; }
    BeaconLedger beacons() { return requireGameplay("beacons"); }
    GateLedger gates() { return requireGameplay("gates"); }
    SiteRuntimeIndex sites() { return requireGameplay("sites"); }
    GarrisonController garrison() { return requireGameplay("garrison"); }
    IgnitionService ignition() { return requireGameplay("ignition"); }
    /**
     * The ignition service, or null when gameplay assembly has not finished.
     *
     * <p>Player interaction can arrive before the journal is recovered and the gameplay services
     * exist. A caller on that path must degrade to "not a wall fire pit" for the moment rather than
     * failing the interaction, so it asks without demanding.
     */
    IgnitionService ignitionIfReady() { return (IgnitionService) gameplay.get("ignition"); }
    WallSpawnerGuard spawners() { return requireGameplay("spawners"); }
    /**
     * The spawner guard, or null when gameplay assembly has not finished.
     *
     * <p>Block entities tick from the moment their chunk is loaded, which can happen before the
     * journal has been recovered and the gameplay services exist. A hot-path caller such as
     * {@code WallSpawnerMixin} must therefore ask without demanding: returning null lets it fall
     * through to vanilla behaviour for that tick instead of turning a normal startup window into a
     * server crash.
     */
    WallSpawnerGuard spawnersIfReady() { return (WallSpawnerGuard) gameplay.get("spawners"); }
    HistoryJournal history() { return requireGameplay("history"); }
    NegotiatedGateService negotiated() { return requireGameplay("negotiated"); }
    /** Arch backfill, used by the explicit repair-confirm path. Null until gameplay is assembled. */
    local.plaincraft.greatwall.compat.road.WallArchBackfill backfillIfReady() {
        return (local.plaincraft.greatwall.compat.road.WallArchBackfill) gameplay.get("backfill");
    }
    DebugService debug() { return requireGameplay("debug"); }
    DiagnosticExporter diagnosticExporter() { return requireGameplay("diagnosticExporter"); }
    WallWorldState worldState() {
        var value = worldState.get("world");
        if (value == null) throw new IllegalStateException("wall history header is not recovered");
        return value;
    }
    /** Null-returning accessors for lifecycle events that may fire before gameplay assembly. */
    SiteRuntimeIndex sitesIfReady() { return (SiteRuntimeIndex) gameplay.get("sites"); }
    GarrisonController garrisonIfReady() { return (GarrisonController) gameplay.get("garrison"); }
    /**
     * Returns a gameplay service, refusing to answer before assembly has actually completed.
     *
     * <p>The explicit {@code gameplayReady} check is what closes the window where {@code installed} is
     * already done (recipe durable) but the journal has not been recovered and the services do not
     * exist yet. Without it a caller could see an "active" session and then receive a confusing
     * missing-service error, or worse, act on a partially assembled world.
     */
    private <T> T requireGameplay(String key) {
        if (!gameplayReady.isDone()) {
            throw new WallFailure(FailureCode.CLOSED,
                    "gameplay services are not ready yet; requested " + key);
        }
        if (gameplayReady.isCompletedExceptionally()) {
            throw new WallFailure(FailureCode.CLOSED,
                    "gameplay assembly failed; requested " + key);
        }
        @SuppressWarnings("unchecked")
        T value = (T) gameplay.get(key);
        if (value == null) throw new IllegalStateException("gameplay service not assembled: " + key);
        return value;
    }

    /** True once every gameplay service has been constructed and published. */
    boolean gameplayReady() { return gameplayReady.isDone() && !gameplayReady.isCompletedExceptionally(); }

    /** Fails every waiter when the session closes before gameplay assembly finished. */
    private void failGameplayWaiters(Throwable error) {
        gameplayReady.completeExceptionally(error);
    }
    private RealizationPlanner buildRealization(ServerLevel level, WorldRecipe recipe) throws IOException {
        var catalog = PaletteResolver.load(server.getResourceManager());
        var palette = new PaletteResolver(recipe.seed(), catalog);
        var biome = new BiomePaletteIndex(BiomePaletteIndex.freeze(level.registryAccess(), catalog));
        return new RealizationPlanner(recipe, new SurfaceProbe(), biome, local.plaincraft.greatwall.component.ImportedSiteGeometry.enabled(recipe)
                ? S2TemplateSet.create(recipe.seed(), palette, recipe.config().templateVersion()) : S1TemplateSet.create(recipe.seed(), palette));
    }

    /**
     * Scans the wall corridor for structure footprints, then releases the bulk prewarm once the
     * obstacle index is published. The scan and the prewarm both run off the server thread; the
     * only ordering that matters is index-before-dispatch, which the completion chain guarantees.
     */
    private void startStructureScanAndPrewarm(ServerLevel level, WorldRecipe recipe, PlannerScheduler planner) {
        // Corridor half-width in blocks. The ridge jitters up to +-512 blocks laterally (searchStep
        // rejects offsets beyond 512*256 Q8), so the corridor must span that full envelope plus the
        // structure footprint; 576 blocks covers the envelope with a 64-block margin. This trades
        // scan width (more candidates) for never missing a structure the wall can actually reach.
        int corridorHalfWidth = 576;
        var range = planner.startupRange();
        if (range.segments() == 0) {
            GreatWallMod.LOGGER.warn("[GreatWall:预热] 冻结边界内没有可完整规划的长城段");
            PrewarmProgress.finish(false);
            return;
        }
        long lengthBlocks = range.structureScanBlocks();
        if (range.segments() < range.configuredSegments() || range.segments() < range.requestedSegments()) {
            GreatWallMod.LOGGER.info("[GreatWall:预热] 本次开局规划 {} 段（请求 {}，总段数 {}），结构扫描 {} 格",
                    range.segments(), range.requestedSegments(), range.configuredSegments(), lengthBlocks);
        }
        // Chunk-bounds of the axis-aligned corridor. The axis may run at an angle, so take a
        // generous AABB around the two endpoints and widen by the corridor half-width.
        int originX = recipe.axis().origin().x();
        int originZ = recipe.axis().origin().z();
        int endX = originX + (int) (recipe.axis().exQ30() * lengthBlocks / (1L << 30));
        int endZ = originZ + (int) (recipe.axis().ezQ30() * lengthBlocks / (1L << 30));
        int minChunkX = Math.floorDiv(Math.min(originX, endX) - corridorHalfWidth, 16);
        int maxChunkX = Math.floorDiv(Math.max(originX, endX) + corridorHalfWidth, 16);
        int minChunkZ = Math.floorDiv(Math.min(originZ, endZ) - corridorHalfWidth, 16);
        int maxChunkZ = Math.floorDiv(Math.max(originZ, endZ) + corridorHalfWidth, 16);

        // A coarse water map of the corridor, from the heightmaps alone: the world surface stands above
        // the ocean floor exactly where there is water, so a station costs two heightmap reads and no
        // density work at all. It runs off the route lease so it never blocks the startup, and its numbers
        // are available whether or not the hydrology classifier ever certifies a column - which is the
        // measurement the crossing rule has always been missing.
        // 诊断请求也会冻结第 0 段；新版由正式水文日志出数，避免先于结构索引启动。
        if (!local.plaincraft.greatwall.model.WallRecipeRules.landFirst(recipe.config())) try {
            var waterLease = planner.requireRoute(0L, Priority.PLAYER_PREFETCH);
            waterLease.future().whenCompleteAsync((waterRoute, waterError) -> {
                try {
                    if (waterError == null && waterRoute != null) {
                        local.plaincraft.greatwall.terrain.CorridorWaterScan.log(level, waterRoute);
                    } else if (waterError != null) {
                        GreatWallMod.LOGGER.warn("[GreatWall:water] CORRIDOR_WATER skipped reason={}",
                                waterError.toString());
                    } else {
                        // Neither a route nor an error: the future completed with nothing at all. This
                        // branch printing nothing is why the measurement went missing from runs whose log
                        // held every other water line.
                        GreatWallMod.LOGGER.warn(
                                "[GreatWall:water] CORRIDOR_WATER skipped reason=null-route");
                    }
                } finally {
                    waterLease.close();
                }
            }, server);
        } catch (RuntimeException cannotLease) {
            GreatWallMod.LOGGER.warn("[GreatWall:water] CORRIDOR_WATER not started reason={}",
                    cannotLease.toString());
        }

        // A structure scan's result is a pure function of the recipe (seed + world datapack/mods):
        // the same recipe always resolves the same footprints. Cache it so a re-open skips the
        // ~3 minute rescan. The cache is keyed by recipeHash, which already covers the terrain,
        // palette and template fingerprints; changing any structure datapack changes the hash and
        // therefore invalidates the cache.
        final String scanKey = recipe.recipeHash() + "-l" + lengthBlocks;
        final List<Bounds> cached = loadStructureScanCache(root, scanKey);
        if (cached != null) {
            GreatWallMod.LOGGER.info("[GreatWall:corridor] structure scan cache hit ({} boxes); skipping rescan", cached.size());
            completeStructureScan(planner, cached, null);
            return;
        }

        var scanner = new local.plaincraft.greatwall.generation.CorridorStructureScanner();
        this.structureScanner = scanner;
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setStage(
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.STRUCTURE_SCAN);
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(0, 0);
        // Start the fine sample NOW, beside the scan. The scan reads the world for 130 s to resolve
        // structure boxes; the sample only needs the recipe's route, so leaving it until the boxes are
        // installed puts the longest stage of the startup behind a stage it does not depend on.
        // Overlapped by default since 0.7.119: two same-seed 16-core runs put the overlap at
        // totalMs=869,011 against 981,497 serial, because the scan is chunk-load bound and therefore
        // hides almost entirely behind the sample. See SERIAL_STAGES above for the full numbers and
        // for why the stage rows mislead. -Dgreatwall.startup.serialStages=true restores serial.
        // 新版精采依赖已经避让结构的冻结墙线和楼址，不能提前启动规划。
        if (!SERIAL_STAGES && !local.plaincraft.greatwall.model.WallRecipeRules.landFirst(recipe.config())) startFineSample(planner);
        // The wall's true corridor band (segment endpoints + half-width): candidates outside it are
        // dropped up front, so an angled wall no longer scans the AABB's 3.5x overscan.
        var corridor = new local.plaincraft.greatwall.generation.CorridorStructureScan.Corridor(
                originX, originZ, endX, endZ, corridorHalfWidth);
        scanner.scan(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ, corridor, scanKey)
                .thenApply(boxes -> {
                    var bounds = new ArrayList<Bounds>(boxes.size());
                    for (var owned : boxes) {
                        var b = owned.box();
                        bounds.add(new Bounds(b.minX(), b.minY(), b.minZ(),
                                b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1));
                    }
                    return List.copyOf(bounds);
                })
                .whenComplete((structures, error) -> server.execute(() -> {
                    if (completeStructureScan(planner, structures, error)) {
                        saveStructureScanCache(root, scanKey, structures);
                    }
                }));
    }

    private boolean completeStructureScan(PlannerScheduler planner, List<Bounds> structures, Throwable error) {
        if (state.containsKey("closed") || state.containsKey("failed")) return false;
        boolean installed = structureGate.complete(structures, error,
                boxes -> installStructureIndex(planner, boxes), failure -> {
                    GreatWallMod.LOGGER.error("[GreatWall:corridor] 结构扫描或索引安装失败；本次会话不发布新长城计划", failure);
                    PrewarmProgress.finish(false);
                });
        if (installed) {
            // 两项工作继续并行；主阶段先发布一次，避免预热标题立即跳回采样。
            PrewarmProgress.beginParallelPlanning();
            startBulkPrewarm(planner, "structures-installed");
            startFineSample(planner);
        }
        return installed;
    }

    /** 先安装索引及编译回调，再开放规划门，避免先消费到未避让的路线。 */
    private void installStructureIndex(PlannerScheduler planner, List<Bounds> structures) {
        int margin = local.plaincraft.greatwall.config.GreatWallConfig.parameters().obstacleMarginBlocks();
        if (structures.isEmpty()) {
            planner.setObstacles(StructureObstacleIndex.empty(margin));
        } else {
            var boxes = new ArrayList<local.plaincraft.greatwall.generation.CorridorStructureScan.OwnedBox>(structures.size());
            for (Bounds b : structures) {
                boxes.add(new local.plaincraft.greatwall.generation.CorridorStructureScan.OwnedBox(
                        "cached", 0L,
                        new BoundingBox(b.minX(), b.minY(), b.minZ(),
                                b.maxXExclusive() - 1, b.maxYExclusive() - 1, b.maxZExclusive() - 1)));
            }
            planner.setObstacles(StructureObstacleIndex.of(boxes, margin));
        }
        planningStructures = structures;
        // Post the compile onto the server queue the moment the prewarm settles, instead of waiting
        // for the tick loop to notice. RoadWeaver parks the server thread in its own managedBlock
        // while waiting for this wall to publish, and that pump is what executes these tasks - so
        // the wall compiles inside the road's wait and the roads start only after the wall can
        // actually paint. Waiting for a tick instead deadlocked the two against each other.
        planner.onBulkReady(() -> server.execute(this::compileAtStartup));

    }

    /**
     * Requests the fine cross-section sample, once per session.
     *
     * <p>Called from two places on purpose: the scan path calls it <em>before</em> the corridor
     * structure scan, and the installer calls it again (a no-op by then) so a structure-scan cache hit
     * still starts the sample. Idempotent, because a second {@code requireFineSamples} would walk the
     * whole route again and publish a second index.
     */
    private void startFineSample(PlannerScheduler planner) {
        if (!startupSamples.begin()) return;
        try {
            // 读缓存也走结案路径；身份由配方、地形和维度共同约束，避免复用另一世界的证据。
            var frozen = recipe();
            planner.setFineSampleCache(FineSampleCache.load(
                    root.resolve("samples").resolve("fine-columns.bin"),
                    frozen.recipeHash() + '|' + frozen.terrainHash() + '|' + frozen.dimension()));
            planner.requireFineSamples(Priority.PLAYER_PREFETCH)
                    .whenComplete((fineIndex, sampleError) -> onFineSampleSettled(fineIndex, sampleError));
        } catch (Throwable couldNotStart) {
            onFineSampleSettled(null, couldNotStart);
        }
    }

    /**
     * Handles the fine sample's completion: publish the index, then run a compile that was waiting for it.
     *
     * <p>Runs inside a {@code whenComplete} whose returned future is discarded, so anything thrown here
     * would vanish - the failure mode that once parked the world for thirty minutes. Every path ends by
     * marking the sample settled and re-triggering a deferred compile.
     */
    private void onFineSampleSettled(FineHeightIndex fineIndex, Throwable sampleError) {
        // 关闭后不收回结果，也不为已退出的会话重新排入编译任务。
        if (!startupSamples.complete(sampleError == null ? fineIndex : null)) return;
        try {
            if (sampleError != null) {
                GreatWallMod.LOGGER.warn("[GreatWall:corridor] 精采样失败，无法编译长城施工计划",
                        sampleError);
            } else if (fineIndex != null) {
                // 采样与结构扫描重叠，不能把精采样的总数盖到当时正在显示的阶段。
                PrewarmProgress.setSubProgress(PrewarmProgress.Stage.FINE_SAMPLE, fineIndex.size(), fineIndex.size());
                logFineCoverage(fineIndex);
            }
        } catch (Throwable publishFailure) {
            GreatWallMod.LOGGER.error("[GreatWall:corridor] publishing the fine sample failed; "
                    + "the wall will not compile", publishFailure);
        } finally {
            triggerDeferredCompile();
        }
    }

    /**
     * One line describing what the fine sample actually covered, not just how many columns it holds.
     *
     * <p>A component whose footprint reaches a cross-section column the sparse plan skipped fails later
     * with {@code complete component T0 column missing: (x,0,z)} and leaves a gap in the wall. That
     * message alone cannot say whether the point was outside the sampled band, inside the band but
     * never probed, or probed and dropped - the lateral span below separates all three. {@code
     * lateralSlots} against {@code fullCrossSection} is the coverage ratio of one cross-section.
     */
    private static void logFineCoverage(local.plaincraft.greatwall.planning.FineHeightIndex index) {
        try {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            int minLateral = Integer.MAX_VALUE, maxLateral = Integer.MIN_VALUE;
            var lateralSlots = new java.util.TreeSet<Integer>();
            for (var column : index.columns()) {
                minX = Math.min(minX, column.x());
                maxX = Math.max(maxX, column.x());
                minZ = Math.min(minZ, column.z());
                maxZ = Math.max(maxZ, column.z());
                minLateral = Math.min(minLateral, column.lateral());
                maxLateral = Math.max(maxLateral, column.lateral());
                lateralSlots.add(column.lateral());
            }
            GreatWallMod.LOGGER.info("[GreatWall:diag] FINE_COVERAGE distinct={} raw={} x=[{},{}] z=[{},{}] "
                            + "lateral=[{},{}] lateralSlots={} fullCrossSection={} band=+/-{}",
                    index.size(), index.orderedColumns().size(), minX, maxX, minZ, maxZ,
                    minLateral, maxLateral, lateralSlots.size(),
                    2 * local.plaincraft.greatwall.planning.WallFinePlanner.LATERAL + 1,
                    local.plaincraft.greatwall.planning.WallFinePlanner.LATERAL);
        } catch (RuntimeException logOnly) {
            // A diagnostic must never take the stage down with it.
            GreatWallMod.LOGGER.warn("[GreatWall:diag] FINE_COVERAGE could not be computed", logOnly);
        }
    }

    /** Runs the compile if an earlier attempt deferred for want of the fine sample. */
    private void triggerDeferredCompile() {
        // The flag is deliberately NOT consumed here any more. It used to be a getAndSet(false) at this
        // point; it is now cleared where the compile really starts (compilePlanningPlans0), so that the
        // thread parked in managedBlock can see it and run the compile itself.
        //
        // Why that matters: a task queued with server.execute() onto a server thread that is parked in
        // managedBlock is only polled between that loop's chunk-queue drain rounds. A measured session
        // published the fine sample at 17:00:31.9 and did not log PLANNING_COMPILE_BEGIN until 17:00:49 -
        // 17.1 s of the 35.1 s compile stage spent waiting for a queue hop, while the whole compile is
        // only 10.7 s. compileRetryPending() lets the parked loop take it on its next round.
        if (!compileDeferred.get() || state.containsKey("closed")) return;
        try {
            server.execute(this::compileAtStartup);
        } catch (Throwable cannotQueue) {
            GreatWallMod.LOGGER.error("[GreatWall:S1C] PLANNING_COMPILE_NOT_QUEUED after the fine sample; "
                    + "releasing the startup stage", cannotQueue);
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
        }
    }

    /**
     * Diagnostic probe for the gap between the fine sample landing and the compile starting.
     *
     * <p><b>Prints once per state change, not once per managedBlock round.</b> The four flags below
     * are the whole state of the wait, and while the fine sample runs they do not move; repeating
     * the same tuple every round says nothing new. One measured session printed this line 3,057
     * times between 19:53:37 and 20:06:46 (about 3.9/s, more than half of the whole log) and then
     * stopped - the last line landed exactly on the fine sample finishing. That is self-limiting,
     * not a loop: as soon as the sample handoff settles, the wait this probe describes is
     * over and the parked loop no longer asks. So the volume was log noise, and this de-rating is
     * noise abatement, not a fix for a spinning probe.
     *
     * <p>The dedup key is exactly {@code (predicate, compileDeferred, sampleSettled, started)}:
     * the first occurrence prints, an unchanged repeat stays silent, and a changed tuple prints
     * again. The 2 s floor on top only bounds the rate if the tuple ever flaps around a transition;
     * it can delay a transition line by at most 2 s and never suppresses a state that persists.
     * Diagnostics only - the flags are read here and nothing is written or decided, so the caller's
     * control flow is untouched.
     */
    public void logCompileRetryProbe() {
        boolean predicate = compileRetryPending();
        boolean deferred = compileDeferred.get();
        boolean settled = startupSamples.settled();
        boolean started = state.containsKey("compile-started");
        int signature = (predicate ? 1 : 0) | (deferred ? 2 : 0) | (settled ? 4 : 0) | (started ? 8 : 0);
        if (signature == lastCompileRetryProbeSignature) return;
        long now = System.nanoTime();
        // Only throttle after a first line exists: System.nanoTime() may legitimately be negative,
        // so a "now - 0" window cannot be relied on to pass on the very first call.
        if (lastCompileRetryProbeSignature != -1
                && now - lastCompileRetryProbeNanos < COMPILE_RETRY_PROBE_FLOOR_NANOS) {
            return;
        }
        lastCompileRetryProbeSignature = signature;
        lastCompileRetryProbeNanos = now;
        GreatWallMod.LOGGER.info("[PGW-COMPILE-RETRY] enter predicate={} compileDeferred={} "
                        + "fineSampleSettled={} started={} thread={}",
                predicate, deferred, settled, started, Thread.currentThread().getName());
    }
    /** Fallback floor between two probe lines; see {@link #logCompileRetryProbe()}. */
    private static final long COMPILE_RETRY_PROBE_FLOOR_NANOS = 2_000_000_000L;
    /** Last printed flag tuple; -1 means nothing has been printed yet. */
    private volatile int lastCompileRetryProbeSignature = -1;
    private volatile long lastCompileRetryProbeNanos;

    /**
     * {@return true when a compile attempt deferred for want of the fine sample and that sample has landed}
     *
     * <p>The startup thread parks inside {@code managedBlock} until the wall is published, and a task
     * queued onto the server task queue is only polled between that loop's chunk-queue drains - a
     * measured session paid 17.1 s there. This predicate lets the parked loop notice the settled sample
     * by itself and run the compile on its next round instead of waiting for the queue hop.
     *
     * <p>False as soon as the compile starts: {@link #activate} sets {@code compile-started} atomically
     * and {@code compilePlanningPlans0} clears the deferral once it is past the wait, so the two callers
     * cannot compile twice.
     */
    public boolean compileRetryPending() {
        return compileDeferred.get() && startupSamples.settled()
                && !state.containsKey("compile-started") && !state.containsKey("closed");
    }

    /**
     * Starts the bulk prewarm without letting a throw strand the startup stage.
     *
     * <p>{@code afterRoadBulk} checks {@code closed()} and then submits to the pump; the two steps are not
     * atomic, so a close in between raises {@link java.util.concurrent.RejectedExecutionException}. Inside
     * a discarded {@code whenComplete} that exception left no trace and the prewarm simply never began.
     */
    private void startBulkPrewarm(PlannerScheduler planner, String reason) {
        if (!structureGate.ready() || state.containsKey("closed") || state.containsKey("failed")) return;
        try {
            planner.afterRoadBulk();
        } catch (Throwable failure) {
            GreatWallMod.LOGGER.error("[GreatWall:S1C] PREWARM_START_FAILED reason={} cause={}; "
                    + "the startup stage continues without waiting for it", reason, failure.toString(), failure);
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
        }
    }

    /**
     * Bumped whenever the scan's own algorithm, band or worker count changes.
     *
     * <p>Part of the cache FILE NAME rather than the recipe fingerprint on purpose: the fingerprint feeds
     * every world's recipe hash, so putting this there would invalidate existing worlds' walls and demand a
     * new world for a pure cache concern. A mismatch here costs one re-scan instead.
     */
    private static String cacheKey(String recipeHash) {
        // 文本和二进制都是同一算法的派生数据，只要求重扫，不改变世界配方。
        return recipeHash + '-' + local.plaincraft.greatwall.generation.CorridorStructureScan.CACHE_REVISION;
    }

    private static List<Bounds> loadStructureScanCache(Path root, String recipeHash) {
        if (recipeHash == null) return null;
        Path file = root.resolve("cache").resolve("structure-scan-" + cacheKey(recipeHash) + ".txt");
        if (!Files.isRegularFile(file)) return null;
        try {
            var out = new ArrayList<Bounds>();
            for (String line : Files.readAllLines(file)) {
                String[] p = line.split(",");
                if (p.length != 6) return null;
                out.add(new Bounds(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                        Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5])));
            }
            return out;
        } catch (IOException | NumberFormatException e) {
            GreatWallMod.LOGGER.warn("[GreatWall:corridor] structure scan cache unreadable; rescanning", e);
            return null;
        }
    }

    private static void saveStructureScanCache(Path root, String recipeHash, List<Bounds> structures) {
        if (recipeHash == null) return;
        try {
            Path dir = root.resolve("cache");
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder(structures.size() * 24);
            for (Bounds b : structures) {
                sb.append(b.minX()).append(',').append(b.minY()).append(',').append(b.minZ()).append(',')
                  .append(b.maxXExclusive()).append(',').append(b.maxYExclusive()).append(',').append(b.maxZExclusive()).append('\n');
            }
            Files.writeString(dir.resolve("structure-scan-" + cacheKey(recipeHash) + ".txt"), sb.toString());
        } catch (IOException e) {
            GreatWallMod.LOGGER.warn("[GreatWall:corridor] failed to save structure scan cache", e);
        }
    }
    void openDimension(ServerLevel level) { levels.put(level.dimension().location().toString(), level); }
    void warming() {
        if (!server.isSameThread()) throw new IllegalStateException("prepareLevels must run on server owner");
        active();
        var level = levels.get("minecraft:overworld");
        if (level == null) throw new IllegalStateException("overworld LOAD has not been recorded");
        try {
            var frozen = ProductionInputs.freeze(level, bootstrap.configuration().join().templateVersion());
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

    /**
     * Records that {@code MinecraftServer.prepareLevels} has returned, therefore that any blocking
     * startup peer which runs at that method's HEAD (RoadWeaver's initial road generation) has
     * finished. Releases the wall's bulk prewarm if the recipe was already installed, and otherwise
     * lets the install path release it as soon as it runs.
     *
     * <p>Order independence matters: the road mixin and the wall mixin both target
     * {@code prepareLevels}, and correctness must not depend on which one wins. Whichever of the two
     * events happens second performs the release.
     */
    void afterRoadReached() {
        if (state.putIfAbsent("after-road-reached", true) != null) return;
        trace.record("PRODUCTION_AFTER_ROAD_REACHED", "minecraft:overworld", 0, 0, "prepareLevels-returned", "none");
        var snapshot = snapshots.get("installed");
        if (snapshot == null) return;
        startBulkPrewarm(snapshot.planner(), "startup-peer-notification");
        trace.record("PRODUCTION_AFTER_ROAD_BULK", snapshot.recipe().dimension(), 0, 0, "post-road-startup", "none");
    }

    /**
     * Releases the bulk prewarm when RoadWeaver reports the rivers are ready.
     *
     * <h2>Target order</h2>
     * <pre>
     *   rivers -> wall prewarm -> roads
     * </pre>
     *
     * <p>Separate from {@link #afterRoadReached()} because the two fire at
     * different moments and mean different things: this one says "the rivers are
     * done", the other says "RoadWeaver's blocking startup work has returned".
     * This one is the earlier of the two and is where the wall belongs.
     *
     * <p>Two guards matter here. The prewarm can only start once a recipe is
     * installed, and the two release paths must not both start it; the planner's
     * {@code afterRoadBulk()} is idempotent, but recording which path won keeps
     * the diagnostics honest about the startup order that actually happened.
     */
    void riversPrewarmed() {
        if (state.putIfAbsent("rivers-prewarmed", true) != null) return;
        trace.record("PRODUCTION_RIVERS_PREWARMED", "minecraft:overworld", 0, 0, "roadweaver-river-prewarm-returned", "none");
        var snapshot = snapshots.get("installed");
        if (snapshot == null) {
            // Recipe not installed yet. The install path checks this flag and
            // releases the prewarm as soon as it runs, so the ordering is kept
            // without waiting for the road stage.
            trace.record("PRODUCTION_RIVERS_PREWARM_DEFERRED", "minecraft:overworld", 0, 0, "recipe-not-installed", "none");
            return;
        }
        startBulkPrewarm(snapshot.planner(), "startup-peer-notification");
        trace.record("PRODUCTION_RIVERS_BULK", snapshot.recipe().dimension(), 0, 0, "post-river-startup", "none");
    }
    void tick() {
        if (state.containsKey("closed") || state.containsKey("failed")) return;
        var snapshot = snapshots.get("installed");
        if (snapshot == null) return;
        var diagnostics = snapshot.planner().diagnostics();
        if (diagnostics.containsKey("startup-failed") || diagnostics.containsKey("bulk-failed")) {
            fail(new IllegalStateException("production prewarm failed")); return;
        }
        if (state.containsKey("server-started") && diagnostics.containsKey("bulk-ready")) {
            activate(snapshot);
        }
        snapshot.planner().tick(new SliceBudget(4096, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25)));
        var garrison = gameplay.get("garrison");
        if (garrison instanceof GarrisonController controller) controller.tick();
    }

    /**
     * Brings the production stage up: one summary log, one planning compile.
     *
     * <p>Idempotent, because it has two callers by design. {@link #compileAtStartup()} runs it from
     * the thread that owns the wall's startup stage - before the server starts ticking - which is
     * what makes the wall publish before RoadWeaver's road generation begins; this tick path stays
     * for a session whose prewarm settles once the server is already running.
     */
    private void activate(Installed snapshot) {
        if (!structureGate.ready()) return;
        if (state.putIfAbsent("active", true) == null) {
            trace.record("PRODUCTION_ACTIVE", snapshot.recipe().dimension(), 0, 0, "server-started-and-prewarm-ready", "none");
            GreatWallMod.LOGGER.info("[GreatWall:S1C] PRODUCTION_SUMMARY wall={} axis={} rootDegraded={} "
                            + "wallLengthBlocks={} prewarmSegments={} counts={}",
                    snapshot.recipe().wallId(), snapshot.recipe().axis(),
                    local.plaincraft.greatwall.terrain.RealizationPlanner.rootDegraded(),
                    local.plaincraft.greatwall.config.GreatWallConfig.parameters().wallLengthBlocks(),
                    snapshot.planner().startupRange().segments(),
                    summarizeForLog(snapshot.planner().diagnostics()));
        }
        if (state.putIfAbsent("compile-started", true) != null) return;
        compilePlanningPlans(snapshot);
        if (compileDeferred.get()) {
            // The prewarm now starts before the fine sample, so the first compile attempt can arrive with
            // the column index still in flight. That is a wait, not a start.
            //
            // The flag must stay SET here. It used to be consumed by a getAndSet(false) at this exact
            // point, and its only other reader is triggerDeferredCompile - so deferring cleared the very
            // flag the retrigger waits on, and the compile never ran. A measured session logged
            // PLANNING_COMPILE_WAIT_FOR_SAMPLE at 13:05:29, published the sample at 13:06:05, and then
            // printed no compile line at all while the roads started. Only the guard that lets another
            // attempt through is cleared here; the deferral is consumed where the compile is queued.
            state.remove("compile-started");
        }
    }

    /**
     * Compiles and publishes the planning window from the startup stage owner, before the server
     * ticks, so the wall is in place before any road work starts.
     *
     * <p>Called by {@code WorldServices.compileWhileBlocked} from inside the wall's own
     * managedBlock, i.e. on the thread that holds the startup stage and before RoadWeaver's gate.
     * Returns false when there is nothing to compile yet: no session, no installed snapshot, or a
     * prewarm that has not settled - in which case the tick path picks the compile up later.
     *
     * @return true when the compile ran, or had already run
     */
    public boolean compileAtStartup() {
        if (!structureGate.ready()) return false;
        if (state.containsKey("closed") || state.containsKey("failed")) return false;
        var snapshot = snapshots.get("installed");
        if (snapshot == null) return false;
        if (!snapshot.planner().diagnostics().containsKey("bulk-ready")) return false;
        activate(snapshot);
        return true;
    }

    /** Compiles every prewarmed segment's ChunkWritePlan into the landing cache, once the prewarm
     *  has settled and the fine samples are published. This is the planning-phase compilation step
     *  that lets the WallFeature paint without any chunk read. It also publishes the planning
     *  vegetation-corridor and biome masks per chunk, replacing the deleted FeatureSlot. */
    private void compilePlanningPlans(Installed snapshot) {
        try {
            compilePlanningPlans0(snapshot);
        } catch (Throwable error) {
            failCompile(snapshot, "prepare", error);
        }
    }

    /**
     * Reports a compile failure and releases everything that waits on it.
     *
     * <h2>Why this exists</h2>
     * The compile used to run inside a {@code whenComplete} callback whose returned future was
     * discarded. {@code CompletableFuture} captures a callback's exception into that returned
     * future, so a failure left no trace anywhere: a measured session logged
     * {@code PLANNING_COMPILE_BEGIN pinnedPlans=4 target=4} and then nothing - no wall, no error,
     * no card change - while the server kept running normally. Every exit from the compile now ends
     * in either a publication or this method, and the peer gate is opened either way so a broken
     * wall cannot park the roads behind it.
     */
    private void failCompile(Installed snapshot, String phase, Throwable error) {
        GreatWallMod.LOGGER.error("[GreatWall:S1C] PLANNING_COMPILE_FAILED phase={} reason={}; "
                + "the wall will not paint in this session", phase, error.toString(), error);
        try {
            snapshot.planner().releasePrewarmLeases();
        } catch (Throwable releaseFailure) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PLANNING_COMPILE_RELEASE_FAILED reason={}",
                    releaseFailure.toString());
        }
        PrewarmProgress.finish(false);
    }

    private void compilePlanningPlans0(Installed snapshot) {
        // 先登记等待再读取交接状态，避免采样结案恰好落在两步之间、看不到重试请求。
        compileDeferred.set(true);
        var sample = startupSamples.claim();
        if (sample.waiting()) {
            // The bulk prewarm starts as soon as the obstacle index exists (it is what the road gate and
            // the world's own startup stage wait for, and it needs no sampled column), so the compile can
            // now be triggered while the fine sample is still running. It needs both inputs, so it waits -
            // the callback that publishes the index re-triggers it - instead of failing the way a null
            // index used to be reported, which would have turned the reordering into "no wall at all".
            GreatWallMod.LOGGER.info("[GreatWall:S1C] PLANNING_COMPILE_WAIT_FOR_SAMPLE the prewarm settled "
                    + "before the fine cross-section sample; the compile runs once the sample publishes");
            return;
        }
        // Past the wait above means this attempt is going to compile, not defer: release the retry
        // predicate so the parked startup loop stops looking for a deferred compile.
        compileDeferred.set(false);
        if (sample.state() == StartupSampleHandoff.State.CLOSED || state.containsKey("closed")) return;
        var index = sample.index();
        if (index == null || realization == null || chunkCompiler == null) {
            // A skipped compile used to return with nothing but a WARN. Nothing then released the
            // pinned prewarm leases or opened the peer gate, so the roads waited out the wall's own
            // thirty-minute give-up while the world sat on the loading card with one WARN to explain
            // it. failCompile reports it, releases the leases and opens the gate, so the run ends
            // with a world that loads and no wall instead of a silent stall.
            failCompile(snapshot, "missing-inputs",
                    new IllegalStateException("fineIndex=" + (index != null)
                            + " realization=" + (realization != null)
                            + " chunkCompiler=" + (chunkCompiler != null)));
            return;
        }
        var level = levels.get("minecraft:overworld");
        if (level == null) {
            failCompile(snapshot, "no-overworld",
                    new IllegalStateException("minecraft:overworld is not loaded"));
            return;
        }
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setStage(
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.COMPILE);
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(0, 0);
        var compiler = new local.plaincraft.greatwall.planning.PlanningChunkCompiler(
                realization, chunkCompiler, index, planningStructures, compileMemory);
        var planner = snapshot.planner();
        // 重试也使用已冻结的窗口，避免重新读取配置后请求边界外计划。
        long target = planner.startupRange().segments();
        if (target == 0) {
            failCompile(snapshot, "empty-startup-range", new IllegalStateException("无安全预热段"));
            return;
        }
        var collected = new java.util.ArrayList<SegmentPlan>();
        // Reuse the prewarm's own leases: they stay pinned until this compile publishes, so the
        // plans are the exact objects the prewarm produced. Re-requesting them (the previous shape)
        // is what let a measured session spend 22 minutes silently re-planning the opening window
        // and publish nothing: the prewarm closed its leases at completion, the memory budget
        // dropped the plans, and the compile re-derived them from scratch after the server started.
        var pinned = planner.prewarmLeases();
        var remaining = new java.util.concurrent.atomic.AtomicInteger(
                Math.max(1, pinned.isEmpty() ? (int) target : pinned.size()));
        GreatWallMod.LOGGER.info("[GreatWall:S1C] PLANNING_COMPILE_BEGIN pinnedPlans={} target={}",
                pinned.size(), target);
        if (!pinned.isEmpty()) {
            for (var lease : pinned) {
                lease.future().whenComplete((plan, error) -> {
                    // The whole body is guarded, and the publication is guarded on its own: an
                    // exception thrown here would otherwise land in the future whenComplete returns
                    // - which nobody holds - and vanish without a log line.
                    try {
                        if (error == null && plan != null) {
                            synchronized (collected) { collected.add(plan); }
                        } else {
                            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PLANNING_COMPILE_PLAN_FAILED reason={}", error);
                        }
                    } catch (Throwable callbackFailure) {
                        failCompile(snapshot, "callback", callbackFailure);
                        return;
                    }
                    if (remaining.decrementAndGet() == 0) {
                        publishOrFail(snapshot, level, compiler, collected, index);
                    }
                });
            }
            return;
        }
        // No pinned prewarm (a session whose prewarm failed or never ran): fall back to requesting
        // the plans, and make every failure visible - the silent catch here is why an unpublished
        // compile left no trace in the log at all.
        var leases = new java.util.ArrayList<PlanLease<SegmentPlan>>();
        for (long i = 0; i < target; i++) {
            long segment = i;
            try {
                var lease = planner.requirePlan(segment, Priority.PLAYER_PREFETCH);
                leases.add(lease);
                lease.future().whenComplete((plan, error) -> {
                    try {
                        if (error == null && plan != null) {
                            synchronized (collected) { collected.add(plan); }
                        } else {
                            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PLANNING_COMPILE_PLAN_FAILED segment={} reason={}",
                                    segment, error);
                        }
                    } finally {
                        lease.close();
                        if (remaining.decrementAndGet() == 0) publishOrFail(snapshot, level, compiler, collected, index);
                    }
                });
            } catch (RuntimeException error) {
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] PLANNING_COMPILE_PLAN_REQUEST_FAILED segment={} reason={}",
                        segment, error.toString());
                if (remaining.decrementAndGet() == 0) publishOrFail(snapshot, level, compiler, collected, index);
            }
        }
    }

    /** Publishes the window, turning any failure into a reported, released outcome. */
    private void publishOrFail(Installed snapshot, ServerLevel level,
            local.plaincraft.greatwall.planning.PlanningChunkCompiler compiler,
            java.util.List<SegmentPlan> plans, FineHeightIndex terrain) {
        try {
            // 全窗精采已齐才解最终缓坡，跨段高点不能再被局部硬边界压回山体。
            var raised = local.plaincraft.greatwall.planning.WindowHeightEnvelope.solve(snapshot.recipe(), plans, terrain);
            snapshot.planner().freezeStartupPlans(raised).whenComplete((ignored, error) -> {
                // 不阻塞推进线程等磁盘；持久化失败不能继续宣告最终高度已经冻结。
                if (error != null) { failCompile(snapshot, "final-plan-write", error); return; }
                try { publishPlanningWindow(snapshot, level, compiler, raised); }
                catch (Throwable failure) { failCompile(snapshot, "publish", failure); }
            });
        } catch (Throwable failure) {
            failCompile(snapshot, "publish", failure);
        }
    }

    private void publishPlanningWindow(Installed snapshot, ServerLevel level,
            local.plaincraft.greatwall.planning.PlanningChunkCompiler compiler,
            java.util.List<SegmentPlan> plans) {
        // 会话只提供真实世界边界；发布结案由独立协作类统一拥有。
        new StartupWindowPublisher(snapshot.recipe(), snapshot.planner()::releasePrewarmLeases,
                (window, progress, sink) -> compiler.compileIntoReport(window, snapshot.recipe().recipeHash(),
                        snapshot.recipe().dimension(), progress, sink, ProductionSession.this::active), new StartupWindowPublisher.Access() {
            public void active() { ProductionSession.this.active(); }
            public void install(ChunkWritePlan plan) { DurablePlanInstall.accept(plan, p -> WallWorldgenPlanCache.put(level,p)); }
            public void footprint(ChunkWritePlan plan,CorridorIndex corridor) {
                WallPlanSupport.publishPlannedColumns(plan, structures);
                vegetation.publishPrepared(plan.owner(),corridor);
                var biomes=biomeRuntime;
                if(biomes!=null&&!biomes.closed())biomes.publishPrepared(plan.owner(),corridor,1);
            }
            public void enqueue(java.util.Map<ChunkKey,String> hashes) { WallBodyBackfill.enqueueCompiled(level,hashes); }
            public long leases() { return vegetation.leases(); }
            public long misses() { return vegetation.misses(); }
            public long suppressed() { return vegetation.suppressed(); }
        }).publish(plans);
    }

    void validateReload() {
        var snapshot = snapshots.get("installed");
        if (snapshot == null) { fail(new IllegalStateException("reload during incomplete production bootstrap")); return; }
        try {
            var next = ProductionInputs.freeze(levels.get("minecraft:overworld"),snapshot.recipe().config().templateVersion()).bootstrap();
            if (!snapshot.recipe().terrainHash().equals(next.terrainHash())
                    || !snapshot.recipe().paletteHash().equals(next.paletteHash())
                    || !snapshot.recipe().templatesHash().equals(next.templatesHash())) {
                throw new WallFailure(FailureCode.RECIPE_MISMATCH, "generation semantics changed; create a new world");
            }
            // Tuned parameters are semantics too: a reload after the screen edited them would otherwise
            // let the second half of the wall be planned from a different ridge choice than the first,
            // which is the exact seam the frozen parameter snapshot exists to prevent. Compared against
            // the live digest on purpose - this is the drift detector, not a generation input.
            if (!snapshot.recipe().config().parametersHash()
                    .equals(local.plaincraft.greatwall.config.GreatWallConfig.get().semantic().landFirstDigest())) {
                throw new WallFailure(FailureCode.RECIPE_MISMATCH, "generation parameters changed; create a new world");
            }
            trace.record("PRODUCTION_RECIPE_ACCEPTED", "*", 0, 0, "reload", "none");
        } catch (WallFailure rejected) {
            if (rejected.code() != FailureCode.RECIPE_MISMATCH) { fail(rejected); return; }
            // New generation semantics on an existing world means "make a new world", not "this
            // installation is broken". The same datapack reload without a live session already takes
            // exactly this graceful path (WorldServices' RECIPE_REJECTED branch); routing it through
            // fail() instead called MinecraftServer.setFatalException, so editing a datapack crashed
            // the running server. Retiring the session stops new planning and publication, keeps the
            // wall already in the landing cache, releases the game's waiters - close() opens the peer
            // gate too - and says in the log which identity no longer matches.
            GreatWallMod.LOGGER.error("[GreatWall:S1C] RECIPE_REJECTED: new generation is disabled; "
                    + "use a new world", rejected);
            trace.record("PRODUCTION_RECIPE_REJECTED", "*", 0, 0, "reload", "none");
            close();
        } catch (Throwable error) { fail(error); }
    }
    void active() {
        if (state.containsKey("closed") || state.containsKey("failed")) throw new WallFailure(FailureCode.CLOSED, "production session is closed/failed");
    }
    void fail(Throwable error) {
        if (state.containsKey("closed") || failures.putIfAbsent("required", error.toString()) != null) {
            GreatWallMod.LOGGER.warn("[GreatWall:DIAG] ProductionSession.fail ALREADY_FAILED thread={} newError={}",
                Thread.currentThread().getName(), error.toString());
            return;
        }
        GreatWallMod.LOGGER.error("[GreatWall:DIAG] ProductionSession.fail FIRST_FAILURE thread={} state={}",
            Thread.currentThread().getName(), Map.copyOf(state), error);
        state.put("failed", true);
        // A failed assembly must release waiters too, not leave them pending on a dead session.
        failGameplayWaiters(error);
        // And any startup peer waiting for this prewarm. Without this the road mod would sit
        // on its gate until its own timeout, thirty minutes later, on a run that already
        // failed - turning a fast failure into a hang at the loading screen.
        PrewarmProgress.finish(false);
        trace.record("PRODUCTION_FAILED", "*", 0, 0, "required-plan", "none");
        GreatWallMod.LOGGER.error("[GreatWall:S1C] FIXTURE_FAILED production remains OFF", error);
        installed.completeExceptionally(error);
        // Some failures mean "this world cannot have this wall", not "this installation is broken".
        // Killing the server over one of those makes the world unplayable and buries the reason in a
        // crash report, so the wall is skipped for the session instead, with the reason logged, and
        // whatever the world already holds stays where it is. Every other failure stays fatal: those
        // mean the artifact is unusable, and a half-built wall left in the world would be worse than a
        // clean stop.
        var survivable = survivableReason(error);
        if (survivable != null) {
            GreatWallMod.LOGGER.error("[GreatWall:S1C] {} the wall is skipped for this session and the "
                    + "world loads without it", survivable);
        } else {
            MinecraftServer.setFatalException(new IllegalStateException("GreatWall production fixture required dependency failed", error));
        }
        close();
    }

    /**
     * {@return the log line explaining why this failure is survivable, or {@code null} when it is not}
     *
     * <p>Two kinds of failure mean "this world cannot have this wall" rather than "this installation is
     * broken":
     * <ul>
     *   <li>{@code ROOT_NOT_FOUND} - no ring candidate the siting contract accepts;</li>
     *   <li>{@code RECIPE_MISMATCH} - the world was generated with different semantics: a semantic knob
     *       was edited, the digest schema was bumped (a new knob whose default changes nothing still
     *       changes the digest), or a datapack edit moved the terrain. A player causes this by playing
     *       or by editing, and it is exactly what {@code validateReload} already handles gracefully at
     *       runtime.</li>
     * </ul>
     * Both leave the recipe, the caches and the session intact; only this world's wall is refused. The
     * same {@code RECIPE_MISMATCH} used to reach {@code MinecraftServer.setFatalException} on the
     * startup path, so an existing world plus a changed knob crashed the game instead of loading.
     *
     * <p>Package-private so {@code WorldServices.failRequiredPlan} - the same decision on the path
     * where no production session exists yet - cannot drift away from it.
     */
    static String survivableReason(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof WallFailure failure) {
                if (failure.code() == FailureCode.ROOT_NOT_FOUND) {
                    return "ROOT_UNAVAILABLE no ring candidate satisfies the siting contract;";
                }
                if (failure.code() == FailureCode.RECIPE_MISMATCH) {
                    return "RECIPE_REJECTED this world was generated with different semantics ("
                            + failure.getMessage() + ");";
                }
            }
        }
        return null;
    }
    void trace(String event, String dimension, int x, int z, String stage, String token) { trace.record(event, dimension, x, z, stage, token); }
    Map<String, Long> counts() { return trace.counts(); }
    boolean completedStage(String dimension, int x, int z) {
        return trace.snapshot().stream().anyMatch(event -> event.event().equals("STAGE_EXIT")
                && event.dimension().equals(dimension) && event.chunkX() == x && event.chunkZ() == z);
    }
    boolean rejected() { return state.containsKey("failed"); }
    CompletableFuture<Void> drain() { return drained.copy(); }

    /**
     * The handful of planner counters that answer "did the wall get built".
     *
     * <p>Filtered rather than dumped. The full diagnostic map holds tens of per-lane and
     * per-phase counters, which is right for the export and unreadable in a log line. These
     * are the ones whose values change when something is actually wrong: how many stages ran,
     * how many wrote, and whether the run failed.
     */
    private static String summarizeForLog(Map<String, Long> counts) {
        var wanted = java.util.List.of("STAGE_ENTER", "STAGE_EXIT", "WRITE_LOCK_ACQUIRED",
                "VEGETATION_SUPPRESSED", "TRACE_TRUNCATED", "PRODUCTION_FAILED",
                "PRODUCTION_RIVERS_PREWARMED", "PRODUCTION_RIVERS_BULK");
        var parts = new java.util.ArrayList<String>(wanted.size());
        for (String name : wanted) {
            Long value = counts.get(name);
            if (value != null) parts.add(name + "=" + value);
        }
        return parts.isEmpty() ? "(none)" : String.join(" ", parts);
    }

    Map<String, Object> diagnostics() {
        var result = new TreeMap<String, Object>();
        result.put("runtime", runtime); result.put("state", Map.copyOf(state)); result.put("failures", Map.copyOf(failures));
        // 正式会话不是serverSmoke夹具；保留旧标签会让真实预热/内存采集被误判为无落块探针。
        result.put("productionWiring", "ON"); result.put("isolatedFixture", false);
        result.put("counts", counts()); result.put("root", bootstrap.progress()); result.put("bootstrap", bootstrap.diagnostics());
        result.put("rootAudit", bootstrap.rootAudit());
        result.put("vegetationLeases", vegetation.leases());
        result.put("compileWindowMemory", compileMemory.snapshot());
        var snapshot = snapshots.get("installed");
        if (snapshot != null) {
            result.put("recipe", snapshot.recipe()); result.put("planner", snapshot.planner().diagnostics());
            result.put("plannerFailures", snapshot.planner().failures());
            result.put("plannerProgress", snapshot.planner().progress());
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
        // 先释放未领走的样本，随后调度器关闭触发的回调就不能再交付结果。
        startupSamples.close();
        installed.completeExceptionally(new WallFailure(FailureCode.CLOSED, "production session closed"));
        // Release anyone waiting on gameplay assembly rather than letting them block forever on a
        // session that will never finish assembling.
        failGameplayWaiters(new WallFailure(FailureCode.CLOSED, "production session closed before gameplay assembly"));
        // A closed session will never finish its prewarm either, so a startup peer waiting on
        // it must be released now rather than at its own timeout.
        PrewarmProgress.finish(false);
        var scanner = structureScanner;
        if (scanner != null) scanner.close();
        // Stop new gameplay mutations before flushing permanent history: garrison birth and
        // beacon credits must cease, then the journal flushes and shuts down its own executor.
        for (String key : List.of("garrison", "beacons", "sites", "negotiated", "debug")) {
            var service = gameplay.get(key);
            if (service instanceof AutoCloseable closable) {
                try { closable.close(); } catch (Exception ignored) {}
            }
        }
        var journal = (HistoryJournal) gameplay.get("history");
        bootstrap.close();
        var frozenInputs=inputs.get("frozen");
        if(frozenInputs!=null)frozenInputs.close();
        bootstrap.drain().whenComplete((value, error) -> {
            // journal 的 WAL force / channel close 必须真正完成后才能宣布 drained，
            // 否则尾写可能丢失或与下一 session 争用文件锁。
            CompletionStage<Void> journalClosed = journal == null
                    ? CompletableFuture.completedFuture(null) : journal.closeAsync();
            journalClosed.whenComplete((ignored, journalError) -> {
                io.shutdown();
                if (error != null) drained.completeExceptionally(error);
                else if (journalError != null) drained.completeExceptionally(journalError);
                else drained.complete(null);
            });
        });
    }
    private Installed requireInstalled() {
        active(); var snapshot = snapshots.get("installed");
        if (snapshot == null) throw new IllegalStateException("production recipe is not installed");
        return snapshot;
    }
    private static void rejectUncertifiedProfiles() {
        // ═══════════════════════════════════════════════════════════════════════════
        // 【用户明确授权的保留项 — 2026-10-05 拍板，禁止在未重新授权前回退】
        // 授权内容：不再对以下四个 mod 做 BLOCKED 拒绝。
        //   tanshugetrees                = 自家移植，RoadWeaver 已有精确占地绕行
        //   plaincraft_erdtree_sanctuary = 独立大群系，不抢长城地块
        //   plaincraft_erdtree_sapling   = 仅玩家手动种植，非世界生成阶段
        //   ohthetreesyoullgrow          = 客户端未安装
        // 授权理由：按 fail-closed 会让长城生产/S1C 在这四个 mod 在场时直接拒绝启动，
        //   而它们对长城的实际影响已被用户逐项确认（详见上表）。
        // 已知代价（用户知情）：未认证植被的树干/冠幅/后置 writer 未做完整认证。
        //   这是用户主动接受的取舍，不是遗漏。
        // 红队报告 cf218db 建议恢复 BLOCKED 并指出违反 S1-城墙本体.md:1484 —— 已被否决。
        // 恢复条件：仅在用户重新授权后，且需先给出这四个 mod 的版本/SHA、完整冠幅与
        //   post-generation writer 证据，并单独跑整包矩阵。
        // ═══════════════════════════════════════════════════════════════════════════
    }
}
