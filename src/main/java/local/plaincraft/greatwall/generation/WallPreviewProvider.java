package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.api.outgoing.WorldgenPreviewApi;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 直接交付冻结施工体素，不在远景线程要求区块或恢复磁盘计划。 */
public final class WallPreviewProvider {
    private WallPreviewProvider() {}
    public static void register() { WorldgenPreviewApi.register("great_wall",WallPreviewProvider::peek); }
    private static WorldgenPreviewApi.Chunk peek(Object world,int x,int z) {
        if (!(world instanceof ServerLevel level)) return WorldgenPreviewApi.Chunk.unavailable();
        return WallWorldgenPlanCache.peekPreview(level,new ChunkKey(level.dimension().location().toString(),x,z));
    }
    static WorldgenPreviewApi.Chunk convert(ChunkWritePlan plan) {
        var runs=new ArrayList<WorldgenPreviewApi.Run>(plan.voxels().size());
        var states=new java.util.HashMap<local.plaincraft.greatwall.model.WallTypes.BlockSpec,String>();
        for(var voxel:plan.voxels()) {
            if(voxel.kind()==IntentKind.KEEP)continue;
            var spec=voxel.state();
            // 大楼只有少量不同状态；不为每个体素重复排序属性和创建相同字符串。
            String state=states.computeIfAbsent(spec,value->value.id()+(value.properties().isEmpty()?"":"["+value.properties().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey()).map(e->e.getKey()+"="+e.getValue())
                    .collect(java.util.stream.Collectors.joining(","))+"]"));
            var p=voxel.pos();
            runs.add(new WorldgenPreviewApi.Run(Math.floorMod(p.x(),16),Math.floorMod(p.z(),16),p.y(),p.y()+1,state));
        }
        return new WorldgenPreviewApi.Chunk(runs.isEmpty()?WorldgenPreviewApi.State.EMPTY:WorldgenPreviewApi.State.READY,
                WorldgenPreviewApi.Fidelity.BLOCKS,plan.hash(),runs);
    }
}
