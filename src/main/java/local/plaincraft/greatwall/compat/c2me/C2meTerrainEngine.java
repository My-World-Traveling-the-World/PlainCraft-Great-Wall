package local.plaincraft.greatwall.compat.c2me;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.generation.TerrainEngine;
import net.minecraft.world.level.ChunkPos;

/** Calls the pinned engine's actual WORLDGEN locks; reflection never names mapped Minecraft methods. */
public final class C2meTerrainEngine implements TerrainEngine {
    private final Object manager;
    public C2meTerrainEngine(Object manager) { this.manager = manager; }
    @Override @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> exclusive(ChunkPos owner, Supplier<T> action) {
        try {
            Class<?> delegate = Class.forName("com.ishland.c2me.rewrites.chunksystem.common.statuses.VanillaWorldGenerationDelegate");
            Class<?> scheduler = Class.forName("com.ishland.c2me.base.common.scheduler.SchedulingManager");
            Supplier<CompletableFuture<T>> locked = () -> {
                StageTrace.enterLockAction();
                try { return CompletableFuture.completedFuture(action.get()); }
                catch (Throwable failure) { return CompletableFuture.failedFuture(failure); }
                finally { StageTrace.leaveLockAction(); }
            };
            return (CompletableFuture<T>) delegate.getMethod("runTaskWithLockArea", int.class, int.class,
                    int.class, int.class, scheduler, Supplier.class).invoke(null, owner.x, owner.z, 1, 1, manager, locked);
        } catch (InvocationTargetException failure) { return CompletableFuture.failedFuture(failure.getCause()); }
        catch (ReflectiveOperationException failure) { return CompletableFuture.failedFuture(failure); }
    }
    @Override public String name() { return "c2me-WORLDGEN-1x1"; }
}
