package local.plaincraft.greatwall.lifecycle;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.generation.WallStageCoordinator;
import local.plaincraft.greatwall.planning.ProbePlanner;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/** S0-only session wiring; opt-in requires a dedicated isolated-world marker. */
public final class WorldServices {
    private static final ConcurrentHashMap<MinecraftServer, WorldServices> SESSIONS = new ConcurrentHashMap<>();
    private final ProbePlanner planner;
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
            if (!Boolean.getBoolean("greatwall.s0.probe")) return;
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
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, successful) -> {
            var services = find(server);
            if (services == null || !successful) return;
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
                services.planner.close();
                services.barriers.values().forEach(WallStageCoordinator::close);
                // Vanilla can fail while saving errored generation; preserve proof before that call.
                services.writeEvidence("STOPPING");
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            var services = SESSIONS.remove(server);
            if (services != null) {
                services.planner.close();
                services.writeEvidence("STOPPED");
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, selection) -> dispatcher.register(
                Commands.literal("greatwall").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(context -> {
                            var services = find(context.getSource().getServer());
                            context.getSource().sendSuccess(() -> Component.literal(services == null
                                    ? "S0 probe disabled; wall generation is not implemented"
                                    : "S0 " + services.planner.state() + " " + services.failure + " " + services.planner.trace().counts()), false);
                            return 1;
                        }))));
    }

    private void openDimension(ServerLevel level) {
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
    public WallStageCoordinator barrier(ServerLevel level) { return barriers.get(level); }
    public static void warming(MinecraftServer server) {
        var services = find(server);
        if (services != null) {
            services.planner.transition(BootstrapState.RECIPE_READY, BootstrapState.WARMING);
            services.trace("STARTUP_WARMING", "*", 0, 0, "prepareLevels", "none");
        }
    }
    public void assertActiveSession() {
        if (!planner.accepting() || SESSIONS.get(server) != this) {
            throw new CancellationException("Closed or stale world session");
        }
    }
    public CompletableFuture<ProbePlanner.Plan> require(String dimension, int x, int z, String stage) {
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
        planner.trace().record(event, dimension, x, z, stage, token);
    }
    public Map<String, Long> counts() { return planner.trace().counts(); }
    public boolean hasCompletedStage(String dimension, int x, int z) {
        return planner.trace().snapshot().stream().anyMatch(event -> event.event().equals("STAGE_EXIT")
                && event.dimension().equals(dimension) && event.chunkX() == x && event.chunkZ() == z);
    }
    public boolean recipeRejected() { return failure.containsKey("reason"); }

    public void failBootstrap(Throwable cause) {
        BootstrapState current = planner.state();
        if (current != BootstrapState.BOOTSTRAP && current != BootstrapState.RECIPE_READY
                && current != BootstrapState.WARMING) return;
        failRequiredPlan(cause);
    }

    public void failRequiredPlan(Throwable cause) {
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
