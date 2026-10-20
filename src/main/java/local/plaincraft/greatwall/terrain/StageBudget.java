package local.plaincraft.greatwall.terrain;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One budget for every heavy stage of a startup.
 *
 * <p>Each of the wall's stages is parallel on its own, and each sizes its pool to the core count. That
 * is right stage by stage and wrong together: twelve sample lanes plus twelve prewarm lanes on a
 * sixteen thread part is twenty-four threads, so each stage spends its time waiting for the other's
 * threads. A measured session showed it from both sides - scanMs 271535 while the sample ran beside
 * it, then fineMs 281685 once the scan was serialised instead.
 *
 * <p>Permits are deliberately fewer than the cores (cores minus two) so the server thread and the
 * world's own generation keep room. Acquisition is timed rather than unbounded: a unit that cannot get
 * a permit runs anyway after a short wait and is counted, because a slow wall is better than a hung
 * one, and a nested acquisition would otherwise deadlock the pool without saying so.
 */
public final class StageBudget {
    private static final int PERMITS = Math.max(4,
            Math.min(16, Runtime.getRuntime().availableProcessors() - 2));
    private static final long WAIT_MILLIS = Long.getLong("greatwall.stageBudgetWaitMillis", 2_000L);
    private static final Semaphore SLOTS = new Semaphore(PERMITS, true);
    private static final AtomicLong OVERFLOWS = new AtomicLong();
    private static final AtomicLong ACQUIRED = new AtomicLong();

    private StageBudget() {}

    /** {@return how many heavy units may run at once} */
    public static int permits() { return PERMITS; }

    /** {@return units granted a slot so far} */
    public static long acquired() { return ACQUIRED.get(); }

    /** {@return units that ran over budget because no slot arrived in time} */
    public static long overflows() { return OVERFLOWS.get(); }

    /**
     * Takes one slot.
     *
     * @return true when a slot was taken and must be released; false when the caller is running over
     *         budget and holds nothing
     */
    public static boolean acquireSlot() {
        try {
            if (SLOTS.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                ACQUIRED.incrementAndGet();
                return true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        OVERFLOWS.incrementAndGet();
        return false;
    }

    /** Releases a slot taken by {@link #acquireSlot()}. */
    public static void releaseSlot() { SLOTS.release(); }
}