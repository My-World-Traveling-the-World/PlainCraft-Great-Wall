package local.plaincraft.greatwall.terrain;

import java.util.Arrays;

/**
 * Coarse height lattice over the corridor band: one cell per {@code cellSize} x {@code cellSize}
 * block square, holding the extremes the coarse pass observed inside that square.
 *
 * <h2>Why a lattice</h2>
 * The fine sample walks the wall's whole cross-section - the centre line plus {@code +/-24} lateral
 * columns, 49 columns per route point - with the accurate probe, and that probe is the expensive one
 * (a real block-state walk per column). The coarse pass covers the same band with far fewer columns,
 * and this lattice is what lets the fine sample skip the columns whose cell it already knows enough
 * about.
 *
 * <h2>Never shave the mountain</h2>
 * A column that is not refined is answered with {@link #cellMaxY}: the highest ground the coarse pass
 * saw anywhere inside that cell. A foundation may therefore end up a little high over a cell whose
 * interior dips - it fills dirt instead of cutting rock. That asymmetry is deliberate and is the rule
 * this class exists to enforce. {@code cellMaxY} is never below any coarse sample inside the cell, and
 * a cell with no data is refined rather than guessed.
 *
 * <h2>Determinism</h2>
 * {@link #needsRefine} is a pure function of the coarse data - no thread order, no timing, no
 * iteration over a concurrent collection. The same world refines exactly the same columns on every
 * run, which is what keeps a published recipe valid.
 *
 * <p>Not thread safe: built and read by one pass at a time. The builder is filled from whatever pool
 * the coarse pass runs on, and the lattice is frozen before the fine sample reads it.
 */
public final class CoarseHeightLattice {

    /** Cells below this many blocks of relief are considered flat enough to answer coarsely. */
    public static final int DEFAULT_STEEP_SPREAD_BLOCKS = 3;

    /** Adjacent cells whose maxima differ by this much always get refined on both sides. */
    public static final int DEFAULT_NEIGHBOUR_JUMP_BLOCKS = 4;

    private final int cellSize;
    private final int originX;
    private final int originZ;
    private final int width;
    private final int depth;
    private final int[] maxY;
    private final int[] minY;
    private final int[] sampleCount;
    private final boolean[] water;

    private CoarseHeightLattice(Builder builder) {
        this.cellSize = builder.cellSize;
        this.originX = builder.originX;
        this.originZ = builder.originZ;
        this.width = builder.width;
        this.depth = builder.depth;
        this.maxY = builder.maxY;
        this.minY = builder.minY;
        this.sampleCount = builder.sampleCount;
        this.water = builder.water;
    }

    /** Blocks per cell edge; also the coarse sampling stride. */
    public int cellSize() { return cellSize; }

    /** Highest coarse ground height inside the cell holding this column, or {@code NO_DATA}. */
    public int cellMaxY(int x, int z) {
        int index = index(x, z);
        return index < 0 ? NO_DATA : maxY[index];
    }

    /** True when the coarse pass never visited the cell holding this column. */
    public boolean unsampled(int x, int z) {
        int index = index(x, z);
        return index < 0 || sampleCount[index] == 0;
    }

    /** Coarse samples the pass recorded inside this cell (0 when unsampled). */
    public int sampleCount(int x, int z) {
        int index = index(x, z);
        return index < 0 ? 0 : sampleCount[index];
    }

    /** Relief inside the cell holding this column, or {@code NO_DATA} when unsampled. */
    public int cellSpread(int x, int z) {
        int index = index(x, z);
        if (index < 0 || sampleCount[index] == 0) return NO_DATA;
        return maxY[index] - minY[index];
    }

    /**
     * Whether the accurate probe must still visit this column.
     *
     * <p>Pure in the coarse data: same lattice and same rules always give the same answer. A cell is
     * refined when it is unsampled (never guess), when its relief reaches {@code steepSpreadBlocks}
     * (a flat answer would shave the peak), when it holds water and the rules ask for water, or when it
     * meets a neighbour whose maximum differs by {@code neighbourJumpBlocks} - both sides of a step
     * must be exact, because the wall's foundation steps there.
     *
     * <p>The wall footprint's own edge band is not decided here: it depends on the lateral offset
     * inside the cross-section, which only the planner knows.
     */
    public boolean needsRefine(int x, int z, RefineRules rules) {
        int index = index(x, z);
        if (index < 0) return true;
        if (sampleCount[index] == 0) return true;
        if (rules.refineWater() && water[index]) return true;
        if (maxY[index] - minY[index] >= rules.steepSpreadBlocks()) return true;
        int cellX = index % width;
        int cellZ = index / width;
        if (neighbourJump(cellX, cellZ, rules.neighbourJumpBlocks())) return true;
        return false;
    }

    /** Whether any of the four neighbours differs from this cell's maximum by the jump threshold. */
    private boolean neighbourJump(int cellX, int cellZ, int jump) {
        int here = maxY[cellZ * width + cellX];
        if (jump <= 0) return false;
        if (cellX > 0 && Math.abs(maxY[cellZ * width + cellX - 1] - here) >= jump) return true;
        if (cellX + 1 < width && Math.abs(maxY[cellZ * width + cellX + 1] - here) >= jump) return true;
        if (cellZ > 0 && Math.abs(maxY[(cellZ - 1) * width + cellX] - here) >= jump) return true;
        if (cellZ + 1 < depth && Math.abs(maxY[(cellZ + 1) * width + cellX] - here) >= jump) return true;
        return false;
    }

    private int index(int x, int z) {
        int cellX = Math.floorDiv(x - originX, cellSize);
        int cellZ = Math.floorDiv(z - originZ, cellSize);
        if (cellX < 0 || cellZ < 0 || cellX >= width || cellZ >= depth) return -1;
        return cellZ * width + cellX;
    }

    /** The value {@link #cellMaxY} returns for a column outside the coarse pass's coverage. */
    public static final int NO_DATA = Integer.MIN_VALUE;

    /** What the fine sample must not trust the coarse pass about. */
    public record RefineRules(int steepSpreadBlocks, boolean refineWater, int neighbourJumpBlocks) {
        public static RefineRules defaults() {
            return new RefineRules(DEFAULT_STEEP_SPREAD_BLOCKS, true, DEFAULT_NEIGHBOUR_JUMP_BLOCKS);
        }
    }

    /** Accumulates coarse samples into cells. One builder per lattice; not thread safe by design. */
    public static final class Builder {
        private final int cellSize;
        private final int originX;
        private final int originZ;
        private final int width;
        private final int depth;
        private final int[] maxY;
        private final int[] minY;
        private final int[] sampleCount;
        private final boolean[] water;

        /**
         * @param cellSize blocks per cell edge (the coarse stride)
         * @param originX  block x of the lattice origin; cell (0,0) covers
         *                 {@code [originX, originX + cellSize)}
         */
        public Builder(int cellSize, int originX, int originZ, int width, int depth) {
            if (cellSize < 1) throw new IllegalArgumentException("cell size must be positive");
            if (width < 1 || depth < 1) throw new IllegalArgumentException("lattice must be non-empty");
            this.cellSize = cellSize;
            this.originX = originX;
            this.originZ = originZ;
            this.width = width;
            this.depth = depth;
            int cells = Math.multiplyExact(width, depth);
            this.maxY = new int[cells];
            this.minY = new int[cells];
            Arrays.fill(this.maxY, NO_DATA);
            Arrays.fill(this.minY, Integer.MAX_VALUE);
            this.sampleCount = new int[cells];
            this.water = new boolean[cells];
        }

        /** Records one coarse sample. Samples outside the lattice are ignored. */
        public Builder offer(int x, int z, int groundY, boolean wet) {
            int cellX = Math.floorDiv(x - originX, cellSize);
            int cellZ = Math.floorDiv(z - originZ, cellSize);
            if (cellX < 0 || cellZ < 0 || cellX >= width || cellZ >= depth) return this;
            int index = cellZ * width + cellX;
            // max/min, never average: the answer the fine sample may reuse must not sit below the
            // highest ground in the cell, or the wall would cut into the peak.
            if (maxY[index] == NO_DATA || groundY > maxY[index]) maxY[index] = groundY;
            if (groundY < minY[index]) minY[index] = groundY;
            sampleCount[index]++;
            if (wet) water[index] = true;
            return this;
        }

        public CoarseHeightLattice build() { return new CoarseHeightLattice(this); }
    }
}
