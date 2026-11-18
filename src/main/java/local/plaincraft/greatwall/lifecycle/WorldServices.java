package local.plaincraft.greatwall.lifecycle;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.command.DiagnosticExporter;
import local.plaincraft.greatwall.command.GreatWallCommands;
import local.plaincraft.greatwall.compat.road.NegotiatedGateService;
import local.plaincraft.greatwall.diagnostics.DebugService;
import local.plaincraft.greatwall.gameplay.BeaconLedger;
import local.plaincraft.greatwall.gameplay.GarrisonController;
import local.plaincraft.greatwall.gameplay.IgnitionService;
import local.plaincraft.greatwall.gameplay.SiteRuntimeIndex;
import local.plaincraft.greatwall.gameplay.WallSpawnerGuard;
import local.plaincraft.greatwall.generation.WallStageCoordinator;
import local.plaincraft.greatwall.generation.ImportedShowcase;
import local.plaincraft.greatwall.generation.ShowcaseGate;
import local.plaincraft.greatwall.persistence.GateLedger;
import local.plaincraft.greatwall.persistence.HistoryJournal;
import local.plaincraft.greatwall.persistence.WallWorldState;
import local.plaincraft.greatwall.planning.ProbePlanner;
import local.plaincraft.greatwall.planning.PlannerScheduler;
import local.plaincraft.greatwall.generation.VegetationGuard;
import local.plaincraft.greatwall.generation.TreeBoundsCatalog;
import local.plaincraft.greatwall.generation.TerrainEngine;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.storage.LevelResource;

/** The mode is fixed per server: S0 laboratory or separately authorized S1C fixture, never public production. */
public final class WorldServices {

    private static final ConcurrentHashMap<MinecraftServer, WorldServices> SESSIONS = new ConcurrentHashMap<>();
    /**
     * Whether the rivers reported ready, retained across the session lifecycle.
     *
     * <h2>Why a retained flag</h2>
     * The river patch signals readiness the moment the rivers finish, which can race the
     * wall's own session registration. If the signal arrives before {@code SESSIONS.put},
     * dispatching only to current sessions would drop it, and the prewarm would never start -
     * the exact failure a real session showed. This flag remembers the signal and replays it
     * to a session the moment it registers, so the wall starts whether the rivers finished
     * before or after it came up.
     */
    private static final RetainedRiverSignal<MinecraftServer> RIVERS_READY = new RetainedRiverSignal<>();
    /** Flat workshops whose completion check waits for the two anchor chunks to load. */
    private static final java.util.concurrent.ConcurrentHashMap<ServerLevel, Long> SHOWCASE_PENDING_CHECK =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final ProbePlanner planner;
    private final ProductionSession production;
    private final MinecraftServer server;
    private final Path evidenceDirectory;
    private final boolean authorizedNewWorld;
    private final ConcurrentHashMap<String, WorldRecipeGuard> recipes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> failure = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ServerLevel, WallStageCoordinator> barriers = new ConcurrentHashMap<>();

    private WorldServices(MinecraftServer server) {
        this.server = server;
        authorizedNewWorld = !server.getWorldData().overworldData().isInitialized();
        evidenceDirectory = server.getWorldPath(LevelResource.ROOT).resolve("greatwall-s0");
        if (!Boolean.getBoolean("greatwall.s0.probe")) {
            planner = null;
            production = new ProductionSession(server, this);
            // 保留信号的订阅在会话入表后原子完成，不能在构造器先检查再入表而漏掉通知。
            return;
        }
        production = null;
        planner = new ProbePlanner(server.getWorldData().worldGenOptions().seed(),
                Duration.ofMillis(Long.getLong("greatwall.s0.delayMs", 1)),
                ProbePlanner.Outcome.valueOf(System.getProperty("greatwall.s0.outcome", "SUCCESS")));
        // Explicit pre-tick pump: not END_SERVER_TICK, and no blocking sleep/join.
        for (int i = 0; i < 4; i++) planner.require(new ProbePlanner.Key("minecraft:overworld", i));
        planner.monitorStop(() -> {
            if (Files.exists(FabricLoader.getInstance().getGameDir().resolve("greatwall-cancel-startup"))) {
                trace("STARTUP_CANCEL", "*", 0, 0, "bootstrap", "none");
                failBootstrap(new CancellationException("Operator canceled unresolved startup planning"));
                server.halt(false);
                return true;
            }
            return !server.isRunning();
        });
        GreatWallMod.LOGGER.info("[GreatWall:S0-02] BOOTSTRAP session={} startupPump=explicit newWorld={}",
                planner.session(), authorizedNewWorld);
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            // 河流信号在停止时清理，启动时只清长城进度，否则会抹掉早于会话注册的真实通知。
            PrewarmProgress.clear();
            // Arm the gate now, before any peer can ask. The latch alone is not enough: a peer that
            // asks while nothing has been requested is told "ready", which is how a road started
            // laying while the wall had not sampled a column.
            PrewarmProgress.request();
            // Production. No JVM flag and no marker file: the wall is installed, so it runs. The old
            // shape required -Dgreatwall.s1c.productionFixture plus two marker files in the game
            // directory and threw outright when -Dgreatwall.production was set, so a shipped jar
            // could not run as itself. -Dgreatwall.s0.probe still selects the S0 laboratory below.
            WorldServices services = new WorldServices(server);
            SESSIONS.put(server, services);
            // 回调只捕获这个世界的生产会话；早到信号与注册交接由同一把锁保证。
            if (services.production != null) {
                RIVERS_READY.attach(server, services.production::riversPrewarmed);
            }
        });
        ServerWorldEvents.LOAD.register((server, level) -> {
            // Install the plan reader before any chunk loads, and seed the queue from the plans on
            // disk the way the road seeds its construction backfill from its ledger. Waiting for
            // CHUNK_LOAD alone is not enough: a save whose corridor was generated before this
            // session has no load event left to fire, so a measured run installed the reader and
            // still queued zero chunks.
            var greatWallRoot = server.getWorldPath(LevelResource.ROOT).resolve("greatwall");
            local.plaincraft.greatwall.generation.WallBodyBackfill.installIfAbsent(greatWallRoot);
            local.plaincraft.greatwall.generation.WallBodyBackfill.seedFromDisk(level, greatWallRoot);
            // The wonder-wall sample: the real wall's proportions, generated procedurally so the
            // player can walk up to it and judge the look before the whole line is built.
            if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD
                    && Boolean.parseBoolean(System.getProperty("greatwall.wonderSample", "true"))
                    && level.getChunkSource().getGenerator()
                            instanceof net.minecraft.world.level.levelgen.FlatLevelSource) {
                var spawn = level.getSharedSpawnPos();
                if (local.plaincraft.greatwall.generation.ShowcaseMarker.alreadyComplete(level, spawn)) {
                    // The workshop is a one-shot template. Re-entering the save must not stamp a
                    // second copy on top of the player's edits.
                    GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_SKIPPED marker=complete");
                } else {
                    // Do not decide yet: at ServerWorldEvents.LOAD the spawn chunks may not be
                    // readable, and a false detector answer would stamp a second copy. The tick
                    // loop waits for the two anchor chunks before starting or adopting.
                    SHOWCASE_PENDING_CHECK.put(level, System.nanoTime());
                    GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_START_DEFERRED awaiting anchor chunks");
                }
            }            var services = find(server);
            if (services != null) services.openDimension(level);
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            var service = find(level.getServer());
            if (service != null && service.barrier(level) != null) service.barrier(level).verifyLoaded(chunk);
            // A chunk generated before the plan existed still has to carry the wall, and a chunk
            // load is the only moment it becomes writable without requesting it.
            local.plaincraft.greatwall.generation.WallBodyBackfill.onChunkLoaded(level,
                    new ChunkKey(level.dimension().location().toString(), chunk.getPos().x, chunk.getPos().z));
            var productionService = find(level.getServer());
            if (productionService != null && productionService.production != null) {
                var sites = productionService.production.sitesIfReady();
                if (sites != null) {
                    var marker = chunk.getAttached(local.plaincraft.greatwall.generation.TerrainMarkers.PRODUCTION);
                    if (marker != null) sites.loaded(chunk, marker);
                }
            }
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            var service = find(level.getServer());
            if (service == null || service.production == null) return;
            var sites = service.production.sitesIfReady();
            if (sites != null) sites.unloaded(new ChunkKey(level.dimension().location().toString(),
                    chunk.getPos().x, chunk.getPos().z));
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            var service = find(level.getServer());
            if (service == null || service.production == null) return;
            var garrison = service.production.garrisonIfReady();
            if (garrison != null) garrison.loaded(entity);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            var service = find(level.getServer());
            if (service == null || service.production == null) return;
            var garrison = service.production.garrisonIfReady();
            if (garrison != null) garrison.unloaded(entity);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var services = find(server);
            if (services == null) return;
            if (services.production != null) { services.production.started(); return; }
            services.planner.transition(BootstrapState.WARMING, BootstrapState.ACTIVE);
            var counts = services.planner.trace().counts();
            if (counts.getOrDefault("STAGE_ENTER", 0L) == 0L
                    || counts.getOrDefault("WAIT_WHILE_LOCKED", 0L) != 0L) {
                throw new IllegalStateException("S0 injection did not execute or waited inside a write lock: " + counts);
            }
            if (System.getProperty("greatwall.s0.expectedAdapter", "vanilla").equals("c2me")
                    && counts.getOrDefault("WRITE_LOCK_ACQUIRED", 0L) == 0L) {
                throw new IllegalStateException("C2ME lock witness was not hit; cannot certify the adapter");
            }
            GreatWallMod.LOGGER.info("[GreatWall:S0-02] ACTIVE session={} counts={}", services.planner.session(), counts);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var services = find(server);
            if (services != null && services.production != null) services.production.tick();
            // Rate limited inside: four chunks per ten ticks, loaded chunks only.
            // A reloaded save has no production session, so install the reader here before draining:

            // otherwise every loaded chunk drains against an empty plan cache and the world stays bare.

            local.plaincraft.greatwall.generation.WallBodyBackfill.installIfAbsent(

                    server.getWorldPath(LevelResource.ROOT).resolve("greatwall"));

            var overworld = server.overworld();
            maybeStartFlatShowcase(overworld);
            local.plaincraft.greatwall.generation.WallBodyBackfill.drain(overworld);
            local.plaincraft.greatwall.generation.GateStamp.tick(overworld);
            local.plaincraft.greatwall.generation.WallStamp.tick(overworld);
            local.plaincraft.greatwall.generation.StairStamp.tick(overworld);
            local.plaincraft.greatwall.generation.ShowcaseStamp.tick(overworld);
            local.plaincraft.greatwall.generation.DiagonalWallShowcase.tick(overworld);
            local.plaincraft.greatwall.generation.TerrainShowcase.tick(overworld);
            ImportedShowcase.tick(overworld);
            if (local.plaincraft.greatwall.generation.ShowcaseGate.completed()
                    && overworld.getChunkSource().getGenerator()
                            instanceof net.minecraft.world.level.levelgen.FlatLevelSource) {
                local.plaincraft.greatwall.generation.ShowcaseMarker.markComplete(
                        overworld, overworld.getSharedSpawnPos(), "stamped");
            }
        });
        // 完整保存之后才承认展位已施工；停服保存到来前不能丢掉本会话的完工状态。
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> {
            var level = server.overworld();
            if (level != null) ImportedShowcase.afterSave(level, flush, force);
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, successful) -> {
            var services = find(server);
            if (services == null || !successful) return;
            if (services.production != null) { services.production.validateReload(); return; }
            try {
                for (var entry : services.recipes.entrySet()) {
                    ServerLevel level = server.getAllLevels().iterator().next();
                    for (var candidate : server.getAllLevels()) {
                        if (candidate.dimension().location().toString().equals(entry.getKey())) level = candidate;
                    }
                    entry.getValue().validate(GenerationResourceFingerprint.capture(level, resources));
                }
                services.trace("RECIPE_ACCEPTED", "*", 0, 0, "reload", "none");
                GreatWallMod.LOGGER.info("[GreatWall:S0-02] RECIPE_ACCEPTED: unchanged generation semantics");
            } catch (IOException | RuntimeException incompatible) {
                services.failure.put("reason", incompatible.toString());
                services.planner.close();
                services.trace("RECIPE_REJECTED", "*", 0, 0, "reload", "none");
                GreatWallMod.LOGGER.error("[GreatWall:S0-02] RECIPE_REJECTED: new generation is disabled; use a new world", incompatible);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // The stamps and their force-load tickets are static. Release them while the level is
            // still open, or a second world in the same JVM can inherit a ticket/write task.
            var overworld = server.overworld();
            SHOWCASE_PENDING_CHECK.remove(overworld);
            local.plaincraft.greatwall.generation.ShowcaseStamp.resetAll(overworld);
            local.plaincraft.greatwall.generation.GateStamp.reset(overworld);
            local.plaincraft.greatwall.generation.WallStamp.reset(overworld);
            local.plaincraft.greatwall.generation.StairStamp.reset(overworld);
            local.plaincraft.greatwall.generation.DiagonalWallShowcase.reset(overworld);
            local.plaincraft.greatwall.generation.TerrainShowcase.reset(overworld);
            ImportedShowcase.reset(overworld);
            local.plaincraft.greatwall.generation.ChunkHold.releaseAll(overworld);
            local.plaincraft.greatwall.generation.WallBodyBackfill.clearAll();
            local.plaincraft.greatwall.compat.road.WallRoadGuard.clear();
            // 停止阶段先注销通知，避免关闭中的生产会话再收到河流广播。
            RIVERS_READY.detach(server);
            var services = find(server);
            if (services != null) {
                if (services.production != null) {
                    services.production.close(); services.production.export("STOPPING"); return;
                }
                services.planner.close();
                services.barriers.values().forEach(WallStageCoordinator::close);
                // Vanilla can fail while saving errored generation; preserve proof before that call.
                services.writeEvidence("STOPPING");
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            // 停服的最后一次保存已结束，再移除展位和道路等待状态。
            ImportedShowcase.forget(server.overworld());
            ShowcaseGate.reset();
            RIVERS_READY.detach(server);
            var services = SESSIONS.remove(server);
            if (services != null) {
                if (services.production != null) {
                    services.production.close(); services.production.export("STOPPED");
                } else {
                    services.planner.close();
                    services.writeEvidence("STOPPED");
                }
            }
            // Cross-session reset: the SERVER_STARTING reset covers the next world, but doing
            // it here too means a world that stops without starting another does not leave a
            // spent latch or a stale rivers-ready flag in the shared statics either.
            RIVERS_READY.clear();
            PrewarmProgress.clear();
            // Arm the gate now, before any peer can ask. The latch alone is not enough: a peer that
            // asks while nothing has been requested is told "ready", which is how a road started
            // laying while the wall had not sampled a column.
            PrewarmProgress.request();
            // The landing cache is keyed by ServerLevel, so it holds a strong reference into a world
            // that has just been closed. Leaving it behind pinned that level - and its LRU of up to
            // 4096 + 16384 compiled plans - for the rest of the JVM's life, which made every world a
            // single-player session had entered stay reachable. Nothing in production ever called
            // invalidate/clearAll, so this is the one place that has to.
            local.plaincraft.greatwall.generation.WallWorldgenPlanCache.clearAll();
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, selection) ->
                GreatWallCommands.register(dispatcher, access, selection));
    }

    private void openDimension(ServerLevel level) {
        if (production != null) { production.openDimension(level); return; }
        String dimension = level.dimension().location().toString();
        try {
            Path manifest = evidenceDirectory.resolve("recipes")
                    .resolve(ProbePlanner.hash(dimension) + ".json");
            recipes.put(dimension, new WorldRecipeGuard(manifest, authorizedNewWorld,
                    GenerationResourceFingerprint.capture(level, server.getResourceManager())));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot authorize the S0 world recipe", failure);
        }
        if (level.dimension().equals(Level.OVERWORLD)) {
            if (Boolean.getBoolean("greatwall.s0.terrain")) {
                barriers.put(level, new WallStageCoordinator(level, this, recipes.get(dimension).hash(),
                        evidenceDirectory.resolve("terrain")));
            }
            planner.transition(BootstrapState.BOOTSTRAP, BootstrapState.RECIPE_READY);
        }
    }

    /** Starts the flat workshop only after its two detection chunks are readable. */
    private static void maybeStartFlatShowcase(ServerLevel level) {
        Long since = SHOWCASE_PENDING_CHECK.get(level);
        if (since == null) return;
        if (!(level.getChunkSource().getGenerator()
                instanceof net.minecraft.world.level.levelgen.FlatLevelSource)) {
            SHOWCASE_PENDING_CHECK.remove(level);
            return;
        }
        var spawn = level.getSharedSpawnPos();
        if (local.plaincraft.greatwall.generation.ShowcaseMarker.alreadyComplete(level, spawn)) {
            SHOWCASE_PENDING_CHECK.remove(level);
            GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_SKIPPED marker=complete");
            return;
        }
        boolean inspectable = local.plaincraft.greatwall.generation.ShowcaseMarker.canInspect(level, spawn);
        boolean timedOut = System.nanoTime() - since > 60_000_000_000L;
        if (!inspectable && !timedOut) return;
        SHOWCASE_PENDING_CHECK.remove(level);
        if (inspectable && local.plaincraft.greatwall.generation.ShowcaseMarker.detectExisting(level, spawn)) {
            // Adopt saves created before the marker existed: the gate/palace is present, so write
            // the marker now and leave every existing block untouched.
            local.plaincraft.greatwall.generation.ShowcaseMarker.markComplete(level, spawn, "adopted");
            GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_SKIPPED marker=adopted-existing");
            return;
        }
        GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_START spawn=({}, {})",
                spawn.getX(), spawn.getZ());
        startFlatShowcase(level, spawn);
    }

    private static void startFlatShowcase(ServerLevel level, net.minecraft.core.BlockPos spawn) {
        // 展台模式：墙、城门、敌台各占一个独立位置，互不相连，便于逐个验收与手工精修。
        local.plaincraft.greatwall.generation.ShowcaseLayout.reset();
        local.plaincraft.greatwall.generation.GateStamp.start(spawn.getX() - 64, spawn.getZ());
        local.plaincraft.greatwall.generation.WallStamp.start(spawn.getX() - 64, spawn.getZ() - 64);
        // 单独留一区做「门楼 + 左墙 + 右墙」拼接验收，不覆盖上面的独立墙/门楼展台。
        int joinedX = spawn.getX() + 256;
        int joinedZ = spawn.getZ();
        // Gate and wall are separate pieces: keep the whole 145-wide gate template and attach one
        // current 48-block wall_module to each open end. 0.7.91 起两者都「直接落地」：模板最底层
        // 坐在 footprint 的地表中位数上（GateStamp / DiagonalWallShowcase / WallStamp 同一口径），
        // 门楼端面 z14..24 的墙接口与墙的 depth 0..17 逐格对齐，不再有任何 heightOffset。
        // Straight display uses the same wall_module path generator as the curved display,
        // only with bend=false. The gatehouse is stamped by that generator as well.
        local.plaincraft.greatwall.generation.DiagonalWallShowcase.start(
                level, joinedX, joinedZ, false);
        GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_JOINED gate={} leftWall={} rightWall={} phases=47/3",
                joinedX, joinedX - 96, joinedX + 97);
        // 斜向走势演示区：门楼保持正东西/正南北，墙先正接再分两段过渡到 45°。
        local.plaincraft.greatwall.generation.DiagonalWallShowcase.start(level,
                joinedX, spawn.getZ() + 120);
        // 生产等价地形演示区：模拟高台/低谷/水塘，再用真实 WallSection 生成 45° 墙与端楼。
        local.plaincraft.greatwall.generation.TerrainShowcase.start(level,
                spawn.getX() + 1600, spawn.getZ() + 300);
        local.plaincraft.greatwall.generation.StairStamp.start(spawn.getX() - 64, spawn.getZ() + 128);
        local.plaincraft.greatwall.generation.ShowcaseStamp.start("beacon.csv", 16, 26, 16, 8, 8, spawn.getX() - 64, spawn.getZ() + 192);
        local.plaincraft.greatwall.generation.ShowcaseStamp.start("corner.csv", 48, 26, 48, 24, 24, spawn.getX() - 64, spawn.getZ() + 320);
        local.plaincraft.greatwall.generation.ShowcaseStamp.start("fort.csv", 14, 12, 14, 7, 7, spawn.getX() - 64, spawn.getZ() + 448);
        // v2 对比位：与 v1 并排放置，互不覆盖
        local.plaincraft.greatwall.generation.ShowcaseStamp.start("tower2.csv", 24, 35, 24, 12, 12, spawn.getX() - 64, spawn.getZ() + 96);
        // 3ds Max 体素化来的新构件
        local.plaincraft.greatwall.generation.ShowcaseStamp.start("palace2.csv", 74, 40, 46, 37, 23, spawn.getX() - 64, spawn.getZ() + 640);
        local.plaincraft.greatwall.generation.ShowcaseStamp.start("watergate.csv", 46, 27, 13, 23, 6, spawn.getX() - 64, spawn.getZ() + 704);
        // 长城套件：36 件体素化后按行列摆开的选型台
        local.plaincraft.greatwall.generation.ShowcaseStamp.start("kitset.csv", 784, 34, 421, 392, 210, spawn.getX() + 900, spawn.getZ() + 400);
    }

    /**
     * Looks up the wall services for a server, tolerating a null server.
     *
     * <p>A null server is the normal case on a client: an integrated-server mixin target can also run
     * in the client process, where {@code Level.getServer()} returns null until a world is hosted.
     * {@link ConcurrentHashMap#get} throws {@link NullPointerException} for a null key, so callers
     * that pass an unguarded {@code level.getServer()} would crash the client. Returning null here
     * keeps every call site on the existing "no services, do nothing" path.
     */
    public static WorldServices find(MinecraftServer server) {
        if (server == null) return null;
        return SESSIONS.get(server);
    }
    public static Optional<WorldServices> find(UUID session) {
        return SESSIONS.values().stream().filter(value -> value.production != null
                && value.production.runtime().equals(session) && !value.production.rejected()).findFirst();
    }
    public boolean productionFixture() { return production != null; }
    /** The durable runtime identity used by optional integrations for this world session. */
    public UUID runtimeSession() { return requireProductionSession().runtime(); }
    public ServerLevel overworld() { assertActiveSession(); return server.overworld(); }
    public java.util.concurrent.Executor serverOwner() { return server; }
    public WorldRecipe recipe() { return requireProductionSession().recipe(); }
    public PlannerScheduler planning() { return requireProductionSession().planning(); }
    public WorldBootstrap bootstrap() { return requireProductionSession().bootstrap(); }
    public VegetationGuard vegetation() { return requireProductionSession().vegetation(); }
    /** Dedicated biome runtime, or null when the biome identity mode is off for this run. */
    public local.plaincraft.greatwall.compat.biome.GreatWallBiomeRuntime biomeRuntime() {
        return requireProductionSession().biomeRuntime();
    }
    public TreeBoundsCatalog trees() { return requireProductionSession().trees(); }
    public CompletableFuture<WorldRecipe> recipeReady() { return requireProductionSession().recipeReady(); }
    public Map<String, Object> productionDiagnostics() { return requireProductionSession().diagnostics(); }
    public BeaconLedger beacons() { return requireProductionSession().beacons(); }
    public GateLedger gates() { return requireProductionSession().gates(); }
    public SiteRuntimeIndex sites() { return requireProductionSession().sites(); }

    /**
     * A wall plan has landed in a chunk. Publish its persistent gameplay index and restore the
     * guard columns even when this plan was read back from disk. No chunk loading is requested.
     */
    public static void onWallChunkReady(ServerLevel level,
                                        net.minecraft.world.level.chunk.ChunkAccess chunk,
                                        local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan plan) {
        if (level == null || chunk == null || plan == null) return;
        local.plaincraft.greatwall.generation.WallPlanSupport.markPlannedColumns(plan);
        var services = find(level.getServer());
        if (services == null || services.production == null) return;
        var sites = services.production.sitesIfReady();
        if (sites == null) return;
        if (!(chunk instanceof net.minecraft.world.level.chunk.LevelChunk loadedChunk)) return;
        var marker = chunk.getAttached(local.plaincraft.greatwall.generation.TerrainMarkers.PRODUCTION);
        if (marker != null) sites.loaded(loadedChunk, marker);
    }

    public GarrisonController garrison() { return requireProductionSession().garrison(); }
    public IgnitionService ignition() { return requireProductionSession().ignition(); }
    /** Ignition service for interaction paths, or null before gameplay assembly completes. */
    public IgnitionService ignitionIfReady() {
        var session = find(server);
        return session == null || session.production == null ? null : session.production.ignitionIfReady();
    }
    public WallSpawnerGuard spawners() { return requireProductionSession().spawners(); }
    /** Spawner guard for hot-path callers, or null before gameplay assembly completes. */
    public WallSpawnerGuard spawnersIfReady() {
        var session = find(server);
        return session == null || session.production == null ? null : session.production.spawnersIfReady();
    }
    public HistoryJournal history() { return requireProductionSession().history(); }
    public NegotiatedGateService negotiated() { return requireProductionSession().negotiated(); }
    /** Arch backfill for the explicit repair path, or null before gameplay assembly completes. */
    public local.plaincraft.greatwall.compat.road.WallArchBackfill backfillIfReady() {
        return requireProductionSession().backfillIfReady();
    }
    public DebugService debug() { return requireProductionSession().debug(); }
    public DiagnosticExporter diagnosticExporter() { return requireProductionSession().diagnosticExporter(); }
    public UUID session() { return requireProductionSession().session(); }
    public WallWorldState worldState() { return requireProductionSession().worldState(); }
    public WallStageCoordinator barrier(ServerLevel level) {
        return barriers.get(level);
    }
    public CompletableFuture<Void> closeProduction() {
        var value = requireProductionSession(); value.close(); return value.drain();
    }
    private ProductionSession requireProductionSession() {
        if (production == null) throw new IllegalStateException("S0 laboratory has no production services");
        return production;
    }
    public static void warming(MinecraftServer server) {
        // A flat test world carries no ridge, no noise generator and no sampling worth doing: the
        // production oracle refuses a non-noise generator outright, and the crash would take the whole
        // world down before the wonder sample ever ran. Skip the wall's warm-up there entirely.
        if (server != null && server.overworld().getChunkSource().getGenerator()
                instanceof net.minecraft.world.level.levelgen.FlatLevelSource) {
            GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_SKIPPED flat=true reason=wonder-sample-world");
            return;
        }
        var services = find(server);        if (services != null) {
            // Mark the prewarm as coming before anything can ask whether one is coming. A peer that polls
            // the gate between world load and the first segment was told "no prewarm has been requested",
            // and awaitCompletion answers that with an immediate true - which is how a road can start
            // laying while the wall has not sampled a column. Every exit from here (published, failed,
            // timed out) calls finish(), which releases the gate for real.
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.request();
            if (services.production != null) { services.production.warming(); return; }
            services.planner.transition(BootstrapState.RECIPE_READY, BootstrapState.WARMING);
            services.trace("STARTUP_WARMING", "*", 0, 0, "prepareLevels", "none");
        }
    }

    /**
     * Called from {@code prepareLevels} TAIL. Any blocking startup peer that runs at that method's
     * HEAD has necessarily returned by now, so this is the sound point to release the wall's bulk
     * prewarm without racing the peer for the same native lanes.
     */
    public static void afterRoadReached(MinecraftServer server) {
        try {
            var services = find(server);
            if (services != null && services.production != null) services.production.afterRoadReached();
        } catch (Throwable t) {
            // A mixin at the TAIL of prepareLevels: anything thrown here ends the server tick and takes
            // the game down with it. Every failure inside the session is already logged and handled -
            // the wall is what gets skipped, never the world. A measured session crashed exactly here,
            // minutes after a planning job had closed its planner.
            GreatWallMod.LOGGER.error("[GreatWall:S0-02] ROAD_REACHED_HOOK_FAILED; the wall is skipped, "
                    + "the world continues", t);
        }
    }

    /**
     * 由河流模组经标准就绪API触发；道路只是契约宿主，不代表河流发布完成。
     *
     * <h2>Where this sits in the startup order</h2>
     * <pre>
     *   rivers -> wall prewarm (here) -> roads
     * </pre>
     * The wall's bulk prewarm used to be released at {@code prepareLevels} TAIL,
     * which is after RoadWeaver has finished generating roads. That ordering made
     * the wall the last stage to start even though its own prerequisites — the
     * frozen recipe and the native lanes — are ready far earlier. Releasing it as
     * soon as the rivers are in place is both earlier and safer: Streams has
     * stopped competing for the terrain samplers by then, and the roads have not
     * started yet, so the wall prewarm overlaps neither.
     *
     * <p>RoadWeaver is optional, so this is a best-effort early release. The
     * {@code prepareLevels} TAIL path still calls
     * {@link #afterRoadReached(MinecraftServer)} and remains the fallback for a
     * session without RoadWeaver; {@code ProductionSession.afterRoadReached} is
     * idempotent, so at most one of the two actually starts the prewarm.
     */
    public static void riversPrewarmed() {
        // 同步保留与分发，防止通知落在构造器检查之后、SESSIONS入表之前而永久丢失。
        PrewarmProgress.request();
        RIVERS_READY.publish();
    }

    /**
     * Waits until the wall can paint, for a startup peer to call.
     *
     * <h2>Why a peer calls this</h2>
     * Rivers, then the wall, then roads is the intended order. RoadWeaver owns the last
     * two, so it asks here between them rather than beginning road generation while the
     * wall is still planning. Running the two together was measured to hurt both: the
     * wall's scheduler recorded 257 watchdog rescues, meaning the server thread stopped
     * delivering ticks for three seconds at a time while the other stage held it.
     *
     * <h2>What "ready" means here</h2>
     * The whole wall startup, not just the bulk prewarm: the chunk compile and its publication
     * are part of the wall's own stage and are what makes a newly generated chunk actually carry
     * the wall. A measured session released the roads in the same second the prewarm's segments
     * completed, while the compile had not run yet and then silently failed - the player got a
     * road network and no wall. See {@link PrewarmProgress#awaitWorldgenReady}.
     *
     * <h2>Contract</h2>
     * Returns immediately when no wall session exists, when no prewarm has started, or when
     * one has already finished, so a session without wall generation is not delayed at all.
     * On timeout it returns false and the caller is expected to proceed: a stage that never
     * reports must not turn a slow startup into a hang.
     *
     * @return 仅严格发布门已开且模板盖章不再等待时为true；失败、超时和未启动为false
     */
    public static boolean awaitPrewarm(long timeout, java.util.concurrent.TimeUnit unit) {
        // The gate must not consult SESSIONS. That map is only populated when the S0 probe or the
        // S1C production fixture is enabled, so in an ordinary world it is empty and this method
        // answered "ready" to every peer before the wall had sampled a column. Measured: the road
        // began its initial generation at 16:33:20, the same second the prewarm completed, on every
        // session that was ever run - the gate had never once held it.
        //
        // The latch is the contract: clear() closes it at server start, request() arms the session,
        // and finish(true) opens it. A peer waits until then.
        if (!PrewarmProgress.awaitWorldgenReadyStrict(timeout, unit)) return false;
        // 模板世界：盖章还没铺完时，道路必须继续等，否则它会在墙列被标记之前就把路铺过去。
        return !local.plaincraft.greatwall.generation.ShowcaseGate.pending();
    }

    /**
     * Owns the server thread for the wall's stage, pumping both event loops until the prewarm
     * settles.
     *
     * <h2>Why the wall, not the roads, owns this</h2>
     * "To whomever owns the stage, the main thread" - each stage must hold the main thread only
     * for itself. The wall used to hand the main thread straight back after warming(), leaving
     * the road side to block-and-pump for it, which is a responsibility inversion: the roads
     * were parking the server thread waiting for a stage they do not own. The wall now pumps
     * here and returns only once its prewarm is done, so the roads enter and pass the gate in
     * zero milliseconds.
     *
     * <h2>Why a managedBlock with a chunk-queue drain, not a bare poll loop</h2>
     * managedBlock raises the in-managed-block depth, which makes {@code MinecraftServer.pollTask}
     * skip its {@code shouldRun()} filter - the filter that, with {@code haveTime()} false during
     * startup, rejects every {@code server.execute()} task the wall posts to install its recipe.
     * The condition body additionally drains every level's chunk queue: vanilla ChunkMap posts
     * its completion callbacks to that world-local queue, and a bare managedBlock never drains it,
     * so the wall's structure-screen chunk requests would stall. Both queues must move.
     */
    public static void blockAndPumpUntilPrewarmSettled(MinecraftServer server) {
        if (server == null) return;
        // A flat test world exists to look at the wonder sample, nothing else: the ridge search would
        // find no ridge, the sample would wait behind it, and the player would watch a loading screen
        // for a wall the flat world cannot carry. Skip the whole pipeline and open the road gate.
        if (server.overworld().getChunkSource().getGenerator()
                instanceof net.minecraft.world.level.levelgen.FlatLevelSource) {
            GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_SKIPPED flat=true reason=wonder-sample-world");
            // 此时出生点已经确定。先发布新展位和待办，再开长城就绪门，避免道路抢占接缝。
            var services = find(server);
            if (services != null && Boolean.parseBoolean(System.getProperty("greatwall.wonderSample", "true"))) {
                var level = server.overworld();
                ImportedShowcase.start(level, level.getSharedSpawnPos(), services.authorizedNewWorld);
            }
            PrewarmProgress.finish(true);
            return;
        }        // No test-mode guard. It used to return here unless the S1C fixture or the S0 probe was
        // enabled, which is why the wall never owned the server thread in a real world: the road
        // ran its own prepareLevels hook with the thread free, its initial generation began at once,
        // and the wall was left sampling beside it. The ownership handover below is the whole design -
        // wall first (priority 950), then the road (priority 1000).
        long timeoutMinutes = Math.max(1L, Long.getLong("greatwall.prewarmTimeoutMinutes", 30L));
        long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(timeoutMinutes);
        // The compile runs once, on this thread, the moment the segments settle. Doing it here rather
        // than from the server tick loop is what makes "the wall is finished before the roads start"
        // true rather than aspirational: this thread holds the stage, the tick loop cannot run while
        // it is parked in managedBlock, and the road gate now waits for the compile to publish.
        final boolean[] compiled = {false};
        final boolean[] painted = {false};
        // One extra compile attempt once the fine sample lands, taken on this thread rather than as a
        // queued task. One shot: if it cannot run, the queued triggerDeferredCompile path still tries.
        final boolean[] retriedDeferred = {false};
        server.managedBlock(() -> {
            // ── 终态失败必须立刻放行（2026-11-03 实测 bug）───────────────────────────────────
            // 一个已经 finish(false) 的会话不会再有任何进展：目前两个来源是 bootstrap 拒绝
            // （RECIPE_REJECTED：存档是用另一套墙体语义生成的，重试一万次也不会匹配）和预热看门狗超时。
            // 缺少这道守卫时，下面的 compileWhileBlocked 会一直返回 false，
            // managedBlock 于是无限重试 → 服务器线程永不释放 → 加载界面死锁在 0%，
            // 同时每轮刷一条 PRODUCTION_STARTUP_COMPILE ran=false。
            // 实测（新世界存档 + 0.7.107）：4 分钟刷了 137,892 行、日志 50 KB/s 涨到 15.8 MB。
            if (PrewarmProgress.isFinished() && PrewarmProgress.isFailed()) {
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] PREWARM_TERMINAL_ABORT - 墙体启动阶段已终态失败"
                        + "（见上方 RECIPE_REJECTED / PREWARM_WAIT_TIMEOUT），立即释放启动阶段，"
                        + "让道路在没有墙的情况下继续");
                return true;
            }
            // A compile that deferred for want of the fine sample is normally queued with
            // server.execute() by triggerDeferredCompile, but this thread is parked in managedBlock and
            // that queue is only polled between drain rounds - a measured session waited 17.1 s there
            // after the sample published. Notice the settled sample here, before the drain, and compile on
            // this thread instead. activate() guards the compile with an atomic "compile-started" key, so
            // this cannot run it twice, and compileRetryPending() goes false the moment it starts.
            var startupOwner = SESSIONS.get(server);
            if (startupOwner != null && startupOwner.production != null) {
                startupOwner.production.logCompileRetryProbe();
            }
            if (!retriedDeferred[0] && startupOwner != null && startupOwner.production != null
                    && startupOwner.production.compileRetryPending()) {
                retriedDeferred[0] = true;
                compiled[0] = compileWhileBlocked(server) || compiled[0];
            }
            // Drain every world-local chunk queue: ChunkMap posts its completion callbacks here
            // and they must run for the structure screen's chunk requests to finish.
            for (var level : server.getAllLevels()) {
                while (level.getChunkSource().pollTask()) {
                    // keep draining until the local queue is empty
                }
            }
            if (System.nanoTime() >= deadlineNanos) {
                // Historically this gave up silently: the wall stopped waiting, the road gate stayed
                // closed until the road's own timeout expired, and nothing in the log said why the
                // player waited twice. Report it and open the gate, so the road proceeds on a stated
                // reason instead of a mystery.
                GreatWallMod.LOGGER.error("[GreatWall:S1C] PREWARM_WAIT_TIMEOUT after {} minute(s); "
                                + "the wall gives up its startup stage and the road gate is opened without it",
                        timeoutMinutes);
                PrewarmProgress.finish(false);
                return true;
            }
            // Wait on the segment latch, never on the peer gate: that one only opens once the
            // compile below has published, so waiting on it here would wait on ourselves.
            if (!PrewarmProgress.awaitCompletion(0, java.util.concurrent.TimeUnit.NANOSECONDS)) return false;
            if (!compiled[0]) {
                // Try the compile, and keep the stage if it could not run yet: at this point the segments
                // have settled but the fine sample may still be running, and compileAtStartup answers
                // false for that. The old shape recorded "compiled" before trying, so a compile that was
                // not ready yet released the server thread: SERVER_STARTED fired, the road's structure
                // discovery began while the wall was still sampling at 76 %, and the loading card stayed
                // up for another three minutes with the road already running beside it.
                compiled[0] = compileWhileBlocked(server);
            }
            if (!compiled[0]) return false;
            // The wall is planned, published and still holding the stage: paint it now, while nothing else
            // can run and the loading screen is up. The drain would get there eventually, one batch per ten
            // ticks, but the player would watch an empty corridor for the first minutes of the world.
            if (PrewarmProgress.awaitWorldgenReadyStrict(0L, java.util.concurrent.TimeUnit.NANOSECONDS)) return true;
            if (!PrewarmProgress.isPublished()) return false;   // plans not on disk yet
        // 用户方案（2026-10-24）：启动期一块都不画。预热只负责「规划 + 编译 + 发布足迹」，
        // 之后立刻放行道路；墙体交给进世界后的 drain（每 tick 150ms）逐区块拉起并写入。
        // 实测依据：启动期同步拉区块，8 分钟预算只画完 25%（pending 仍 343）；进世界 drain
        // 456 块约 3 分钟，且完全不占加载界面。
        // 前提：道路避让不能依赖"写块时打标记"，所以先把规划里每一列墙标进 WallRoadGuard，
        // 这样墙还没长出来，道路就知道该避开哪里。
        if (!painted[0]) {
            painted[0] = true;
            try {
                int seeded = local.plaincraft.greatwall.generation.WallBodyBackfill
                        .seedRoadGuardFromPlans(server.overworld());
                GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_PAINT_DEFERRED_TO_WORLD pending={} "
                                + "seededColumns={} - the road gate opens now, the in-game drain paints",
                        local.plaincraft.greatwall.generation.WallBodyBackfill.pending(server.overworld()),
                        seeded);
            } catch (Throwable seedFailed) {
                GreatWallMod.LOGGER.error("[GreatWall:S1C] ROAD_GUARD_SEED_FAILED", seedFailed);
            }
        }
            // The wall is in the world: only now may the road start.
            PrewarmProgress.finish(true);
            // The compile can be deferred: compileAtStartup returns true after asking for it, and the
            // compile itself only runs once the fine sample publishes (PLANNING_COMPILE_WAIT_FOR_SAMPLE).
            // Its return value is therefore "I asked", not "it published" - holding the stage on it released
            // the server thread with nothing published, which is what let the road start at 10:36 while
            // the wall was still sampling at 76 %. The gate that really opens on publish is worldgenReady,
            // released by finish(true) after the compile or by finish(false) on failure.
            return PrewarmProgress.awaitWorldgenReadyStrict(0L, java.util.concurrent.TimeUnit.NANOSECONDS);
        });
    }

    /**
     * Runs the wall's planning compile on the thread that owns the wall's startup stage.
     *
     * <p>Never throws: startup must not fail because the wall could not compile. The compile itself
     * reports its own failure loudly (see {@code ProductionSession.PLANNING_COMPILE_FAILED}) and
     * opens the road gate either way, so a broken wall costs a warning instead of a hung server.
     */
    private static boolean compileWhileBlocked(MinecraftServer server) {
        var services = SESSIONS.get(server);
        if (services == null || services.production == null) return true;
        try {
            boolean ran = services.production.compileAtStartup();
            GreatWallMod.LOGGER.info("[GreatWall:S1C] PRODUCTION_STARTUP_COMPILE ran={}", ran);
            return ran;
        } catch (Throwable error) {
            GreatWallMod.LOGGER.error("[GreatWall:S1C] PRODUCTION_STARTUP_COMPILE_FAILED", error);
            return true;
        }
    }

    /** The wall's current prewarm progress, for diagnostics and for a progress display. */
    public static PrewarmProgress.Snapshot prewarmSnapshot() {
        return PrewarmProgress.snapshot();
    }
    public void assertActiveSession() {
        if (production != null) {
            if (SESSIONS.get(server) != this) throw new CancellationException("Stale production session");
            production.active(); return;
        }
        if (!planner.accepting() || SESSIONS.get(server) != this) {
            throw new CancellationException("Closed or stale world session");
        }
    }
    /** {@return true when this session is the S0 laboratory rather than a production build} */
    public boolean probeOnly() { return production == null; }

    public CompletableFuture<ProbePlanner.Plan> require(String dimension, int x, int z, String stage) {
        if (production != null) throw new IllegalStateException("Production cannot use ProbePlanner");
        if (!planner.accepting() || SESSIONS.get(server) != this) {
            return CompletableFuture.failedFuture(new CancellationException("Closed or stale world session"));
        }
        planner.trace().assertOutsideLock();
        trace("REQUEST_PLAN", dimension, x, z, stage, "none");
        trace("WAIT_PLAN_NO_LOCK", dimension, x, z, stage, "none");
        if (planner.trace().counts().getOrDefault("WAIT_PLAN_NO_LOCK", 0L) == 1L) {
            GreatWallMod.LOGGER.info("[GreatWall:S0-02] WAIT_PLAN_NO_LOCK session={} chunk={},{} stage={}",
                    planner.session(), x, z, stage);
        }
        // A diagnostic axis only, not the S1 route or its PrefixSummaryIndex.
        var result = planner.require(new ProbePlanner.Key(dimension, Math.floorDiv(x, 128)));
        return result.whenComplete((plan, error) -> {
            if (error == null) trace("PLAN_READY", dimension, x, z, stage, "none");
        });
    }
    public void trace(String event, String dimension, int x, int z, String stage, String token) {
        if (production != null) { production.trace(event, dimension, x, z, stage, token); return; }
        planner.trace().record(event, dimension, x, z, stage, token);
    }
    public Map<String, Long> counts() { return production == null ? planner.trace().counts() : production.counts(); }
    public boolean hasCompletedStage(String dimension, int x, int z) {
        if (production != null) return production.completedStage(dimension, x, z);
        return planner.trace().snapshot().stream().anyMatch(event -> event.event().equals("STAGE_EXIT")
                && event.dimension().equals(dimension) && event.chunkX() == x && event.chunkZ() == z);
    }
    public boolean recipeRejected() { return production == null ? failure.containsKey("reason") : production.rejected(); }

    public void failBootstrap(Throwable cause) {
        if (production != null) { production.fail(cause); return; }
        BootstrapState current = planner.state();
        if (current != BootstrapState.BOOTSTRAP && current != BootstrapState.RECIPE_READY
                && current != BootstrapState.WARMING) return;
        failRequiredPlan(cause);
    }

    public void failRequiredPlan(Throwable cause) {
        if (production != null) { production.fail(cause); return; }
        if (SESSIONS.get(server) != this || !server.isRunning()) return;
        BootstrapState current = planner.state();
        if ((current == BootstrapState.STOPPING || current == BootstrapState.CLOSED) && !recipeRejected()) return;
        if (failure.putIfAbsent("requiredPlan", cause.toString()) != null) return;
        boolean startup = current == BootstrapState.BOOTSTRAP || current == BootstrapState.RECIPE_READY
                || current == BootstrapState.WARMING;
        trace(startup ? "BOOTSTRAP_FAILED" : "REQUIRED_PLAN_FAILED", "*", 0, 0, "required-plan", "none");
        // The same two survivable conditions the production path already honours - "this world has no
        // site the wall accepts" and "this world was generated with different semantics" - are not a
        // broken installation here either. Without this, an existing world whose stored recipe no
        // longer matches (a semantic knob edited, the digest schema bumped, a datapack moved the
        // terrain) took the running server down through this path instead of loading without a wall.
        String survivable = ProductionSession.survivableReason(cause);
        if (survivable != null) {
            GreatWallMod.LOGGER.error("[GreatWall:S0-02] {} the wall is skipped for this session and the "
                    + "world loads without it", survivable);
            planner.close();
            return;
        }
        // C2ME reports transaction errors without completing the spawn-search future.
        // The same MARK_BROKEN path can strand new requests after a rejected reload.
        // Vanilla checks this channel both before startup ticks and in the active loop.
        MinecraftServer.setFatalException(new IllegalStateException("GreatWall required planning dependency failed", cause));
        planner.close();
    }

    private void writeEvidence(String lifecycleEvent) {
        try {
            Files.createDirectories(evidenceDirectory);
            var gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(evidenceDirectory.resolve("session.json"), gson.toJson(Map.of(
                    "session", planner.session(), "state", planner.state(), "inFlight", planner.inFlightCount(),
                    "counts", planner.trace().counts(), "planner", planner.stats(), "failure", Map.copyOf(failure),
                    "lifecycleEvent", lifecycleEvent)));
            Files.writeString(evidenceDirectory.resolve("events.json"), gson.toJson(planner.trace().snapshot()));
            GreatWallMod.LOGGER.info("[GreatWall:S0-02] CLOSED session={} futures={} evidence={}",
                    planner.session(), planner.inFlightCount(), evidenceDirectory);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot persist S0 verification evidence", failure);
        }
    }
}
