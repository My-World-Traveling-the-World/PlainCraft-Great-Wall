package local.plaincraft.greatwall.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.CompletableFuture;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChunkMap.class)
public abstract class VanillaGateMixin {
    @WrapMethod(method = "applyStep")
    private CompletableFuture<ChunkAccess> greatwall$gate(GenerationChunkHolder holder, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> chunks, Operation<CompletableFuture<ChunkAccess>> original) {
        var access = (ChunkMapAccess) this;
        var services = WorldServices.find(access.greatwall$level().getServer());
        if (services == null || step.targetStatus() != ChunkStatus.FEATURES) {
            return original.call(holder, step, chunks);
        }
        var pos = holder.getPos();
        var dimension = access.greatwall$level().dimension().location().toString();
        return services.require(dimension, pos.x, pos.z, "vanilla:features")
                .<CompletableFuture<ChunkAccess>>handleAsync((plan, planningFailure) -> {
            if (planningFailure != null) return CompletableFuture.failedFuture(planningFailure);
            services.assertActiveSession();
            services.trace("STAGE_ENTER", dimension, pos.x, pos.z, "vanilla:features", "vanilla-mailbox");
            return original.call(holder, step, chunks).whenComplete((chunk, failure) ->
                    services.trace("STAGE_EXIT", dimension, pos.x, pos.z, "vanilla:features", "vanilla-mailbox"));
        }, command -> access.greatwall$worldgenMailbox().tell(ChunkTaskPriorityQueueSorter.message(holder, command)))
                .thenCompose(result -> result);
    }
}
