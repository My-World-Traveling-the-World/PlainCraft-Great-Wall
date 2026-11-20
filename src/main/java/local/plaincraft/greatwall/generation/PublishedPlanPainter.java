package local.plaincraft.greatwall.generation;

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
        // 正式入口仍一次跑完；可暂停状态先离线守卫，不能提前释放区块或给半块盖完成章。
        return new PublishedPlanCursor<>(plan,world).runAll();
    }
}
