package local.plaincraft.greatwall.mixin;

import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MinecraftServer.class, priority = 950)
public abstract class MinecraftServerProbeMixin {
    @Inject(method = "prepareLevels", at = @At("HEAD"))
    private void greatwall$startupWarmup(CallbackInfo callback) {
        WorldServices.warming((MinecraftServer) (Object) this);
    }

    /**
     * Releases the wall's bulk prewarm once the blocking startup peer has returned.
     *
     * <p>RoadWeaver performs its initial road generation at this same method's HEAD, so a TAIL
     * observation is ordered strictly after it. Using TAIL rather than a second HEAD makes the
     * release independent of mixin ordering between the two mods.
     */
    @Inject(method = "prepareLevels", at = @At("TAIL"))
    private void greatwall$afterRoadBulk(CallbackInfo callback) {
        WorldServices.afterRoadReached((MinecraftServer) (Object) this);
    }
}
