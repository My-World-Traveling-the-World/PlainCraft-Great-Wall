package local.plaincraft.greatwall.lifecycle;

import java.util.List;
import java.util.Optional;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import local.plaincraft.greatwall.planning.PrefixSummaryIndex;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 以冻结段数校验异步收集结果，不能让缺失项改变验收分母。 */
class StartupPlanWindowTest {
    private static final WorldRecipe RECIPE = PlanningFixtures.recipe(1);

    @Test void outOfOrderCompletionsKeepTheEntireWindowInSegmentOrder() {
        var a = plan(RECIPE, 0); var b = plan(RECIPE, 1);
        assertEquals(List.of(a, b), StartupPlanWindow.complete(List.of(b, a), 2, RECIPE));
    }

    @Test void missingFirstMiddleLastOrAllNeverBecomesACompleteWindow() {
        var a = plan(RECIPE, 0); var b = plan(RECIPE, 1); var c = plan(RECIPE, 2);
        for (var partial : List.of(List.of(a,b), List.of(a,c), List.of(b,c), List.<SegmentPlan>of())) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> StartupPlanWindow.complete(partial, 3, RECIPE));
            assertTrue(failure.getMessage().contains("expected=3"));
            assertTrue(failure.getMessage().contains("received=" + partial.size()));
        }
    }

    @Test void duplicateSegmentCannotReplaceTheMissingOneEvenWithTheRightCount() {
        var a = plan(RECIPE, 0);
        assertThrows(IllegalStateException.class, () -> StartupPlanWindow.complete(List.of(a,a), 2, RECIPE));
    }

    @Test void anotherWorldCannotSupplyAMissingSegment() {
        var other = PlanningFixtures.recipe(2);
        assertNotEquals(RECIPE.recipeHash(), other.recipeHash());
        assertThrows(IllegalStateException.class, () -> StartupPlanWindow.complete(List.of(plan(other,0)), 1, RECIPE));
    }

    @Test void productionChecksCompletenessBeforeHeightSolvingAndPersistence() throws Exception {
        // 配合行为用例锁住真实接线，避免辅助类有测试但会话从未调用。
        var source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/local/plaincraft/greatwall/lifecycle/ProductionSession.java"));
        int check = source.indexOf("var complete = StartupPlanWindow.complete(plans, snapshot.planner().startupRange().segments(), snapshot.recipe());");
        int solve = source.indexOf("WindowHeightEnvelope.solve(snapshot.recipe(), complete, terrain)");
        int persist = source.indexOf("freezeStartupPlans(raised)");
        assertTrue(check >= 0); assertTrue(solve > check); assertTrue(persist > solve);
    }

    private static SegmentPlan plan(WorldRecipe recipe, long index) {
        var geometry = PlanningFixtures.geometry(recipe,index,Optional.empty());
        return new SegmentPlan(geometry,PrefixSummaryIndex.origin(),new StationTable(List.of(
                new Station(0,geometry.points().getFirst(),84,0),
                new Station(geometry.localSQ8().getLast(),geometry.points().getLast(),84,0)),List.of(),PlanningFixtures.HASH),
                List.of(),PlanningFixtures.HASH);
    }
}
