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
import local.plaincraft.greatwall.generation.WallStageCoordinator;
import local.plaincraft.greatwall.planning.ProbePlanner;
import local.plaincraft.greatwall.planning.PlannerScheduler;
import local.plaincraft.greatwall.generation.VegetationGuard;
import local.plaincraft.greatwall.generation.TreeBoundsCatalog;
import local.plaincraft.greatwall.generation.TerrainEngine;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.storage.LevelResource;

/** The mode is fixed per server: S0 laboratory or separately authorized S1C fixture, never public production. */
public final class WorldServices {
    private static final ConcurrentHashMap<MinecraftServer, WorldServices> SESSIONS = new ConcurrentHashMap<>();
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
        if (Boolean.getBoolean("greatwall.s1c.productionFixture")) {
            if (Boolean.getBoolean("greatwall.s0.probe")) throw new IllegalStateException("S0 and S1C modes are mutually exclusive");
            planner = null;
            production = new ProductionSession(server, this);
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
            if (Boolean.getBoolean("greatwall.production")) {
                throw new IllegalStateException("Formal production remains OFF until S1C acceptance");
            }
            if (!Boolean.getBoolean("greatwall.s0.probe") && !Boolean.getBoolean("greatwall.s1c.productionFixture")) return;
            if (!Files.isRegularFile(FabricLoader.getInstance().getGameDir().resolve("greatwall_test_world"))) {
                throw new IllegalStateException("S0 probe is forbidden without an isolated test-world marker");
            }
            SESSIONS.put(server, new WorldServices(server));
        });
        ServerWorldEvents.LOAD.register((server, level) -> {
            var services = find(server);
            if (services != null) services.openDimension(level);
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            var service = find(level.getServer());
            if (service != null && service.barrier(level) != null) service.barrier(level).verifyLoaded(chunk);
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
                    services.production.close(); services.production.export("STOPPED"); return;
                }
                services.planner.close();
                services.writeEvidence("STOPPED");
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, selection) -> dispatcher.register(
                Commands.literal("greatwall").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(context -> {
                            var services = find(context.getSource().getServer());
                            context.getSource().sendSuccess(() -> Component.literal(services == null
                                    ? "Wall production OFF"
                                    : services.production != null ? "S1C isolated fixture; production OFF " + services.production.diagnostics()
                                    : "S0 " + services.planner.state() + " " + services.failure + " " + services.planner.trace().counts()), false);
                            return 1;
                        }))));
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

    public static WorldServices find(MinecraftServer server) { return SESSIONS.get(server); }
    public static Optional<WorldServices> find(UUID session) {
        return SESSIONS.values().stream().filter(value -> value.production != null
                && value.production.runtime().equals(session) && !value.production.rejected()).findFirst();
    }
    public boolean productionFixture() { return production != null; }
    public WorldRecipe recipe() { return requireProductionSession().recipe(); }
    public PlannerScheduler planning() { return requireProductionSession().planning(); }
    public WorldBootstrap bootstrap() { return requireProductionSession().bootstrap(); }
    public VegetationGuard vegetation() { return requireProductionSession().vegetation(); }
    public TreeBoundsCatalog trees() { return requireProductionSession().trees(); }
    public CompletableFuture<WorldRecipe> recipeReady() { return requireProductionSession().recipeReady(); }
    public Map<String, Object> productionDiagnostics() { return requireProductionSession().diagnostics(); }
    public WallStageCoordinator barrier(ServerLevel level) {
        return production == null ? barriers.get(level) : production.barrier(level);
    }
    public PlanLease<Void> featureLease(String dimension, int x, int z) {
        assertActiveSession();
        trace("WAIT_PLAN_NO_LOCK", dimension, x, z, "production:features", "none");
        return requireProductionSession().featureLease(new ChunkKey(dimension, x, z));
    }
    public CompletableFuture<Void> requireProduction(String dimension, int x, int z, String stage) {
        var lease = featureLease(dimension, x, z);
        return lease.future().whenComplete((value, error) -> lease.close());
    }
    public CompletableFuture<Void> beforeProductionLight(ChunkKey owner, ChunkAccess chunk, TerrainEngine engine) {
        assertActiveSession();
        return requireProductionSession().beforeLight(owner, chunk, engine);
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
            if (services.production != null) { services.production.warming(); return; }
            services.planner.transition(BootstrapState.RECIPE_READY, BootstrapState.WARMING);
            services.trace("STARTUP_WARMING", "*", 0, 0, "prepareLevels", "none");
        }
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
