package local.plaincraft.greatwall.smoke.mixin;

import local.plaincraft.greatwall.GreatWallMod;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 仅隔离烟测按需观察原版卸载重排，不取消任务、不改引用计数，也不进入成品。 */
@Mixin(ChunkMap.class)
abstract class ChunkUnloadTraceMixin {
    @Unique private long greatwall$unreadyUnloads;

    @Inject(method="scheduleUnload",at=@At("HEAD"))
    private void greatwall$traceUnload(long key,ChunkHolder holder,CallbackInfo callback) {
        if(!Boolean.getBoolean("greatwall.smoke.traceUnload")||!holder.getSaveSyncFuture().isDone()
                ||holder.getGenerationRefCount()==0)return;
        long count=++greatwall$unreadyUnloads;
        // 只在二次幂记录，空转数百万次时也只产生几十行证据，避免日志改变现场负载。
        if((count&(count-1))==0)GreatWallMod.LOGGER.warn("[GreatWall:卸载诊断] retries={} chunk={} generationRefs={} saveDone={} saveFailed={}",
                count,holder.getPos(),holder.getGenerationRefCount(),holder.getSaveSyncFuture().isDone(),holder.getSaveSyncFuture().isCompletedExceptionally());
    }
}
