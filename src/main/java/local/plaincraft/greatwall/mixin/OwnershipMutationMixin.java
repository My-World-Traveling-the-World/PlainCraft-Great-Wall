package local.plaincraft.greatwall.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import local.plaincraft.greatwall.generation.OwnershipMutations;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LevelChunk.class)
public abstract class OwnershipMutationMixin {
    @WrapMethod(method="setBlockState")
    private BlockState greatwall$observe(BlockPos pos,BlockState next,boolean moving,Operation<BlockState> original) {
        BlockState previous=original.call(pos,next,moving);
        if(previous!=null&&!previous.equals(next))
            OwnershipMutations.changed((LevelChunk)(Object)this,pos,previous,next);
        return previous;
    }
}
