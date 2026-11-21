package local.plaincraft.greatwall.lifecycle;

import java.util.List;
import local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage;

/** 开局卡片只展示进世界前的工作，补建不属于预热门完成条件。 */
public final class PrewarmUiModel {
    private PrewarmUiModel() {}

    public static List<Stage> stages() {
        return List.of(Stage.ROOT_SEARCH, Stage.STRUCTURE_SCAN, Stage.FINE_SAMPLE,
                Stage.PREWARM, Stage.COMPILE, Stage.DONE);
    }

    /** 并行分段规划必须看自身计数，不能按枚举位置推断已经结束。 */
    public static boolean complete(Stage stage, PrewarmProgress.Snapshot snapshot) {
        if (stage == Stage.BACKFILL) return false;
        if (stage == Stage.DONE) return !snapshot.active() && !snapshot.failed();
        if (stage == Stage.PREWARM) return snapshot.total() > 0 && snapshot.completed() >= snapshot.total();
        return stage.ordinal() < snapshot.stage().ordinal();
    }

    public static boolean running(Stage stage, PrewarmProgress.Snapshot snapshot) {
        if (snapshot.failed() || !snapshot.active()) return false;
        return stage == snapshot.stage() || stage == Stage.PREWARM && snapshot.stage() == Stage.FINE_SAMPLE
                && !complete(stage, snapshot);
    }
}
