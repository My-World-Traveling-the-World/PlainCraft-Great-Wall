package local.plaincraft.greatwall.generation;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;

/** Short synchronous work inside the engine's own authorization; never wait in the action. */
public interface TerrainEngine {
    <T> CompletableFuture<T> exclusive(ChunkPos owner, Supplier<T> action);
    String name();
}
