package local.plaincraft.greatwall.planning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8.435：编译失败的「按原因计数、只留前几条栈」。
 *
 * <p>真机一次 1783/2131 owner 因同一个异常失败，每条都打栈 ⇒ 约五万行日志。计数本身是信息，
 * 栈只在头几条有意义。这里钉住这个边界：前 N 条给栈、之后只计数、末尾一句汇总按次数排序。
 */
final class CompileFailureTallyTest {

    private static Throwable failure(String message) {
        return new IllegalStateException(message);
    }

    @Test
    void onlyTheFirstFewOfOneCauseKeepAStackTrace() {
        PlanningChunkCompiler.FailureTally tally = new PlanningChunkCompiler.FailureTally(3);

        int detailed = 0;
        for (int i = 0; i < 10; i++) {
            String kind = tally.record(failure("no proven ground exit"));
            if (tally.shouldLogDetail(kind)) detailed++;
        }

        assertEquals(3, detailed, "three traces of one cause are enough to diagnose it");
        assertEquals(1, tally.distinct());
        assertEquals(10, tally.total());
    }

    @Test
    void suppressionIsCountedPerCause() {
        PlanningChunkCompiler.FailureTally tally = new PlanningChunkCompiler.FailureTally(2);
        String kindA = tally.record(failure("a"));
        for (int i = 0; i < 4; i++) tally.record(failure("a"));
        String kindB = tally.record(failure("b"));
        for (int i = 0; i < 3; i++) tally.record(failure("b"));

        assertEquals(3, tally.suppressed(kindA), "5 seen, 2 kept ⇒ 3 suppressed");
        assertEquals(2, tally.suppressed(kindB), "4 seen, 2 kept ⇒ 2 suppressed");
        assertEquals(2, tally.distinct());
    }

    @Test
    void aCauseWithoutAMessageStillHasAName() {
        PlanningChunkCompiler.FailureTally tally = new PlanningChunkCompiler.FailureTally(1);
        String kind = tally.record(new NullPointerException());

        assertTrue(kind.contains("NullPointerException"), kind);
        assertTrue(tally.shouldLogDetail(kind));
    }

    @Test
    void theSummaryNamesTheFrequentCausesFirstAndCountsTheRest() {
        PlanningChunkCompiler.FailureTally tally = new PlanningChunkCompiler.FailureTally(0);
        for (int i = 0; i < 3; i++) tally.record(failure("common"));
        for (int i = 0; i < 2; i++) tally.record(failure("mid"));
        tally.record(failure("rare"));

        String summary = tally.summary(2);

        assertTrue(summary.startsWith("3x "), summary);
        assertTrue(summary.contains("common"), summary);
        assertTrue(summary.contains("+1 more"), summary);
        assertFalse(summary.contains("rare"), "a limited summary must not name the tail: " + summary);
    }
}
