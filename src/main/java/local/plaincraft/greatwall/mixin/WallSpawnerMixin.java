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
 *
 * <p>Block entities begin ticking as soon as their chunk loads, which can precede the journal
 * recovery and gameplay assembly that publish the spawner guard. So this asks for the guard through
 * the non-throwing accessor and falls through to vanilla behaviour while it is absent, rather than
 * demanding a service that legitimately does not exist yet.
 */
@Mixin(BaseSpawner.class)
public abstract class WallSpawnerMixin {
    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true, require = 1)
    private void greatwall$tick(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        var services = WorldServices.find(level.getServer());
        if (services == null) return;
        var guard = services.spawnersIfReady();
        if (guard == null) return;
        if (guard.tickOwned((BaseSpawner) (Object) this, level, pos)) {
            ci.cancel();
        }
    }
}
