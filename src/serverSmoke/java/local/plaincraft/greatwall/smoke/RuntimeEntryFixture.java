package local.plaincraft.greatwall.smoke;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Consumer;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.smoke.mixin.ChunkFutureAccess;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/** Only in the smoke JAR. All mutable fixture state belongs to the server thread. */
final class RuntimeEntryFixture {
    private static final TicketType<ChunkPos> TICKET = TicketType.create("greatwall_s0_smoke", Comparator.comparingLong(ChunkPos::toLong));
    private int phase;
    private int startedAt;
    private boolean ready;
    private Throwable failure;

    boolean tick(MinecraftServer server, int ticks) {
        if (ticks < 10) return false;
        if (startedAt == 0) startedAt = ticks;
        if (ticks - startedAt > 2200) throw new IllegalStateException("Runtime entry fixture timed out");
        if (failure != null) throw new IllegalStateException("Runtime entry fixture failed", failure);
        if (phase == 0) {
            phase = 1;
            startChunky(server);
        } else if (phase == 1 && ready) {
            assertGate(server, 300000, 0);
            GreatWallMod.LOGGER.info("[GreatWall:S0-02] CHUNKY_ENTRY_PASS center=300000,0");
            ready = false;
            phase = 2;
            teleport(server, 100000);
        } else if (phase == 2 && ready) {
            ready = false;
            phase = 3;
            teleport(server, 1000000);
        } else if (phase == 3 && ready) {
            GreatWallMod.LOGGER.info("[GreatWall:S0-02] RUNTIME_ENTRIES_PASS Chunky=9 chunks teleport=100000,1000000");
            return true;
        }
        return false;
    }

    private void startChunky(MinecraftServer server) {
        var container = FabricLoader.getInstance().getEntrypointContainers("main", ModInitializer.class).stream()
                .filter(entry -> entry.getProvider().getMetadata().getId().equals("chunky")).findFirst().orElseThrow();
        try {
            Object entrypoint = container.getEntrypoint();
            Object chunky = entrypoint.getClass().getMethod("getChunky").invoke(entrypoint);
            Object api = chunky.getClass().getMethod("getApi").invoke(chunky);
            // In pinned Chunky 1.4.23, onGenerationComplete fires after submission,
            // before the async chunk futures finish. Progress.complete follows callbacks.
            Consumer<Object> callback = event -> {
                try {
                    if ((boolean) event.getClass().getMethod("complete").invoke(event)) {
                        long count = (long) event.getClass().getMethod("chunks").invoke(event);
                        server.execute(() -> {
                            if (count != 9) failure = new IllegalStateException("Expected nine Chunky completions, got " + count);
                            ready = true;
                        });
                    }
                } catch (ReflectiveOperationException error) {
                    server.execute(() -> failure = error);
                }
            };
            api.getClass().getMethod("onGenerationProgress", Consumer.class).invoke(api, callback);
            boolean started = (boolean) api.getClass().getMethod("startTask", String.class, String.class,
                    double.class, double.class, double.class, double.class, String.class)
                    .invoke(api, "minecraft:overworld", "square", 300000.0, 0.0, 16.0, 16.0, "concentric");
            if (!started) throw new IllegalStateException("Actual Chunky task refused to start");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Pinned Chunky API unavailable", error);
        }
    }

    private void teleport(MinecraftServer server, int blockX) {
        var level = server.overworld();
        var source = level.getChunkSource();
        ChunkPos pos = new ChunkPos(Math.floorDiv(blockX, 16), 0);
        source.addRegionTicket(TICKET, pos, 0, pos);
        ((ChunkFutureAccess) source).greatwall$future(pos.x, pos.z, ChunkStatus.FULL, true)
                .whenCompleteAsync((result, error) -> {
                    try {
                        if (error != null) throw new IllegalStateException("Destination generation failed", error);
                        var chunk = result.orElse(null);
                        if (chunk == null) throw new IllegalStateException("Destination chunk unavailable");
                        assertGate(server, blockX, 0);
                        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "gw-smoke-player"), false);
                        ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation()) {
                            @Override public boolean isCreative() { return true; }
                        };
                        Connection connection = new Connection(PacketFlow.SERVERBOUND);
                        // Same embedded connection path as vanilla GameTestHelper.makeMockServerPlayerInLevel.
                        EmbeddedChannel channel = new EmbeddedChannel(connection);
                        try {
                                server.getPlayerList().placeNewPlayer(connection, player, cookie);
                                int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, 0) + 1;
                                player.teleportTo(level, blockX + 0.5, y, 0.5, 0, 0);
                                if (!player.blockPosition().equals(new BlockPos(blockX, y, 0))) {
                                    throw new IllegalStateException("Actual ServerPlayer teleport position mismatch");
                                }
                                GreatWallMod.LOGGER.info("[GreatWall:S0-02] TELEPORT_ENTRY_PASS player={} target={},{} prewarm=async", player.getUUID(), blockX, y);
                        } finally {
                            server.getPlayerList().remove(player);
                            channel.finishAndReleaseAll();
                        }
                        ready = true;
                    } catch (Throwable throwable) {
                        failure = throwable;
                    } finally {
                        source.removeRegionTicket(TICKET, pos, 0, pos);
                    }
                }, server);
    }

    private static void assertGate(MinecraftServer server, int blockX, int blockZ) {
        var services = WorldServices.find(server);
        if (services == null || !services.hasCompletedStage("minecraft:overworld", Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16))) {
            throw new IllegalStateException("Runtime generation bypassed the planning gate");
        }
    }

    static void requestRejectedGeneration(MinecraftServer server) {
        var source = server.overworld().getChunkSource();
        var pos = new ChunkPos(62500, 0);
        // This S0-02 fault fixture is a normal load, not S0-03's FEATURES-only PREPARE.
        source.addRegionTicket(TICKET, pos, 0, pos);
        ((ChunkFutureAccess) source).greatwall$future(pos.x, pos.z, ChunkStatus.FULL, true)
                .whenCompleteAsync((result, error) -> {
                    source.removeRegionTicket(TICKET, pos, 0, pos);
                    GreatWallMod.LOGGER.info("[GreatWall:S0-02] RELOAD_WORLDGEN_COMPLETION result={} error={}", result, error);
                }, server);
    }
}
