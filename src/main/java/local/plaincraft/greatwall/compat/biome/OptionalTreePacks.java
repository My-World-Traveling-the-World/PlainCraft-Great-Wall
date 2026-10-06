package local.plaincraft.greatwall.compat.biome;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the bundled data packs that carry the Great Wall biome identity.
 *
 * <h2>Why a built-in pack rather than loose resources</h2>
 * The identity, the injector and the density function must travel together. Putting them in one
 * built-in pack keeps them atomic: either the whole identity exists or none of it does. Splitting
 * them across the main resource tree would let a partially-applied state exist, where the injector
 * references a biome that was never registered.
 *
 * <h2>Why this is additive and cannot block generation</h2>
 * The pack only adds data. If it is absent, disabled or fails to register, no biome key resolves,
 * and the runtime reports the identity as absent and stays off — the wall still generates exactly
 * as it did before this feature existed. Nothing here throws into the world-load path.
 */
public final class OptionalTreePacks {
    private static final Logger LOGGER = LoggerFactory.getLogger("plaincraft_great_wall/biome-packs");
    /** Sub-path inside the mod jar, as Fabric requires. */
    private static final String PACK_PATH = "great_wall_biome";

    private OptionalTreePacks() {}

    /**
     * Registers the biome data pack. Idempotent; safe to call once from the common entrypoint.
     *
     * <p>Activation is deliberately {@link ResourcePackActivationType#NORMAL}, i.e. opt-in. The pack
     * changes which biomes a world may contain, which changes the world recipe fingerprint; enabling
     * it by default would make every pre-existing world refuse to load with a recipe mismatch. An
     * operator enables it explicitly for a new world instead. Do not flip this to
     * {@code DEFAULT_ENABLED} without also accepting that every existing save becomes unloadable.
     */
    public static void register() {
        var loader = FabricLoader.getInstance();
        var container = loader.getModContainer("plaincraft_great_wall").orElse(null);
        if (container == null) {
            LOGGER.warn("Own mod container is unavailable; the Great Wall biome pack was not registered.");
            return;
        }
        var id = ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", PACK_PATH);
        boolean registered = ResourceManagerHelper.registerBuiltinResourcePack(
                id, container, ResourcePackActivationType.NORMAL);
        if (registered) {
            LOGGER.info("Great Wall biome data pack registered as NORMAL (opt-in) ({}).", id);
        } else {
            // A duplicate registration is not fatal: the pack is already present.
            LOGGER.warn("Great Wall biome data pack {} was already registered; keeping the first registration.", id);
        }
    }
}
