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
    /** Set while the fine sample is still in flight; the compile waits on it instead of failing. */
    private volatile boolean fineSampleSettled;
    /**
     * Whether the fine sample has been requested for this session. It is started before the corridor
     * structure scan, not after it: sampling walks terrain columns along a route the recipe already
     * fixed, so it needs no obstacle index, and the 130 s scan would otherwise sit in front of the
     * longest stage of the startup instead of running beside it.
     */
    private volatile boolean fineSampleStarted;
    /**
     * Whether startup stages run one at a time.
     *
     * <p>True by default: the wall's stages are individually parallel and share one CPU, so running
     * two of them at once means each waits for the other's threads.
     */
    private static final boolean SERIAL_STAGES =
            !"false".equalsIgnoreCase(System.getProperty("greatwall.startup.serialStages", "true"));
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
    /** Planning-resolved fine cross-section samples + structure boxes, published once the prewarm
     *  settles and consumed by the planning-phase chunk compilation. */
    private volatile local.plaincraft.greatwall.planning.FineHeightIndex fineHeightIndex;
    private volatile java.util.List<local.plaincraft.greatwall.model.WallTypes.Bounds> planningStructures = java.util.List.of();
    /** Realization + chunk-plan compiler, held for the planning-phase compilation. */
    private volatile local.plaincraft.greatwall.terrain.RealizationPlanner realization;
    private volatile local.plaincraft.greatwall.generation.ChunkPlanCompiler chunkCompiler;

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
                // Phase split: the pump starts demand-only so a blocking startup peer (RoadWeaver
                // blocks inside prepareLevels and asks the wall for recipe and plans) is never
                // competing with the wall's own bulk prewarm.
                //
                // Target order is rivers -> wall prewarm -> roads. The prewarm is released by
                // whichever of the two startup events arrives once the recipe exists:
                //   * riversPrewarmed()  - RoadWeaver reports the river plates are built; this is
                //                          the intended release point and fires before any road.
                //   * afterRoadReached() - prepareLevels TAIL; the fallback for a session without
                //                          RoadWeaver, and the late release when the recipe only
                //                          became durable after the rivers were already done.
                // Both are idempotent, so checking both here cannot double-start the prewarm.
                planner.startupPump();
                // 「到谁谁占用主线程」：长城拥有 prepareLevels HEAD(950) 的独占窗口，此刻河流
                // 已在 createLevels(900) 彻底完成，且主线程正停在 blockAndPumpUntilPrewarmSettled
                // 里死等预热。recipe 一旦持久化就绪，必须无条件开始批量预热，绝不可依赖
                // after-road-reached（TAIL 路径，主线程被 HEAD 阻塞永远到不了）或 rivers-prewarmed
                // （跨阶段易失标志，道路回退 0.8.402 后传递链断裂）。afterRoadBulk 自带幂等保护。
                //
                // The corridor structure scan must settle BEFORE the bulk prewarm starts: the ridge
                // detour stage reads the obstacle index while planning each segment, so a scan that
                // runs after prewarm would leave the wall cutting through structures. The scan is
                // fire-and-forget with a degraded fallback (empty index = no structure avoidance =
                // the pre-refactor behaviour); it must never block or fail the world.
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
        return new RealizationPlanner(recipe, new SurfaceProbe(), biome, S1TemplateSet.create(recipe.seed(), palette));
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
        long lengthBlocks = local.plaincraft.greatwall.config.GreatWallConfig.parameters().wallLengthBlocks();
        if (lengthBlocks <= 0) lengthBlocks = 8190L;
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
        try {
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
        final List<Bounds> cached = loadStructureScanCache(root, recipe.recipeHash());
        if (cached != null) {
            GreatWallMod.LOGGER.info("[GreatWall:corridor] structure scan cache hit ({} boxes); skipping rescan", cached.size());
            installStructuresAndSample(planner, cached);
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
        // Serial by default: both stages are CPU-bound and each already saturates the machine (twelve
        // sample lanes plus up to twelve scan workers on sixteen threads), so overlapping them does not
        // fill idle cores - it oversubscribes the CPU, and the stage that is nearly finished crawls
        // while the next one takes its threads. The sample starts from installStructuresAndSample
        // instead. -Dgreatwall.startup.serialStages=false restores the overlap for an A/B run.
        if (!SERIAL_STAGES) startFineSample(planner);
        // The wall's true corridor band (segment endpoints + half-width): candidates outside it are
        // dropped up front, so an angled wall no longer scans the AABB's 3.5x overscan.
        var corridor = new local.plaincraft.greatwall.generation.CorridorStructureScan.Corridor(
                originX, originZ, endX, endZ, corridorHalfWidth);
        scanner.scan(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ, corridor)
                .whenComplete((boxes, error) -> {
                    // This callback's returned future is discarded, so a throw here would vanish and take
                    // the whole startup stage with it (no prewarm, no compile, the world parked until the
                    // thirty-minute give-up). Everything below is inside the guard for that reason.
                    try {
                        final List<Bounds> structures;
                        if (error != null) {
                            GreatWallMod.LOGGER.warn("[GreatWall:corridor] structure scan failed; "
                                    + "falling back to no structure avoidance", error);
                            structures = List.of();
                        } else {
                            var bounds = new java.util.ArrayList<Bounds>(boxes.size());
                            for (var owned : boxes) {
                                var b = owned.box();
                                if (b == null) continue;
                                bounds.add(new Bounds(b.minX(), b.minY(), b.minZ(),
                                        b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1));
                            }
                            structures = List.copyOf(bounds);
                            saveStructureScanCache(root, recipe.recipeHash(), structures);
                        }
                        installStructuresAndSample(planner, structures);
                    } catch (Throwable failure) {
                        GreatWallMod.LOGGER.error("[GreatWall:corridor] installing the structure scan failed; "
                                + "the startup stage is released without a wall this session", failure);
                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
                    }
                });
    }

    /** Installs the resolved structure footprints as the planner's obstacle index, then chains the
     *  fine cross-section sample into the bulk prewarm. Reached both from a fresh scan and from a
     *  cache hit, so it is the single place that advances the prewarm past the structure stage. */
    private void installStructuresAndSample(PlannerScheduler planner, List<Bounds> structures) {
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
        // The bulk prewarm starts HERE, not from the fine-sample callback: it needs nothing but the
        // obstacle index installed above, and it is precisely what the road gate, the world's startup
        // stage and the compile's lease release all wait for. Ordering it behind the fine sample (as this
        // method used to) serialised a bounded stage behind an unbounded one: the measured session logged
        // zero PREWARM_BEGIN lines because the sample never finished, so the world waited out the wall's
        // own thirty-minute give-up instead of the two minutes this stage actually needs. The sample still
        // runs, concurrently, and the compile waits for both (see compilePlanningPlans0).
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setStage(
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.PREWARM);
        startBulkPrewarm(planner, "structures-installed");
        // Sampled terrain columns do not change between sessions, but the stage used to re-walk every
        // one of them on every world entry - the single most expensive thing this mod does. Hand it
        // the previous session's columns instead. The file is bound to the recipe and terrain
        // identity, so a world whose terrain configuration changed re-probes rather than trusting
        // columns sampled under different terrain.
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setStage(
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.FINE_SAMPLE);
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(0, 0);
        // Started here rather than when every segment exists: the sample consumes segment i the
        // moment that segment's route future completes, so it is pipelined with the prewarm by
        // design. Waiting for all four only serialises a pipeline that already works - and the
        // overlap is what keeps the early segments from idling the machine.
        startFineSample(planner);
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
        if (fineSampleStarted) return;
        fineSampleStarted = true;
        // Sampled terrain columns do not change between sessions, but the stage used to re-walk every
        // one of them on every world entry - the single most expensive thing this mod does. Hand it
        // the previous session's columns instead. The file is bound to the recipe and terrain
        // identity, so a world whose terrain configuration changed re-probes rather than trusting
        // columns sampled under different terrain.
        planner.setFineSampleCache(FineSampleCache.load(
                root.resolve("samples").resolve("fine-columns.bin"),
                recipe().recipeHash() + '|' + recipe().terrainHash() + '|' + recipe().dimension()));
        fineSampleSettled = false;
        try {
            planner.requireFineSamples(Priority.PLAYER_PREFETCH)
                    .whenComplete((fineIndex, sampleError) -> onFineSampleSettled(fineIndex, sampleError));
        } catch (Throwable couldNotStart) {
            // Losing the sample must not take the startup stage with it: without this, a throw here would
            // leave `fineSampleSettled` false forever and a deferred compile would never be re-triggered.
            fineSampleSettled = true;
            GreatWallMod.LOGGER.error("[GreatWall:corridor] fine sample could not be started; "
                    + "the wall will not compile", couldNotStart);
            triggerDeferredCompile();
        }
    }

    /**
     * Handles the fine sample's completion: publish the index, then run a compile that was waiting for it.
     *
     * <p>Runs inside a {@code whenComplete} whose returned future is discarded, so anything thrown here
     * would vanish - the failure mode that once parked the world for thirty minutes. Every path ends by
     * marking the sample settled and re-triggering a deferred compile.
     */
    private void onFineSampleSettled(local.plaincraft.greatwall.planning.FineHeightIndex fineIndex,
            Throwable sampleError) {
        try {
            if (sampleError != null) {
                GreatWallMod.LOGGER.warn("[GreatWall:corridor] fine sample failed; wall will not compile",
                        sampleError);
            } else if (fineIndex != null) {
                fineHeightIndex = fineIndex;
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                        fineIndex.size(), fineIndex.size());
            }
        } catch (Throwable publishFailure) {
            GreatWallMod.LOGGER.error("[GreatWall:corridor] publishing the fine sample failed; "
                    + "the wall will not compile", publishFailure);
        } finally {
            fineSampleSettled = true;
            triggerDeferredCompile();
        }
    }

    /** Runs the compile if an earlier attempt deferred for want of the fine sample. */
    private void triggerDeferredCompile() {
        // Consumed here, where the compile is actually queued - not at the point that set the deferral,
        // which left this check permanently false and the compile never started.
        if (!compileDeferred.getAndSet(false)) return;
        try {
            server.execute(this::compileAtStartup);
        } catch (Throwable cannotQueue) {
            GreatWallMod.LOGGER.error("[GreatWall:S1C] PLANNING_COMPILE_NOT_QUEUED after the fine sample; "
                    + "releasing the startup stage", cannotQueue);
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
        }
    }

    /**
     * Starts the bulk prewarm without letting a throw strand the startup stage.
     *
     * <p>{@code afterRoadBulk} checks {@code closed()} and then submits to the pump; the two steps are not
     * atomic, so a close in between raises {@link java.util.concurrent.RejectedExecutionException}. Inside
     * a discarded {@code whenComplete} that exception left no trace and the prewarm simply never began.
     */
    private void startBulkPrewarm(PlannerScheduler planner, String reason) {
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
    private static final String CORRIDOR_SCAN_CACHE_VERSION = "v1";

    private static String cacheKey(String recipeHash) {
        return recipeHash + '-' + CORRIDOR_SCAN_CACHE_VERSION;
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
        snapshot.planner().afterRoadBulk();
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
        snapshot.planner().afterRoadBulk();
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
        if (state.putIfAbsent("active", true) == null) {
            trace.record("PRODUCTION_ACTIVE", snapshot.recipe().dimension(), 0, 0, "server-started-and-prewarm-ready", "none");
            GreatWallMod.LOGGER.info("[GreatWall:S1C] PRODUCTION_SUMMARY wall={} axis={} rootDegraded={} "
                            + "wallLengthBlocks={} prewarmSegments={} counts={}",
                    snapshot.recipe().wallId(), snapshot.recipe().axis(),
                    local.plaincraft.greatwall.terrain.RealizationPlanner.rootDegraded(),
                    local.plaincraft.greatwall.config.GreatWallConfig.parameters().wallLengthBlocks(),
                    local.plaincraft.greatwall.config.GreatWallConfig.parameters().prewarmSegmentsTarget(),
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
        var index = fineHeightIndex;
        if (index == null && !fineSampleSettled) {
            // The bulk prewarm starts as soon as the obstacle index exists (it is what the road gate and
            // the world's own startup stage wait for, and it needs no sampled column), so the compile can
            // now be triggered while the fine sample is still running. It needs both inputs, so it waits -
            // the callback that publishes the index re-triggers it - instead of failing the way a null
            // index used to be reported, which would have turned the reordering into "no wall at all".
            compileDeferred.set(true);
            GreatWallMod.LOGGER.info("[GreatWall:S1C] PLANNING_COMPILE_WAIT_FOR_SAMPLE the prewarm settled "
                    + "before the fine cross-section sample; the compile runs once the sample publishes");
            return;
        }
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
                realization, chunkCompiler, index, planningStructures);
        var planner = snapshot.planner();
        long target = local.plaincraft.greatwall.config.GreatWallConfig.parameters().prewarmSegmentsTarget();
        if (target <= 0) target = 4;
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
                        publishOrFail(snapshot, level, compiler, collected);
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
                        if (remaining.decrementAndGet() == 0) publishOrFail(snapshot, level, compiler, collected);
                    }
                });
            } catch (RuntimeException error) {
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] PLANNING_COMPILE_PLAN_REQUEST_FAILED segment={} reason={}",
                        segment, error.toString());
                if (remaining.decrementAndGet() == 0) publishOrFail(snapshot, level, compiler, collected);
            }
        }
    }

    /** Publishes the window, turning any failure into a reported, released outcome. */
    private void publishOrFail(Installed snapshot, ServerLevel level,
            local.plaincraft.greatwall.planning.PlanningChunkCompiler compiler,
            java.util.List<SegmentPlan> plans) {
        try {
            publishPlanningWindow(snapshot, level, compiler, plans);
        } catch (Throwable failure) {
            failCompile(snapshot, "publish", failure);
        }
    }

    private void publishPlanningWindow(Installed snapshot, ServerLevel level,
            local.plaincraft.greatwall.planning.PlanningChunkCompiler compiler,
            java.util.List<SegmentPlan> plans) {
        if (plans.isEmpty()) {
            // No plan survived. The wall cannot paint, but the loading card and the startup peer
            // must still be released rather than waiting on a compile that produced nothing.
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PLANNING_COMPILE_EMPTY no segment plan survived; "
                    + "wall will not paint");
            snapshot.planner().releasePrewarmLeases();
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.signalReady();
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(false);
            return;
        }
        var window = new PlanWindow(List.copyOf(plans));
        // Compile progress: owners compiled out of the owners the window holds. The compiler
        // discovers the whole owner list before it starts compiling, so this is a real fraction
        // rather than the indeterminate 0 the single-pass shape had to publish - which the card
        // renders as a stage parked at 0 % for the compile's entire duration.
        long compileStartedNanos = System.nanoTime();
        PrewarmProgress.setSubProgress(0, 0);
        var compiled = compiler.compileWindow(window, snapshot.recipe().recipeHash(),
                snapshot.recipe().dimension(),
                (done, total) -> PrewarmProgress.setSubProgress(
                        PrewarmProgress.Stage.COMPILE, done, total));
        long compileMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - compileStartedNanos);
        // Publish per owner, isolating each owner's failure: one chunk that cannot take its mask
        // must not cost the wall every other chunk. Failures are counted and reported loudly,
        // because a partially published wall is a state the player has to be told about.
        int published = 0;
        int failed = 0;
        Throwable firstFailure = null;
        var publishedKeys = new java.util.ArrayList<local.plaincraft.greatwall.model.WallTypes.ChunkKey>();
        for (var entry : compiled.entrySet()) {
            try {
                local.plaincraft.greatwall.generation.WallWorldgenPlanCache.put(level, entry.getValue());
                // The wall body is written by a queue as well as by the worldgen feature: this
                // corridor was generated before the plan existed (the measured session published
                // 17.6 minutes after the prewarm finished), so nothing here would ever reach the
                // feature stage again. See WallBodyBackfill.
                publishedKeys.add(entry.getKey());
                // The road may be released as soon as this window publishes; mark the plan's
                // columns now, not one drain later.
                local.plaincraft.greatwall.generation.WallPlanSupport.markPlannedColumns(entry.getValue());
                local.plaincraft.greatwall.generation.WallBodyBackfill.persist(entry.getValue());
                // Publish the planning vegetation corridor + biome mask for this chunk, replacing the
                // deleted FeatureSlot's per-chunk publication.
                vegetation.publish(entry.getKey(), window);
                var biomes = biomeRuntime;
                if (biomes != null && !biomes.closed()) {
                    biomes.publish(entry.getKey(), window, 1);
                }
                published++;
            } catch (Throwable ownerFailure) {
                failed++;
                if (firstFailure == null) firstFailure = ownerFailure;
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] PLANNING_COMPILE_OWNER_FAILED owner={},{} reason={}",
                        entry.getKey().x(), entry.getKey().z(), ownerFailure.toString());
            }
        }
        GreatWallMod.LOGGER.info("[GreatWall:S1C] PLANNING_COMPILE_TRIGGERED segments={} chunks={} published={} "
                        + "failed={} compileMs={} vegetationLeases={} guardMisses={} suppressed={}",
                plans.size(), compiled.size(), published, failed, compileMillis,
                vegetation.leases(), vegetation.misses(), vegetation.suppressed());
        // Queue the published window for the loaded-chunk writer. Loaded chunks are written within
        // seconds; the rest wait for the player, which is the only way a chunk generated before the
        // plan can ever carry the wall.
                // A partially published window used to be reported as a failure and returned here - while a later
        // stage still opened the gate with success=true, so the two signals contradicted each other and
        // RoadWeaver could read either one. The wall has a better answer now: every published chunk is
        // written by the backfill, and an owner that failed is a hole, not a dead session. So a small
        // failure rate is reported and survived; only a window that mostly failed is called a failure.
        int tolerated = Math.max(8, compiled.size() / 20);
        if (published == 0) {
            // Nothing published is never a tolerable partial: the gate would otherwise open on a
            // wall that does not exist anywhere.
            GreatWallMod.LOGGER.error("[GreatWall:S1C] PLANNING_COMPILE_EMPTY owners={} failed={}",
                    compiled.size(), failed);
            snapshot.planner().releasePrewarmLeases();
            PrewarmProgress.finish(false);
            return;
        }
        local.plaincraft.greatwall.generation.WallBodyBackfill.enqueuePublished(level, publishedKeys);
        if (failed > tolerated) {
            GreatWallMod.LOGGER.error("[GreatWall:S1C] PLANNING_COMPILE_FAILED published={} failed={} of "
                            + "{} owners (tolerated {})",
                    published, failed, compiled.size(), tolerated, firstFailure);
            snapshot.planner().releasePrewarmLeases();
            PrewarmProgress.finish(false);
            return;
        }
        if (failed > 0) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PLANNING_COMPILE_PARTIAL published={} failed={} of "
                            + "{} owners (tolerated {}); those chunks will not carry the wall",
                    published, failed, compiled.size(), tolerated, firstFailure);
        }
        // The prewarm's leases have served their purpose: this compile consumed their plans and the
        // window is published, so the memory budget can have them back now.
        snapshot.planner().releasePrewarmLeases();
        PrewarmProgress.setSubProgress(compiled.size(), compiled.size());
        PrewarmProgress.setStage(PrewarmProgress.Stage.DONE);
        // The wall startup is truly over only now: the bulk prewarm, the fine sample and the
        // chunk compile have all settled, so the loading card closes and the startup peers are
        // released. This is the single owner of the final finish - the bulk prewarm must not
        // finish earlier, or the card would close at 100 % mid-compile and RoadWeaver would start
        // its road generation while the wall is still being compiled. finish() opens both the
        // card's latch and the worldgen gate the roads wait on.
        PrewarmProgress.signalReady();
        // Publishing puts the plans on disk; the gate opens only once they are written into the world,
        // which the startup stage does on the server thread before calling finish(true).
        PrewarmProgress.markPublished();
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
            // Tuned parameters are semantics too: a reload after the screen edited them would otherwise
            // let the second half of the wall be planned from a different ridge choice than the first,
            // which is the exact seam the frozen parameter snapshot exists to prevent. Compared against
            // the live digest on purpose - this is the drift detector, not a generation input.
            if (!snapshot.recipe().config().parametersHash()
                    .equals(local.plaincraft.greatwall.config.GreatWallConfig.parameterDigest())) {
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
        result.put("productionWiring", "OFF"); result.put("isolatedFixture", true);
        result.put("counts", counts()); result.put("root", bootstrap.progress()); result.put("bootstrap", bootstrap.diagnostics());
        result.put("rootAudit", bootstrap.rootAudit());
        result.put("vegetationLeases", vegetation.leases());
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
