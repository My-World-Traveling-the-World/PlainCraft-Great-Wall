package local.plaincraft.greatwall.smoke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class ServerSmoke implements ModInitializer {
    private int ticks;
    private boolean started;
    private boolean stopRequested;
    private String evidence;
    private int reloadRound;
    private final RuntimeEntryFixture entries = new RuntimeEntryFixture();
    private final SamplerParityFixture parity = new SamplerParityFixture();
    private final TerrainBarrierFixture terrain = new TerrainBarrierFixture();
    private final RealTerrainFixture wall = new RealTerrainFixture();
    private final RootHydrologyFixture survey = new RootHydrologyFixture();
    /** Lazily created on first tick when the S5 stress profile is selected. */
    private local.plaincraft.greatwall.stress.StressFixture stressFixture;

    @Override
    public void onInitialize() {
        TerrainBarrierFixture.register();
        Path directory = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        if (!Files.isRegularFile(directory.resolve("greatwall_test_world"))) {
            throw new IllegalStateException("Smoke requires an explicit isolated test-world marker");
        }
        String profile = System.getProperty("greatwall.smoke.profile", "vanilla");
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!(server instanceof DedicatedServer)) {
                throw new IllegalStateException("Expected an ordinary DedicatedServer, not GameTestServer");
            }
            var loader = FabricLoader.getInstance();
            if (!profile.equals("vanilla") && !loader.isModLoaded("tectonic")) {
                throw new IllegalStateException("Missing Tectonic in " + profile);
            }
            if ((profile.equals("tectonic-c2me") || profile.equals("pack-opencl"))
                    && !loader.isModLoaded("c2me")) {
                throw new IllegalStateException("Missing C2ME in " + profile);
            }
            if (profile.equals("pack-opencl") && !loader.isModLoaded("c2me-opts-accel-opencl")) {
                throw new IllegalStateException("Missing OpenCL in full pack smoke");
            }
            if (Boolean.getBoolean("greatwall.s0.disableRewrite")) {
                if (!loader.isModLoaded("c2me") || !loader.isModLoaded("c2me-base")
                        || loader.isModLoaded("c2me-rewrites-chunk-system") || loader.isModLoaded("c2me-notickvd")) {
                    throw new IllegalStateException("Rewrite-disable capability fixture has wrong module set");
                }
                GreatWallMod.LOGGER.info("[GreatWall:S0-02] REWRITE_DISABLED_LOADER: C2ME present, rewrite/notickvd absent; original JAR unchanged");
            }
            var level = server.overworld();
            evidence = "serverClass=" + server.getClass().getName() + "\n"
                    + "profile=" + profile + "\nseed=" + level.getSeed() + "\n"
                    + "minY=" + level.getMinBuildHeight() + "\n"
                    + "maxYExclusive=" + level.getMaxBuildHeight() + "\n"
                    + "generator=" + level.getChunkSource().getGenerator().getClass().getName() + "\n"
                    + "loadedChunks=" + level.getChunkSource().getLoadedChunksCount() + "\n"
                    + "mods=" + loader.getAllMods().size() + "\n";
            started = true;
            GreatWallMod.LOGGER.info("[GreatWall:S0-01] SERVER_SMOKE_STARTED {}", evidence);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!started || stopRequested) return;
            ticks++;
            if (Boolean.getBoolean("greatwall.s5.stress")) {
                if (!stress(server)) return;
            } else if (RootHydrologyFixture.enabled()) {
                if (!survey.tick(server)) return;
            } else if (Boolean.getBoolean("greatwall.s1c.productionFixture")) {
                if (!wall.tick(server)) return;
            } else if (Boolean.getBoolean("greatwall.smoke.samplerParity")) {
                if (!parity.tick(server, ticks)) return;
            } else if (Boolean.getBoolean("greatwall.s0.terrain")) {
                if (!terrain.tick(server, ticks)) return;
            } else if (Boolean.getBoolean("greatwall.s0.entries")) {
                if (!entries.tick(server, ticks)) return;
            } else if (!System.getProperty("greatwall.s0.reload", "none").equals("none")) {
                if (!reloadFixture(server)) return;
            } else if (ticks < 40) {
                return;
            }
            if (!stopRequested) {
                stopRequested = true;
                GreatWallMod.LOGGER.info("[GreatWall:S0-01] SERVER_SMOKE_STOP_REQUEST: ticks={}", ticks);
                server.halt(false);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { wall.close(); survey.close(); parity.close(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (!started || !stopRequested) {
                if (Boolean.getBoolean("greatwall.s0.expectedFailure")) {
                    GreatWallMod.LOGGER.info("[GreatWall:S0-02] EXPECTED_STARTUP_FAILURE: started={}", started);
                    return;
                }
                throw new IllegalStateException("Server stopped before smoke completion");
            }
            try {
                Files.writeString(directory.resolve("server-smoke-passed.txt"),
                        "PASS: DedicatedServer startup, " + ticks + " ticks and clean shutdown\n" + evidence);
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot persist smoke evidence", exception);
            }
            GreatWallMod.LOGGER.info("[GreatWall:S0-01] SERVER_SMOKE_PASS: stopped normally");
        });
    }

    /**
     * Drives the S5 stress profile. Returns false while the profile is still running and true once it
     * has terminated, so the shared stop request fires exactly as it does for the other fixtures.
     *
     * <p>The fixture is created lazily on the first tick so a missing parameter surfaces as a start
     * failure rather than a silently shortened run.
     */
    private boolean stress(MinecraftServer server) {
        if (stressFixture == null) {
            stressFixture = local.plaincraft.greatwall.stress.StressFixture
                    .fromSystemProperties(FabricLoader.getInstance().getGameDir());
            try {
                stressFixture.start(server);
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException("S5 stress profile failed to start", failure);
            }
        }
        return stressFixture.tick(server);
    }

    private boolean reloadFixture(MinecraftServer server) {
        if (ticks > 1200) throw new IllegalStateException("Reload fixture timed out");
        var services = WorldServices.find(server);
        if (services == null) throw new IllegalStateException("Reload fixture requires S0 probe");
        long accepted = services.counts().getOrDefault("RECIPE_ACCEPTED", 0L);
        try {
            Path fixture = server.getWorldPath(LevelResource.ROOT).resolve("datapacks/greatwall-s0/data/plaincraft_great_wall");
            if (reloadRound == 0 && ticks >= 10) {
                reloadRound = 1;
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
            } else if (reloadRound == 1 && accepted >= 1) {
                Path text = fixture.resolve("advancement/text_probe.json");
                Files.createDirectories(text.getParent());
                Files.writeString(text, "{\"criteria\":{\"never\":{\"trigger\":\"minecraft:impossible\"}},"
                        + "\"display\":{\"icon\":{\"id\":\"minecraft:stone\"},\"title\":\"Changed display text\","
                        + "\"description\":\"S0 non-generation text\",\"show_toast\":false,\"announce_to_chat\":false}}");
                reloadRound = 2;
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
            } else if (reloadRound == 2 && accepted >= 2) {
                if (System.getProperty("greatwall.s0.reload").equals("stable")) return true;
                Files.writeString(fixture.resolve("worldgen/noise/probe_noise.json"), "{\"firstOctave\":-4,\"amplitudes\":[1.0]}");
                reloadRound = 3;
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
            } else if (reloadRound == 3 && services.recipeRejected()) {
                var rejected = services.require("minecraft:overworld", 512, 0, "rejected-fixture");
                if (!rejected.isCompletedExceptionally()) {
                    throw new IllegalStateException("Changed world recipe still accepted new generation");
                }
                if (Boolean.getBoolean("greatwall.s0.reloadWorldgen")) {
                    reloadRound = 4;
                    GreatWallMod.LOGGER.info("[GreatWall:S0-02] RELOAD_WORLDGEN_REQUEST: cold load with retained ticket after rejection");
                    RuntimeEntryFixture.requestRejectedGeneration(server);
                    return false;
                }
                GreatWallMod.LOGGER.info("[GreatWall:S0-02] RELOAD_FIXTURE_PASS: stable/text accepted; geometry rejected");
                return true;
            } else if (reloadRound == 4 && ticks > 500) {
                throw new IllegalStateException("Rejected native generation did not abort visibly and promptly");
            }
            return false;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot mutate isolated reload fixture", failure);
        }
    }
}
