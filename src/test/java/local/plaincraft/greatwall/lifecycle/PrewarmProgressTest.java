package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A startup peer waits here for the wall's prewarm, so a waiter must never be orphaned.
 *
 * <p>The ordering that matters is the real one: the peer arrives first, because its gate
 * opens as soon as the road stage begins, and the prewarm starts later, once the rivers are
 * built and the recipe is installed. These tests pin that a waiter already blocked when
 * {@code begin} runs is still released by that prewarm's {@code finish}, which is exactly
 * what an unconditional latch replacement would break.
 */
class PrewarmProgressTest {
    @AfterEach
    void clearState() {
        PrewarmProgress.clear();
    }

    @Test
    void aWaiterAlreadyBlockedIsReleasedByTheFirstFinish() throws Exception {
        PrewarmProgress.clear();
        var released = new AtomicBoolean();
        var waiter = new Thread(() -> {
            // Long timeout: the point is that finish() releases this, not that it expires.
            released.set(PrewarmProgress.awaitCompletion(30, TimeUnit.SECONDS));
        }, "test-waiter");
        waiter.start();

        // Let the waiter reach the latch before the prewarm begins, which is the real order.
        Thread.sleep(150);
        PrewarmProgress.begin(4, 16);
        Thread.sleep(50);
        PrewarmProgress.finish(true);

        waiter.join(5_000);
        assertFalse(waiter.isAlive(), "the waiter must not still be blocked after finish()");
        assertTrue(released.get(), "finish() must release a waiter that arrived before begin()");
    }

    @Test
    void aSecondPrewarmBlocksRatherThanBeingFinishedByTheFirst() throws Exception {
        PrewarmProgress.clear();
        PrewarmProgress.begin(2, 4);
        PrewarmProgress.finish(true);

        // A second prewarm starts - after a dimension change, say. The latch from the first is
        // spent, so a fresh one must be installed; otherwise this waiter would be told the work
        // is already done by a prewarm that finished before it arrived.
        PrewarmProgress.begin(2, 4);

        var returnedSettled = new AtomicBoolean();
        var returned = new CountDownLatch(1);
        var waiter = new Thread(() -> {
            // Short timeout: this test is about whether the waiter blocks at all, not how long.
            returnedSettled.set(PrewarmProgress.awaitCompletion(300, TimeUnit.MILLISECONDS));
            returned.countDown();
        }, "test-second-waiter");
        waiter.start();

        assertTrue(returned.await(5, TimeUnit.SECONDS), "the waiter must return, not block forever");
        assertFalse(returnedSettled.get(),
                "a running second prewarm must not be reported as already finished by the first one's latch");

        // And it is still released by that second prewarm's own completion.
        PrewarmProgress.finish(true);
        assertTrue(PrewarmProgress.awaitCompletion(1, TimeUnit.SECONDS),
                "once the second prewarm finishes, waiting must report settled");
    }

    @Test
    void waitingWithNoPrewarmEverStartedReturnsImmediately() {
        PrewarmProgress.clear();
        long started = System.nanoTime();
        // Nothing was ever requested: there is nothing to wait for, and a peer must not be
        // delayed by a stage that will not run.
        boolean settled = PrewarmProgress.awaitCompletion(30, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(settled, "a session with no prewarm must report ready");
        assertTrue(elapsedMs < 1_000, "reporting ready must not wait; took " + elapsedMs + "ms");
    }

    @Test
    void aRequestedPrewarmThatHasNotStartedYetStillBlocks() throws Exception {
        PrewarmProgress.clear();
        // The rivers have reported ready, but the wall is still choosing a root position - a
        // window measured in minutes. A peer arriving here must wait: the prewarm has been
        // asked for and will run. Returning ready in this window is what let a real session
        // open its gate in 1 ms while the wall's root search was only just beginning.
        PrewarmProgress.request();

        var settled = new AtomicBoolean(true);
        var returned = new CountDownLatch(1);
        var waiter = new Thread(() -> {
            // Short timeout: this asserts that it blocks, not how long for.
            settled.set(PrewarmProgress.awaitCompletion(300, TimeUnit.MILLISECONDS));
            returned.countDown();
        }, "test-requested-waiter");
        waiter.start();

        assertTrue(returned.await(5, TimeUnit.SECONDS), "the waiter must return via its timeout");
        assertFalse(settled.get(), "a requested but not-yet-started prewarm must not report ready");

        // And the real prewarm then releases it properly.
        PrewarmProgress.begin(4, 16);
        PrewarmProgress.finish(true);
        assertTrue(PrewarmProgress.awaitCompletion(1, TimeUnit.SECONDS),
                "once the requested prewarm finishes, waiting must report settled");
    }

    @Test
    void clearingForgetsThatAPrewarmWasRequested() {
        PrewarmProgress.clear();
        PrewarmProgress.request();
        // A new session must not inherit the previous one's request, or it would wait out the
        // full timeout for a prewarm that this session may never run.
        PrewarmProgress.clear();
        assertTrue(PrewarmProgress.awaitCompletion(30, TimeUnit.SECONDS),
                "a cleared session has no pending prewarm and must report ready");
    }

    @Test
    void aFailedPrewarmReleasesWaitersToo() throws Exception {
        PrewarmProgress.clear();
        var settled = new AtomicBoolean(true);
        var waiter = new Thread(() -> settled.set(PrewarmProgress.awaitCompletion(30, TimeUnit.SECONDS)),
                "test-failure-waiter");
        waiter.start();
        Thread.sleep(150);
        PrewarmProgress.begin(4, 16);
        Thread.sleep(50);
        // A failed prewarm ends the wait as surely as a successful one; otherwise a peer sits
        // on its gate for the full timeout on a run that already failed.
        PrewarmProgress.finish(false);

        waiter.join(5_000);
        assertFalse(waiter.isAlive(), "a failed prewarm must release waiters");
        assertTrue(settled.get(), "failure is a settled outcome, not a timeout");
    }

    @Test
    void theSnapshotReportsProgressOnlyWhileActive() {
        PrewarmProgress.clear();
        assertFalse(PrewarmProgress.snapshot().active(), "an idle progress must not report active");

        PrewarmProgress.begin(4, 16);
        PrewarmProgress.enterRootSearch();
        var searching = PrewarmProgress.snapshot();
        assertTrue(searching.active());
        assertTrue(searching.rootSearch(), "the root search phase must be distinguishable");
        assertTrue(searching.total() == 4);
        assertTrue(searching.percent() == 0, "no segment is done while the root is still being chosen");

        PrewarmProgress.enterPlanning();
        PrewarmProgress.setCompleted(2);
        var planning = PrewarmProgress.snapshot();
        assertFalse(planning.rootSearch());
        assertTrue(planning.percent() == 50, "two of four segments is fifty percent");

        PrewarmProgress.finish(true);
        assertFalse(PrewarmProgress.snapshot().active(), "a finished prewarm must not report active");
    }

    @Test
    void completedCannotExceedTotal() {
        PrewarmProgress.clear();
        PrewarmProgress.begin(2, 4);
        // Clamped rather than allowed to overshoot: the card shows a percentage, and a value
        // above the total would render an over-full bar.
        PrewarmProgress.setCompleted(99);
        var snapshot = PrewarmProgress.snapshot();
        assertTrue(snapshot.completed() == 2, "completed must clamp to the total, was " + snapshot.completed());
        assertTrue(snapshot.percent() == 100);
    }
}
