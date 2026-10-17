package local.plaincraft.greatwall.planning;

import java.util.ArrayDeque;
import java.util.OptionalInt;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.Quality;

/**
 * Decides which columns of one wall cross-section the accurate probe must visit, and what the
 * remaining columns are filled with.
 *
 * <h2>Why this exists</h2>
 * A cross-section is {@code 2 * LATERAL + 1} columns per route point, and the corridor is
 * millions of blocks long, so the fine sample's cost is essentially the number of columns it probes.
 * This plan probes a sparse base set, then spends extra probes only where the terrain demands them.
 *
 * <h2>The three phases</h2>
 * <ol>
 *   <li><b>Sparse base</b> - slots {@code 0, step, 2*step, ...} plus the last slot, so a
 *       cross-section always has both edges resolved.</li>
 *   <li><b>Refinement</b> - for adjacent probed slots {@code (a, b)} with {@code b - a > 1} whose
 *       heights differ by at least {@code steepThreshold}, probe the midpoint and repeat. A step in
 *       the terrain is exactly where a wall foundation changes level, so those columns are never
 *       guessed.</li>
 *   <li><b>Conservative fill</b> - every remaining slot takes the <em>maximum</em> of its two nearest
 *       probed neighbours.</li>
 * </ol>
 *
 * <h2>Red lines</h2>
 * <b>Never shave the mountain.</b> A filled column is never below either neighbour, so a foundation
 * may end up a little high over a dip - it fills dirt instead of cutting rock. Heights on the edges of
 * the cross-section are always real probes, and a fill always has a probed neighbour on both sides
 * (slot 0 and the last slot are in the base set).
 *
 * <p><b>Water is never filled.</b> Height can be bounded conservatively from neighbours; water cannot
 * be guessed at all. Filling a water column as land makes the wall run through a river; filling land as
 * water makes the assembly skip those blocks ({@code ComponentAssembly} skips {@code Part.WATER}) and
 * leaves holes in the wall. So any water seen while probing escalates the whole cross-section to
 * full resolution, and the escalation carries to the next route point so a water body cannot end
 * between two points.
 *
 * <p><b>Determinism.</b> The schedule is a pure function of the probed values: the base set is fixed,
 * refinement is driven only by heights already probed, and escalation is driven only by water already
 * seen. Probe order cannot change the outcome, which is what keeps a published recipe valid.
 *
 * <p>Not thread safe; one plan per cross-section, owned by the cursor walking it.
 */
final class FineSamplePlan {

    /** How aggressively the plan may thin the base set. */
    enum Mode {
        /** Probe every column: the behaviour before thinning existed. */
        FULL,
        /** Sparse base + refinement + conservative fill. */
        AUTO
    }

    /**
     * Plan inputs. A record so a test can pin them without touching the config file.
     *
     * @param coreHalfWidth slots within this distance of the cross-section centre are always probed at
     *                      stride 1, however coarse {@code step} is. Thinning is only ever allowed to
     *                      reach the flanks, because the centre band is the wall body itself and because
     *                      the fill rule cannot see a feature narrower than the stride: a one-block
     *                      spike or pond between two probed columns is invisible to both the refinement
     *                      rule (heights match) and the water rule. Keeping the core exact bounds that
     *                      blind spot to the flanks, where the wall is only approaches and stairs.
     */
    record Rules(Mode mode, int step, int steepThreshold, boolean refineWater, int coreHalfWidth) {
        Rules {
            if (step < 1) throw new IllegalArgumentException("step must be >= 1");
            if (steepThreshold < 1) throw new IllegalArgumentException("steep threshold must be >= 1");
            if (coreHalfWidth < 0) throw new IllegalArgumentException("core width must be >= 0");
        }

        static Rules full() { return new Rules(Mode.FULL, 1, 1, true, Integer.MAX_VALUE); }

        /**
         * Current user settings.
         *
         * <p>{@code fineStep = 1} means {@link Mode#FULL}: the staging switch and the "no thinning at
         * all" switch are the same knob, which keeps the config to four numbers and makes "behave
         * exactly like before" reachable without a second flag.
         */
        static Rules fromConfig() {
            // Frozen snapshot, not the live config: these four numbers decide which columns of a
            // cross-section are probed and how the gaps are filled, so a mid-world edit would change the
            // terrain the second half of the wall is built on.
            var conf = local.plaincraft.greatwall.config.GreatWallConfig.parameters();
            int step = conf.fineStep();
            // The grid thins the section before this plan ever sees it (see WallFinePlanner), so core
            // width has to be counted in grid slots to keep meaning "the wall body, plus/minus eight
            // blocks": at a grid step of four, slot 2 is block 8.
            int gridStep = conf.fineSampleGridStep();
            int coreSlots = gridStep <= 1 ? conf.coreHalfWidth() : conf.coreHalfWidth() / gridStep;
            return new Rules(step <= 1 ? Mode.FULL : Mode.AUTO, step,
                    conf.steepThreshold(), conf.refineWater(), coreSlots);
        }
    }

    private final int size;
    private final Rules rules;
    private final ColumnSample[] probed;
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();
    private boolean escalating;
    private boolean refined;
    private int emitted;

    FineSamplePlan(int size, Rules rules) { this(size, rules, false); }

    /**
     * @param carryWater true when the previous route point escalated because of water. One block along
     *                   the route is not enough for a water body to end, so the escalation has to carry
     *                   to the next cross-section; otherwise a river could be filled as land exactly at
     *                   the point where its last visible column sat on the previous cross-section.
     */
    FineSamplePlan(int size, Rules rules, boolean carryWater) {
        this(size, rules, carryWater, size / 2);
    }

    /**
     * Same, with the slot that is the cross-section centre.
     *
     * <p>The centre used to be {@code size / 2}, which is the centre only while the section is the whole
     * {@code -halfWidth..+halfWidth} band. The session-wide claim set drops the columns another slice
     * already owns, so {@code size} shrinks and its midpoint slides away from the real centre line: the
     * core band - the columns that are always probed instead of filled - then starts somewhere else and
     * the columns that matter most fall into the throttled flank. The caller knows the true centre, so it
     * passes it.
     */
    FineSamplePlan(int size, Rules rules, boolean carryWater, int centreSlot) {
        if (size < 1) throw new IllegalArgumentException("cross-section must be non-empty");
        this.size = size;
        this.rules = rules;
        this.probed = new ColumnSample[size];
        if (rules.mode() == Mode.FULL) {
            for (int slot = 0; slot < size; slot++) queue.add(slot);
            return;
        }
        if (carryWater && rules.refineWater()) {
            escalating = true;
            for (int slot = 0; slot < size; slot++) queue.add(slot);
            return;
        }
        int centre = Math.max(0, Math.min(size - 1, centreSlot));
        for (int slot = 0; slot < size; slot++) {
            // Stride on the flanks, every column in the core, both edges always probed.
            if (slot % rules.step() == 0 || Math.abs(slot - centre) <= rules.coreHalfWidth()) {
                queue.add(slot);
            }
        }
        if (!queue.contains(size - 1)) queue.add(size - 1);
        if (!queue.contains(0)) queue.add(0);
    }

    /** The next slot to probe, or {@code -1} when probing is finished. */
    int nextSlot() {
        // Repeated until a sweep adds nothing: refining a pair can expose a new stepped pair, so one
        // sweep is not enough. The sweep is a pure function of the probed values, so repeating it
        // cannot make the schedule depend on anything but the terrain.
        if (queue.isEmpty() && rules.mode() == Mode.AUTO && !escalating) refine();
        Integer slot = queue.poll();
        return slot == null ? -1 : slot;
    }

    /** Records a finished probe. Water escalates, a step schedules refinement. */
    void record(int slot, ColumnSample sample) {
        if (slot < 0 || slot >= size) throw new IllegalArgumentException("slot out of range");
        if (sample == null) throw new NullPointerException("sample");
        if (probed[slot] != null) return;   // idempotent: a repeat must not restart a sweep
        probed[slot] = sample;
        if (rules.mode() != Mode.AUTO) return;
        if (rules.refineWater() && sample.waterTopY().isPresent() && !escalating) {
            escalating = true;
            queue.clear();
            for (int missing = 0; missing < size; missing++) {
                if (probed[missing] == null) queue.add(missing);
            }
        }
    }

    /** True once every slot is either probed or unconditionally filled. */
    boolean probingDone() { return queue.isEmpty(); }

    /** Whether this cross-section had to escalate to full resolution because of water. */
    boolean escalated() { return escalating; }

    /** The number of slots this plan still expects to probe. */
    int pendingProbes() { return queue.size(); }

    /**
     * The column to emit for a slot: the real probe when there is one, otherwise the conservative
     * fill. Fills are emitted in slot order after probing finishes.
     */
    ColumnSample column(int slot) {
        if (slot < 0 || slot >= size) throw new IllegalArgumentException("slot out of range");
        ColumnSample real = probed[slot];
        if (real != null) return real;
        if (!probingDone()) throw new IllegalStateException("column requested before probing finished");
        return fill(slot);
    }

    private ColumnSample fill(int slot) {
        int left = nearestProbed(slot, -1);
        int right = nearestProbed(slot, +1);
        if (left < 0 || right < 0) {
            // Cannot happen by construction (both edges are always probed), but a fill must never be
            // invented from one side: say so instead of guessing a height.
            throw new IllegalStateException("fill without a probed neighbour on both sides");
        }
        ColumnSample l = probed[left];
        ColumnSample r = probed[right];
        // Maximum, never an average or an interpolation: a foundation may sit high over a dip, but it
        // must never cut into the higher of its neighbours.
        int ground = Math.max(l.groundTopY(), r.groundTopY());
        // Dry, with the left neighbour's biome: water is never synthesised (see the class doc).
        return new ColumnSample(ground, OptionalInt.empty(), OptionalInt.empty(),
                l.biomeKey(), Quality.REFERENCE, false);
    }

    private int nearestProbed(int slot, int direction) {
        for (int i = slot + direction; i >= 0 && i < size; i += direction) {
            if (probed[i] != null) return i;
        }
        return -1;
    }

    /** Queues the midpoints of every adjacent probed pair whose heights step by the threshold. */
    private void refine() {
        for (int slot = 0; slot < size; slot++) {
            if (probed[slot] == null) continue;
            int next = nearestProbed(slot, +1);
            if (next < 0) return;
            int gap = next - slot;
            if (gap <= 1) continue;
            if (Math.abs(probed[next].groundTopY() - probed[slot].groundTopY()) < rules.steepThreshold()) {
                continue;
            }
            for (int missing = slot + 1; missing < next; missing++) {
                if (probed[missing] == null) queue.add(missing);
            }
        }
    }

    /** Marks the plan as fully emitted so a caller can assert one emission per slot. */
    boolean emitNext() { return ++emitted <= size; }

    int emitted() { return emitted; }
}
