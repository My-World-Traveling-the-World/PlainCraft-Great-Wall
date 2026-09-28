package local.plaincraft.greatwall.smoke;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import local.plaincraft.greatwall.GreatWallMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;

public final class ClientSmoke implements ClientModInitializer {
    private int stableTicks;
    private boolean finished;

    @Override
    public void onInitializeClient() {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (finished) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Client smoke timed out before the title screen");
            }
            if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) {
                stableTicks = 0;
                return;
            }
            if (++stableTicks < 20) {
                return;
            }
            finished = true;
            // Synchronous test-only capture allows the report to survive a clean shutdown.
            var screenshot = client.gameDirectory.toPath().resolve("client-smoke.png");
            try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
                image.writeToFile(screenshot);
                Files.writeString(client.gameDirectory.toPath().resolve("client-smoke-passed.txt"),
                        "PASS: main/client entrypoints, resource reload and title screen\n");
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot write client smoke evidence", exception);
            }
            GreatWallMod.LOGGER.info("[GreatWall:S0-01] CLIENT_SMOKE_PASS: title screen ready");
            client.stop();
        });
    }
}
