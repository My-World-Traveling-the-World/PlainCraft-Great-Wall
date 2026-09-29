package local.plaincraft.greatwall.mixin;

import local.plaincraft.greatwall.compat.c2me.C2meProbeAdapter;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "local.plaincraftc2meqos.FeatureOrderCycleFallback", remap = false)
public abstract class C2meFeatureSkipMixin {
    @Inject(method = "advanceWithoutDecoration(Ljava/lang/Object;Ljava/lang/Object;)V", at = @At("HEAD"), remap = false)
    private static void greatwall$rejectIncompleteT0(Object delegate, Object context, CallbackInfo callback) {
        var level = ((ChunkMapAccess) C2meProbeAdapter.invoke(context, "tacs")).greatwall$level();
        var service = WorldServices.find(level.getServer());
        if (service != null && service.barrier(level) != null) {
            throw new IllegalStateException("T0_COMPAT_FEATURE_SKIPPED: QoS decoration skip is incompatible with T0");
        }
    }
}
