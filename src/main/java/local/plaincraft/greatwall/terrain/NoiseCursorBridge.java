package local.plaincraft.greatwall.terrain;

import net.minecraft.world.level.block.state.BlockState;

/** Private oracle NoiseChunk only; every operation is confined to the cursor's owning lane. */
public interface NoiseCursorBridge {
    void greatwall$beginSlices();
    boolean greatwall$advanceSlices(int maxPoints);
    BlockState greatwall$state();
}
