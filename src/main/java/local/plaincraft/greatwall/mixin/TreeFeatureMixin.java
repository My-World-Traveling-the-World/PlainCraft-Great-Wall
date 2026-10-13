package local.plaincraft.greatwall.mixin;

import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.DimensionBounds;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TreeFeature.class)
public abstract class TreeFeatureMixin {
    @Inject(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void greatwall$guard(FeaturePlaceContext<TreeConfiguration> context,
            CallbackInfoReturnable<Boolean> callback) {
        // Player bone meal uses ServerLevel, not a native FEATURES WorldGenRegion.
        if (!(context.level() instanceof WorldGenRegion region)) return;
        var services = WorldServices.find(region.getLevel().getServer());
        if (services == null || !services.productionFixture()) return;
        var center = region.getCenter();
        var owner = new ChunkKey(region.getLevel().dimension().location().toString(), center.x, center.z);
        if (!owner.dimension().equals("minecraft:overworld") || services.bootstrap().mayPassBeforeRecipe(owner)) return;
        // Spatial pre-check, and it is the load-bearing one: the guard's snapshots only exist for
        // the prewarmed window's chunks, so asking outside that band is a guaranteed miss. A chunk
        // that cannot reach any published corridor band (inflated by the widest tree) cannot need
        // suppression, so it passes here and the worldgen thread never queries a missing snapshot.
        if (!services.vegetation().mayAffect(owner, services.trees().maximumRadius() + 1)) return;
        var bounds = new DimensionBounds(region.getMinBuildHeight(), region.getMaxBuildHeight(), 29_999_000);
        if (services.vegetation().intersects(owner, services.trees().vanilla(context.origin(), context.config(), bounds))) {
            services.trace("VEGETATION_SUPPRESSED", owner.dimension(), owner.x(), owner.z(), "tree:complete-bounds", "readonly");
            callback.setReturnValue(false);
        }
    }
}
