package local.plaincraft.greatwall.lifecycle;

import java.util.Comparator;
import java.util.List;
import local.plaincraft.greatwall.model.WallTypes.SegmentPlan;
import local.plaincraft.greatwall.model.WallTypes.WorldRecipe;

/** 回调只收到了成功项，不能把这个列表自身当成完整窗口的证明。 */
final class StartupPlanWindow {
    private StartupPlanWindow() {}

    static List<SegmentPlan> complete(List<SegmentPlan> plans, long expected, WorldRecipe recipe) {
        if (plans.size() != expected)
            throw new IllegalStateException("长城启动计划缺段：expected=" + expected + " received=" + plans.size());
        var sorted = plans.stream().sorted(Comparator.comparingLong(p -> p.geometry().key().index())).toList();
        for (int i = 0; i < sorted.size(); i++) {
            var key = sorted.get(i).geometry().key();
            // 数量相同仍可能重复一段；身份和段号一起核对，禁止错窗计划进入持久化。
            if (key.index() != i || !key.recipeHash().equals(recipe.recipeHash())
                    || !key.dimension().equals(recipe.dimension()))
                throw new IllegalStateException("长城启动计划不连续或身份不符：expectedSegment=" + i + " actual=" + key);
        }
        return sorted;
    }
}
