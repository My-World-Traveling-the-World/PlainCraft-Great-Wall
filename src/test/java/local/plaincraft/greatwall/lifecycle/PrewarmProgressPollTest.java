package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Simulates the full non-blocking serialisation between the rivers, the wall and the roads.
 *
 * <p>The road side polls the wall rather than blocking, because blocking the server thread
 * deadlocks: the wall's own startup needs that thread to keep ticking. These tests model the
 * exact poll/complete sequence and pin that a poll never blocks the caller, that it reports
 * "not done" for the whole life of the prewarm, and that it flips to "done" only when the
 * prewarm finishes or fails.
 */
class PrewarmProgressPollTest {
    @AfterEach
    void clearState() {
        PrewarmProgress.clear();
    }

    /**
     * The poll must be non-blocking. A zero-timeout await is the road side's gate, called once
     * per tick; if it blocked even briefly, the server thread would stop driving the wall and
     * the two would freeze each other, which is the exact deadlock this design exists to avoid.
     */
    @Test
    void aPollReturnsImmediatelyAndReportsNotDone() {
        PrewarmProgress.clear();
        PrewarmProgress.request();
        PrewarmProgress.beginRootSearch(16);

        long started = System.nanoTime();
        boolean done = PrewarmProgress.awaitCompletion(0, TimeUnit.NANOSECONDS);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertFalse(done, "a prewarm that is running must not report done");
        assertTrue(elapsedMs < 50, "a poll must not block; took " + elapsedMs + "ms");
    }

    /**
     * The whole poll-until-done loop, as the road side runs it once per tick. The caller never
     * blocks, keeps its own work going in parallel, and exits exactly once the wall finishes.
     */
    @Test
    void pollLoopExitsWhenThePrewarmFinishes() throws Exception {
        PrewarmProgress.clear();
        PrewarmProgress.request();
        PrewarmProgress.beginRootSearch(16);

        var pollCount = new AtomicInteger();
        var exited = new AtomicBoolean();
        var caller = new Thread(() -> {
            // The road side's per-tick gate: keep asking, doing other work between polls.
            while (!PrewarmProgress.awaitCompletion(0, TimeUnit.NANOSECONDS)) {
                pollCount.incrementAndGet();
                Thread.yield();
            }
            exited.set(true);
        }, "road-poller");
        caller.start();

        // The wall is still working while the road polls. Give it time to poll a few times.
        Thread.sleep(200);
        assertFalse(exited.get(), "the poller must still be waiting while the wall works");
        assertTrue(pollCount.get() > 0, "the poller should have polled several times already");

        // The wall finishes its prewarm.
        PrewarmProgress.begin(4, 16);
        PrewarmProgress.finish(true);

        caller.join(5_000);
        assertFalse(caller.isAlive(), "the poller must exit once the prewarm finishes");
        assertTrue(exited.get(), "finish() must let the poller through");
    }

    /**
     * A prewarm that never starts - because the wall is absent, or its session never reached
     * the recipe - must let the road through immediately rather than polling forever. This is
     * the "no wall installed" case, which must cost nothing.
     */
    @Test
    void noPrewarmAtAllReportsDoneImmediately() {
        PrewarmProgress.clear();
        // Nothing requested, nothing started: there is no prewarm to wait for.
        assertTrue(PrewarmProgress.awaitCompletion(0, TimeUnit.NANOSECONDS),
                "a session with no prewarm must report done so the road starts without delay");
    }

    /**
     * A failed prewarm is a settled outcome too: the road must start after it, not keep polling
     * a prewarm that will never finish on its own. This covers the wall throwing mid-prewarm.
     */
    @Test
    void aFailedPrewarmLetsThePollerThrough() {
        PrewarmProgress.clear();
        PrewarmProgress.request();
        PrewarmProgress.beginRootSearch(16);
        assertFalse(PrewarmProgress.awaitCompletion(0, TimeUnit.NANOSECONDS),
                "while running, a poll reports not done");

        PrewarmProgress.finish(false);
        assertTrue(PrewarmProgress.awaitCompletion(0, TimeUnit.NANOSECONDS),
                "a failed prewarm is settled, so the poll must report done");
    }

    /**
     * The root-search phase runs before the first segment, so beginRootSearch sets active with a
     * zero total. A poll during that window must still report "not done" - a previous bug
     * treated "no prewarm started" as "done" and let the road start during the search.
     */
    @Test
    void theRootSearchWindowReportsNotDone() {
        PrewarmProgress.clear();
        PrewarmProgress.request();
        PrewarmProgress.beginRootSearch(16);

        assertFalse(PrewarmProgress.awaitCompletion(0, TimeUnit.NANOSECONDS),
                "the root-search window is part of the prewarm and must block the road");
    }

    /**
     * A second world must not inherit the first world's "requested" flag, or the road would
     * poll forever for a prewarm that the new world will never run.
     */
    @Test
    void aNewSessionStartsClean() {
        PrewarmProgress.clear();
        PrewarmProgress.request();
        PrewarmProgress.begin(4, 16);
        PrewarmProgress.finish(true);

        PrewarmProgress.clear();
        assertTrue(PrewarmProgress.awaitCompletion(0, TimeUnit.NANOSECONDS),
                "a fresh session must report done until its own prewarm is requested");
    }
}
