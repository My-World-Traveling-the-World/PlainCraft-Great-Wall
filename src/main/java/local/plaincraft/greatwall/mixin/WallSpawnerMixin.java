package local.plaincraft.greatwall.mixin;

import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels vanilla {@code serverTick} only for a certified wall-owned spawner;
 * every other cage, patrol and raid keeps its original behaviour. The client
 * still observes the vanilla sync animation and never makes a birth decision
 * here.
 */
@Mixin(BaseSpawner.class)
public abstract class WallSpawnerMixin {
    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true, require = 1)
    private void greatwall$tick(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        var services = WorldServices.find(level.getServer());
        if (services == null) return;
        if (services.spawners().tickOwned((BaseSpawner) (Object) this, level, pos)) {
            ci.cancel();
        }
    }
}
