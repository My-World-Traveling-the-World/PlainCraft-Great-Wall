package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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
}
