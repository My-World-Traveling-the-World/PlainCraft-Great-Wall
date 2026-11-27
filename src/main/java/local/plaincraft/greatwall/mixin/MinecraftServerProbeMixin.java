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
        MinecraftServer server = (MinecraftServer) (Object) this;
        // 河流已完成时仍在加载画面内等待长城；河流尚未开始时不能堵住其他 HEAD。
        WorldServices.runWallStartup(server, false);
    }

    // 所有 HEAD 已执行，而原版尚未请求出生区块，不能等到 TAIL 才首次安装长城计划。
    @Inject(method = "prepareLevels", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;overworld()Lnet/minecraft/server/level/ServerLevel;", ordinal = 0), require = 1)
    private void greatwall$beforeSpawnChunks(CallbackInfo callback) {
        WorldServices.runWallStartup((MinecraftServer) (Object) this, true);
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
        MinecraftServer server = (MinecraftServer) (Object) this;
        WorldServices.afterRoadReached(server);
    }
}
