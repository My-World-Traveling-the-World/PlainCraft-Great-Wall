package local.plaincraft.greatwall;

import local.plaincraft.greatwall.diagnostics.RuntimeVersions;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GreatWallMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger(BuildContract.MOD_ID);

    @Override
    public void onInitialize() {
        TerrainMarkers.register();
        local.plaincraft.greatwall.generation.OwnershipMutations.register();
        WorldServices.register();
        local.plaincraft.greatwall.diagnostics.DebugPayload.registerType();
        // Soft-dependency RoadWeaver bridge: guard class loading with Loader before touching
        // the optional interop types, exactly as RoadWeaverAdapter's own docs require.
        if (FabricLoader.getInstance().isModLoaded("roadweaver")) {
            local.plaincraft.greatwall.compat.road.RoadWeaverAdapter.initializeIfPresent();
        }
        // Process-once soft dependency: the core never references FTB types in its static
        // surface, and FtbIntegration is only reachable when the optional mod is present.
        if (FabricLoader.getInstance().isModLoaded("ftbquests")) {
            local.plaincraft.greatwall.compat.ftb.FtbIntegration.initialize();
        }
        LOGGER.info("[GreatWall:S0-01] Common entrypoint loaded; world generation disabled");
        RuntimeVersions.capture().forEach(version -> LOGGER.info(
                "[GreatWall:version] {}={}", version.id(), version.version()));
    }
}
