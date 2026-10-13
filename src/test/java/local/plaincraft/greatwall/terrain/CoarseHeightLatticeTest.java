package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks the two rules the two-level sampler must never break: a coarse answer is never below the
 * highest ground in its cell (so a wall is never cut into a peak), and the refine decision is a pure
 * function of coarse data (so a recipe stays valid across runs).
 */
class CoarseHeightLatticeTest {

    private static final CoarseHeightLattice.RefineRules DEFAULTS =
            CoarseHeightLattice.RefineRules.defaults();

    @Test
    void cellMaxIsNeverBelowAnySampleInThatCell() {
        var lattice = new CoarseHeightLattice.Builder(4, 0, 0, 4, 4)
                .offer(0, 0, 70, false)
                .offer(1, 2, 83, false)
                .offer(3, 3, 76, false)
                .build();
        assertEquals(83, lattice.cellMaxY(0, 0));
        assertEquals(83, lattice.cellMaxY(2, 1), "every column in the cell sees the cell maximum");
        assertEquals(83, lattice.cellMaxY(3, 3));
        assertEquals(3, lattice.sampleCount(1, 1));
    }

    @Test
    void unsampledCellHasNoHeightAndMustBeRefined() {
        var lattice = new CoarseHeightLattice.Builder(4, 0, 0, 2, 2)
                .offer(0, 0, 64, false)
                .build();
        assertEquals(CoarseHeightLattice.NO_DATA, lattice.cellMaxY(5, 5));
        assertTrue(lattice.unsampled(5, 5));
        assertTrue(lattice.needsRefine(5, 5, DEFAULTS));
        // Out of coverage entirely: still "unsampled", never a silent zero height.
        assertEquals(CoarseHeightLattice.NO_DATA, lattice.cellMaxY(-100, -100));
        assertTrue(lattice.needsRefine(-100, -100, DEFAULTS));
    }

    @Test
    void reliefAtTheThresholdRefinesAndFlatterCellsDoNot() {
        var steep = new CoarseHeightLattice.Builder(4, 0, 0, 1, 1)
                .offer(0, 0, 70, false).offer(3, 3, 73, false).build();
        assertEquals(3, steep.cellSpread(0, 0));
        assertTrue(steep.needsRefine(1, 1, DEFAULTS), "relief at the threshold is not trusted coarsely");

        var flat = new CoarseHeightLattice.Builder(4, 0, 0, 1, 1)
                .offer(0, 0, 70, false).offer(3, 3, 72, false).build();
        assertEquals(2, flat.cellSpread(0, 0));
        assertFalse(flat.needsRefine(1, 1, DEFAULTS));
        assertEquals(72, flat.cellMaxY(1, 1));
    }

    @Test
    void waterOnlyRefinesWhenTheRulesAskForIt() {
        var wet = new CoarseHeightLattice.Builder(4, 0, 0, 1, 1)
                .offer(0, 0, 63, true).offer(2, 2, 63, false).build();
        assertTrue(wet.needsRefine(1, 1, DEFAULTS));
        assertFalse(wet.needsRefine(1, 1,
                        new CoarseHeightLattice.RefineRules(3, false, 4)),
                "with water refinement off the cell is flat and dry enough to answer coarsely");
    }

    @Test
    void aStepBetweenCellsRefinesBothSides() {
        var lattice = new CoarseHeightLattice.Builder(4, 0, 0, 2, 1)
                .offer(0, 0, 70, false).offer(4, 0, 80, false).build();
        assertTrue(lattice.needsRefine(1, 1, DEFAULTS), "the high side of the step");
        assertTrue(lattice.needsRefine(5, 1, DEFAULTS), "the low side of the step");
        // Both cells are flat on their own: only the neighbour rule can be firing here.
        assertEquals(0, lattice.cellSpread(1, 1));
        assertEquals(0, lattice.cellSpread(5, 1));
    }

    @Test
    void neighbourJumpCanBeDisabled() {
        var lattice = new CoarseHeightLattice.Builder(4, 0, 0, 2, 1)
                .offer(0, 0, 70, false).offer(4, 0, 80, false).build();
        var noJump = new CoarseHeightLattice.RefineRules(3, false, 0);
        assertFalse(lattice.needsRefine(1, 1, noJump));
        assertFalse(lattice.needsRefine(5, 1, noJump));
        assertEquals(80, lattice.cellMaxY(5, 1), "the coarse answer still stays conservative");
    }

    @Test
    void refineDecisionIsIndependentOfInsertionOrder() {
        List<int[]> samples = new ArrayList<>(List.of(
                new int[] {0, 0, 70, 0}, new int[] {2, 2, 74, 0}, new int[] {3, 1, 63, 1},
                new int[] {4, 0, 71, 0}, new int[] {5, 3, 80, 0}, new int[] {7, 2, 72, 0}));
        var forward = build(samples);
        Collections.reverse(samples);
        var backward = build(samples);
        for (int x = -2; x < 10; x++) {
            for (int z = -2; z < 6; z++) {
                assertEquals(forward.cellMaxY(x, z), backward.cellMaxY(x, z),
                        "height at " + x + "," + z);
                assertEquals(forward.needsRefine(x, z, DEFAULTS), backward.needsRefine(x, z, DEFAULTS),
                        "refine decision at " + x + "," + z);
            }
        }
    }

    private static CoarseHeightLattice build(List<int[]> samples) {
        var builder = new CoarseHeightLattice.Builder(4, 0, 0, 2, 2);
        for (int[] sample : samples) {
            builder.offer(sample[0], sample[1], sample[2], sample[3] != 0);
        }
        return builder.build();
    }
}
