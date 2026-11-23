package local.plaincraft.greatwall.generation;

import java.util.function.BooleanSupplier;

/** 控制一次 tick 内的单块施工，实体与完成发布保持同一结案窗口。 */
final class WallBackfillSlice {
    private static final int MAX_VOXELS_PER_SLICE = 512;

    private WallBackfillSlice() {}

    static void advance(WallSettlementCursor<?> cursor, BooleanSupplier shouldYield) {
        while (!cursor.terminal() && !shouldYield.getAsBoolean()) {
            var stage = cursor.stage();
            cursor.advance(MAX_VOXELS_PER_SLICE, shouldYield);
            // 无论有没有扫完，体素只处理一批；下个 tick 再续扫或结案。
            if (stage == WallSettlementCursor.Stage.PAINT) return;
            if (cursor.stage() == WallSettlementCursor.Stage.PUBLISH) {
                // 完成章已写，当前 tick 一并发布，避免跨 tick 留下半结案状态。
                cursor.advance(MAX_VOXELS_PER_SLICE);
            }
        }
    }
}
