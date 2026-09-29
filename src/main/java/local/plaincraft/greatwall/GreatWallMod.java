package local.plaincraft.greatwall;

import local.plaincraft.greatwall.diagnostics.RuntimeVersions;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GreatWallMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(BuildContract.MOD_ID);

    @Override
    public void onInitialize() {
        TerrainMarkers.register();
        WorldServices.register();
        LOGGER.info("[GreatWall:S0-01] Common entrypoint loaded; world generation disabled");
        RuntimeVersions.capture().forEach(version -> LOGGER.info(
                "[GreatWall:version] {}={}", version.id(), version.version()));
    }
}
