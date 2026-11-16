package local.plaincraft.greatwall.lifecycle;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 严格出口是长城给下游的生产判据；退出旧世界只能结束等待，不能变成成功。 */
class StrictWallStartupGateTest {
    @BeforeEach
    @AfterEach
    void clear() {
        PrewarmProgress.clear();
    }

    @Test
    void noRequestDoesNotMeanPublished() {
        assertFalse(PrewarmProgress.awaitWorldgenReadyStrict(0, TimeUnit.NANOSECONDS));
    }

    @Test
    void segmentCompletionAndFailureBothKeepStrictGateClosed() {
        PrewarmProgress.request();
        PrewarmProgress.begin(2, 1);
        PrewarmProgress.signalReady();
        assertTrue(PrewarmProgress.awaitCompletion(0, TimeUnit.NANOSECONDS));
        assertFalse(PrewarmProgress.awaitWorldgenReadyStrict(0, TimeUnit.NANOSECONDS));
        PrewarmProgress.finish(false);
        assertTrue(PrewarmProgress.isFailed());
        assertFalse(PrewarmProgress.awaitWorldgenReadyStrict(0, TimeUnit.NANOSECONDS));
    }

    @Test
    void earlyWaiterSurvivesBeginAndOnlyPublicationReleasesIt() throws Exception {
        PrewarmProgress.request();
        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread waiter = waiter(result);
        try {
            waitUntilParked(waiter);
            PrewarmProgress.begin(2, 1);
            PrewarmProgress.finish(true);
            waiter.join(2000);
            assertFalse(waiter.isAlive());
            assertEquals(Boolean.TRUE, result.get());
        } finally {
            release(waiter);
        }
    }

    @Test
    void clearingOldWorldReleasesWaiterAsNotReady() throws Exception {
        PrewarmProgress.request();
        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread waiter = waiter(result);
        try {
            waitUntilParked(waiter);
            PrewarmProgress.clear();
            waiter.join(2000);
            assertFalse(waiter.isAlive());
            assertEquals(Boolean.FALSE, result.get());
            assertFalse(PrewarmProgress.awaitWorldgenReadyStrict(0, TimeUnit.NANOSECONDS));
        } finally {
            release(waiter);
        }
    }

    @Test
    void reopenedWorldClearsStageAndSubProgress() {
        PrewarmProgress.begin(2, 1);
        PrewarmProgress.setStage(PrewarmProgress.Stage.COMPILE);
        PrewarmProgress.setSubProgress(PrewarmProgress.Stage.COMPILE, 7, 10);
        assertEquals(7, PrewarmProgress.subDoneFor(PrewarmProgress.Stage.COMPILE));
        PrewarmProgress.finish(true);
        PrewarmProgress.clear();
        var snapshot = PrewarmProgress.snapshot();
        assertEquals(PrewarmProgress.Stage.ROOT_SEARCH, snapshot.stage());
        assertEquals(0, snapshot.subDone());
        assertEquals(0, snapshot.subTotal());
        assertTrue(snapshot.subByStage().isEmpty());
        assertEquals(0, PrewarmProgress.subDoneFor(PrewarmProgress.Stage.COMPILE));
        assertFalse(PrewarmProgress.isPublished());
    }

    @Test
    void interruptedStrictWaitReturnsFalseAndPreservesInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertFalse(PrewarmProgress.awaitWorldgenReadyStrict(0, TimeUnit.NANOSECONDS));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static Thread waiter(AtomicReference<Boolean> result) {
        Thread thread = new Thread(() -> result.set(
                PrewarmProgress.awaitWorldgenReadyStrict(10, TimeUnit.SECONDS)), "strict-wall-test");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void waitUntilParked(Thread thread) throws Exception {
        // 确认线程已进入旧闩锁的等待，避免clear碰巧先于方法读取闩锁。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.TIMED_WAITING && thread.isAlive()
                && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(Thread.State.TIMED_WAITING, thread.getState());
    }

    private static void release(Thread thread) throws Exception {
        PrewarmProgress.clear();
        thread.interrupt();
        thread.join(2000);
        assertFalse(thread.isAlive());
    }
}
