package local.plaincraft.greatwall.mixin;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Arrays stay inside the private NoiseChunk lane; no reference is published to planning results. */
@Mixin(targets="net.minecraft.world.level.levelgen.NoiseChunk$NoiseInterpolator")
public interface NoiseInterpolatorAccess {
    @Accessor("slice0") double[][] greatwall$slice0();
    @Accessor("slice1") double[][] greatwall$slice1();
    @Accessor("noiseFiller") DensityFunction greatwall$filler();
}
