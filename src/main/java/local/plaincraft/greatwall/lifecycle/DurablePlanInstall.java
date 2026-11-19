package local.plaincraft.greatwall.lifecycle;

import java.util.function.Consumer;
import local.plaincraft.greatwall.generation.WallBodyBackfill;
import local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan;

/** 正式适配器与离线守卫共用此边界，磁盘确认之前不能对缓存/补建宣称可用。 */
final class DurablePlanInstall {
    private DurablePlanInstall() { }
    static void accept(ChunkWritePlan plan, Consumer<ChunkWritePlan> cache) {
        WallBodyBackfill.persist(plan);
        cache.accept(plan);
    }
}
