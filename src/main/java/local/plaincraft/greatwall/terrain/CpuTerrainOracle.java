package local.plaincraft.greatwall.terrain;

import java.util.ArrayList;
import java.util.List;
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
    private long maxConstructNanos, maxCellNanos, maxSlicePointNanos, maxBlockNanos, maxCoarseNanos, maxBiomeNanos;
    private long over40msPrimitives, referencePrimitives, coarsePrimitives;
    private long maxAdvanceNanos, over40msAdvances;

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
        if (primitiveLimit < 1 || primitiveLimit > 256) throw new IllegalArgumentException("primitive limit 1..256");
        long started = System.nanoTime();
        try {
            for (int step = 0; step < primitiveLimit && c.result == null; step++) {
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
    }

    private void coarse(Cursor c) {
        long start = System.nanoTime();
        double density = randomState.router().initialDensityWithoutJaggedness().compute(
                new DensityFunction.SinglePointContext(Math.floorDiv(c.x, 4) * 4, c.y, Math.floorDiv(c.z, 4) * 4));
        long duration = System.nanoTime() - start;
        maxCoarseNanos = Math.max(maxCoarseNanos, duration); observe(duration);
        if (density > 0.390625) {
            c.groundTop = c.y + 1;
            c.censored = c.y == bounds.maxYExclusive() - 1;
            complete(c, Quality.COARSE);
        } else {
            c.y -= noise.getCellHeight();
            if (c.y < bounds.minY()) { c.censored = true; complete(c, Quality.CENSORED); }
        }
    }

    private void reference(Cursor c) {
        if (c.chunk == null) {
            int width = noise.getCellWidth();
            long start = System.nanoTime();
            c.chunk = new NoiseChunk(1, randomState, Math.floorDiv(c.x, width) * width, Math.floorDiv(c.z, width) * width,
                    noise, DensityFunctions.BeardifierMarker.INSTANCE, settings, fluidPicker, Blender.empty());
            c.bridge = (NoiseCursorBridge) (Object) c.chunk;
            c.bridge.greatwall$beginSlices();
            long duration = System.nanoTime() - start;
            maxConstructNanos = Math.max(maxConstructNanos, duration); observe(duration);
            return;
        }
        if (!c.slicesReady) {
            long start = System.nanoTime();
            c.slicesReady = c.bridge.greatwall$advanceSlices(1);
            long duration = System.nanoTime() - start;
            maxSlicePointNanos = Math.max(maxSlicePointNanos, duration); observe(duration);
            return;
        }
        if (c.selectedCell != c.cellY) {
            long start = System.nanoTime();
            c.chunk.selectCellYZ(c.cellY, 0);
            long duration = System.nanoTime() - start;
            maxCellNanos = Math.max(maxCellNanos, duration); observe(duration);
            c.selectedCell = c.cellY;
            return;
        }
        long start = System.nanoTime();
        c.chunk.updateForY(c.y, (double) c.innerY / noise.getCellHeight());
        c.chunk.updateForX(c.x, (double) Math.floorMod(c.x, noise.getCellWidth()) / noise.getCellWidth());
        c.chunk.updateForZ(c.z, (double) Math.floorMod(c.z, noise.getCellWidth()) / noise.getCellWidth());
        BlockState state = c.bridge.greatwall$state();
        if (state == null) state = settings.defaultBlock();
        long duration = System.nanoTime() - start;
        maxBlockNanos = Math.max(maxBlockNanos, duration); observe(duration);
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
        c.result = new ColumnSample(c.groundTop, c.waterTop, c.bedTop, biome, quality, c.censored);
    }

    private void stop(Cursor c) {
        if (c.chunk != null) { c.chunk.stopInterpolation(); c.chunk = null; c.bridge = null; }
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
        private NoiseChunk chunk;
        private NoiseCursorBridge bridge;
        private ColumnSample result;
        private Cursor(int x, int z, SampleKind kind) { this.x = x; this.z = z; this.kind = kind; }
        private CpuTerrainOracle oracle() { return CpuTerrainOracle.this; }
        @Override public boolean done() { checkOpen(); return result != null; }
    }
}
