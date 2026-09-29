package local.plaincraft.greatwall.smoke;

import local.plaincraft.greatwall.generation.BarrierFixtureLayout;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** An actual registered FEATURES write from A into B, within the declared radius one. */
public final class NeighborStripeFeature extends Feature<NoneFeatureConfiguration> {
    public NeighborStripeFeature() { super(NoneFeatureConfiguration.CODEC); }
    @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        var level = context.level().getLevel();
        var service = WorldServices.find(level.getServer());
        var barrier = service == null ? null : service.barrier(level);
        var source = new ChunkPos(context.origin());
        if (barrier == null || !barrier.layout().featureSource(source)) return false;
        if (Boolean.getBoolean("greatwall.s0.terrainSkip")) {
            throw new IllegalStateException("Feature order cycle found: deliberate S0-03 QoS compatibility fault");
        }
        var target = new ChunkPos(source.x + 1, source.z);
        for (int x = 0; x < 16; x++) {
            if (!context.level().setBlock(new BlockPos(target.getMinBlockX() + x, BarrierFixtureLayout.MIN_Y,
                    target.getMinBlockZ() + 8), Blocks.STONE.defaultBlockState(), 2)) {
                throw new IllegalStateException("Neighbor stripe write rejected by native WorldGenRegion");
            }
        }
        return true;
    }
}
