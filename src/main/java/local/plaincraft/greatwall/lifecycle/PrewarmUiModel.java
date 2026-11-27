package local.plaincraft.greatwall.lifecycle;

import java.util.List;
import local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage;

/** 开局卡片只展示进世界前的工作，补建不属于预热门完成条件。 */
public final class PrewarmUiModel {
    private PrewarmUiModel() {}

    public static List<Stage> stages() {
        return List.of(Stage.ROOT_SEARCH, Stage.STRUCTURE_SCAN, Stage.PREWARM,
                Stage.FINE_SAMPLE, Stage.COMPILE, Stage.DONE);
    }

    /** 并行分段规划必须看自身计数，不能按枚举位置推断已经结束。 */
    public static boolean complete(Stage stage, PrewarmProgress.Snapshot snapshot) {
        if (stage == Stage.BACKFILL) return false;
        if (stage == Stage.DONE) return snapshot.stage() == Stage.DONE && snapshot.finished() && !snapshot.failed();
        if (stage == Stage.PREWARM) return snapshot.total() > 0 && snapshot.completed() >= snapshot.total();
        // 精采可与剩余分段重叠；只有编译屏障通过才证明全部采样已经结束。
        return switch (stage) {
            case ROOT_SEARCH -> snapshot.stage() != Stage.ROOT_SEARCH;
            case STRUCTURE_SCAN -> snapshot.stage() != Stage.ROOT_SEARCH && snapshot.stage() != Stage.STRUCTURE_SCAN;
            case FINE_SAMPLE -> snapshot.stage() == Stage.COMPILE || snapshot.stage() == Stage.DONE;
            case COMPILE -> snapshot.stage() == Stage.DONE;
            default -> false;
        };
    }

    public static boolean running(Stage stage, PrewarmProgress.Snapshot snapshot) {
        if (snapshot.failed() || !snapshot.active()) return false;
        if (stage == Stage.FINE_SAMPLE && snapshot.stage() == Stage.STRUCTURE_SCAN)
            return snapshot.subByStage().containsKey(stage);
        return stage == snapshot.stage() || stage == Stage.PREWARM && snapshot.stage() == Stage.FINE_SAMPLE
                && !complete(stage, snapshot);
    }

    // 失败仍指向最后一次真实发布的阶段，已经结束的分段规划不替编译背锅。
    public static boolean failed(Stage stage, PrewarmProgress.Snapshot snapshot) {
        return snapshot.failed() && snapshot.stage() == stage;
    }

    // 同一批候选可以反复复核占地和接口，文案描述正在做的工作。
    public static String rootStep(int phase) {
        return switch (phase) {
            case PrewarmProgress.ROOT_MACRO_SURVEY -> "macro";
            case PrewarmProgress.ROOT_ENDPOINT_GROUND -> "endpoint_ground";
            case PrewarmProgress.ROOT_ENDPOINT_STRUCTURES -> "endpoint_structures";
            case 1 -> "footprint";
            case 2 -> "axis";
            case 3 -> "selected";
            case 4 -> "structures";
            case 5 -> "entrance";
            default -> "candidates";
        };
    }
}
