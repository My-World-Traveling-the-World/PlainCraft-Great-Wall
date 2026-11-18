package local.plaincraft.greatwall.generation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.model.WallTypes.BlockSpec;

/** 首次施工只落实已发布计划；世界访问由入口持有，离线可验证成功与结案次序。 */
final class PublishedPlanPainter {
    interface World<S> {
        boolean validHeight(int y);
        S resolve(BlockSpec spec);
        S read(Point3i point);
        boolean write(Point3i point,S state);
        void finish(Set<Point3i> satisfied,boolean complete);
        void failure(Point3i point,RuntimeException failure);
        default void written(Point3i point,S state,int count) {}
    }

    record Result(boolean complete,int written,int failed) {}

    static boolean preserveCompleted(boolean forced,boolean complete,String existingHash,String planHash) {
        // 完成证据绑定确切计划；仅显式修复可以越过保护，重开不能把玩家修改重新抹掉。
        return !forced && complete && planHash.equals(existingHash);
    }

    static <S> Result paint(ChunkWritePlan plan,World<S> world) {
        var palette=new HashMap<BlockSpec,S>();
        var satisfied=new HashSet<Point3i>();
        int written=0,failed=0;
        for(var voxel:plan.voxels()) {
            var p=voxel.pos();
            if(Math.floorDiv(p.x(),16)!=plan.owner().x()||Math.floorDiv(p.z(),16)!=plan.owner().z()
                    ||voxel.kind()==IntentKind.KEEP)continue;
            try {
                if(!world.validHeight(p.y()))throw new IllegalArgumentException("计划落块超出世界高度: "+p);
                // 解析同一计划材料一次，避免每个体素重复解析注册表与属性。
                S target=palette.computeIfAbsent(voxel.state(),world::resolve);
                if(!Objects.equals(world.read(p),target)) {
                    if(!world.write(p,target))throw new IllegalStateException("计划落块被世界拒绝: "+p);
                    written++;
                    world.written(p,target,written);
                }
                // 相同目标块也属于首次未结案计划，实体payload必须可以补齐。
                satisfied.add(p);
            } catch(RuntimeException failure) {
                failed++;
                world.failure(p,failure);
            }
        }
        try {
            // 所有必要落块完成后才允许盖完成章；失败仍写不完整标记以便诊断/重试。
            world.finish(Set.copyOf(satisfied),failed==0);
        } catch(RuntimeException failure) {
            failed++;
            world.failure(null,failure);
        }
        return new Result(failed==0,written,failed);
    }
}
