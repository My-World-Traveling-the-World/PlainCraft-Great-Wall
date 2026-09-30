package local.plaincraft.greatwall.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.reactivex.rxjava3.core.Completable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import local.plaincraft.greatwall.compat.c2me.C2meProbeAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.VanillaWorldGenerationDelegate", remap = false)
public abstract class C2meGateMixin {
    @Inject(method = "upgradeToThis(Lcom/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext;Lcom/ishland/flowsched/scheduler/Cancellable;)Lio/reactivex/rxjava3/core/Completable;",
            at = @At("RETURN"), cancellable = true, remap = false)
    private void greatwall$beforeLock(@Coerce Object context, @Coerce Object cancellation,
                                     CallbackInfoReturnable<Completable> callback) {
        callback.setReturnValue(C2meProbeAdapter.gate(context, this, callback.getReturnValue()));
    }

    @WrapMethod(method = "runTaskWithLockArea", remap = false)
    private static <T> CompletableFuture<T> greatwall$lockWitness(int x, int z, int sizeX, int sizeZ,
            @Coerce Object manager, Supplier<CompletableFuture<T>> action,
            Operation<CompletableFuture<T>> original) {
        return C2meProbeAdapter.schedule(() -> original.call(x, z, sizeX, sizeZ, manager,
                C2meProbeAdapter.instrument(action, x + "," + z + ":" + sizeX + "x" + sizeZ)));
    }
}
