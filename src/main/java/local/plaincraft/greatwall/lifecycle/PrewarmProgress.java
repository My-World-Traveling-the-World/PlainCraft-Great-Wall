package local.plaincraft.greatwall.lifecycle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
    /**
     * Whether a prewarm has started in this session.
     *
     * <p>Distinguishes "no prewarm will ever run" from "a prewarm is running and has not
     * finished" - states an unreleased latch cannot tell apart.
     */
    private static final AtomicBoolean EVER_STARTED = new AtomicBoolean();
    /**
     * Whether a prewarm has been requested, even if it has not started planning yet.
     *
     * <h2>The gap this closes</h2>
     * The rivers finishing and the first segment being planned are not the same instant.
     * Between them the wall installs its recipe, freezes terrain inputs and searches for a
     * root position - measured at over ten minutes on a real world. A peer that gates in
     * that window must wait, because the prewarm has already been asked for and will run.
     *
     * <p>Without this the peer saw "no prewarm has started" and returned ready in about a
     * millisecond, which is exactly what a measured session did: the gate opened at 16:20:50
     * with {@code waitedMs=1}, the wall's root search began the same second, and road work
     * proceeded alongside it - the very overlap the gate exists to prevent.
     *
     * <p>So this is set when the prewarm is requested, and a waiter waits once it is set.
     * {@link #EVER_STARTED} still decides the different question of whether a prewarm has
     * actually begun, which the progress display uses.
     */
    private static final AtomicBoolean REQUESTED = new AtomicBoolean();
    private static final ConcurrentHashMap<String, Boolean> STATE = new ConcurrentHashMap<>();
    private static final Object LATCH_LOCK = new Object();
    private static volatile CountDownLatch settled = new CountDownLatch(1);
    /**
     * Released once the wall can actually paint, which is later than {@link #settled}.
     *
     * <h2>Why the peer gate needs its own latch</h2>
     * {@code settled} releases when the bulk prewarm's segments are planned, because
     * {@code blockAndPumpUntilPrewarmSettled} parks the server thread on it and the compile that
     * follows can only run once that thread is free (latch -> compile -> tick -> managedBlock).
     * That makes {@code settled} the wrong signal to hand a startup peer: RoadWeaver gated on it
     * and began its initial generation in the same second the prewarm completed, while the wall's
     * compile had not run yet.
     *
     * <p>This latch is released by {@code publishPlanningWindow}, i.e. once the chunk plans are in
     * {@code WallWorldgenPlanCache} and the corridor/biome masks are published - the point after
     * which every newly generated chunk really does carry the wall. A peer that waits on it cannot
     * overlap the wall's compile.
     */
    private static volatile CountDownLatch worldgenReady = new CountDownLatch(1);
    /** Candidate index the root search has reached, and the total it will scan. */
    private static final AtomicInteger ROOT_CANDIDATE = new AtomicInteger();
    private static final AtomicInteger ROOT_TOTAL = new AtomicInteger();
    /** Root-search phase (0 coarse, 1 footprint, 2 axis, 4 structure, 5 entrance). */
    private static final AtomicInteger ROOT_PHASE = new AtomicInteger();
    /** Structure-screen candidate the wall is validating, and the total. */
    private static final AtomicInteger STRUCTURE_CANDIDATE = new AtomicInteger();
    private static final AtomicInteger STRUCTURE_TOTAL = new AtomicInteger();

    /**
     * The wall's startup is a sequence of large stages, each with a sub-progress. The loading card
     * shows every stage as a line in order, highlights the active one and lights the wall line-art
     * left-to-right with the overall fraction. ROOT_SEARCH keeps its existing phase detail; the
     * newer stages (structure scan, fine sampling, compile) each report a done/total pair.
     */
    public enum Stage {
        ROOT_SEARCH, STRUCTURE_SCAN, FINE_SAMPLE, PREWARM, COMPILE, DONE
    }
    /**
     * Overall-percent weight of each stage, indexed by {@link Stage#ordinal()}. The weights are the
     * stages' relative measured durations on a real world, and they must sum to 100.
     *
     * <p>Measured on the 2026-10-15 world: root search 128 s, structure scan 181 s, fine sample
     * 158 s (708 s on the session before it - it is the one stage whose cost swings with how much
     * terrain the route crosses), segment prewarm 20.5 s, compile unknown but bounded by the owner
     * count now that it reports a real total. The previous table gave the prewarm 40 of the 100
     * points for those 20 seconds and the structure scan only 10 for its 181, so the bar crawled
     * through the scan and then filled half its length at the very end.
     */
    private static final int[] STAGE_WEIGHT = {22, 30, 28, 5, 15, 0};
    /** Monotonic overall peak for the loading card; reset when a new prewarm begins or clears. */
    private static final java.util.concurrent.atomic.AtomicInteger PEAK_OVERALL_PERCENT =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final AtomicReference<Stage> STAGE = new AtomicReference<>(Stage.ROOT_SEARCH);
    /**
     * Measured wall-clock nanoseconds per {@link Stage}, indexed by ordinal.
     *
     * <p>The startup is four five-minute stages and nobody could say which one took what without
     * grepping a log by hand. The account is kept here because this is the one class every stage
     * transition already passes through, and it is printed once, at the end.
     */
    private static final java.util.concurrent.atomic.AtomicLongArray STAGE_NANOS =
            new java.util.concurrent.atomic.AtomicLongArray(Stage.values().length);
    /** When the active stage started, or 0 when no account is running. */
    private static volatile long stageSinceNanos;
    /** When the first startup stage opened; the wall-clock anchor for totalMs. */
    private static final java.util.concurrent.atomic.AtomicLong SESSION_START_NANOS =
            new java.util.concurrent.atomic.AtomicLong();
    /** Sub-progress of the active stage: done/total; total 0 means "indeterminate, still running". */
    private static final AtomicInteger SUB_DONE = new AtomicInteger();
    private static final AtomicInteger SUB_TOTAL = new AtomicInteger();

    private PrewarmProgress() {
    }

    /** Starts a fresh stage account for a new startup. */
    private static void resetStageAccount() {
        for (int i = 0; i < STAGE_NANOS.length(); i++) STAGE_NANOS.set(i, 0L);
        long now = System.nanoTime();
        SESSION_START_NANOS.compareAndSet(0L, now);
        stageSinceNanos = now;
    }

    /** Charges the running stage with the time it has held so far, and stops the clock. */
    private static synchronized void closeStageAccount() {
        long since = stageSinceNanos;
        if (since == 0L) return;
        STAGE_NANOS.addAndGet(STAGE.get().ordinal(), Math.max(0L, System.nanoTime() - since));
        stageSinceNanos = 0L;
    }

    /** {@return the wall-clock milliseconds charged to one stage so far} */
    static long accountedMillis(Stage stage) {
        return STAGE_NANOS.get(stage.ordinal()) / 1_000_000L;
    }

    /** Selects the active large stage; the card dims earlier stages and lights the active one. */
    public static void setStage(Stage stage) {
        Stage next = stage == null ? Stage.ROOT_SEARCH : stage;
        Stage previous = STAGE.getAndSet(next);
        // A repeated call is a progress tick, not a transition: restarting the clock here would
        // charge the whole stage to whichever tick happened to be last.
        if (previous == next) return;
        long now = System.nanoTime();
        long since = stageSinceNanos;
        if (since != 0L) STAGE_NANOS.addAndGet(previous.ordinal(), Math.max(0L, now - since));
        stageSinceNanos = now;
    }

    /** Publishes the active stage's sub-progress (done/total); total 0 is an indeterminate running
     *  stage with no countable unit. */
    public static void setSubProgress(int done, int total) {
        SUB_DONE.set(Math.max(0, done));
        SUB_TOTAL.set(Math.max(0, total));
    }

    /**
     * Records that a prewarm has been asked for.
     *
     * <p>Called the moment the rivers report ready, which is well before the first segment
     * can be planned - the wall still has to install its recipe, freeze terrain inputs and
     * choose a root position. Waiteres that arrive in that window must wait, so this is
     * what {@link #awaitCompletion} tests; see {@link #REQUESTED}.
     */
    public static void request() {
        REQUESTED.set(true);
    }

    /** Records that a prewarm began, how many segments it will produce, and on how many lanes. */
    public static void begin(int totalSegments, int lanes) {
        REQUESTED.set(true);
        PEAK_OVERALL_PERCENT.set(0);
        synchronized (LATCH_LOCK) {
            // Replace the latch only once the previous one has been released.
            //
            // A fresh latch is needed so a second prewarm - after a dimension change, say - is
            // not instantly "finished" by the first one's countdown. But replacing it
            // unconditionally orphans anyone already waiting: awaitCompletion() reads the
            // reference once and then blocks on that object, so a peer that arrived before the
            // first begin() would wait on a latch nobody will ever count down and sit out its
            // entire timeout while the prewarm it was waiting for ran and finished normally.
            //
            // That ordering is the expected one, not a corner case: a startup peer gates as
            // soon as the road stage opens, while this begins only once the rivers are built
            // and the recipe is installed. Keeping the existing latch while it is still
            // unreleased means early waiters are released by the first finish(), and later
            // waiters after a completed prewarm get a new latch as intended.
            if (settled == null || settled.getCount() == 0L) {
                settled = new CountDownLatch(1);
            }
            // Same rule for the peer gate: a completed prewarm's latch must not make the next
            // prewarm look finished, and an unreleased one must not be replaced under a waiter.
            if (worldgenReady == null || worldgenReady.getCount() == 0L) {
                worldgenReady = new CountDownLatch(1);
            }
        }
        TOTAL_SEGMENTS.set(Math.max(0, totalSegments));
        COMPLETED_SEGMENTS.set(0);
        LANES.set(Math.max(1, lanes));
        STARTED_AT_NANOS.set(System.nanoTime());
        FAILED.set(false);
        STATE.clear();
        // Keep what the root search and the structure scan already cost: both run before this call, and
        // an unconditional reset made every measured session report rootMs=0 scanMs=0 (a real session:
        // 17 minutes of search, printed as zero).
        if (stageSinceNanos == 0L) resetStageAccount();
        ACTIVE.set(totalSegments > 0);
        EVER_STARTED.set(true);
    }

    /** Selects the label shown while the root position is still being chosen. */
    public static void enterRootSearch() {
        STATE.put("root-search", true);
    }

    /**
     * Marks the wall's startup phase as active while the root is still being chosen.
     *
     * <h2>Why a separate entry point</h2>
     * {@link #begin} is called only once the first segment is planned, and it sets active
     * from a segment count - so the whole root search, which runs for minutes between the
     * rivers finishing and that first segment, had no active state. The loading card
     * therefore skipped that window entirely, and the wall's longest invisible phase
     * looked exactly like a hang: no title, no bar, no elapsed time. A session that spent
     * most of its startup there appeared frozen to the player.
     *
     * <p>This marks the phase active with a zero segment total and the root-search label, so
     * the card shows "choosing the root position" with a live elapsed time. Once the search
     * lands and {@link #begin} runs, the segment count and percentage take over; the two are
     * the same lifecycle and {@link #finish} closes whichever one was entered.
     */
    public static void beginRootSearch(int lanes) {
        synchronized (LATCH_LOCK) {
            if (settled == null || settled.getCount() == 0L) {
                settled = new CountDownLatch(1);
            }
            if (worldgenReady == null || worldgenReady.getCount() == 0L) {
                worldgenReady = new CountDownLatch(1);
            }
        }
        // A new world in the same JVM must re-anchor the wall-clock total, or the second session
        // reports the first session's duration as well.
        SESSION_START_NANOS.set(0L);
        TOTAL_SEGMENTS.set(0);
        COMPLETED_SEGMENTS.set(0);
        LANES.set(Math.max(1, lanes));
        STARTED_AT_NANOS.set(System.nanoTime());
        FAILED.set(false);
        STATE.clear();
        STATE.put("root-search", true);
        resetStageAccount();
        ACTIVE.set(true);
        REQUESTED.set(true);
        EVER_STARTED.set(true);
    }

    /** Selects the label shown once segments are being planned. */
    public static void enterPlanning() {
        STATE.remove("root-search");
    }

    /**
     * Publishes the root search's candidate progress for the loading card.
     *
     * <p>The search scans a finite ring of candidates (the rings defined in RootSearch) before any
     * segment exists. Without this number the card could only show "searching", which looks
     * frozen for the minutes the search can take. The candidate index is the one monotonically
     * advancing signal, so it is what the card reports.
     */
    public static void setRootProgress(int candidate, int total) {
        ROOT_CANDIDATE.set(Math.max(0, candidate));
        ROOT_TOTAL.set(Math.max(0, total));
    }

    /**
     * Publishes the root-search phase for the loading card.
     *
     * <p>The phases are coarse (0), footprint (1), axis (2), structure screen (4) and entrance
     * (5). The card shows the phase so the player can tell "still screening ring candidates" from
     * "validating structure conflicts", which otherwise all read as one long "searching".
     */
    public static void setRootPhase(int phase) {
        ROOT_PHASE.set(Math.max(0, phase));
    }

    /**
     * Publishes the structure-screen progress for the loading card.
     *
     * <p>The structure screen validates each shortlisted candidate against native structure
     * starts; it can take minutes and previously showed nothing, which read as a freeze.
     */
    public static void setStructureProgress(int candidate, int total) {
        STRUCTURE_CANDIDATE.set(Math.max(0, candidate));
        STRUCTURE_TOTAL.set(Math.max(0, total));
    }

    /** Records one more finished segment. */
    public static void setCompleted(int completedSegments) {
        COMPLETED_SEGMENTS.set(Math.max(0, Math.min(TOTAL_SEGMENTS.get(), completedSegments)));
    }

    /** Records that the prewarm finished, successfully or not. */
    public static void finish(boolean success) {
        // The one place a session actually releases the road gate, so this is where the ordering question
        // gets answered. It used to be logged inside signalWorldgenReady, which clear() also calls to
        // release a stale latch before installing a fresh one - so every world load printed a release that
        // had not happened. A measured log opened with "ROAD_GATE_OPENED stage=ROOT_SEARCH completed=0/0"
        // five minutes before the root search even started.
        if (ACTIVE.get() || EVER_STARTED.get()) {
            var snap = snapshot();
            closeStageAccount();
            local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                    "[GreatWall:S1C] ROAD_GATE_OPENED success={} stage={} completed={}/{} elapsedMs={} - a "
                            + "startup peer (RoadWeaver) may now begin",
                    success, snap.stage(), snap.completed(), snap.total(), snap.elapsedMillis());
            // One line, once per session: what each startup stage actually cost. Without it the only
            // way to answer "where did the twenty minutes go" was to read timestamps out of the log.
            // Total is wall clock from the first stage (the root search), not from the prewarm begin:
            // the measured session spent 17.6 minutes searching and reported an 8.5-minute total.
            long sessionStart = SESSION_START_NANOS.get();
            long totalMs = sessionStart == 0L ? snap.elapsedMillis()
                    : (System.nanoTime() - sessionStart) / 1_000_000L;
            local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                    "[GreatWall:S1C] WALL_STARTUP_ACCOUNT totalMs={} rootMs={} scanMs={} fineMs={} "
                            + "prewarmMs={} compileMs={} segments={}/{} lanes={}",
                    totalMs,
                    accountedMillis(Stage.ROOT_SEARCH), accountedMillis(Stage.STRUCTURE_SCAN),
                    accountedMillis(Stage.FINE_SAMPLE), accountedMillis(Stage.PREWARM),
                    accountedMillis(Stage.COMPILE), snap.completed(), snap.total(), snap.lanes());
        }
        COMPLETED_SEGMENTS.set(TOTAL_SEGMENTS.get());
        FAILED.set(!success);
        ACTIVE.set(false);
        STATE.clear();
        var latch = settled;
        if (latch != null) latch.countDown();
        // A finished prewarm is always a settled peer gate, successful or not: a wall that cannot
        // paint must not hold the roads behind it for the whole timeout.
        signalWorldgenReady();
    }

    /**
     * Releases the startup-peer latch without closing the loading card.
     *
     * <p>The bulk prewarm (segment sampling) finishing and the whole wall startup finishing are
     * not the same instant: the fine sample and the chunk compile still run afterwards. The latch
     * is released as soon as the sampling settles - from the prewarm's completion callback in
     * {@code PlannerScheduler} - because {@code blockAndPumpUntilPrewarmSettled} parks the server
     * thread on this latch inside {@code managedBlock}, and the compile that still follows runs
     * from the server tick loop, which cannot fire until that managedBlock returns. Signalling only
     * at {@code ProductionSession.publishPlanningWindow} therefore deadlocked (latch -> compile ->
     * tick -> managedBlock). Releasing the peer here does not close the card: {@link #finish(boolean)}
     * still closes it at publishPlanningWindow once the compile has published.
     */
    public static void signalReady() {
        var latch = settled;
        if (latch != null) latch.countDown();
    }

    /**
     * Releases the worldgen gate: every chunk generated from now on carries the wall.
     *
     * <p>Called once the compile has published the chunk plans and the masks. A failed or empty
     * compile calls it too (through {@link #finish(boolean)}), because a wall that will never paint
     * must not park the roads behind a gate that can no longer open.
     */
    public static void signalWorldgenReady() {
        var latch = worldgenReady;
        // Silent on purpose: clear() calls this to release a stale latch before replacing it, so a log
        // here fires on every world load and reads as a gate that opened before anything ran. The real
        // release is logged by finish().
        if (latch != null) latch.countDown();
    }

    /**
     * Blocks until the whole wall startup has published, for a startup peer to call.
     *
     * <p>Stronger than {@link #awaitCompletion}: that one answers "have the segments been planned",
     * which the wall needed internally to avoid its own deadlock. This answers "will a chunk
     * generated now contain the wall", which is the question a peer - RoadWeaver's initial road
     * generation - actually has to ask. Waiting on the weaker signal is what let a measured session
     * start the roads in the same second the prewarm completed, while the compile had not run.
     *
     * @return true if the wall published, failed, or will not run at all; false on timeout
     */
    public static boolean awaitWorldgenReady(long timeout, TimeUnit unit) {
        if (!REQUESTED.get()) return true;
        var latch = worldgenReady;
        if (latch == null) return true;
        try {
            boolean ready = latch.await(timeout, unit);
            if (!ready && timeout != 0L) {
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] WORLDGEN_GATE_TIMEOUT waitedMs={} active={} "
                                + "stage={} completed={}/{} ; proceeding without waiting further",
                        unit.toMillis(timeout), ACTIVE.get(), STAGE.get(),
                        COMPLETED_SEGMENTS.get(), TOTAL_SEGMENTS.get());
            }
            return ready;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Clears everything; used between sessions so a stale snapshot cannot be served. */
    public static void clear() {
        ACTIVE.set(false);
        // A new world in the same JVM must re-anchor the wall-clock total, or the second session
        // reports the first session's duration as well.
        SESSION_START_NANOS.set(0L);
        TOTAL_SEGMENTS.set(0);
        COMPLETED_SEGMENTS.set(0);
        LANES.set(0);
        STARTED_AT_NANOS.set(0L);
        FAILED.set(false);
        PEAK_OVERALL_PERCENT.set(0);
        STATE.clear();
        ROOT_CANDIDATE.set(0);
        ROOT_TOTAL.set(0);
        ROOT_PHASE.set(0);
        STRUCTURE_CANDIDATE.set(0);
        STRUCTURE_TOTAL.set(0);
        // Reset with the rest: the next session's prewarm has not been requested yet, and
        // leaving this set would make a peer wait for a prewarm that may never run.
        EVER_STARTED.set(false);
        REQUESTED.set(false);
        synchronized (LATCH_LOCK) {
            // Release any waiter still parked on the old gate before replacing it: a session that is being
            // torn down must not leave a waiter holding a latch nobody will ever open. Both gates are
            // released - the old shape only opened worldgenReady, so a consumer that had already read the
            // settled latch waited out its own timeout.
            settled.countDown();
            settled = new CountDownLatch(1);
            // The replacement is needed so the next session's gate is not born already open.
            signalWorldgenReady();
            worldgenReady = new CountDownLatch(1);
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
     * <p>Returns true immediately when no prewarm has started, so a session without wall
     * generation is not delayed at all. That case cannot be inferred from the latch alone -
     * an unreleased latch looks identical whether a prewarm is running or none will ever run -
     * so it is decided from the recorded lifecycle state instead.
     */
    public static boolean awaitCompletion(long timeout, TimeUnit unit) {
        // Nothing has been requested: there is no prewarm to wait for, so the caller must not
        // be made to sit out a timeout for it. Tested against REQUESTED rather than
        // EVER_STARTED because the window between "the rivers are done" and "the first segment
        // is planned" is where the wall spends most of its startup - a waiter arriving there
        // would otherwise be told the work was already finished and let road generation start
        // alongside the prewarm.
        if (!REQUESTED.get()) return true;
        var latch = settled;
        if (latch == null) return true;
        try {
            boolean finished = latch.await(timeout, unit);
            if (!finished && timeout != 0L) {
                // A zero timeout is a poll, not a wait: the caller asked "is it done?" and will
                // ask again. Logging here would flood the log every tick the prewarm is still
                // running. Only a real wait that expires is worth reporting.
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
                STATE.containsKey("root-search"), FAILED.get(), ROOT_CANDIDATE.get(), ROOT_TOTAL.get(),
                ROOT_PHASE.get(), STRUCTURE_CANDIDATE.get(), STRUCTURE_TOTAL.get(),
                STAGE.get(), SUB_DONE.get(), SUB_TOTAL.get());
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
     * @param rootCandidate candidates the root search has scanned so far
     * @param rootTotal    total candidates the root search will scan
     * @param rootPhase    root-search phase (0 coarse, 1 footprint, 2 axis, 4 structure, 5 entrance)
     * @param structureCandidate structure-screen candidate being validated
     * @param structureTotal structure-screen candidate total
     * @param stage        the active large stage, for the stepper and the line-art band
     * @param subDone      done count of the active stage's sub-progress
     * @param subTotal     total of the active stage's sub-progress; 0 means indeterminate
     */
    public record Snapshot(boolean active, int total, int completed, int lanes, long elapsedMillis,
                           boolean rootSearch, boolean failed, int rootCandidate, int rootTotal,
                           int rootPhase, int structureCandidate, int structureTotal,
                           Stage stage, int subDone, int subTotal) {
        public int percent() {
            if (total <= 0) return 0;
            return Math.max(0, Math.min(100, Math.round(completed * 100.0F / total)));
        }

        /** Candidate fraction of the root search, for the searching label. */
        public int rootPercent() {
            if (rootTotal <= 0) return 0;
            return Math.max(0, Math.min(100, Math.round(rootCandidate * 100.0F / rootTotal)));
        }

        /**
         * Fraction of the active stage, 0-100.
         *
         * <p>Each stage picks the one count that monotonically advances while it runs: the
         * root search scans candidates, the structure scan resolves boxes, the fine sampling
         * measures columns, the prewarm plans segments and the compile writes chunks. A stage
         * with no countable unit yet reports 0 rather than a lie.
         */
        public int stagePercent() {
            return switch (stage) {
                case ROOT_SEARCH -> rootTotal > 0
                        ? rootPercent()
                        : (subTotal > 0 ? subPercent() : 0);
                case PREWARM -> total > 0
                        ? percent()
                        : (subTotal > 0 ? subPercent() : 0);
                case STRUCTURE_SCAN, FINE_SAMPLE, COMPILE -> subPercent();
                case DONE -> 100;
            };
        }

        /**
         * Fraction of the active stage's sub-counter, 0-100 - capped at 99 while the stage runs.
         *
         * <p>The cap is the point, not a rounding guard. A stage's sub-total is an <em>estimate</em>
         * made before the work starts (the fine sample guesses columns from the route geometry), and
         * an estimate that runs low used to turn the bar into a permanent 100%: a real session showed
         * "采样地形 435385/435385 列" for eleven minutes while the sampler climbed past 1.2M columns,
         * because the caller clamped the total to the done count and this fraction then could not be
         * anything but 100. A stage that is still running may therefore read 99 at most; only the
         * stage transition (or {@link #setCompleted}) reports 100.
         */
        private int subPercent() {
            if (subTotal <= 0) return 0;
            if (subDone >= subTotal) return 99;
            return Math.max(0, Math.min(99, Math.round(subDone * 100.0F / subTotal)));
        }

        /**
         * Overall startup progress across all the wall stages, 0-100.
         *
         * <p>Each stage owns the slice of the bar given by {@link #STAGE_WEIGHT}; the active
         * stage fills its slice by {@link #stagePercent()}. Finished stages fill theirs
         * completely, pending stages not at all, so the number only ever moves forward.
         */
        public int overallPercent() {
            if (!active && !failed) return 100;
            int done = 0;
            int activeOrdinal = stage.ordinal();
            for (int i = 0; i < activeOrdinal && i < STAGE_WEIGHT.length; i++) {
                done += STAGE_WEIGHT[i];
            }
            if (activeOrdinal < STAGE_WEIGHT.length) {
                done += Math.round(STAGE_WEIGHT[activeOrdinal] * stagePercent() / 100.0F);
            }
            final int calculated = Math.max(0, Math.min(100, done));
            // Monotonic peak guard (mirrors RoadWeaver's PEAK_OVERALL_PERCENT): the card never
            // walks backwards when a stage's sub-total is not ready yet or the stage flips.
            return PEAK_OVERALL_PERCENT.updateAndGet(current -> Math.max(current, calculated));
        }

        /**
         * Estimated remaining wall-clock milliseconds, or -1 when no estimate exists yet.
         *
         * <p>Derived from the elapsed time and the overall fraction: once a fifth of the
         * startup is done the remaining time is extrapolated as elapsed * (1-f) / f. Before
         * that the estimate swings too wildly to be worth showing.
         */
        public long etaMillis() {
            if (!active || elapsedMillis <= 0L) return -1L;
            int overall = overallPercent();
            if (overall <= 20 || overall >= 100) return -1L;
            return Math.round(elapsedMillis * (100.0D - overall) / overall);
        }

        /** Seconds elapsed, formatted for the detail line. */
        public String elapsedSeconds() {
            return String.format(java.util.Locale.ROOT, "%.1f", elapsedMillis / 1000.0D);
        }
    }
}
