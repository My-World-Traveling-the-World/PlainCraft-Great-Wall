package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A startup peer must not generate chunks until they would actually carry the wall.
 *
 * <h2>The measured defect this pins</h2>
 * This gate is what RoadWeaver's initial road generation waits on. It used to wait on the same
 * latch the loading card uses, which the bulk prewarm releases as soon as the segments have been
 * sampled. A measured session shows what that costs: {@code PREWARM_COMPLETE segments=4} and
 * {@code RoadWeaver initial generation hardware lanes prepared} landed in the same second, while
 * the chunk compile - the step that fills the in-memory plan table the wall feature reads - had
 * not run yet. Every chunk the roads generated in that window was written without a wall, and the
 * wall feature cannot retrofit a generated chunk.
 *
 * <p>So the prewarm's own completion signal must stay exactly as weak as it is, and the peer gate
 * must open only when the compile has published. These tests hold those two signals apart.
 */
class PrewarmWorldgenGateTest {
    @AfterEach
    void clearState() {
        PrewarmProgress.clear();
    }

    @Test
    void theSegmentSignalDoesNotReleaseTheWorldgenGate() throws Exception {
        PrewarmProgress.clear();
        PrewarmProgress.begin(4, 16);

        var released = new AtomicBoolean();
        var waiter = new Thread(() -> {
            // Long timeout: the point is that the gate opens, not that the wait expires.
            released.set(PrewarmProgress.awaitWorldgenReady(30, TimeUnit.SECONDS));
        }, "test-worldgen-waiter");
        waiter.start();
        Thread.sleep(150);

        // The bulk prewarm's completion signal: necessary to avoid the managedBlock deadlock, and
        // deliberately not enough to let a peer generate chunks.
        PrewarmProgress.signalReady();
        Thread.sleep(150);
        assertFalse(released.get(),
                "the segment signal must not release a peer before the compile has published");

        PrewarmProgress.signalWorldgenReady();
        waiter.join(TimeUnit.SECONDS.toMillis(10));
        assertTrue(released.get(), "publishing the window releases the peer");
    }

    @Test
    void publishingReleasesTheWorldgenGate() {
        PrewarmProgress.begin(4, 16);
        assertFalse(PrewarmProgress.awaitWorldgenReady(0, TimeUnit.NANOSECONDS),
                "a running prewarm with no compile yet is not a released gate");

        PrewarmProgress.finish(true);
        assertTrue(PrewarmProgress.awaitWorldgenReady(0, TimeUnit.NANOSECONDS));
    }

    /**
     * A failed wall does NOT open the peer gate.
     *
     * <p>This pins the opposite of the previous contract. Releasing on failure let a session start
     * its roads fifty seconds after the compile died, so the roads were laid through a corridor the
     * wall had never published; the release also made "the wall finished" and "the wall gave up"
     * indistinguishable to the peer. The peer now waits on its own stall timeout instead, which
     * {@link #aFailedPrewarmStillSettlesItsOwnWaiters} covers.
     */
    @Test
    void aFailedPrewarmHoldsTheWorldgenGate() {
        PrewarmProgress.begin(4, 16);

        PrewarmProgress.finish(false);
        assertFalse(PrewarmProgress.awaitWorldgenReady(0, TimeUnit.NANOSECONDS));
        // The prewarm's own waiter is still released: the wall giving up must not hang its own
        // loading block, only the peer's gate.
        assertTrue(PrewarmProgress.awaitCompletion(0, TimeUnit.NANOSECONDS));
    }

    @Test
    void aSuccessfulPrewarmReleasesTheWorldgenGate() {
        PrewarmProgress.begin(4, 16);

        PrewarmProgress.finish(true);
        assertTrue(PrewarmProgress.awaitWorldgenReady(0, TimeUnit.NANOSECONDS));
        assertTrue(PrewarmProgress.awaitCompletion(0, TimeUnit.NANOSECONDS));
    }

    @Test
    void withoutARequestedPrewarmTheGateIsOpen() {
        PrewarmProgress.clear();

        // No wall generation in this session: a peer must not pay a timeout for a prewarm that
        // will never run. The latch alone cannot say this, so the lifecycle state decides it.
        assertTrue(PrewarmProgress.awaitWorldgenReady(0, TimeUnit.NANOSECONDS));
    }

    @Test
    void aSecondPrewarmClosesTheGateAgain() {
        PrewarmProgress.begin(4, 16);
        PrewarmProgress.finish(true);
        assertTrue(PrewarmProgress.awaitWorldgenReady(0, TimeUnit.NANOSECONDS));

        // The next wall must not inherit the previous session's open gate.
        PrewarmProgress.begin(4, 16);
        assertFalse(PrewarmProgress.awaitWorldgenReady(0, TimeUnit.NANOSECONDS),
                "a new prewarm starts closed, whatever the previous one did");
    }
}
