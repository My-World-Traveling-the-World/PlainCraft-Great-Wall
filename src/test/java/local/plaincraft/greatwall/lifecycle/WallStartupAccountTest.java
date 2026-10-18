package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 2026-10-19：启动各阶段自带账本。
 *
 * <p>之前要知道「这二十分钟花在哪」只能拿日志时间戳人工对齐；现在每次启动末尾打一行
 * {@code WALL_STARTUP_ACCOUNT}。这个测试钉住两件事：重复调用 setStage 不能把计时清零，
 * 以及阶段切换确实把时间记到前一个阶段头上。
 */
final class WallStartupAccountTest {

    @Test
    void aRepeatedStageCallIsAProgressTickNotATransition() throws Exception {
        PrewarmProgress.beginRootSearch(2);
        PrewarmProgress.setStage(PrewarmProgress.Stage.STRUCTURE_SCAN);
        Thread.sleep(30L);
        // A progress tick for the same stage: restarting the clock here would charge the whole
        // stage to whichever tick happened to come last.
        PrewarmProgress.setStage(PrewarmProgress.Stage.STRUCTURE_SCAN);
        PrewarmProgress.setStage(PrewarmProgress.Stage.STRUCTURE_SCAN);
        PrewarmProgress.setStage(PrewarmProgress.Stage.FINE_SAMPLE);

        assertTrue(PrewarmProgress.accountedMillis(PrewarmProgress.Stage.STRUCTURE_SCAN) >= 25L,
                "the scan stage held the clock for at least the sleep");
        PrewarmProgress.finish(true);
    }

    @Test
    void finishChargesTheRunningStageAndClosesTheAccount() throws Exception {
        PrewarmProgress.beginRootSearch(2);
        PrewarmProgress.setStage(PrewarmProgress.Stage.COMPILE);
        Thread.sleep(20L);
        PrewarmProgress.finish(false);

        long compile = PrewarmProgress.accountedMillis(PrewarmProgress.Stage.COMPILE);
        assertTrue(compile >= 15L, "the running stage is charged when the account closes: " + compile);
        assertTrue(PrewarmProgress.accountedMillis(PrewarmProgress.Stage.ROOT_SEARCH) >= 0L);
    }
}
