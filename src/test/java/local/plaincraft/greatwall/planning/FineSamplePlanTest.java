package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.Quality;
import org.junit.jupiter.api.Test;

/**
 * Locks what {@link FineSamplePlan} is allowed to do: a filled column is never below either probed
 * neighbour, thinning never reaches the centre band, water always escalates to a full cross-section,
 * and the whole schedule is a pure function of the probed values.
 *
 * <p>It also pins the blind spot honestly: a feature narrower than the stride, hiding between two
 * probed columns, is invisible to the plan. That is why the core band is exempt from thinning.
 */
class FineSamplePlanTest {

    private static final int SIZE = 49;
    private static final FineSamplePlan.Rules AUTO =
            new FineSamplePlan.Rules(FineSamplePlan.Mode.AUTO, 2, 3, true, 12);

    @Test
    void coreBandIsNeverThinnedAndBothEdgesAreAlwaysProbed() {
        var order = new ArrayList<Integer>();
        run(new FineSamplePlan(SIZE, AUTO), flat(70), none(), order);
        for (int slot = 0; slot < SIZE; slot++) {
            if (Math.abs(slot - SIZE / 2) <= 12 || slot == 0 || slot == SIZE - 1) {
                assertTrue(order.contains(slot), "core or edge slot must be probed: " + slot);
            }
        }
    }

    @Test
    void flatTerrainThinsTheFlanksButKeepsEveryCentreColumnExact() {
        int[] terrain = flat(70);
        var order = new ArrayList<Integer>();
        int[] emitted = run(new FineSamplePlan(SIZE, AUTO), terrain, none(), order);
        assertArrayEquals(terrain, emitted, "a flat cross-section must come out unchanged");
        assertTrue(order.size() < SIZE, "a flat cross-section must probe fewer than every column");
        // Flanks are thinned, core is not: 24 outer columns at stride two.
        assertTrue(order.size() <= 38, "only the flanks may thin (core 25 + even flank columns)");
    }

    @Test
    void filledColumnsAreNeverBelowEitherProbedNeighbour() {
        int[] terrain = new int[SIZE];
        for (int slot = 0; slot < SIZE; slot++) terrain[slot] = 64 + (slot % 7) * 3;
        var order = new ArrayList<Integer>();
        int[] emitted = run(new FineSamplePlan(SIZE, AUTO), terrain, none(), order);
        for (int slot = 0; slot < SIZE; slot++) {
            if (order.contains(slot)) {
                assertEquals(terrain[slot], emitted[slot], "a probed column keeps its exact height");
                continue;
            }
            int left = nearest(order, slot, -1);
            int right = nearest(order, slot, +1);
            assertTrue(left >= 0 && right >= 0, "a fill always has probed neighbours on both sides");
            int floor = Math.max(terrain[left], terrain[right]);
            assertEquals(floor, emitted[slot], "a fill takes the maximum of its probed neighbours");
            assertTrue(emitted[slot] >= terrain[left] && emitted[slot] >= terrain[right],
                    "never shave: the fill is not below either probed neighbour");
        }
    }

    @Test
    void aHeightsStepOnTheFlankIsProbedNotFilled() {
        int[] terrain = flat(70);
        terrain[4] = 85;                    // a cliff between probed flank columns 4 and 6
        var order = new ArrayList<Integer>();
        int[] emitted = run(new FineSamplePlan(SIZE, AUTO), terrain, none(), order);
        assertTrue(order.contains(5), "the midpoint of a stepped pair must be probed");
        assertEquals(85, emitted[4], "the cliff top is exact");
        assertEquals(70, emitted[5], "the refined midpoint reports the real terrain there");
        assertEquals(70, emitted[3], "a stepped gap is probed outright, not filled, so it reports real terrain");
    }

    @Test
    void waterEscalatesTheWholeCrossSectionAndNeverGetsFilled() {
        boolean[] water = new boolean[SIZE];
        water[2] = true;
        var order = new ArrayList<Integer>();
        var plan = new FineSamplePlan(SIZE, AUTO);
        int[] emitted = run(plan, flat(70), water, order);
        assertTrue(plan.escalated(), "water must escalate the cross-section");
        assertEquals(SIZE, order.size(), "an escalated cross-section probes every column");
        assertEquals(OptionalInt.of(63), plan.column(2).waterTopY(), "the water column keeps its level");
        for (int slot = 0; slot < SIZE; slot++) {
            if (slot == 2) continue;
            assertTrue(plan.column(slot).waterTopY().isEmpty(),
                    "no other column may invent a water level: " + slot);
        }
        assertEquals(70, emitted[0]);
    }

    @Test
    void carriedWaterEscalatesBeforeAnyProbeOfThisSectionSeesIt() {
        // Two adjacent route points are one block apart, which is not enough for a water body to end.
        // When the previous section escalated, the next one has to start escalated as well - even
        // though none of its own probes has seen water yet. A skipped (redundant) section must not
        // forget that flag, so this also pins the mechanism the skip path relies on.
        var order = new ArrayList<Integer>();
        var plan = new FineSamplePlan(SIZE, AUTO, true);
        run(plan, flat(70), none(), order);
        assertTrue(plan.escalated(), "a carried escalation must not be forgotten");
        assertEquals(SIZE, order.size(), "a carried cross-section probes every column");
    }

    @Test
    void theScheduleIsAPureFunctionOfTheProbedValues() {
        int[] terrain = new int[SIZE];
        for (int slot = 0; slot < SIZE; slot++) terrain[slot] = 60 + (slot * slot) % 13;
        var first = new ArrayList<Integer>();
        var second = new ArrayList<Integer>();
        int[] a = run(new FineSamplePlan(SIZE, AUTO), terrain, none(), first);
        int[] b = run(new FineSamplePlan(SIZE, AUTO), terrain, none(), second);
        assertEquals(first, second, "same terrain must yield the same probe order");
        assertArrayEquals(a, b, "same terrain must yield the same emitted columns");
    }

    @Test
    void fullModeStillProbesEveryColumn() {
        var order = new ArrayList<Integer>();
        var plan = new FineSamplePlan(SIZE, FineSamplePlan.Rules.full());
        run(plan, flat(70), none(), order);
        assertEquals(SIZE, order.size(), "FULL must behave exactly like the old fixed sweep");
        assertFalse(plan.escalated(), "FULL never needs to escalate");
    }

    // ── helpers ──

    private static int[] run(FineSamplePlan plan, int[] terrain, boolean[] water, List<Integer> order) {
        int[] emitted = new int[terrain.length];
        int slot;
        while ((slot = plan.nextSlot()) >= 0) {
            order.add(slot);
            plan.record(slot, sample(terrain, water, slot));
        }
        assertTrue(plan.probingDone(), "probing must be finished before columns are read");
        for (int i = 0; i < terrain.length; i++) emitted[i] = plan.column(i).groundTopY();
        return emitted;
    }

    private static ColumnSample sample(int[] terrain, boolean[] water, int slot) {
        boolean wet = water != null && water[slot];
        return new ColumnSample(terrain[slot],
                wet ? OptionalInt.of(63) : OptionalInt.empty(),
                wet ? OptionalInt.of(62) : OptionalInt.empty(),
                "minecraft:plains", Quality.REFERENCE, false);
    }

    private static int nearest(List<Integer> order, int slot, int direction) {
        for (int i = slot + direction; i >= 0 && i < SIZE; i += direction) {
            if (order.contains(i)) return i;
        }
        return -1;
    }

    private static int[] flat(int height) {
        int[] terrain = new int[SIZE];
        java.util.Arrays.fill(terrain, height);
        return terrain;
    }

    private static boolean[] none() { return new boolean[SIZE]; }
}
