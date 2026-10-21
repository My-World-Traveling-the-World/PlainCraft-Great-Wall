package local.plaincraft.greatwall.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.CompletableFuture;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.generation.VanillaTerrainEngine;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
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
        if (services == null) {
            return original.call(holder, step, chunks);
        }
        var pos = holder.getPos();
        var dimension = access.greatwall$level().dimension().location().toString();
        var barrier = services.barrier(access.greatwall$level());
        java.util.concurrent.Executor executor = command -> access.greatwall$worldgenMailbox()
                .tell(ChunkTaskPriorityQueueSorter.message(holder, command));
        // The wall now lands through the registered WallFeature (decoration stage), not through this
        // applyStep gate. A production fixture no longer intercepts INITIALIZE_LIGHT / FEATURES here;
        // the only remaining gate is the S0 laboratory's barrier below.
        if (barrier != null && step.targetStatus() == ChunkStatus.INITIALIZE_LIGHT && barrier.layout().owns(pos)) {
            return barrier.beforeLight(pos, holder.getChunkIfPresentUnchecked(ChunkStatus.FEATURES),
                    new VanillaTerrainEngine(access.greatwall$level())).thenComposeAsync(ignored ->
                    original.call(holder, step, chunks), executor);
        }
        if (step.targetStatus() != ChunkStatus.FEATURES) return original.call(holder, step, chunks);
        // require() is the S0 laboratory's ProbePlanner entry and throws outright under a production
        // session ("Production cannot use ProbePlanner"). This mixin is registered in the main config, not
        // in a probe-only one, so a production chunk reaching FEATURES would hit that throw; the wall does
        // not need this gate in production anyway, it lands through WallFeature at the decoration stage.
        if (!services.probeOnly()) return original.call(holder, step, chunks);
        return services.require(dimension, pos.x, pos.z, "vanilla:features").thenCompose(plan -> barrier == null
                        ? CompletableFuture.completedFuture(null) : barrier.delayFeatures(pos))
                .<CompletableFuture<ChunkAccess>>handleAsync((plan, planningFailure) -> {
            if (planningFailure != null) return CompletableFuture.failedFuture(planningFailure);
            services.assertActiveSession();
            services.trace("STAGE_ENTER", dimension, pos.x, pos.z, "vanilla:features", "vanilla-mailbox");
            return original.call(holder, step, chunks).whenComplete((chunk, failure) ->
                    services.trace("STAGE_EXIT", dimension, pos.x, pos.z, "vanilla:features", "vanilla-mailbox"));
        }, executor)
                .thenCompose(result -> result);
    }
}
