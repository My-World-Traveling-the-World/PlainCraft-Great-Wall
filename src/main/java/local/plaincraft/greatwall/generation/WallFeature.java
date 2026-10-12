package local.plaincraft.greatwall.generation;

import java.util.EnumSet;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.IntentKind;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

public class WallFeature extends Feature<NoneFeatureConfiguration> {
    public WallFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel world = ctx.level();
        Level level = world.getLevel();
        if (!(level instanceof ServerLevel server) || !Level.OVERWORLD.equals(server.dimension())) return false;
        int cx = ctx.origin().getX() >> 4;
        int cz = ctx.origin().getZ() >> 4;
        var key = new ChunkKey("minecraft:overworld", cx, cz);
        var plan = WallWorldgenPlanCache.get(server, key);
        if (plan == null || plan.voxels().isEmpty()) return false;

        ChunkAccess chunk = world.getChunk(cx, cz);
        var marker = chunk.getAttached(TerrainMarkers.PRODUCTION);
        if (marker != null && marker.complete()) return true;

        for (var voxel : plan.voxels()) {
            Point3i p = voxel.pos();
            if (Math.floorDiv(p.x(), 16) != cx || Math.floorDiv(p.z(), 16) != cz) continue;
            if (voxel.kind() == IntentKind.KEEP) continue;
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            BlockState state = BlockCatalog.resolveState(voxel.state());
            BlockState current = chunk.getBlockState(pos);
            if (!canReplace(current)) continue;
            chunk.setBlockState(pos, state, false);
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(
                Heightmap.Types.WORLD_SURFACE_WG,
                Heightmap.Types.OCEAN_FLOOR_WG,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
        for (var p : plan.postprocess()) chunk.markPosForPostprocessing(new BlockPos(p.x(), p.y(), p.z()));
        return true;
    }

    private static boolean canReplace(BlockState current) {
        if (current.isAir()) return true;
        if (current.hasBlockEntity()) return false;
        return !current.is(Blocks.BEDROCK);
    }
}
