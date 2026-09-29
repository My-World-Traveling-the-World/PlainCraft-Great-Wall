package local.plaincraft.greatwall.mixin;

import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerProbeMixin {
    @Inject(method = "prepareLevels", at = @At("HEAD"))
    private void greatwall$startupWarmup(CallbackInfo callback) {
        WorldServices.warming((MinecraftServer) (Object) this);
    }
}
