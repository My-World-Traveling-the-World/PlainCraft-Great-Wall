package local.plaincraft.greatwall.smoke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import local.plaincraft.greatwall.GreatWallMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.dedicated.DedicatedServer;

public final class ServerSmoke implements ModInitializer {
    private int ticks;
    private boolean started;
    private boolean stopRequested;
    private String evidence;

    @Override
    public void onInitialize() {
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
            if (started && !stopRequested && ++ticks >= 40) {
                stopRequested = true;
                GreatWallMod.LOGGER.info("[GreatWall:S0-01] SERVER_SMOKE_STOP_REQUEST: ticks={}", ticks);
                server.halt(false);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (!started || !stopRequested) {
                throw new IllegalStateException("Server stopped before smoke completion");
            }
            try {
                Files.writeString(directory.resolve("server-smoke-passed.txt"),
                        "PASS: DedicatedServer startup, 40 ticks and clean shutdown\n" + evidence);
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot persist smoke evidence", exception);
            }
            GreatWallMod.LOGGER.info("[GreatWall:S0-01] SERVER_SMOKE_PASS: stopped normally");
        });
    }
}
