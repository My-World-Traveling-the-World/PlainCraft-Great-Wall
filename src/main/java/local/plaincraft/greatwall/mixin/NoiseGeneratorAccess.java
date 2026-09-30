package local.plaincraft.greatwall.mixin;

import java.util.function.Supplier;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Freeze the actual supplier after registry/LOAD initialization, including Tectonic's replacement. */
@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseGeneratorAccess {
    @Accessor("globalFluidPicker") Supplier<Aquifer.FluidPicker> greatwall$fluidPicker();
}
