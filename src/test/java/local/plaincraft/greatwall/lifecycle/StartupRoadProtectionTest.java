package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.generation.WallRoadRecovery.Report;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 对拍严格道路门：启动线程可收尾，保护失败不能冒充发布。 */
class StartupRoadProtectionTest {
    @AfterEach void clear() { PrewarmProgress.clear(); }

    @Test void readyOnlyAfterSuccessfulRestorationIncludingAnEmptyWorld() {
        begin();
        StartupRoadProtection.finishAfterRestore(() -> {
            assertFalse(ready());
            return new Report(0, 0, 0, 0, 0, 0, false);
        });
        assertTrue(ready());
        assertTrue(PrewarmProgress.isFinished());
        assertFalse(PrewarmProgress.isFailed());
    }

    @Test void missingUnreadableOrUnenumeratedProofsFinishWithoutOpeningRoads() {
        for (Report report : new Report[]{
                new Report(2, 9, 12, 1, 0, 1, false),
                new Report(2, 9, 12, 0, 1, 1, false),
                new Report(0, 0, 0, 0, 0, 0, true)}) {
            begin();
            StartupRoadProtection.finishAfterRestore(() -> report);
            assertTrue(PrewarmProgress.isFinished(), "释放启动等待");
            assertTrue(PrewarmProgress.isFailed(), "让下游收到明确失败");
            assertFalse(ready(), "已有保护不证明覆盖完整");
        }
    }

    @Test void publishingAnExistingPlanThrowsAndStillEndsAsFailure() {
        begin();
        StartupRoadProtection.finishAfterRestore(() -> { throw new IllegalStateException("发布失败"); });
        assertTrue(PrewarmProgress.isFinished());
        assertTrue(PrewarmProgress.isFailed());
        assertFalse(ready());
    }

    private static void begin() {
        PrewarmProgress.clear();
        PrewarmProgress.request();
        PrewarmProgress.markPublished();
    }

    private static boolean ready() {
        return PrewarmProgress.awaitWorldgenReadyStrict(0, TimeUnit.NANOSECONDS);
    }
}
