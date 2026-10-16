package local.plaincraft.greatwall.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import local.plaincraft.greatwall.mixin.NoiseGeneratorAccess;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Natural noise only. Mutable sampling is lane-confined; final surface authority remains durable T0. */
public final class CpuTerrainOracle implements NaturalTerrainOracle {
    /** Inputs from finalized registries/LOAD callbacks, with no live world or mutable RandomState. */
    public record FrozenInput(NoiseGeneratorSettings settings, HolderGetter<NormalNoise.NoiseParameters> noises,
            Aquifer.FluidPicker fluidPicker, BiomeSource biomeSource, DimensionBounds bounds,
            long seed, String fingerprint) {
        public FrozenInput {
            Objects.requireNonNull(settings); Objects.requireNonNull(noises); Objects.requireNonNull(fluidPicker);
            Objects.requireNonNull(biomeSource); Objects.requireNonNull(bounds); Objects.requireNonNull(fingerprint);
            if (!fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("terrain fingerprint SHA-256 required");
            var noise = settings.noiseSettings().clampToHeightAccessor(
                    LevelHeightAccessor.create(bounds.minY(), Math.subtractExact(bounds.maxYExclusive(), bounds.minY())));
            if (noise.height() == 0 || noise.minY() != bounds.minY()
                    || Math.addExact(noise.minY(), noise.height()) != bounds.maxYExclusive()
                    || Math.floorMod(noise.minY(), noise.getCellHeight()) != 0
                    || Math.floorMod(noise.height(), noise.getCellHeight()) != 0) {
                throw new WallFailure(FailureCode.UNSUPPORTED_ADAPTER, "noise settings do not cover the complete aligned dimension");
            }
        }
        public CpuTerrainOracle createOracle() { return new CpuTerrainOracle(this); }
    }

    /** Native initialization has its own clock; all repeatable sampling clocks retain the 40ms limit. */
    public record TimingSnapshot(long randomStateNanos, long maxConstructNanos, long maxCellNanos,
            long maxSlicePointNanos, long maxBlockNanos, long maxCoarseNanos, long maxBiomeNanos,
            long over40msPrimitives, long referencePrimitives, long coarsePrimitives,
            long maxAdvanceNanos, long over40msAdvances) {}

    private static final long FORTY_MS = 40_000_000L;
    /**
     * Hard ceiling on a single column's native sampling steps.
     *
     * <p>A reference column walks the native slice fill first, then down from the top of the
     * dimension one block per step. The slice fill alone is cellCountXZ &times; cellCountY
     * &times; 2 sides &times; interpolators - on the order of tens of thousands of advance
     * calls for a large noise configuration. The earlier bound of 4096 was far too low and
     * censored every column, which made the root search reject every candidate as unsupported.
     *
     * <p>This is set to a million, comfortably above any legitimate walk and far below an
     * infinite one. Only a genuinely stuck cursor - an empty interpolator list or a broken
     * cell traversal - ever reaches it, and only then is the column censored so the search
     * can move on instead of holding a lane forever.
     */
    private static final int MAX_REFERENCE_ADVANCE_STEPS = 1_000_000;
    /**
     * Blocks above the smooth density surface where a reference walk starts.
     *
     * <p>The smooth surface is a lower bound in practice but not a guarantee: the jagged band,
     * the aquifer and the surface rules can lift the real top by tens of blocks. The walk
     * therefore starts with this short lead and raises it to the full historical margin
     * ({@code seaLevel - minY + 32}) as soon as the first walked block proves the short lead
     * sat at or below the real surface. Columns whose estimate was right - the overwhelming
     * majority - never pay for the margin, and every raised column is bit-identical to the
     * old fixed-margin walk.
     */
    private static final int REFERENCE_LEAD_BLOCKS = 32;
    private final Thread owner = Thread.currentThread();
    private final UUID identity = UUID.randomUUID();
    private final RandomState randomState;
    private final NoiseGeneratorSettings settings;
    private final NoiseSettings noise;
    private final Aquifer.FluidPicker fluidPicker;
    private final BiomeSource biomeSource;
    private final DimensionBounds bounds;
    private final String fingerprint;
    private final long randomStateNanos;
    private Cursor active;
    private boolean closed;

    /**
     * Fully baked {@link NoiseChunk}s keyed by their cell origin, reused across columns.
     *
     * <h2>Why</h2>
     * Every column used to build its own chunk and run the whole slice fill before walking a single
     * block, then throw the chunk away ({@code stopInterpolation}). The fine sample walks a 49-wide
     * cross-section one block at a time and then repeats that sweep one route point further along, so
     * the same cells are revisited constantly - with a cell width of four, ~13 distinct cells per
     * sweep, and almost every one of them still warm on the next sweep. Caching them removes most of
     * the slice fill from the stage.
     *
     * <h2>Safety</h2>
     * The key is the chunk's own construction origin, so a reused chunk is exactly the chunk the
     * column would have built: same origin, same cell, same fractions. A chunk only enters the cache
     * once its slice walk reported complete ({@code slicesReady}), because a half-baked chunk is not
     * reusable. This map is touched from one lane thread only, like the rest of the oracle.
     */
    private final java.util.LinkedHashMap<Long, NoiseChunk> chunkCache =
            new java.util.LinkedHashMap<>(32, 0.75F, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long, NoiseChunk> eldest) {
                    // Configured per lane (clamped to 4..64); see GreatWallConfig.chunkCacheLimit.
                    if (size() <= local.plaincraft.greatwall.config.GreatWallConfig.get().chunkCacheLimit()) {
                        return false;
                    }
                    // Evicted chunks are the only ones that still need their scratch arrays released.
                    eldest.getValue().stopInterpolation();
                    return true;
                }
            };
    private long chunkCacheHits, chunkCacheMisses;
    /** Baked-chunk reuse counters, surfaced so a real session can prove the cache is working. */
    public long chunkCacheHits() { return chunkCacheHits; }
    public long chunkCacheMisses() { return chunkCacheMisses; }
    /**
     * Cost model of this lane's column walks.
     *
     * <p>Collected unconditionally - two adds per walked block - because the fine sample's bottleneck
     * cannot be attributed after the fact. A measured session walked 1.35M emitted columns at about
     * 1.25k columns/s over twelve lanes (9.6 ms per column) and left no evidence of where that time
     * went: whether the descent, the slice fill of a freshly built chunk, the cell switch or the biome
     * lookup owned it. The ceiling-margin retry alone is worth counting: a column whose short lead
     * missed pays a walk from near the top of a 2112-block dimension, which is two orders of magnitude
     * more blocks than a clean descent.
     */
    public Map<String, Long> costModel() {
        var costs = new java.util.LinkedHashMap<String, Long>();
        costs.put("walked-blocks", walkedBlocks);
        costs.put("lead-retries", leadRetries);
        costs.put("construct-ms", constructNanosTotal / 1_000_000L);
        costs.put("slice-ms", sliceNanosTotal / 1_000_000L);
        costs.put("cell-ms", cellNanosTotal / 1_000_000L);
        costs.put("block-ms", blockNanosTotal / 1_000_000L);
        costs.put("biome-ms", biomeNanosTotal / 1_000_000L);
        costs.put("surface-ms", surfaceNanosTotal / 1_000_000L);
        costs.put("surface-memo-hits", surfaceMemoHits);
        return Map.copyOf(costs);
    }

    private long maxConstructNanos, maxCellNanos, maxSlicePointNanos, maxBlockNanos, maxCoarseNanos, maxBiomeNanos;
    private long over40msPrimitives, referencePrimitives, coarsePrimitives;
    private long maxAdvanceNanos, over40msAdvances;
    private long walkedBlocks, leadRetries;
    private long constructNanosTotal, sliceNanosTotal, cellNanosTotal, blockNanosTotal, biomeNanosTotal;
    /** Surface-estimate memo: a ring of (4x4 cell column, estimated surface). */
    private static final int SURFACE_MEMO_ENTRIES = 128;
    private final long[] surfaceMemoKeys = new long[SURFACE_MEMO_ENTRIES];
    private final int[] surfaceMemoValues = new int[SURFACE_MEMO_ENTRIES];
    private int surfaceMemoNext;
    private long surfaceMemoHits, surfaceNanosTotal;

    public CpuTerrainOracle(ServerLevel level, String fingerprint) { this(freeze(level, fingerprint)); }

    public CpuTerrainOracle(FrozenInput frozen) {
        OracleLane.claimNativeInitialization();
        Objects.requireNonNull(frozen);
        settings = frozen.settings(); fluidPicker = frozen.fluidPicker(); biomeSource = frozen.biomeSource();
        bounds = frozen.bounds(); fingerprint = frozen.fingerprint();
        noise = settings.noiseSettings().clampToHeightAccessor(
                LevelHeightAccessor.create(bounds.minY(), bounds.maxYExclusive() - bounds.minY()));
        long start = System.nanoTime();
        randomState = RandomState.create(settings, frozen.noises(), frozen.seed());
        randomStateNanos = System.nanoTime() - start;
    }

    /** Called on server owner after all LOAD callbacks, before publishing a lane factory. */
    public static FrozenInput freeze(ServerLevel level, String fingerprint) {
        Objects.requireNonNull(level);
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("terrain inputs must freeze on the server owner");
        }
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            throw new WallFailure(FailureCode.UNSUPPORTED_ADAPTER, "production oracle requires NoiseBasedChunkGenerator");
        }
        int horizontalBits = (64 - BlockPos.PACKED_Y_LENGTH) / 2;
        int horizontalLimit = Math.toIntExact(Math.min(30_000_000L, (1L << (horizontalBits - 1)) - 1));
        return new FrozenInput(generator.generatorSettings().value(),
                level.registryAccess().registryOrThrow(Registries.NOISE).asLookup(),
                ((NoiseGeneratorAccess) (Object) generator).greatwall$fluidPicker().get(), generator.getBiomeSource(),
                new DimensionBounds(level.getMinBuildHeight(), level.getMaxBuildHeight(), horizontalLimit),
                level.getSeed(), fingerprint);
    }

    @Override public ColumnCursor begin(int x, int z, SampleKind kind) {
        checkOpen();
        Objects.requireNonNull(kind);
        if (Math.abs((long) x) > bounds.horizontalLimit() || Math.abs((long) z) > bounds.horizontalLimit()) {
            throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "natural column is outside frozen horizontal bounds");
        }
        if (active != null) throw new IllegalStateException("finish the current natural column before beginning another");
        active = new Cursor(x, z, kind);
        return active;
    }

    @Override public WorkState advance(ColumnCursor cursor, int primitiveLimit) {
        var c = own(cursor);
        if (primitiveLimit < 1 || primitiveLimit > 4096) throw new IllegalArgumentException("primitive limit 1..4096");
        long started = System.nanoTime();
        try {
            for (int step = 0; step < primitiveLimit && c.result == null; step++) {
                // A native slice walk should finish in a small multiple of the column's
                // height. A cursor that is still walking after this many steps has hit a
                // malformed interpolation - an empty interpolator list or an off-by-one in
                // the cell traversal - and would otherwise spin forever, holding a whole
                // lane and, through the root search's serial chain, the whole startup. Censor
                // instead: the column is treated as unbuildable, which is what an unreadable
                // native column means anyway, and the search moves on.
                if (++c.advanceSteps > MAX_REFERENCE_ADVANCE_STEPS) {
                    c.censored = true;
                    complete(c, Quality.CENSORED);
                    break;
                }
                if (c.kind == SampleKind.COARSE) { coarse(c); coarsePrimitives++; }
                else { reference(c); referencePrimitives++; }
            }
            return c.result == null ? WorkState.MORE : WorkState.DONE;
        } finally {
            long elapsed = System.nanoTime() - started;
            maxAdvanceNanos = Math.max(maxAdvanceNanos, elapsed);
            if (elapsed > FORTY_MS) over40msAdvances++;
        }
    }

    @Override public ColumnSample finish(ColumnCursor cursor) {
        var c = own(cursor);
        if (c.result == null) throw new IllegalStateException("natural column is unfinished");
        if (active == c) active = null;
        return c.result;
    }

    /**
     * Drops an unfinished cursor so the lane can sample its next column.
     *
     * <p>{@link #finish} cannot do this - it refuses a column that never resolved - so a caller that
     * abandons a column (the coarse sampler's step budget) has no other way to clear {@link #active},
     * and every later {@code begin} on this lane would throw instead of sampling.
     */
    @Override public void abandon(ColumnCursor cursor) {
        var c = own(cursor);
        if (active == c) active = null;
    }

    /** Up to three additional native states below the top solid; missing evidence remains censored. */
    public List<BlockSpec> supportEvidence(ColumnCursor cursor) {
        var c = own(cursor);
        if (c.result == null) throw new IllegalStateException("support evidence is unfinished");
        var result = new ArrayList<BlockSpec>(c.supportCount);
        for (int i = 0; i < c.supportCount; i++) result.add(BlockCatalog.specification(c.support[i]));
        return List.copyOf(result);
    }
    /** Actual foundation layers h-1,h-2,h-3, distinct from the legacy three additional read states. */
    public List<BlockSpec> foundationEvidence(ColumnCursor cursor) {
        var c=own(cursor);
        if(c.result==null)throw new IllegalStateException("foundation evidence is unfinished");
        if(c.firstSolid==null||c.supportCount<2)return List.of();
        return List.of(BlockCatalog.specification(c.firstSolid),BlockCatalog.specification(c.support[0]),
                BlockCatalog.specification(c.support[1]));
    }

    public TimingSnapshot timings() {
        checkOpen();
        return new TimingSnapshot(randomStateNanos, maxConstructNanos, maxCellNanos, maxSlicePointNanos,
                maxBlockNanos, maxCoarseNanos, maxBiomeNanos, over40msPrimitives, referencePrimitives, coarsePrimitives,
                maxAdvanceNanos, over40msAdvances);
    }
    /** Immutable diagnostic identity, never a RandomState reference. */
    public UUID identity() { checkOpen(); return identity; }

    @Override public String fingerprint() { return fingerprint; }

    @Override public void close() {
        checkOwner();
        if (closed) return;
        closed = true;
        if (active != null) { stop(active); active = null; }
        // Cached chunks hold scratch arrays and this lane is going away: release every one.
        for (var chunk : chunkCache.values()) chunk.stopInterpolation();
        chunkCache.clear();
    }

    /**
     * Highest solid Y for a column per the smooth terrain density (no jaggedness, no
     * aquifer). The reference walk starts a margin above this and walks down, so a column
     * no longer traverses the whole air column from the top of a tall dimension - the
     * single biggest cost of the fine sample. The smooth density is monotonic in Y for a
     * fixed column, so a binary search converges in {@code O(log height)} steps instead of
     * the {@code O(height)} block walk it replaces.
     */
    /**
     * The next rung of the walk's retry ladder: double the previous climb, never less than one lead.
     *
     * <p>Exponential search, so the number of rungs is logarithmic in the distance to clear air rather
     * than linear in the dimension's height.
     */
    static int nextLeadClimb(int climb) {
        return Math.max(REFERENCE_LEAD_BLOCKS, climb * 2);
    }

    private int preliminarySurface(int x, int z) {
        int x4 = Math.floorDiv(x, 4) * 4;
        int z4 = Math.floorDiv(z, 4) * 4;
        // The estimate is a pure function of the 4x4 cell column, and the fine sample sweeps the same
        // cell columns over and over: a cross-section is 33 to 105 blocks wide, so it visits 9 to 26
        // cells, and the next route point - a fraction of a block along - visits the same ones. The
        // binary search below is O(log height) evaluations of the router's initial density, which is
        // not a scalar but the compiled noise graph, so answering from a ring memo instead is worth
        // far more than the 128-entry scan that finds it. Pure memoisation: same (x4, z4), same value,
        // and the walk it starts is unchanged.
        long key = ((long) (x4 >> 2) << 32) ^ ((z4 >> 2) & 0xffffffffL);
        for (int i = 0; i < SURFACE_MEMO_ENTRIES; i++) {
            // Newest first: a repeated cell is almost always one of the last few the sweep touched.
            int slot = Math.floorMod(surfaceMemoNext - 1 - i, SURFACE_MEMO_ENTRIES);
            if (surfaceMemoValues[slot] != Integer.MIN_VALUE && surfaceMemoKeys[slot] == key) {
                surfaceMemoHits++;
                return surfaceMemoValues[slot];
            }
        }
        var density = randomState.router().initialDensityWithoutJaggedness();
        long started = System.nanoTime();
        int lo = bounds.minY();
        int hi = bounds.maxYExclusive() - 1;
        while (lo < hi) {
            int mid = lo + (hi - lo + 1) / 2;
            if (density.compute(new DensityFunction.SinglePointContext(x4, mid, z4)) > 0.390625) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        surfaceNanosTotal += System.nanoTime() - started;
        surfaceMemoKeys[surfaceMemoNext] = key;
        surfaceMemoValues[surfaceMemoNext] = lo;
        surfaceMemoNext = (surfaceMemoNext + 1) % SURFACE_MEMO_ENTRIES;
        return lo;
    }

    private void coarse(Cursor c) {
        long start = System.nanoTime();
        var density = randomState.router().initialDensityWithoutJaggedness();
        int x4 = Math.floorDiv(c.x, 4) * 4;
        int z4 = Math.floorDiv(c.z, 4) * 4;
        int cellHeight = noise.getCellHeight();
        int top = bounds.maxYExclusive() - 1;
        // Binary-search the surface cell instead of walking one cell per advance step from
        // the top. The smooth density is monotonic in Y for a fixed column, so this is
        // bit-identical to the old top-down walk while replacing ~cellCountY evaluations
        // with ~log2(cellCountY). This is the coarse probe's share of the root search.
        int steps = (top - bounds.minY()) / cellHeight;
        int lo = 0;
        int hi = steps;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int y = top - mid * cellHeight;
            if (density.compute(new DensityFunction.SinglePointContext(x4, y, z4)) > 0.390625) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        int y = top - lo * cellHeight;
        boolean solid = density.compute(new DensityFunction.SinglePointContext(x4, y, z4)) > 0.390625;
        long duration = System.nanoTime() - start;
        maxCoarseNanos = Math.max(maxCoarseNanos, duration); observe(duration);
        if (solid) {
            c.groundTop = y + 1;
            c.censored = y == top;
            complete(c, Quality.COARSE);
        } else {
            c.censored = true;
            complete(c, Quality.CENSORED);
        }
    }

    private void reference(Cursor c) {
        if (c.chunk == null) {
            int width = noise.getCellWidth();
            int cellX = Math.floorDiv(c.x, width);
            int cellZ = Math.floorDiv(c.z, width);
            long key = ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
            var cached = chunkCache.get(key);
            if (cached != null) {
                chunkCacheHits++;
                c.chunk = cached;
                c.bridge = (NoiseCursorBridge) (Object) cached;
                // Already baked: skipping the slice walk is the entire point of the reuse.
                c.slicesReady = true;
                return;
            }
            chunkCacheMisses++;
            long start = System.nanoTime();
            c.chunk = new NoiseChunk(1, randomState, cellX * width, cellZ * width,
                    noise, DensityFunctions.BeardifierMarker.INSTANCE, settings, fluidPicker, Blender.empty());
            c.bridge = (NoiseCursorBridge) (Object) c.chunk;
            c.bridge.greatwall$beginSlices();
            long duration = System.nanoTime() - start;
            maxConstructNanos = Math.max(maxConstructNanos, duration); observe(duration);
            constructNanosTotal += duration;
            return;
        }
        if (!c.slicesReady) {
            long start = System.nanoTime();
            c.slicesReady = c.bridge.greatwall$advanceSlices(1);
            long duration = System.nanoTime() - start;
            maxSlicePointNanos = Math.max(maxSlicePointNanos, duration); observe(duration);
            sliceNanosTotal += duration;
            return;
        }
        if (c.selectedCell != c.cellY) {
            long start = System.nanoTime();
            c.chunk.selectCellYZ(c.cellY, 0);
            long duration = System.nanoTime() - start;
            maxCellNanos = Math.max(maxCellNanos, duration); observe(duration);
            cellNanosTotal += duration;
            c.selectedCell = c.cellY;
            return;
        }
        long start = System.nanoTime();
        c.chunk.updateForY(c.y, (double) c.innerY / noise.getCellHeight());
        if (!c.axesReady) {
            // The column's x/z never change, so the horizontal interpolation fractions are constant
            // for the whole descent: they are set once instead of on every walked block, which is
            // two fewer density-interpolation calls per block with an identical result.
            c.chunk.updateForX(c.x, (double) Math.floorMod(c.x, noise.getCellWidth()) / noise.getCellWidth());
            c.chunk.updateForZ(c.z, (double) Math.floorMod(c.z, noise.getCellWidth()) / noise.getCellWidth());
            c.axesReady = true;
        }
        BlockState state = c.bridge.greatwall$state();
        if (state == null) state = settings.defaultBlock();
        walkedBlocks++;
        if (!c.leadChecked && c.y == c.attemptStartY) {
            // The first block of the walk proves whether the short lead cleared the real surface.
            // Non-air here means the smooth estimate sat at or below the ground (or the water top),
            // so climb and try again.
            //
            // The climb doubles instead of jumping straight to the full historical margin. That margin
            // is {@code seaLevel - minY + 32} above the estimate - with a 2112-block dimension that is
            // over a thousand blocks, and the old code paid every one of them as a walked block for
            // every column whose estimate was low. The estimate is only ever wrong by the height of the
            // jagged band, the aquifer and the surface rules, which is tens of blocks, so an exponential
            // search reaches clear air in O(log distance) single-block probes: 32, 64, 128, ... blocks
            // above the short lead, and the walk then descends from the first clear start it finds.
            //
            // Same acceptance rule as before, applied at more candidate heights: a start is accepted
            // only when its own first block is air, which is exactly the condition the short lead has
            // always been trusted on. The ceiling margin stays the last rung, so a column whose terrain
            // really does reach for the sky still walks what it always walked.
            c.leadChecked = true;
            if (!state.isAir() && c.attemptStartY < c.highStartY) {
                blockNanosTotal += System.nanoTime() - start;
                leadRetries++;
                int climb = nextLeadClimb(c.leadClimb);
                c.leadClimb = climb;
                c.resetWalk(Math.min(c.attemptStartY + climb, c.highStartY));
                return;
            }
        }
        long duration = System.nanoTime() - start;
        maxBlockNanos = Math.max(maxBlockNanos, duration); observe(duration);
        blockNanosTotal += duration;
        if (c.solidFound) {
            c.support[c.supportCount++] = state;
            if (c.supportCount == 3) { complete(c, c.censored ? Quality.CENSORED : Quality.REFERENCE); return; }
        } else if (state.getFluidState().is(FluidTags.WATER) && c.skyOpen) {
            if (c.waterTop.isEmpty()) c.waterTop = OptionalInt.of(c.y + 1);
            if (c.y == bounds.maxYExclusive() - 1) c.censored = true;
        } else if (!state.isAir() && state.getFluidState().isEmpty()) {
            c.firstSolid=state;
            c.solidFound = true; c.skyOpen = false; c.groundTop = c.y + 1;
            if (c.waterTop.isPresent()) c.bedTop = OptionalInt.of(c.groundTop);
            if (c.y == bounds.maxYExclusive() - 1) c.censored = true;
        } else if (!state.getFluidState().isEmpty()) {
            // A lava-covered column is not silently presented as a buildable dry column.
            c.censored = true; c.skyOpen = false;
        }
        c.y--; c.innerY--;
        if (c.innerY < 0) { c.innerY = noise.getCellHeight() - 1; c.cellY--; }
        if (c.y < bounds.minY()) { c.censored = true; complete(c, Quality.CENSORED); }
    }

    private void complete(Cursor c, Quality quality) {
        stop(c);
        long start = System.nanoTime();
        String biome = biomeSource.getNoiseBiome(Math.floorDiv(c.x, 4),
                Math.floorDiv(c.groundTop, 4), Math.floorDiv(c.z, 4), randomState.sampler()).unwrapKey()
                .orElseThrow(() -> new WallFailure(FailureCode.INVALID_RESOURCE, "unkeyed natural biome")).location().toString();
        long duration = System.nanoTime() - start;
        maxBiomeNanos = Math.max(maxBiomeNanos, duration); observe(duration);
        biomeNanosTotal += duration;
        c.result = new ColumnSample(c.groundTop, c.waterTop, c.bedTop, biome, quality, c.censored);
    }

    private void stop(Cursor c) {
        if (c.chunk == null) return;
        // A fully baked chunk goes back into the cell cache instead of being torn down: the next column
        // in the same cell - or the next route point sweeping the same band - would otherwise rebuild it
        // and re-run the entire slice fill. Only fully baked chunks qualify; a partially baked one is not
        // reusable and must release its scratch arrays now.
        if (c.slicesReady && !closed) {
            int width = noise.getCellWidth();
            long key = ((long) Math.floorDiv(c.x, width) << 32) ^ (Math.floorDiv(c.z, width) & 0xffffffffL);
            var displaced = chunkCache.put(key, c.chunk);
            // An equal key holds the same chunk (it came from the cache). A different value means the
            // entry was evicted and rebuilt while this column ran, so that one is stale.
            if (displaced != null && displaced != c.chunk) displaced.stopInterpolation();
            c.chunk = null; c.bridge = null;
            return;
        }
        c.chunk.stopInterpolation(); c.chunk = null; c.bridge = null;
    }
    private void observe(long nanos) { if (nanos > FORTY_MS) over40msPrimitives++; }
    private Cursor own(ColumnCursor cursor) {
        checkOpen();
        if (!(cursor instanceof CpuTerrainOracle.Cursor c) || c.oracle() != this) {
            throw new IllegalArgumentException("foreign natural column cursor");
        }
        return c;
    }
    private void checkOwner() {
        if (owner != Thread.currentThread()) throw new IllegalStateException("natural oracle used outside its owning lane");
    }
    private void checkOpen() {
        checkOwner();
        if (closed) throw new WallFailure(FailureCode.CLOSED, "natural oracle is closed");
    }
    private final class Cursor implements ColumnCursor {
        private final int x, z;
        private final SampleKind kind;
        private final BlockState[] support = new BlockState[3];
        private BlockState firstSolid;
        private int y = bounds.maxYExclusive() - 1, groundTop = bounds.minY(), supportCount;
        private int cellY = noise.height() / noise.getCellHeight() - 1, innerY = noise.getCellHeight() - 1;
        private int selectedCell = -1;
        private OptionalInt waterTop = OptionalInt.empty(), bedTop = OptionalInt.empty();
        private boolean skyOpen = true, solidFound, censored, slicesReady;
        /** Short-lead start, the full historical margin, and the start the walk is on now. */
        private int lowStartY, highStartY, attemptStartY = -1;
        /** How far the retry ladder has climbed above the short lead, in blocks. */
        private int leadClimb;
        /** Whether the current attempt's first block has already been inspected. */
        private boolean leadChecked;
        private NoiseChunk chunk;
        /** Whether the chunk's horizontal interpolation fractions describe this column already. */
        private boolean axesReady;
        private NoiseCursorBridge bridge;
        private ColumnSample result;
        /** Guard against a malformed native slice walk that never terminates. */
        private int advanceSteps;
        private Cursor(int x, int z, SampleKind kind) {
            this.x = x; this.z = z; this.kind = kind;
            if (kind == SampleKind.REFERENCE) {
                // Start with a short lead above the smooth terrain surface instead of walking the
                // air column from the top of a tall dimension - the single biggest cost of the fine
                // sample. The lead is raised to the full historical margin only when the first
                // walked block shows the estimate sat at or below the real surface. The coarse probe
                // keeps its own full top-down walk, so it is untouched.
                int surface = preliminarySurface(x, z);
                int top = bounds.maxYExclusive() - 1;
                this.lowStartY = Math.min(surface + REFERENCE_LEAD_BLOCKS, top);
                this.highStartY = Math.min(surface + settings.seaLevel() - bounds.minY() + 32, top);
                seekStart(this.lowStartY);
            }
        }
        /**
         * Places the walk at {@code startY}. The noise chunk and its baked slices are kept.
         *
         * <p>The chunk's cell grid is indexed from its own top cell downwards, exactly as the
         * original top-down walk did ({@code maxYExclusive - 1} sits in cell
         * {@code height/cellHeight - 1} with {@code cellHeight - 1} walked blocks left). The cell
         * index therefore has to be derived from that same anchor: indexing straight from the world
         * origin runs one cell off the bottom of the slice array, because the chunk's height is not
         * a whole number of world cells (it starts below {@code minY}).
         */
        private void seekStart(int startY) {
            int cellHeight = noise.getCellHeight();
            int topY = bounds.maxYExclusive() - 1;
            int topCell = noise.height() / cellHeight - 1;
            int floorY = topY - (cellHeight * (topCell + 1) - 1);
            int start = Math.max(floorY, Math.min(topY, startY));
            int back = topY - start;
            this.y = start;
            this.cellY = topCell - Math.floorDiv(back, cellHeight);
            this.innerY = Math.floorMod(cellHeight - 1 - back, cellHeight);
            this.attemptStartY = start;
            this.selectedCell = -1;
        }
        /** Restarts the walk from a raised lead, discarding everything the short attempt saw. */
        private void resetWalk(int startY) {
            seekStart(startY);
            this.leadChecked = false;
            this.supportCount = 0;
            this.firstSolid = null;
            this.solidFound = false;
            this.skyOpen = true;
            this.censored = false;
            this.groundTop = bounds.minY();
            this.waterTop = OptionalInt.empty();
            this.bedTop = OptionalInt.empty();
        }
        private CpuTerrainOracle oracle() { return CpuTerrainOracle.this; }
        @Override public boolean done() { checkOpen(); return result != null; }
    }
}
