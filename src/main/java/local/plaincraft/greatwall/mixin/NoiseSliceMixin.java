package local.plaincraft.greatwall.mixin;

import java.util.List;
import local.plaincraft.greatwall.terrain.NoiseCursorBridge;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Opt-in private oracle cursor. Does not overwrite native worldgen interpolation or fillArray. */
@Mixin(NoiseChunk.class)
public abstract class NoiseSliceMixin implements NoiseCursorBridge {
    @Shadow @Final private int firstCellX;
    @Shadow @Final private int firstCellZ;
    @Shadow @Final int cellCountXZ;
    @Shadow @Final int cellCountY;
    @Shadow @Final int cellWidth;
    @Shadow @Final List<?> interpolators;
    @Shadow @Final private DensityFunction.ContextProvider sliceFillingContextProvider;
    @Shadow boolean interpolating;
    @Shadow private int cellStartBlockX;
    @Shadow private int cellStartBlockZ;
    @Shadow int inCellX;
    @Shadow int inCellZ;
    @Shadow long interpolationCounter;
    @Shadow long arrayInterpolationCounter;
    @Unique private int greatwall$side,greatwall$z,greatwall$interpolator,greatwall$y;
    @Unique private Thread greatwall$owner;
    @Unique private boolean greatwall$initialized,greatwall$done;

    @Override public void greatwall$beginSlices() {
        if(greatwall$initialized||interpolating)throw new IllegalStateException("private slice cursor may begin only once");
        greatwall$owner=Thread.currentThread();
        greatwall$initialized=true;greatwall$done=false;
        greatwall$side=0;greatwall$z=0;greatwall$interpolator=0;greatwall$y=0;
        interpolating=true;interpolationCounter=0;
        cellStartBlockX=Math.multiplyExact(firstCellX,cellWidth);inCellX=0;
        greatwall$setupZ();
    }

    @Override public boolean greatwall$advanceSlices(int maxPoints) {
        if(!greatwall$initialized||greatwall$owner!=Thread.currentThread())
            throw new IllegalStateException("private slice cursor used outside its owning lane");
        if(maxPoints<1||maxPoints>256)throw new IllegalArgumentException("slice limit 1..256");
        if(!interpolating)throw new IllegalStateException("private interpolation already stopped");
        int points=0;
        while(!greatwall$done&&points<maxPoints) {
            if(interpolators.isEmpty()) {
                greatwall$nextZ();points++;
                continue;
            }
            var interpolation=(NoiseInterpolatorAccess)interpolators.get(greatwall$interpolator);
            var target=(greatwall$side==0?interpolation.greatwall$slice0():interpolation.greatwall$slice1())[greatwall$z];
            var context=sliceFillingContextProvider.forIndex(greatwall$y);
            target[greatwall$y]=interpolation.greatwall$filler().compute(context);
            points++;greatwall$y++;
            if(greatwall$y>cellCountY) {
                greatwall$y=0;greatwall$interpolator++;
                if(greatwall$interpolator==interpolators.size()) {
                    greatwall$interpolator=0;greatwall$nextZ();
                }
            }
        }
        return greatwall$done;
    }

    @Unique private void greatwall$setupZ() {
        cellStartBlockZ=Math.multiplyExact(Math.addExact(firstCellZ,greatwall$z),cellWidth);
        inCellZ=0;arrayInterpolationCounter++;
    }
    @Unique private void greatwall$nextZ() {
        greatwall$z++;
        if(greatwall$z<=cellCountXZ) {
            greatwall$setupZ();return;
        }
        arrayInterpolationCounter++;
        greatwall$side++;
        if(greatwall$side==2) {
            cellStartBlockX=Math.multiplyExact(firstCellX,cellWidth);
            greatwall$done=true;return;
        }
        greatwall$z=0;
        cellStartBlockX=Math.multiplyExact(Math.addExact(firstCellX,1),cellWidth);inCellX=0;
        greatwall$setupZ();
    }

    @Override @Invoker("getInterpolatedState")
    public abstract BlockState greatwall$state();
}
