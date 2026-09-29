package local.plaincraft.greatwall.generation;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.mixin.ChunkMapAccess;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Vanilla's serial worldgen mailbox, not a fabricated C2ME-style region lock. */
public final class VanillaTerrainEngine implements TerrainEngine {
    private final ChunkMapAccess access;
    public VanillaTerrainEngine(ServerLevel level) { access = (ChunkMapAccess) level.getChunkSource().chunkMap; }
    @Override public <T> CompletableFuture<T> exclusive(ChunkPos owner, Supplier<T> action) {
        var result = new CompletableFuture<T>();
        access.greatwall$worldgenMailbox().tell(ChunkTaskPriorityQueueSorter.message(() -> {
            StageTrace.enterLockAction();
            try { result.complete(action.get()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
            finally { StageTrace.leaveLockAction(); }
        }, owner.toLong(), () -> 0));
        return result;
    }
    @Override public String name() { return "vanilla-worldgen-mailbox"; }
}
