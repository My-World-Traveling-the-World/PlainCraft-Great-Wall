package local.plaincraft.greatwall.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.mixin.NoiseGeneratorAccess;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import local.plaincraft.greatwall.terrain.CpuTerrainOracle;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;

public final class OracleGameTests implements FabricGameTest {
    private static final String HASH = "0".repeat(64);

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=20000)
    public void referenceAndSupportMatchIndependentNativeReduction512Columns(GameTestHelper helper) {
        new Parity(helper).next();
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void frozenFactoryOwnsItsLaneAndRejectsClosedForeignAndUnfinishedCursors(GameTestHelper helper) {
        var generator = generator(helper);
        var input = input(helper, generator, 1);
        try (var oracle = input.createOracle(); var other = input.createOracle()) {
            var cursor = oracle.begin(-17, 16, SampleKind.REFERENCE);
            helper.assertTrue(rejects(() -> oracle.finish(cursor)), "unfinished finish must fail");
            helper.assertTrue(rejects(() -> oracle.begin(0, 0, SampleKind.COARSE)), "overlapping unfinished column must fail");
            helper.assertTrue(rejects(() -> oracle.advance(cursor, 0)), "zero limit must fail");
            helper.assertTrue(rejects(() -> oracle.advance(cursor, 257)), "oversized limit must fail");
            helper.assertTrue(rejects(() -> other.advance(cursor, 1)), "foreign cursor must fail");
            helper.assertTrue(rejects(() -> oracle.begin(Integer.MIN_VALUE, 0, SampleKind.COARSE)), "int minimum must fail without abs overflow");
            oracle.advance(cursor, 1); // Begin real private interpolation, then close it.
            oracle.close();
            helper.assertTrue(rejects(cursor::done), "closed cursor is not a default empty sample");
            helper.assertTrue(rejects(() -> oracle.finish(cursor)), "closed finish must fail");
        }
        // The production constructor rejects the flat GameTest world instead of disguising it as supported noise.
        if (!(helper.getLevel().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator)) {
            try {
                new CpuTerrainOracle(helper.getLevel(), HASH);
                helper.fail("flat production generator must not be accepted");
            } catch (WallFailure expected) {
                helper.assertTrue(expected.code() == FailureCode.UNSUPPORTED_ADAPTER, "wrong flat rejection");
            }
        }
        var serverOwned = input.createOracle();
        CompletableFuture.supplyAsync(() -> {
            boolean foreignRejected = rejects(() -> serverOwned.begin(0, 0, SampleKind.REFERENCE));
            boolean freezeRejected = rejects(() -> CpuTerrainOracle.freeze(helper.getLevel(), HASH));
            try (var laneOwned = input.createOracle()) {
                var cursor = laneOwned.begin(0, 0, SampleKind.REFERENCE);
                while (!cursor.done()) laneOwned.advance(cursor, 7);
                return foreignRejected && freezeRejected && laneOwned.finish(cursor).quality() == Quality.REFERENCE;
            }
        }).orTimeout(10, TimeUnit.SECONDS).whenComplete((okay, error) -> helper.getLevel().getServer().execute(
                () -> helper.runAfterDelay(1, () -> {
            try {
                if (error != null) helper.fail(error.toString());
                helper.assertTrue(Boolean.TRUE.equals(okay), "frozen lane factory or owner guards failed");
                helper.succeed();
            } finally { serverOwned.close(); }
        })));
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=20000)
    public void clippedOpenWaterIsCensoredAndRoofWaterIsNotExposed(GameTestHelper helper) {
        var base = generator(helper);
        int min = base.generatorSettings().value().noiseSettings().minY();
        int max = min + base.generatorSettings().value().noiseSettings().height();
        var flooded = withDensity(base, DensityFunctions.yClampedGradient(0, 16, 1, -1), max);
        try (var oracle = input(helper, flooded, 1).createOracle()) {
            var c = oracle.begin(0, 0, SampleKind.REFERENCE);
            while (!c.done()) oracle.advance(c, 7);
            var result = oracle.finish(c);
            helper.assertTrue(result.waterTopY().orElseThrow() == max, "counterexample must actually fill the upper bound with water");
            helper.assertTrue(result.upperCensored() && result.quality() == Quality.CENSORED,
                    "water touching the top of the dimension has no proven exposed atmospheric surface");
        }
        var y = DensityFunctions.yClampedGradient(min, max, min, max);
        var roof = withDensity(base, DensityFunctions.rangeChoice(y, 80, 88,
                DensityFunctions.constant(1), DensityFunctions.constant(-1)), 80);
        try (var oracle = input(helper, roof, 1).createOracle()) {
            var c = oracle.begin(0, 0, SampleKind.REFERENCE);
            while (!c.done()) oracle.advance(c, 7);
            var result = oracle.finish(c);
            helper.assertTrue(result.groundTopY() == 88 && result.waterTopY().isEmpty() && result.bedTopY().isEmpty(),
                    "water below an opaque roof is not an exposed river");
            helper.assertTrue(result.quality() == Quality.REFERENCE && !result.upperCensored(), "roof is below the dimension ceiling");
        }
        helper.succeed();
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=20000)
    public void coarseUsesAlignedFourBlockDensityAndDoesNotClaimFinalSurface(GameTestHelper helper) {
        var generator = generator(helper);
        var frozen = input(helper, generator, 9282026);
        var reference = RandomState.create(frozen.settings(), frozen.noises(), frozen.seed());
        var noise = frozen.settings().noiseSettings();
        try (var oracle = frozen.createOracle()) {
            for (int x : new int[] {-1000000, -17, -16, -1, 0, 1, 16, 1000000}) {
                int z = -x;
                int y = frozen.bounds().maxYExclusive() - 1;
                while (y >= frozen.bounds().minY()
                        && reference.router().initialDensityWithoutJaggedness().compute(new DensityFunction.SinglePointContext(
                                Math.floorDiv(x, 4) * 4, y, Math.floorDiv(z, 4) * 4)) <= 0.390625) {
                    y -= noise.getCellHeight();
                }
                var cursor = oracle.begin(x, z, SampleKind.COARSE);
                while (!cursor.done()) oracle.advance(cursor, 1);
                var actual = oracle.finish(cursor);
                helper.assertTrue(actual.groundTopY() == (y < frozen.bounds().minY() ? frozen.bounds().minY() : y + 1),
                        "coarse alignment/threshold mismatch");
                helper.assertTrue(actual.quality() != Quality.REFERENCE, "coarse must not claim final surface");
                helper.assertTrue(actual.waterTopY().isEmpty() && actual.bedTopY().isEmpty(), "coarse must not invent hydrology");
            }
        }
        helper.succeed();
    }

    private static boolean rejects(Runnable action) {
        try { action.run(); return false; }
        catch (IllegalStateException | IllegalArgumentException | WallFailure expected) { return true; }
    }
    private static NoiseBasedChunkGenerator generator(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        return new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(registries
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)),
                registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD));
    }
    private static CpuTerrainOracle.FrozenInput input(GameTestHelper helper, NoiseBasedChunkGenerator generator, long seed) {
        var settings = generator.generatorSettings().value();
        var noise = settings.noiseSettings();
        return new CpuTerrainOracle.FrozenInput(settings, helper.getLevel().registryAccess()
                .registryOrThrow(Registries.NOISE).asLookup(),
                ((NoiseGeneratorAccess) (Object) generator).greatwall$fluidPicker().get(), generator.getBiomeSource(),
                new DimensionBounds(noise.minY(), noise.minY() + noise.height(), 30000000), seed, HASH);
    }
    /** Synthetic native corner case only, not a replacement for Tectonic/6000 profile certification. */
    private static NoiseBasedChunkGenerator withDensity(NoiseBasedChunkGenerator base, DensityFunction density, int seaLevel) {
        var s = base.generatorSettings().value();
        var r = s.noiseRouter();
        var router = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(),
                r.lavaNoise(), r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(),
                r.initialDensityWithoutJaggedness(), density, r.veinToggle(), r.veinRidged(), r.veinGap());
        var settings = new NoiseGeneratorSettings(s.noiseSettings(), s.defaultBlock(), s.defaultFluid(), router,
                s.surfaceRule(), s.spawnTarget(), seaLevel, true, false, false, s.useLegacyRandomSource());
        return new NoiseBasedChunkGenerator(base.getBiomeSource(), Holder.direct(settings));
    }

    private static final class Parity {
        private static final List<Long> SEEDS = List.of(0L, 1L, 9282026L, Long.MIN_VALUE);
        private final GameTestHelper helper;
        private final NoiseBasedChunkGenerator generator;
        private CpuTerrainOracle oracle;
        private CpuTerrainOracle.FrozenInput frozen;
        private RandomState reference;
        private int seedIndex, columnIndex, wetColumns, dryColumns;
        private long maxRandomState, maxConstruct, maxCell, maxSlice, maxBlock, maxBiome, over40;
        private Parity(GameTestHelper helper) { this.helper = helper; generator = generator(helper); }
        private void next() {
            if (seedIndex == SEEDS.size()) {
                helper.assertTrue(wetColumns > 0 && dryColumns > 0, "fixture must exercise both dry and exposed water columns");
                System.out.println("S1C_ORACLE_PARITY seeds=4 columns=512 wet=" + wetColumns + " dry=" + dryColumns
                        + " maxRandomStateNanos=" + maxRandomState + " maxConstructNanos=" + maxConstruct
                        + " maxCellNanos=" + maxCell + " maxSlicePointNanos=" + maxSlice
                        + " maxBlockNanos=" + maxBlock + " maxBiomeNanos=" + maxBiome + " over40msPrimitives=" + over40
                        + " scope=native-oracle-only-not-worldgen-acceptance");
                helper.succeed(); return;
            }
            if (columnIndex == 0) {
                frozen = input(helper, generator, SEEDS.get(seedIndex));
                oracle = frozen.createOracle();
                reference = RandomState.create(frozen.settings(), frozen.noises(), frozen.seed());
            }
            try {
                int x, z;
                if (columnIndex < 8) {
                    int[] edge = {-1000000, -17, -16, -1, 0, 1, 16, 1000000};
                    x = edge[columnIndex]; z = edge[7 - columnIndex];
                } else { x = -4096 + Math.floorMod(columnIndex * 977, 8192); z = -4096 + Math.floorMod(columnIndex * 619, 8192); }
                compare(x, z);
            } catch (RuntimeException | Error failure) { oracle.close(); throw failure; }
            columnIndex++;
            if (columnIndex == 128) {
                var timing = oracle.timings();
                maxRandomState = Math.max(maxRandomState, timing.randomStateNanos());
                maxConstruct = Math.max(maxConstruct, timing.maxConstructNanos());
                maxCell = Math.max(maxCell, timing.maxCellNanos());
                maxSlice = Math.max(maxSlice, timing.maxSlicePointNanos());
                maxBlock = Math.max(maxBlock, timing.maxBlockNanos());
                maxBiome = Math.max(maxBiome, timing.maxBiomeNanos()); over40 += timing.over40msPrimitives();
                oracle.close(); columnIndex = 0; seedIndex++;
            }
            helper.runAfterDelay(1, this::next);
        }
        private void compare(int x, int z) {
            var height = LevelHeightAccessor.create(frozen.bounds().minY(), frozen.bounds().maxYExclusive() - frozen.bounds().minY());
            NoiseColumn nativeColumn = generator.getBaseColumn(x, z, height, reference); // Independent test reference only.
            int top = frozen.bounds().minY(), solidY = Integer.MIN_VALUE;
            OptionalInt water = OptionalInt.empty();
            boolean skyOpen = true, censored = false;
            for (int y = frozen.bounds().maxYExclusive() - 1; y >= frozen.bounds().minY(); y--) {
                var state = nativeColumn.getBlock(y);
                if (state.getFluidState().is(FluidTags.WATER) && skyOpen) {
                    if (water.isEmpty()) water = OptionalInt.of(y + 1);
                    if (y == frozen.bounds().maxYExclusive() - 1) censored = true;
                } else if (!state.isAir() && state.getFluidState().isEmpty()) {
                    top = y + 1; solidY = y; break;
                } else if (!state.getFluidState().isEmpty()) { skyOpen = false; censored = true; }
            }
            var support = new ArrayList<BlockSpec>();
            if (solidY != Integer.MIN_VALUE) {
                for (int y = solidY - 1; y >= Math.max(frozen.bounds().minY(), solidY - 3); y--) {
                    support.add(BlockCatalog.specification(nativeColumn.getBlock(y)));
                }
            }
            if (support.size() != 3 || solidY == frozen.bounds().maxYExclusive() - 1) censored = true;
            var biome = generator.getBiomeSource().getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(top, 4),
                    Math.floorDiv(z, 4), reference.sampler()).unwrapKey().orElseThrow().location().toString();
            var expected = new ColumnSample(top, water, water.isPresent() && solidY != Integer.MIN_VALUE
                    ? OptionalInt.of(top) : OptionalInt.empty(), biome, censored ? Quality.CENSORED : Quality.REFERENCE, censored);
            var cursor = oracle.begin(x, z, SampleKind.REFERENCE);
            int call = 0;
            while (!cursor.done()) {
                oracle.advance(cursor, new int[] {1, 2, 7, 256}[call++ % 4]);
                if (call > 100000) helper.fail("private reference cursor did not terminate");
            }
            var actual = oracle.finish(cursor);
            helper.assertTrue(expected.equals(actual), "native reduction mismatch seed=" + SEEDS.get(seedIndex)
                    + " xz=" + x + "," + z + " expected=" + expected + " actual=" + actual);
            helper.assertTrue(support.equals(oracle.supportEvidence(cursor)), "three native support states differ");
            if (water.isPresent()) wetColumns++; else dryColumns++;
        }
    }
}
