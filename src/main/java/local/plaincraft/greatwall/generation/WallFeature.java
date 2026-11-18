package local.plaincraft.greatwall.generation;

import java.util.EnumSet;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
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
        if (marker != null && PublishedPlanPainter.preserveCompleted(false,
                marker.complete(),marker.planHash(),plan.hash())) {
            // An already-written chunk still has to publish its ownership to the road guard and
            // gameplay index after a restart; returning early here used to lose both forever.
            WallPlanSupport.markPlannedColumns(plan);
            local.plaincraft.greatwall.lifecycle.WorldServices.onWallChunkReady(server, chunk, plan);
            return true;
        }

        WallPlanSupport.markPlannedColumns(plan);
        if(marker!=null && marker.complete())WallPlanSupport.writeMarkers(chunk,plan,false);
        int[] failures={0};
        var result=PublishedPlanPainter.paint(plan,new PublishedPlanPainter.World<BlockState>() {
            public boolean validHeight(int y) { return !world.isOutsideBuildHeight(y); }
            public BlockState resolve(local.plaincraft.greatwall.model.WallTypes.BlockSpec spec) {
                return BlockCatalog.resolveState(spec);
            }
            public BlockState read(Point3i p) { return chunk.getBlockState(new BlockPos(p.x(),p.y(),p.z())); }
            public boolean write(Point3i p,BlockState target) {
                var pos=new BlockPos(p.x(),p.y(),p.z());
                // 与补建采用同一首次施工政策，替换实体后清理旧身份，避免带入漏检建筑的数据。
                chunk.setBlockState(pos,target,false);
                if (!target.hasBlockEntity()) chunk.removeBlockEntity(pos);
                return chunk.getBlockState(pos)==target;
            }
            public void finish(java.util.Set<Point3i> satisfied,boolean complete) {
                Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG,
                        Heightmap.Types.OCEAN_FLOOR_WG,Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
                for(var p:plan.postprocess())chunk.markPosForPostprocessing(new BlockPos(p.x(),p.y(),p.z()));
                WallPlanSupport.writeBlockEntities(chunk,plan,satisfied);
                WallPlanSupport.writeMarkers(chunk,plan,complete);
            }
            public void failure(Point3i p,RuntimeException failure) {
                // 坏材料会影响整片体素，每块仅记首个原因，避免异常日志反而拖住生成线程。
                if(failures[0]++==0)local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                        "[GreatWall:施工] 世界生成落块未完成 owner={},{} pos={} 原因={}",cx,cz,p,failure.toString());
            }
        });
        if(result.complete()) {
            local.plaincraft.greatwall.lifecycle.WorldServices.onWallChunkReady(server,chunk,plan);
        } else {
            // feature阶段不会重跑，缺口交给已有加载事件与计划缓存补建，不能只返回成功丢失失败。
            WallBodyBackfill.enqueueUnfinished(server, plan.owner());
        }
        return result.complete();
    }
}
