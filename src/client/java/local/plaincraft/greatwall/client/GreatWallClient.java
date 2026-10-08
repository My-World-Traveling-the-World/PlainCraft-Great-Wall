package local.plaincraft.greatwall.client;

import java.util.concurrent.atomic.AtomicBoolean;
import local.plaincraft.greatwall.GreatWallMod;
import net.fabricmc.api.ClientModInitializer;

public final class GreatWallClient implements ClientModInitializer {
    /** Logged once: a broken button must not spam the log on every screen open. */
    private static final AtomicBoolean CONFIG_BUTTON_FAILURE_LOGGED = new AtomicBoolean();

    @Override
    public void onInitializeClient() {
        GreatWallMod.LOGGER.info("[GreatWall:S0-01] Client entrypoint loaded; overlay disabled");
    }

    /**
     * Reports a failure to add the world-creation config button.
     *
     * <p>Called from inside vanilla's screen construction, so it must never
     * throw: a cosmetic button is not worth failing world creation over. The
     * message is emitted once and then suppressed to keep the log readable.
     */
    public static void reportConfigButtonFailure(Throwable failure) {
        if (!CONFIG_BUTTON_FAILURE_LOGGED.compareAndSet(false, true)) return;
        GreatWallMod.LOGGER.warn("[GreatWall:config] could not add the world-creation config button; "
                + "the wall still generates with its saved settings", failure);
    }
}
