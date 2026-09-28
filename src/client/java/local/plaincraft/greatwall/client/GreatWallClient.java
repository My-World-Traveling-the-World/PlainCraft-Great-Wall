package local.plaincraft.greatwall.client;

import local.plaincraft.greatwall.GreatWallMod;
import net.fabricmc.api.ClientModInitializer;

public final class GreatWallClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        GreatWallMod.LOGGER.info("[GreatWall:S0-01] Client entrypoint loaded; overlay disabled");
    }
}

