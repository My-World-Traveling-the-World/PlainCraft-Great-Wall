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
        // Registers the bundled data pack carrying the dedicated Great Wall biome identity. This is
        // additive data only: if it fails to register, no biome key resolves and generation proceeds
        // exactly as before.
        local.plaincraft.greatwall.compat.biome.OptionalTreePacks.register();
        WorldServices.register();
        local.plaincraft.greatwall.diagnostics.DebugPayload.registerType();
        // Register the vanilla-feature landing entry (mirrors RoadWeaver). The feature reads the
        // planning-compiled ChunkWritePlan from WallWorldgenPlanCache and paints it; no neighbour
        // reads, no futures.
        local.plaincraft.greatwall.generation.WallFeatureRegistry.register();
        // RoadWeaver interop is disabled: the wall no longer verifies a RoadWeaver ABI allowlist
        // and never registers a wall obstacle provider or gate-height agreement. The wall and the
        // roads generate independently, with the wall still owning the main thread for its own
        // prewarm so the three startup stages stay serial.
        // Process-once soft dependency: the core never references FTB types in its static
        // surface, and FtbIntegration is only reachable when the optional mod is present.
        if (FabricLoader.getInstance().isModLoaded("ftbquests")) {
            local.plaincraft.greatwall.compat.ftb.FtbIntegration.initialize();
        }
        LOGGER.info("[GreatWall:S0-01] Common entrypoint loaded; world generation disabled");
        // One line, not 767. This list was printed one INFO line per mod on every launch: measured
        // 767 lines / ~100 KB in a single session log, for a list nothing reads at INFO. The digest
        // the wall actually depends on is printed by the fingerprint stage; the full list is one
        // DEBUG line away when a terrain-mismatch diagnosis needs it.
        var versions = RuntimeVersions.capture();
        if (LOGGER.isDebugEnabled()) {
            versions.forEach(version -> LOGGER.debug(
                    "[GreatWall:version] {}={}", version.id(), version.version()));
        }
        LOGGER.info("[GreatWall:version] captured {} mod versions (full list at DEBUG)", versions.size());
    }
}
