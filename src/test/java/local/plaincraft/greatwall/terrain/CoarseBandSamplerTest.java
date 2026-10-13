package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.Quality;
import local.plaincraft.greatwall.model.WallTypes.SampleKind;
import local.plaincraft.greatwall.model.WallTypes.WorkState;
import org.junit.jupiter.api.Test;

/**
 * Locks what the coarse band sweep promises: it covers exactly the band's lattice, it never invents a
 * height for a column it could not resolve, and a cell it did not visit stays flagged for refinement.
 */
class CoarseBandSamplerTest {

    @Test
    void sweepsEveryLatticeCellAndReportsMonotoneProgress() {
        var progress = new ArrayList<Integer>();
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var result = CoarseBandSampler.sample(oracle,
                    new CoarseBandSampler.Band(0, 0, 8, 8), 4, progress::add);
            assertEquals(9, result.samples() + result.skipped(), "3x3 cells must be visited");
            assertEquals(0, result.skipped(), "the analytic oracle resolves every coarse column");
            assertEquals(9, progress.size(), "one progress report per column");
            for (int i = 1; i < progress.size(); i++) {
                assertTrue(progress.get(i) > progress.get(i - 1), "progress must be monotone");
            }
            assertFalse(result.lattice().unsampled(0, 0), "the first cell must be sampled");
            assertFalse(result.lattice().unsampled(8, 8), "the far corner must be sampled");
            assertFalse(result.lattice().unsampled(4, 4), "the middle cell of the 3x3 grid is sampled too");
        }
    }

    @Test
    void cellHeightMatchesTheCoarseProbeAndStaysConservative() {
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var result = CoarseBandSampler.sample(oracle,
                    new CoarseBandSampler.Band(0, 0, 0, 0), 4, null);
            assertEquals(1, result.samples());
            int height = result.lattice().cellMaxY(0, 0);
            assertTrue(height > 0, "a resolved cell must carry a real height, got " + height);
            // A single-sample cell has zero spread, so nothing about it demands refinement by relief.
            assertEquals(0, result.lattice().cellSpread(0, 0));
        }
    }

    @Test
    void anUnsampledCellIsAlwaysRefinedRatherThanGuessed() {
        try (var oracle = AnalyticTerrainOracle.ridge(true)) {
            var result = CoarseBandSampler.sample(oracle,
                    new CoarseBandSampler.Band(0, 0, 4, 4), 4, null);
            var rules = CoarseHeightLattice.RefineRules.defaults();
            assertTrue(result.lattice().needsRefine(400, 400, rules),
                    "outside the band the lattice must ask for the accurate probe");
            assertEquals(CoarseHeightLattice.NO_DATA, result.lattice().cellMaxY(400, 400));
        }
    }

    @Test
    void aColumnThatRefusesToFinishIsAbandonedInsteadOfPoisoningTheLane() {
        try (var oracle = new StepBudgetOracle()) {
            var result = CoarseBandSampler.sample(oracle,
                    new CoarseBandSampler.Band(0, 0, 8, 8), 4, null);
            assertEquals(1, oracle.abandoned, "the refusing cursor has to be handed back");
            assertEquals(1, result.skipped(), "and only the refusing column is skipped");
            assertEquals(8, result.samples(), "every other column must still be sampled");
        }
    }

    /**
     * Models the production lane contract: one active cursor at a time, and a cursor that is never
     * finished blocks every later {@code begin} until it is abandoned ({@code CpuTerrainOracle} throws
     * "finish the current natural column before beginning another"). The first column of the band is
     * the one that refuses to finish; every other column resolves at once.
     */
    private static final class StepBudgetOracle implements NaturalTerrainOracle {
        private int abandoned;
        private boolean active;

        @Override public ColumnCursor begin(int x, int z, SampleKind kind) {
            if (active) throw new IllegalStateException("finish the current natural column before beginning another");
            if (x == 0 && z == 0) {
                active = true;
                return () -> false; // burns the sampler's whole step budget on every advance
            }
            return () -> true;
        }

        @Override public WorkState advance(ColumnCursor cursor, int primitiveLimit) {
            return WorkState.MORE;
        }

        @Override public ColumnSample finish(ColumnCursor cursor) {
            return new ColumnSample(64, OptionalInt.empty(), OptionalInt.empty(),
                    "minecraft:plains", Quality.REFERENCE, false);
        }

        @Override public void abandon(ColumnCursor cursor) {
            abandoned++;
            active = false;
        }

        @Override public String fingerprint() { return "step-budget-fixture"; }

        @Override public void close() {}
    }
}
