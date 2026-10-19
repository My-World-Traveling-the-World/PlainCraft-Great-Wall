package local.plaincraft.greatwall.terrain;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.function.Predicate;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * The road's terrain sampler, for the wall.
 *
 * <h2>What this copies, and why</h2>
 * RoadWeaver's {@code NoiseChunkHeightSampler} reproduces what vanilla itself would write into the
 * heightmaps: it bakes a real {@link NoiseChunk} - aquifer, surface blocks and all - and walks each
 * column down with the three vanilla heightmap predicates. That is why its heights match the world
 * exactly, and the wall's do not: {@code CpuTerrainOracle.preliminarySurface} binary-searches
 * {@code initialDensityWithoutJaggedness} for a single threshold, which has no notion of aquifers,
 * carvers or overhangs, and lands on bedrock wherever the density happens to hover around the
 * threshold. The measured session accepted {@code groundTopY=-61} under a sparse jungle whose real
 * surface was y=121, and started the wall at y=-44.
 *
 * <p>The port is deliberately narrow: {@code worldSurface} (the wall's ground) and
 * {@code oceanFloor} (its water bed). A column is only baked once - the wall sweeps 33 to 105 wide
 * cross-sections and revisits the same cells constantly, so the per-chunk cache does the same work
 * the road's does.
 *
 * <h2>Threading</h2>
 * A {@link NoiseChunk} is not thread safe. One sampler belongs to one lane thread, exactly like the
 * oracle that owns it.
 */
final class NoiseSurfaceSampler {
    /** Vanilla records "the y of the first air above the surface", so ground = surface - 1. */
    private static final int COLUMN_COUNT = 256;
    /** Two sweeps of the wall revisit the same chunk far more often than they visit 64 distinct ones. */
    private static final int CACHE_CHUNKS = 512;

    private final RandomState randomState;
    private final NoiseSettings noiseSettings;
    private final NoiseGeneratorSettings generatorSettings;
    private final Aquifer.FluidPicker fluidPicker;
    private final BlockState defaultBlock;
    private final LinkedHashMap<Long, Heights> cache = new LinkedHashMap<>(512, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Long, Heights> eldest) {
            return size() > CACHE_CHUNKS;
        }
    };
    private long sweeps;
    private long corrections;

    NoiseSurfaceSampler(RandomState randomState, NoiseSettings noiseSettings,
            NoiseGeneratorSettings generatorSettings, Aquifer.FluidPicker fluidPicker) {
        this.randomState = randomState;
        this.noiseSettings = noiseSettings;
        this.generatorSettings = generatorSettings;
        this.fluidPicker = fluidPicker;
        this.defaultBlock = generatorSettings.defaultBlock();
    }

    /** {@return the vanilla WORLD_SURFACE_WG height} in heightmap semantics, or the dimension floor. */
    int worldSurface(int x, int z) {
        int cellWidth = noiseSettings.getCellWidth();
        return heightsFor(x, z).worldSurface[(x & (cellWidth - 1)) + ((z & (cellWidth - 1)) * cellWidth)];
    }

    /** {@return the vanilla OCEAN_FLOOR_WG height} in heightmap semantics. */
    int oceanFloor(int x, int z) {
        int cellWidth = noiseSettings.getCellWidth();
        return heightsFor(x, z).oceanFloor[(x & (cellWidth - 1)) + ((z & (cellWidth - 1)) * cellWidth)];
    }

    /** {@return the three solid states under this column's surface, or null when unknown} */
    BlockState[] supportColumn(int x, int z) {
        int cellWidth = noiseSettings.getCellWidth();
        BlockState[] support = heightsFor(x, z).support[(x & (cellWidth - 1)) + ((z & (cellWidth - 1)) * cellWidth)];
        return support == null ? null : support.clone();
    }

    long sweeps() { return sweeps; }
    long corrections() { return corrections; }
    void countedCorrection() { corrections++; }

    private Heights heightsFor(int x, int z) {
        // Keyed by the noise CELL, not the chunk. The wall asks for one column at a time over a ring
        // thousands of chunks wide, so a chunk bake would compute 256 columns to answer one: measured
        // 67 ms per column. One cell holds cellWidth^2 columns (16 at cellWidth 4), which is what a
        // sparse query actually needs.
        int cellWidth = noiseSettings.getCellWidth();
        long key = ((long) Math.floorDiv(x, cellWidth) << 32) ^ (Math.floorDiv(z, cellWidth) & 0xffffffffL);
        Heights cached = cache.get(key);
        if (cached != null) return cached;
        Heights fresh = sweep(Math.floorDiv(x, cellWidth), Math.floorDiv(z, cellWidth));
        cache.put(key, fresh);
        sweeps++;
        return fresh;
    }

    /**
     * Bakes one noise cell: cellWidth x cellWidth columns.
     *
     * <p>The port used to bake a whole chunk (256 columns) because that is what the road needs: it
     * samples a road footprint, which is dense. The wall asks for single columns spread over a ring
     * thousands of chunks wide, so a chunk bake computed 255 columns nobody read - measured 67 ms per
     * answered column, and 3.4 s per siting candidate.
     */
    private Heights sweep(int cellX, int cellZ) {
        int cellWidth = noiseSettings.getCellWidth();
        int columns = cellWidth * cellWidth;
        int[] worldSurface = new int[columns];
        int[] oceanFloor = new int[columns];
        int minY = noiseSettings.minY();
        Arrays.fill(worldSurface, minY);
        Arrays.fill(oceanFloor, minY);

        int cellHeight = noiseSettings.getCellHeight();
        int cellCountY = Mth.floorDiv(noiseSettings.height(), cellHeight);
        int cellNoiseMinY = Mth.floorDiv(minY, cellHeight);
        int airSkipMargin = generatorSettings.seaLevel() - minY + 32;
        BlockState[][] support = new BlockState[columns][];
        int[] supportDepth = new int[columns];
        java.util.Arrays.fill(supportDepth, -1);
        byte[] unresolvedMasks = new byte[columns];
        Arrays.fill(unresolvedMasks, (byte) 0x03);
        int unresolvedColumns = columns;

        Predicate<BlockState> worldSurfacePredicate = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
        Predicate<BlockState> oceanFloorPredicate = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();

        int minBlockX = cellX * cellWidth;
        int minBlockZ = cellZ * cellWidth;
        AccessibleNoiseChunk noiseChunk = new AccessibleNoiseChunk(1, randomState, minBlockX, minBlockZ,
                noiseSettings, ZeroBeardifier.INSTANCE, generatorSettings, fluidPicker);
        noiseChunk.initializeForFirstCellX();
        try {
            noiseChunk.advanceCellX(0);
            int repX = minBlockX + (cellWidth >> 1);
            int repZ = minBlockZ + (cellWidth >> 1);
            int startCellY = Mth.floorDiv(preliminarySurface(repX, repZ) + airSkipMargin, cellHeight)
                    - cellNoiseMinY;
            startCellY = Math.max(0, Math.min(cellCountY - 1, startCellY));
            outer:
            for (int cellY = startCellY; cellY >= 0; cellY--) {
                noiseChunk.selectCellYZ(cellY, 0);
                for (int inCellY = cellHeight - 1; inCellY >= 0; inCellY--) {
                    int blockY = (cellNoiseMinY + cellY) * cellHeight + inCellY;
                    noiseChunk.updateForY(blockY, (double) inCellY / cellHeight);
                    for (int inCellX = 0; inCellX < cellWidth; inCellX++) {
                        noiseChunk.updateForX(minBlockX + inCellX, (double) inCellX / cellWidth);
                        for (int inCellZ = 0; inCellZ < cellWidth; inCellZ++) {
                            int index = inCellX + inCellZ * cellWidth;
                            byte mask = unresolvedMasks[index];
                            if (mask == 0) continue;
                            noiseChunk.updateForZ(minBlockZ + inCellZ, (double) inCellZ / cellWidth);
                            BlockState state = noiseChunk.sampleInterpolatedState();
                            if (state == null) state = defaultBlock;
                            if (state.isAir()) continue;
                            byte next = mask;
                            if ((next & 0x01) != 0 && worldSurfacePredicate.test(state)) {
                                worldSurface[index] = blockY + 1;
                                supportDepth[index] = 0;
                                if (support[index] == null) support[index] = new BlockState[3];
                                support[index][supportDepth[index]++] = state;
                                next &= ~0x01;
                            }
                            if ((next & 0x02) != 0 && oceanFloorPredicate.test(state)) {
                                oceanFloor[index] = blockY + 1;
                                next &= ~0x02;
                            }
                            // The first solid block under the surface starts the foundation evidence: the
                            // wall certifies its base against these three states, and reading them from the
                            // same vanilla sweep is what lets the density walk go away entirely.
                            if (supportDepth[index] >= 0 && supportDepth[index] < 3) {
                                if (support[index] == null) support[index] = new BlockState[3];
                                support[index][supportDepth[index]++] = state;
                            }
                            if (next != mask) {
                                unresolvedMasks[index] = next;
                                if (next == 0 && --unresolvedColumns == 0) break outer;
                            }
                        }
                    }
                }
            }
            noiseChunk.swapSlices();
        } finally {
            noiseChunk.stopInterpolation();
        }
        return new Heights(worldSurface, oceanFloor, support);
    }
    /** The same start heuristic the road uses: a cheap descent bound, never the answer itself. */
    private int preliminarySurface(int blockX, int blockZ) {
        DensityFunction density = randomState.router().initialDensityWithoutJaggedness();
        int qx = Mth.floorDiv(blockX, 4) * 4;
        int qz = Mth.floorDiv(blockZ, 4) * 4;
        int minY = noiseSettings.minY();
        int top = minY + noiseSettings.height();
        int lo = minY - 1;
        int hi = top;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (density.compute(new DensityFunction.SinglePointContext(qx, mid, qz)) > 0.390625) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** One cell's heightmaps plus the first three solid blocks under each column's surface. */
    private record Heights(int[] worldSurface, int[] oceanFloor, BlockState[][] support) {
    }

    static Aquifer.FluidPicker fluidPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus fluid = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : fluid;
    }

    private static final class AccessibleNoiseChunk extends NoiseChunk {
        private AccessibleNoiseChunk(int cellCountXZ, RandomState randomState, int minBlockX, int minBlockZ,
                NoiseSettings noiseSettings, DensityFunctions.BeardifierOrMarker beardifier,
                NoiseGeneratorSettings generatorSettings, Aquifer.FluidPicker fluidPicker) {
            super(cellCountXZ, randomState, minBlockX, minBlockZ, noiseSettings, beardifier,
                    generatorSettings, fluidPicker, Blender.empty());
        }

        private BlockState sampleInterpolatedState() {
            return this.getInterpolatedState();
        }
    }

    private enum ZeroBeardifier implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;

        @Override
        public double compute(DensityFunction.FunctionContext context) { return 0.0D; }

        @Override
        public void fillArray(double[] values, DensityFunction.ContextProvider contextProvider) {
            java.util.Arrays.fill(values, 0.0D);
        }

        @Override
        public double minValue() { return 0.0D; }

        @Override
        public double maxValue() { return 0.0D; }
    }
}
