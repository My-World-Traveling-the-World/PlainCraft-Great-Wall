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
    /**
     * Lead used when the start height comes from the neighbouring column instead of an estimate.
     *
     * <p>A cross-section is walked column by column and neighbouring surfaces differ by a couple of blocks,
     * so ten above the last real height starts almost exactly at the surface. The retry ladder still climbs
     * in 32-block steps when the terrain turns out to be a cliff, so a bad seed costs a retry, never a wrong
     * answer.
     */
    private static final int NEIGHBOUR_LEAD_BLOCKS = 10;
    /**
     * How far a certified ground may sit from the density estimate before the walk is not trusted.
     *
     * <p>The estimate is coarse (it is a bisection on the density function) but it cannot be off by a
     * hundred blocks on real terrain. A walk that disagrees with it by more than this was started in the
     * wrong place, and the height it found belongs to a cliff face, not to this column.
     */
    private static final int SANITY_BLOCKS = 96;
    /** The last REFERENCE column this lane finished, used as this column start hint. */
    private int lastTopX = Integer.MIN_VALUE, lastTopZ = Integer.MIN_VALUE, lastTopY = Integer.MIN_VALUE;
    private final Thread owner = Thread.currentThread();
    private final UUID identity = UUID.randomUUID();
    private final RandomState randomState;
    /** Vanilla NoiseChunk heightmaps, used where the density walk cannot certify a column. */
    private final NoiseSurfaceSampler surfaceSampler;
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
        // The road's sampler, ported: a real NoiseChunk sweep with the vanilla heightmap
        // predicates. It only answers for columns the walk could not certify (and for columns that
        // read at bedrock), which is exactly where the density-only estimate used to be silently
        // wrong - see NoiseSurfaceSampler.
        surfaceSampler = new NoiseSurfaceSampler(randomState, noise, settings, fluidPicker);
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
        // Road-style answer: the vanilla sweep already knows this column, so there is nothing to walk.
        // It also hands back the three solid states under the surface, which is what the foundation
        // certificate reads - the density walk existed only to collect them.
        int sea = settings.seaLevel();
        int surface = surfaceSampler.worldSurface(x, z);
        int floor = surfaceSampler.oceanFloor(x, z);
        int ground = surface - 1;
        boolean sane = ground > bounds.minY() + 8 && ground < bounds.maxYExclusive();
        var water = base(sea, surface, floor);
        String biome = biomeSource.getNoiseBiome(Math.floorDiv(x, 4),
                Math.floorDiv(sane ? ground : sea, 4), Math.floorDiv(z, 4), randomState.sampler())
                .unwrapKey().orElseThrow(() -> new WallFailure(FailureCode.INVALID_RESOURCE,
                        "unkeyed natural biome")).location().toString();
        Quality quality = sane ? Quality.REFERENCE : Quality.CENSORED;
        if (!sane) active.censored = true;
        active.groundTop = sane ? ground : bounds.minY() + 16;
        active.waterTop = water[0];
        active.bedTop = water[1];
        BlockState[] support = surfaceSampler.supportColumn(x, z);
        if (support != null) {
            active.firstSolid = support[0];
            for (int i = 0; i < support.length && i < active.support.length; i++) {
                active.support[i] = support[i];
            }
            active.supportCount = Math.min(support.length, active.support.length);
        }
        active.result = new ColumnSample(active.groundTop, active.waterTop, active.bedTop, biome, quality, active.censored);
        return active;
    }

    /**
     * Every column is already answered by {@link #begin}: the vanilla sweep resolves the height,
     * the water and the three foundation states in one pass, so there is nothing left to advance.
     */
    @Override public WorkState advance(ColumnCursor cursor, int primitiveLimit) {
        own(cursor);
        if (primitiveLimit < 1 || primitiveLimit > 4096) throw new IllegalArgumentException("primitive limit 1..4096");
        return WorkState.DONE;
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
    /**
     * The biome at one column, sampled at sea level.
     *
     * <p>This is the cheap question: a biome lookup costs a few microseconds and a column sample about
     * ten milliseconds, so a whole route can be classified land-versus-sea before anything is sampled -
     * which is what lets the planner skip the middle of a sea instead of paying the deepest columns in
     * the run. The Y is sea level, not the terrain height, because the caller does not have the height
     * yet; that is the point.
     */
    public String biomeKeyAt(int x, int z) {
        checkOpen();
        return biomeSource.getNoiseBiome(Math.floorDiv(x, 4),
                        Math.floorDiv(settings.seaLevel(), 4), Math.floorDiv(z, 4),
                        randomState.sampler())
                .unwrapKey().orElseThrow(() -> new WallFailure(FailureCode.INVALID_RESOURCE,
                        "unkeyed natural biome")).location().toString();
    }

    public UUID identity() { checkOpen(); return identity; }

    @Override public String fingerprint() { return fingerprint; }

    @Override public void close() {
        checkOwner();
        if (closed) return;
        closed = true;
        active = null;
        // Cached chunks hold scratch arrays and this lane is going away: release every one.
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
    private OptionalInt[] base(int sea, int surface, int floor) {
        if (surface <= sea && floor < surface) {
            return new OptionalInt[] {OptionalInt.of(sea), OptionalInt.of(Math.max(bounds.minY(), floor - 1))};
        }
        return new OptionalInt[] {OptionalInt.empty(), OptionalInt.empty()};
    }

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
    /** One column, answered whole by the vanilla sweep. The walk this class used to run is gone. */
    private final class Cursor implements ColumnCursor {
        private final int x, z;
        private final SampleKind kind;
        private final BlockState[] support = new BlockState[3];
        private BlockState firstSolid;
        private int groundTop = bounds.minY(), supportCount;
        private OptionalInt waterTop = OptionalInt.empty(), bedTop = OptionalInt.empty();
        private boolean censored;
        private ColumnSample result;

        private Cursor(int x, int z, SampleKind kind) {
            this.x = x; this.z = z; this.kind = kind;
        }

        /** The cursor is answered the moment it is created: nothing is ever pending. */
        @Override public boolean done() { return result != null; }

        private CpuTerrainOracle oracle() { return CpuTerrainOracle.this; }
    }
}
