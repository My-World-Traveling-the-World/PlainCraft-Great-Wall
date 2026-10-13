package local.plaincraft.greatwall.terrain;

import java.util.function.IntConsumer;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.Quality;
import local.plaincraft.greatwall.model.WallTypes.SampleKind;

/**
 * Walks a corridor band with the cheap {@link SampleKind#COARSE} probe and fills a
 * {@link CoarseHeightLattice}.
 *
 * <h2>What the coarse probe is and is not</h2>
 * It evaluates the density router only: no block states, no support check, no water. So it is a
 * <em>predictor</em>, never a source of truth - which is exactly why a coarse answer may never be
 * published as a wall height (the downstream pipeline requires {@code Quality.REFERENCE} and treats
 * anything else as invalid). Its two legitimate uses are choosing how densely the accurate probe
 * should sweep, and filtering hopeless root candidates before paying for an accurate probe.
 *
 * <p>Because it cannot see water, the lattice's water flag stays false for every coarse cell. Water is
 * only ever learned from accurate probes; {@link CoarseHeightLattice} keeps the flag for exactly that
 * reason (a sparse reference sweep feeds it), and this pass must not be mistaken for a water source.
 *
 * <h2>Failure policy</h2>
 * A coarse pass is an optimisation, so it never fails the wall: a column that does not finish within
 * {@link #MAX_STEPS_PER_COLUMN} primitives, or that comes back censored, is simply left out of the
 * lattice. An unvisited cell reports {@link CoarseHeightLattice#NO_DATA} and
 * {@link CoarseHeightLattice#needsRefine} answers true for it, i.e. the accurate probe still visits it.
 * That keeps a broken coarse pass a slowdown rather than a correctness problem.
 *
 * <h2>Status: no caller yet (deliberate)</h2>
 * This is a tested building block with no consumer. It was written for the two uses that were
 * evaluated and then dropped - pre-filtering root-search candidates, and filling a second loading-card
 * progress line - because the numbers did not support them: root search screens about 12k columns
 * against the fine sample's roughly 400k (some 3%, i.e. seconds), its screening criterion needs water
 * evidence that a coarse probe cannot see, and a GPU offload at that volume costs more in transfer
 * than it saves. The full evaluation is recorded in {@code roadweaver-变更历史.md}. It is kept because
 * it is the only place that documents what a coarse probe can and cannot answer, and because a future
 * pass that genuinely needs bulk coarse terrain can start from a tested component. Nothing calls it,
 * so it costs nothing at runtime.
 */
public final class CoarseBandSampler {

    /**
     * Ceiling on primitives spent on one coarse column. A density-only probe resolves in a handful of
     * steps; this only stops a malformed router from holding the pass forever.
     */
    private static final int MAX_STEPS_PER_COLUMN = 4096;

    /** Primitive grant per advance call, kept inside the 1..256 window the cursor contracts enforce. */
    private static final int STEP_GRANT = 64;

    private CoarseBandSampler() {}

    /** An inclusive block rectangle to sweep. */
    public record Band(int minX, int minZ, int maxX, int maxZ) {
        public Band {
            if (maxX < minX || maxZ < minZ) throw new IllegalArgumentException("empty band");
        }
    }

    /** The swept lattice plus how many columns actually contributed. */
    public record Result(CoarseHeightLattice lattice, int samples, int skipped) {}

    /**
     * Sweeps the band at {@code step} block spacing.
     *
     * @param step blocks between coarse samples; this is the lattice's cell size as well, so the
     *             lattice geometry and the sampling stride cannot drift apart
     * @param progress optional sink receiving the number of columns finished so far
     */
    public static Result sample(NaturalTerrainOracle oracle, Band band, int step, IntConsumer progress) {
        if (step < 1) throw new IllegalArgumentException("step must be >= 1");
        int width = Math.floorDiv(band.maxX() - band.minX(), step) + 1;
        int depth = Math.floorDiv(band.maxZ() - band.minZ(), step) + 1;
        var builder = new CoarseHeightLattice.Builder(step, band.minX(), band.minZ(), width, depth);
        int samples = 0;
        int skipped = 0;
        for (int x = band.minX(); x <= band.maxX(); x += step) {
            for (int z = band.minZ(); z <= band.maxZ(); z += step) {
                ColumnSample sample = probe(oracle, x, z);
                if (sample == null) {
                    skipped++;
                } else {
                    // The coarse probe cannot report water, so every coarse cell is dry by definition.
                    builder.offer(x, z, sample.groundTopY(), false);
                    samples++;
                }
                if (progress != null) progress.accept(samples + skipped);
            }
        }
        return new Result(builder.build(), samples, skipped);
    }

    /** One coarse column, or {@code null} when it is censored or refuses to finish. */
    private static ColumnSample probe(NaturalTerrainOracle oracle, int x, int z) {
        var cursor = oracle.begin(x, z, SampleKind.COARSE);
        int steps = 0;
        while (!cursor.done()) {
            if (++steps > MAX_STEPS_PER_COLUMN) return null;
            oracle.advance(cursor, STEP_GRANT);
        }
        ColumnSample sample = oracle.finish(cursor);
        // Only a censored column is unusable: it carries no resolved surface. Any other quality is
        // accepted, because an oracle that answers a coarse request with a better sample is still a
        // perfectly good predictor - and a stricter check would make this pass reject test oracles
        // that do not model probe kinds at all.
        return sample.upperCensored() || sample.quality() == Quality.CENSORED ? null : sample;
    }
}
