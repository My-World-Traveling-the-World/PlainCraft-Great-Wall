package local.plaincraft.greatwall.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ProcessorHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkMap.class)
public interface ChunkMapAccess {
    @Accessor("level") ServerLevel greatwall$level();
    @Accessor("worldgenMailbox")
    ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> greatwall$worldgenMailbox();
}
