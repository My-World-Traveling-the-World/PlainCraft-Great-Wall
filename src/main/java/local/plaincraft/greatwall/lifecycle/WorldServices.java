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
    private static final java.util.concurrent.atomic.AtomicBoolean RIVERS_READY = new java.util.concurrent.atomic.AtomicBoolean();
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
            // The rivers may have finished before this session registered. Replay the retained
            // signal so the wall still starts, then the recipe-install path releases the bulk
            // prewarm once it is durable (ProductionSession.riversPrewarmed is idempotent).
            if (RIVERS_READY.get()) {
                production.riversPrewarmed();
            }
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
            // Cross-session reset lives in SERVER_STOPPED (below): it already clears RIVERS_READY
            // and PrewarmProgress when a world stops. Resetting RIVERS_READY here would run BEFORE
            // the constructor replays it (the "rivers finished before this session registered"
            // branch in WorldServices.<init>), so the replay could never fire. Only clear the latch;
            // RIVERS_READY is left to SERVER_STOPPED so an early rivers signal is not lost.
            PrewarmProgress.clear();
            // Arm the gate now, before any peer can ask. The latch alone is not enough: a peer that
            // asks while nothing has been requested is told "ready", which is how a road started
            // laying while the wall had not sampled a column.
            PrewarmProgress.request();
            // Production. No JVM flag and no marker file: the wall is installed, so it runs. The old
            // shape required -Dgreatwall.s1c.productionFixture plus two marker files in the game
            // directory and threw outright when -Dgreatwall.production was set, so a shipped jar
            // could not run as itself. -Dgreatwall.s0.probe still selects the S0 laboratory below.
            SESSIONS.put(server, new WorldServices(server));
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
            var services = find(server);
            if (services != null) services.openDimension(level);
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            var service = find(level.getServer());
            if (service != null && service.barrier(level) != null) service.barrier(level).verifyLoaded(chunk);
            // A chunk generated before the plan existed still has to carry the wall, and a chunk
            // load is the only moment it becomes writable without requesting it.
            local.plaincraft.greatwall.generation.WallBodyBackfill.onChunkLoaded(level,
                    new ChunkKey(level.dimension().location().toString(), chunk.getPos().x, chunk.getPos().z));
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

            local.plaincraft.greatwall.generation.WallBodyBackfill.drain(server.overworld());
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
            RIVERS_READY.set(false);
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
        var services = find(server);
        if (services != null) {
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
     * Called from RoadWeaver's river-prewarm return, i.e. once the rivers exist and
     * before any road is built.
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
        // Retained so a session that registers after the rivers finish still receives the
        // signal; the constructor replays it. request() is separate and marks the prewarm as
        // coming so a peer polling on it waits rather than concluding none will run.
        RIVERS_READY.set(true);
        PrewarmProgress.request();
        for (var server : SESSIONS.keySet()) {
            var services = SESSIONS.get(server);
            if (services == null || services.production == null) continue;
            services.production.riversPrewarmed();
        }
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
     * @return true if the wall published, failed, or will not run; false if the wait timed out
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
        return PrewarmProgress.awaitWorldgenReadyStrict(timeout, unit);
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
        // No test-mode guard. It used to return here unless the S1C fixture or the S0 probe was
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
        server.managedBlock(() -> {
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
            if (!painted[0]) {
                painted[0] = true;
                try {
                    local.plaincraft.greatwall.generation.WallBodyBackfill
                            .paintPendingAtStartup(server.overworld());
                } catch (Throwable paintFailed) {
                    GreatWallMod.LOGGER.error("[GreatWall:S1C] WALL_BACKFILL_STARTUP_FAILED", paintFailed);
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
