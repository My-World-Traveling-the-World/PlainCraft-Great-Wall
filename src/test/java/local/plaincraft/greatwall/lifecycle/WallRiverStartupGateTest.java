package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.compat.RiverStartupQuery.Action;
import local.plaincraft.greatwall.compat.RiverStartupQuery.Check;
import org.junit.jupiter.api.Test;

/** 用同一对 HEAD/TAIL 重现重开世界的注入次序，禁止成功、失败回调重复执行。 */
class WallRiverStartupGateTest {
    @Test void pendingHeadReturnsAndSameWorldCompletionAtTailBuildsOnce() {
        var gate = new WallRiverStartupGate();
        var starts = new AtomicInteger();
        assertFalse(gate.enter(false, () -> new Check(Action.WAIT, 7, "BUILDING", ""),
                () -> fail("不能提前冻结"), reason -> fail(reason)));
        assertTrue(gate.enter(true, () -> new Check(Action.ALLOW, 7, "COMPLETE", ""),
                starts::incrementAndGet, reason -> fail(reason)));
        assertTrue(gate.enter(true, () -> fail("重复钩子不应再查询"), starts::incrementAndGet, reason -> fail(reason)));
        assertEquals(1, starts.get());
    }

    @Test void terminalFailureOrMissingTerminalNeverStartsAndFreshWorldCanProceed() {
        for (var check : new Check[] {new Check(Action.REJECT, 1, "FAILED", "采样错误"),
                new Check(Action.REJECT, 1, "STOPPED", ""), new Check(Action.WAIT, 1, "BUILDING", ""),
                new Check(Action.ALLOW, 2, "COMPLETE", "")}) {
            var gate = new WallRiverStartupGate();
            var errs = new java.util.ArrayList<String>();
            assertFalse(gate.enter(false, () -> new Check(Action.WAIT, 1, "BUILDING", ""),
                    () -> fail("提前启动"), errs::add));
            assertFalse(gate.enter(true, () -> check, () -> fail("失败或换会话不能开工"), errs::add));
            assertEquals(1, errs.size());
            assertTrue(errs.getFirst().contains(check.phase()));
            assertFalse(gate.enter(true, () -> fail("已经终止"), () -> fail("不能复活"), errs::add));
            assertEquals(1, errs.size());
        }
        var fresh = new WallRiverStartupGate();
        var called = new AtomicInteger();
        assertTrue(fresh.enter(false, () -> new Check(Action.ALLOW, 0, "ABSENT", ""),
                called::incrementAndGet, reason -> fail(reason)));
        assertEquals(1, called.get());
    }
}
