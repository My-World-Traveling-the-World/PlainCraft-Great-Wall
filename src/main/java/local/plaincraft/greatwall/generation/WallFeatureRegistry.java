package local.plaincraft.greatwall.generation;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import local.plaincraft.greatwall.BuildContract;

/**
 * Registers the wall's vanilla-feature landing entry, mirroring RoadWeaver's RoadFeatureRegistry:
 * the wall is a {@code Feature<NoneFeatureConfiguration>} placed in {@code TOP_LAYER_MODIFICATION},
 * so vanilla dispatches it per chunk like any ore/tree and the wall never blocks on a neighbour.
 */
public final class WallFeatureRegistry {
    private WallFeatureRegistry() {
    }

    public static void register() {
        Feature<NoneFeatureConfiguration> feature = new WallFeature();
        Registry.register(BuiltInRegistries.FEATURE,
                ResourceLocation.fromNamespaceAndPath(BuildContract.MOD_ID, "wall_feature"), feature);

        ResourceKey<PlacedFeature> placedKey = ResourceKey.create(
                Registries.PLACED_FEATURE,
                ResourceLocation.fromNamespaceAndPath(BuildContract.MOD_ID, "wall_feature_placed"));
        BiomeModifications.addFeature(
                BiomeSelectors.foundInOverworld(),
                GenerationStep.Decoration.TOP_LAYER_MODIFICATION,
                placedKey);
    }
}
