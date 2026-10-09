package local.plaincraft.greatwall.lifecycle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import local.plaincraft.greatwall.GreatWallMod;

/**
 * Publishes startup prewarm progress, and lets a startup peer wait for it to finish.
 *
 * <h2>Progress</h2>
 * Deliberately shaped like the river patch's progress holder: a begin/setCompleted/finish
 * lifecycle over a total and a completed count, read by a client-side renderer that draws
 * a card during level loading. The wall's prewarm is the longest single stage of a new
 * world, and until now it was invisible - the loading screen showed the rivers finish and
 * then appeared to hang for twenty minutes.
 *
 * <h2>Waiting</h2>
 * Three stages are meant to run in order: rivers, then the wall, then roads. RoadWeaver
 * owns the last two - it finishes the rivers and immediately begins building roads - so
 * without a way for it to ask whether the wall is ready, the two compete for the same
 * machine. A measured session showed the result: 257 watchdog rescues on the wall's
 * scheduler, meaning the server thread stopped delivering ticks for three seconds at a
 * time while the other stage held it.
 *
 * <h2>Contract</h2>
 * Every method is safe to call when no prewarm is running - a session without wall
 * generation, before the wall starts, or after it has finished. In those cases
 * {@link #awaitCompletion} returns immediately and {@link #snapshot} reports inactive.
 * Nothing here throws to the caller: a startup peer must never fail because the wall did.
 */
public final class PrewarmProgress {
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final AtomicInteger TOTAL_SEGMENTS = new AtomicInteger();
    private static final AtomicInteger COMPLETED_SEGMENTS = new AtomicInteger();
    private static final AtomicInteger LANES = new AtomicInteger();
    private static final AtomicLong STARTED_AT_NANOS = new AtomicLong();
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static final ConcurrentHashMap<String, Boolean> STATE = new ConcurrentHashMap<>();
    private static final Object LATCH_LOCK = new Object();
    private static volatile CountDownLatch settled = new CountDownLatch(1);

    private PrewarmProgress() {
    }

    /** Records that a prewarm began, how many segments it will produce, and on how many lanes. */
    public static void begin(int totalSegments, int lanes) {
        synchronized (LATCH_LOCK) {
            // A fresh latch per prewarm: a session may prewarm, finish, and prewarm again
            // after a dimension change, and a stale completed latch would make the second
            // wait return instantly.
            settled = new CountDownLatch(1);
        }
        TOTAL_SEGMENTS.set(Math.max(0, totalSegments));
        COMPLETED_SEGMENTS.set(0);
        LANES.set(Math.max(1, lanes));
        STARTED_AT_NANOS.set(System.nanoTime());
        FAILED.set(false);
        STATE.clear();
        ACTIVE.set(totalSegments > 0);
    }

    /** Selects the label shown while the root position is still being chosen. */
    public static void enterRootSearch() {
        STATE.put("root-search", true);
    }

    /** Selects the label shown once segments are being planned. */
    public static void enterPlanning() {
        STATE.remove("root-search");
    }

    /** Records one more finished segment. */
    public static void setCompleted(int completedSegments) {
        COMPLETED_SEGMENTS.set(Math.max(0, Math.min(TOTAL_SEGMENTS.get(), completedSegments)));
    }

    /** Records that the prewarm finished, successfully or not. */
    public static void finish(boolean success) {
        COMPLETED_SEGMENTS.set(TOTAL_SEGMENTS.get());
        FAILED.set(!success);
        ACTIVE.set(false);
        STATE.clear();
        var latch = settled;
        if (latch != null) latch.countDown();
    }

    /** Clears everything; used between sessions so a stale snapshot cannot be served. */
    public static void clear() {
        ACTIVE.set(false);
        TOTAL_SEGMENTS.set(0);
        COMPLETED_SEGMENTS.set(0);
        LANES.set(0);
        STARTED_AT_NANOS.set(0L);
        FAILED.set(false);
        STATE.clear();
        synchronized (LATCH_LOCK) {
            settled = new CountDownLatch(1);
        }
    }

    public static boolean isActive() { return ACTIVE.get(); }

    /**
     * Blocks until the prewarm finishes, or until {@code timeout} elapses.
     *
     * <p>Returns true if the prewarm settled - successfully or having failed - and false on
     * timeout. A caller that gets false should proceed anyway: waiting forever on a stage
     * that may never report would turn a slow startup into a hang, and the roads are still
     * useful without the wall.
     *
     * <p>Returns true immediately when no prewarm ever started, so a session without wall
     * generation is not delayed at all.
     */
    public static boolean awaitCompletion(long timeout, TimeUnit unit) {
        var latch = settled;
        if (latch == null) return true;
        try {
            boolean finished = latch.await(timeout, unit);
            if (!finished) {
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] PREWARM_AWAIT_TIMEOUT waitedMs={} active={} "
                                + "completed={}/{} ; proceeding without waiting further",
                        unit.toMillis(timeout), ACTIVE.get(), COMPLETED_SEGMENTS.get(), TOTAL_SEGMENTS.get());
            }
            return finished;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static Snapshot snapshot() {
        int total = TOTAL_SEGMENTS.get();
        int completed = Math.min(total, COMPLETED_SEGMENTS.get());
        long startedAt = STARTED_AT_NANOS.get();
        long elapsedMillis = startedAt == 0L
                ? 0L
                : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedAt));
        return new Snapshot(ACTIVE.get(), total, completed, LANES.get(), elapsedMillis,
                STATE.containsKey("root-search"), FAILED.get());
    }

    /**
     * One consistent view of the prewarm.
     *
     * @param active       whether a prewarm is in progress right now
     * @param total        segments this prewarm will produce; zero until known
     * @param completed    segments finished so far
     * @param lanes        native lanes the prewarm is using, for the detail line
     * @param elapsedMillis wall-clock time since the prewarm began
     * @param rootSearch   true while the root position is still being chosen
     * @param failed       true once a prewarm has finished unsuccessfully
     */
    public record Snapshot(boolean active, int total, int completed, int lanes, long elapsedMillis,
                           boolean rootSearch, boolean failed) {
        public int percent() {
            if (total <= 0) return 0;
            return Math.max(0, Math.min(100, Math.round(completed * 100.0F / total)));
        }

        /** Seconds elapsed, formatted for the detail line. */
        public String elapsedSeconds() {
            return String.format(java.util.Locale.ROOT, "%.1f", elapsedMillis / 1000.0D);
        }
    }
}
