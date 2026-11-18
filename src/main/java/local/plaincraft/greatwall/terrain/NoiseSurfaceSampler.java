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
    /**
     * {@code worldSurface}/{@code solidTop} already carry the "+1" that heightmap semantics put on top of
     * the block they name, so callers must not subtract one from either: doing that is the 0.7.91 bug that
     * sank every wall and gate one block into the ground (see generation/GroundLevel).
     */
    private static final int COLUMN_COUNT = 256;
    /**
     * Solid states kept per column, counted from the topmost one down.
     *
     * <p>Four, not three: the foundation certificate reads {@code h-1,h-2,h-3} (the top solid and the two
     * below it) while the legacy extra-read view reads one further down ({@code h-2,h-3,h-4}), and that
     * view is what proves the old root call site rejects a roof with three real foundation layers. Three
     * slots could not answer both.
     */
    static final int SUPPORT_STATES = 4;
    /**
     * Baked cells kept per terrain, shared by every lane (see {@link #SHARED}).
     *
     * <p>One siting candidate covers 64x64 blocks - 256 cells - and neighbouring candidates sit about
     * ten blocks apart, so their footprints overlap heavily. A 512-cell cache held barely one
     * candidate, which made every candidate pay for every cell again. Each cell is roughly a
     * kilobyte, and the map is shared, so the default trades reuse against one budget rather than
     * twelve. Override with the system property greatwall.samplerCacheCells.
     *
     * <p><b>0.7.120: 65,536 → 131,072; 0.7.121: back to 65,536.</b> The doubling bet that the fine
     * sample's ~124,000 cell bakes were re-bakes caused by eviction: the corridor alone is 8,190 x
     * 105 blocks - about 53,750 cells at cellWidth 4 - which sat right against the old budget. That
     * bet was measured and lost. With the 131,072 budget a real session reported
     * {@code chunkCacheMisses=124,239} (0.7.120: 124,571, i.e. -0.27%) and the
     * {@code cell cache eviction} log printed <b>zero</b> lines: the map never crossed the budget, so
     * eviction never ran and the misses were ~124,000 genuinely distinct cells rather than churn.
     * A larger cache buys nothing here and costs the extra 64 MB, so the default is 65,536 again
     * (the map is shared by every lane, keyed by the terrain fingerprint, so this is 64 MB in total,
     * not per lane). Do not raise this again without a run whose {@code CACHE_EVICTIONS} is above 0.
     *
     * <p><b>Why the misses are ~1 per column, not ~1/16.</b> {@code heightsFor} keys by noise cell and
     * {@code cellWidth = QuartPos.toBlock(size_horizontal) = 4} for the overworld (vanilla
     * {@code overworld.json} has {@code size_horizontal=1}), so one baked cell answers up to 16
     * columns. A dense sample would thus show about 0.06 misses per column. A measured session instead
     * showed 124,571 misses for 130,155 columns (0.96 per column; the 0.7.121 run 124,239, the same
     * within noise) - one fresh cell per sampled column, because neighbours almost never land in a
     * cell another sampled column already touched. The miss count is therefore structurally equal to
     * the number of columns sampled; the only ways down are sampling fewer columns or making one
     * {@code sweep()} cheaper - never a larger cache.
     */
    private static final int CACHE_CELLS = Math.max(512,
            Integer.getInteger("greatwall.samplerCacheCells", 65536));

    private final RandomState randomState;
    private final NoiseSettings noiseSettings;
    private final NoiseGeneratorSettings generatorSettings;
    private final Aquifer.FluidPicker fluidPicker;
    private final BlockState defaultBlock;
    /**
     * Baked cells, shared by every lane of one terrain.
     *
     * <p>A result is immutable once built, so lanes can read each other's cells; only the NoiseChunk
     * used to build one is thread-confined. Sharing matters because the lanes sweep the same ring:
     * the whole coarse phase touches roughly forty thousand distinct cells, which fits easily in one
     * shared budget but not in twelve separate ones.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.ConcurrentHashMap<Long, Heights>> SHARED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.concurrent.ConcurrentHashMap<Long, Heights> cache;
    /**
     * Per-cell cache hits and misses, so the fine sample can say where its time went.
     *
     * <p>These are the numbers {@code CpuTerrainOracle.costModel()} reports as
     * {@code chunk-cache-hits}/{@code chunk-cache-misses}. The baked-chunk cache the oracle javadoc
     * describes was replaced by this per-cell cache, so the counters have to live here. Atomic because
     * the cache instance is shared by every lane built on the same {@code RandomState}.
     */
    private final java.util.concurrent.atomic.AtomicLong cacheHits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong cacheMisses = new java.util.concurrent.atomic.AtomicLong();

    public long cacheHits() { return cacheHits.get(); }
    public long cacheMisses() { return cacheMisses.get(); }
    private long sweeps;
    private long corrections;

    NoiseSurfaceSampler(RandomState randomState, NoiseSettings noiseSettings,
            NoiseGeneratorSettings generatorSettings, Aquifer.FluidPicker fluidPicker) {
        this.randomState = randomState;
        this.noiseSettings = noiseSettings;
        this.generatorSettings = generatorSettings;
        this.fluidPicker = fluidPicker;
        this.defaultBlock = generatorSettings.defaultBlock();
        // Keyed by the terrain fingerprint so two saves in one JVM can never share heights.
        this.cache = SHARED.computeIfAbsent(Integer.toHexString(System.identityHashCode(randomState))
                + ":" + generatorSettings.seaLevel() + ":" + noiseSettings.minY() + ":" + noiseSettings.height(),
                ignored -> new java.util.concurrent.ConcurrentHashMap<>());
    }

    /** {@return the vanilla WORLD_SURFACE_WG height} in heightmap semantics, or the dimension floor. */
    int worldSurface(int x, int z) {
        int cellWidth = noiseSettings.getCellWidth();
        return heightsFor(x, z).worldSurface[(x & (cellWidth - 1)) + ((z & (cellWidth - 1)) * cellWidth)];
    }

    /**
     * {@return the first block that is neither air nor a fluid, plus one} - the wall's ground.
     *
     * <p>Neither vanilla heightmap answers this. {@code WORLD_SURFACE_WG} is "not air", so a river
     * reports its water surface and a forest reports its canopy; {@code OCEAN_FLOOR_WG} is "opaque", so
     * it skips the canopy and reports the trunk base instead. The wall's ground is the first block it can
     * actually stand on, which is the same thing the native column walk computes as {@code solidY + 1}.
     * Anchoring on this one predicate keeps the height and the foundation evidence on the same block, so
     * they can never disagree by one again.
     */
    int solidTop(int x, int z) {
        int cellWidth = noiseSettings.getCellWidth();
        return heightsFor(x, z).solidTop[(x & (cellWidth - 1)) + ((z & (cellWidth - 1)) * cellWidth)];
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
        int cellX = Math.floorDiv(x, cellWidth);
        int cellZ = Math.floorDiv(z, cellWidth);
        Heights cached = cache.get(key);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }
        Heights[] built = new Heights[1];
        Heights fresh = cache.computeIfAbsent(key, ignored -> {
            built[0] = sweep(cellX, cellZ);
            return built[0];
        });
        if (built[0] != null) {
            cacheMisses.incrementAndGet();
            sweeps++;
            evictIfOversized();
        }
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
    /**
     * 0.7.120 诊断：cell 缓存淘汰了多少（决定 CACHE_CELLS 该不该加大）。
     *
     * <p>真机一局 FINE_SAMPLE_DONE 报 {@code chunkCacheMisses=124,571} 而只采了
     * {@code columns=130,155} 列 —— 几乎每列一次 cell 烘焙，fine 阶段 400~545 s。
     * 两种解释只能靠这两个计数器分开：①预算 65,536 不够、cell 被挤出去又重烘；
     * ②本来就有一百万个不同的噪声 cell 要烘。
     *
     * <p><b>0.7.121 结论：①被实测证伪，真相是②。</b>预算加倍到 131,072 后，真机一局
     * {@code cell cache eviction} 日志一条都没有（{@code CACHE_EVICTIONS} 从未自增，说明
     * map 根本没越过预算），而 {@code chunkCacheMisses} 只从 124,571 变到 124,239（−0.27%，
     * 噪声级）。所以预算不是瓶颈，那 12.4 万次 miss 对应的是 12.4 万个真实且互不相同的 cell。
     * 两个计数器与这条节流日志保留下来继续做诊断，但<b>不要再往「加大预算」这个方向投入</b>：
     * 真要降 miss，只能减少要采的列数，或者降低单次 {@code sweep()} 的成本（见 CACHE_CELLS 注释）。
     */
    private static final java.util.concurrent.atomic.AtomicLong CACHE_EVICTIONS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong CACHE_EVICTED_CELLS =
            new java.util.concurrent.atomic.AtomicLong();

    /** A shared map has no LRU: drop an eighth of it when the budget is crossed. */
    private void evictIfOversized() {
        if (cache.size() <= CACHE_CELLS) return;
        int before = cache.size();
        int target = cache.size() - CACHE_CELLS / 8;
        var iterator = cache.keySet().iterator();
        while (cache.size() > target && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
        long n = CACHE_EVICTIONS.incrementAndGet();
        long dropped = CACHE_EVICTED_CELLS.addAndGet(before - cache.size());
        if (n <= 5 || n % 200 == 0) {
            local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                    "[GreatWall:sampler] cell cache eviction #{} size={}->{} budget={} evictedTotal={}",
                    n, before, cache.size(), CACHE_CELLS, dropped);
        }
    }

    /** Room left above the density estimate on the first pass; a cell that outruns it is redone. */
    private static final int SHORT_MARGIN = 32;

    private Heights sweep(int cellX, int cellZ) {
        // Short margin first, and a retry that can actually tell it started too low.
        //
        // The estimate is a density bisection; in mountains and tall old-growth it can sit well below the
        // real surface (measured: 80 where the native column says 86, 112 where it says 116). The old fast
        // path could not notice, because the sweep still found *a* surface - just a lower one - so the
        // retry never ran, and raising the short margin only moved the error to the next tall column.
        // sweepCell now reports that case explicitly: if the first solid block it meets sits on the very
        // first row it examines, the start was already inside the ground, so the cell is redone at full
        // margin. Ordinary terrain never pays for the retry.
        Heights fast = sweepCell(cellX, cellZ, SHORT_MARGIN);
        if (fast.complete) return fast;
        return sweepCell(cellX, cellZ, generatorSettings.seaLevel() - noiseSettings.minY() + 32);
    }

    private Heights sweepCell(int cellX, int cellZ, int airSkipMargin) {
        int cellWidth = noiseSettings.getCellWidth();
        int columns = cellWidth * cellWidth;
        int[] worldSurface = new int[columns];
        int[] solidTop = new int[columns];
        int minY = noiseSettings.minY();
        Arrays.fill(worldSurface, minY);
        Arrays.fill(solidTop, minY);

        int cellHeight = noiseSettings.getCellHeight();
        int cellCountY = Mth.floorDiv(noiseSettings.height(), cellHeight);
        int cellNoiseMinY = Mth.floorDiv(minY, cellHeight);
        // Start just above the estimate, not a hundred and sixty blocks above it: the estimate the
        // sweep uses is its own density bisection, accurate to a few blocks, and every extra cell of
        // air above it costs a full 16-column pass. The old margin was the road's, where a chunk bake
        // answers 256 columns and the waste amortises; here it was most of the work. A cell that the
        // short margin leaves unresolved is swept again from the full margin, so accuracy is kept.
        BlockState[][] support = new BlockState[columns][];
        int[] supportDepth = new int[columns];
        java.util.Arrays.fill(supportDepth, -1);
        byte[] unresolvedMasks = new byte[columns];
        // Three bits, not two: 0x01 worldSurface, 0x02 oceanFloor, 0x04 support still incomplete. Without
        // the third the sweep cleared both heightmap bits on the same surface block and broke out of the
        // whole loop right there, so it never collected the blocks below the surface that the foundation
        // and extra-read views read (they saw one state, or a duplicated one).
        Arrays.fill(unresolvedMasks, (byte) 0x07);
        int unresolvedColumns = columns;

        Predicate<BlockState> worldSurfacePredicate = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
        // The wall's ground, and the anchor the foundation evidence hangs off: the first block that is
        // neither air nor a fluid. This is exactly what the native column walk treats as `solidY`, so a
        // canopy counts as the surface and a river's water does not.
        Predicate<BlockState> solidPredicate = state -> !state.isAir() && state.getFluidState().isEmpty();

        int minBlockX = cellX * cellWidth;
        int minBlockZ = cellZ * cellWidth;
        // Set when the first solid block the sweep meets sits on its very first row: the start was already
        // inside the ground, so the height it reports is a lower block than the real surface and sweep()
        // redoes the cell at full margin. Declared out here because Heights is built after the finally.
        boolean startedInsideGround = false;
        AccessibleNoiseChunk noiseChunk = new AccessibleNoiseChunk(1, randomState, minBlockX, minBlockZ,
                noiseSettings, ZeroBeardifier.INSTANCE, generatorSettings, fluidPicker);
        noiseChunk.initializeForFirstCellX();
        try {
            noiseChunk.advanceCellX(0);
            int repX = minBlockX + (cellWidth >> 1);
            int repZ = minBlockZ + (cellWidth >> 1);
            // The estimate is a descent bound, and it reads initialDensityWithoutJaggedness while the blocks
            // come from finalDensity. Those two disagree wherever a generator (or a test fixture) rewrites
            // only the final density - there the bisection never crosses its threshold, bottoms out below
            // the dimension floor, and the sweep would start inside the bedrock and report deep stone as
            // the surface instead of the real one far above. An estimate that bottoms out is therefore
            // discarded and the sweep starts from the top of the world; anything still below sea level is
            // clamped up to it, because ground is never under the water line.
            int estimate = preliminarySurface(repX, repZ);
            if (estimate < noiseSettings.minY() + cellHeight) {
                estimate = noiseSettings.minY() + noiseSettings.height();
            }
            estimate = Math.max(estimate, generatorSettings.seaLevel());
            int startCellY = Mth.floorDiv(estimate + airSkipMargin, cellHeight) - cellNoiseMinY;
            startCellY = Math.max(0, Math.min(cellCountY - 1, startCellY));
            final int firstBlockY = (cellNoiseMinY + startCellY) * cellHeight + (cellHeight - 1);
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
                            byte next = mask;
                            // One block answers at most one append below. The worldSurface branch already
                            // records this state as support[0]; letting the tail append run in the same
                            // iteration stored the same block twice, so the two evidence views read
                            // [top, top, next] instead of [top, next, next-1].
                            boolean appended = false;
                            if (!state.isAir()) {
                                if ((next & 0x01) != 0 && worldSurfacePredicate.test(state)) {
                                    worldSurface[index] = blockY + 1;
                                    next &= ~0x01;
                                }
                                if ((next & 0x02) != 0 && solidPredicate.test(state)) {
                                    solidTop[index] = blockY + 1;
                                    // The surface is the first block the sweep looked at: it started inside
                                    // the ground, so this answer is too low and the cell needs the retry.
                                    if (blockY == firstBlockY) startedInsideGround = true;
                                    next &= ~0x02;
                                    // The evidence is anchored on the first solid block for the same reason
                                    // the height is: a fluid is not something the wall can stand on, and a
                                    // canopy is. Anchoring on OCEAN_FLOOR skipped the canopy; anchoring on
                                    // WORLD_SURFACE stopped on the water.
                                    supportDepth[index] = 0;
                                    if (support[index] == null) support[index] = new BlockState[SUPPORT_STATES];
                                    support[index][supportDepth[index]++] = state;
                                    appended = true;
                                }
                                // Every opaque block below the anchor is the next evidence slot: the wall
                                // certifies its base against these states, and reading them from the same
                                // vanilla sweep is what lets the density walk go away entirely.
                                if (!appended && supportDepth[index] >= 0 && supportDepth[index] < SUPPORT_STATES) {
                                    if (support[index] == null) support[index] = new BlockState[SUPPORT_STATES];
                                    support[index][supportDepth[index]++] = state;
                                }
                            } else if (supportDepth[index] >= 1 && supportDepth[index] < SUPPORT_STATES) {
                                // Air under the anchor is evidence too: an overhang has no support there, and
                                // the extra-read view exists precisely to prove the old call site rejected such
                                // a roof. Skipping air made that view unanswerable (it could never fill three).
                                if (support[index] == null) support[index] = new BlockState[SUPPORT_STATES];
                                support[index][supportDepth[index]++] = state;
                            }
                            // The support bit clears once the column has all it can get: either the slots are
                            // full, or there is no world left below to read. Otherwise the sweep stops on the
                            // surface block, where both heightmap bits clear together.
                            if (supportDepth[index] >= SUPPORT_STATES || blockY <= noiseSettings.minY()) {
                                next &= ~0x04;
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
        return new Heights(worldSurface, solidTop, support,
                unresolvedColumns == 0 && !startedInsideGround);
    }
    /** The same start heuristic the road uses: a cheap descent bound, never the answer itself. */
    private int preliminarySurface(int blockX, int blockZ) {
        return preliminarySurface(randomState.router().initialDensityWithoutJaggedness(),
                noiseSettings.minY(), noiseSettings.height(), blockX, blockZ);
    }

    // 单独接收密度与高度边界，让负高度算术可直接验证，生产仍使用同一采样入口。
    static int preliminarySurface(DensityFunction density, int minY, int height, int blockX, int blockZ) {
        int qx = Mth.floorDiv(blockX, 4) * 4;
        int qz = Mth.floorDiv(blockZ, 4) * 4;
        int top = minY + height;
        int lo = minY - 1;
        int hi = top;
        while (hi - lo > 1) {
            // 下界可能低于零，先取区间差，避免无符号移位把负和变成巨大的正高度。
            int mid = lo + ((hi - lo) >>> 1);
            if (density.compute(new DensityFunction.SinglePointContext(qx, mid, qz)) > 0.390625) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** One cell's heightmaps plus the solid blocks under each column's surface. */
    private record Heights(int[] worldSurface, int[] solidTop, BlockState[][] support, boolean complete) {
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
