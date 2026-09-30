package local.plaincraft.greatwall.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import local.plaincraft.greatwall.mixin.NoiseGeneratorAccess;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import local.plaincraft.greatwall.terrain.CpuTerrainOracle;
import local.plaincraft.greatwall.terrain.OracleLane;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
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
    private static final List<Long> SEEDS = List.of(0L, 1L, 9282026L, Long.MIN_VALUE);

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=20000)
    public void referenceAndSupportMatchIndependentNativeReduction512Columns(GameTestHelper helper) {
        var generator = generator(helper);
        var inputs = SEEDS.stream().map(seed -> input(helper, generator, seed)).toList();
        var totals = new java.util.concurrent.ConcurrentHashMap<String, Long>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (var frozen : inputs) chain = chain.thenCompose(ignored -> onLane(frozen, oracle -> {
            var reference = RandomState.create(frozen.settings(), frozen.noises(), frozen.seed()); // Independent test baseline.
            int wet = 0, dry = 0;
            for (int index = 0; index < 128; index++) {
                int[] point = point(index);
                var actual = compare(generator, frozen, reference, oracle, point[0], point[1]);
                if (actual.waterTopY().isPresent()) wet++; else dry++;
            }
            totals.merge("wet", (long) wet, Long::sum); totals.merge("dry", (long) dry, Long::sum);
            var timing = oracle.timings();
            totals.merge("maxNativeInitNanos", timing.randomStateNanos(), Math::max);
            totals.merge("maxSteadyConstructNanos", timing.maxConstructNanos(), Math::max);
            totals.merge("maxSteadyAdvanceNanos", timing.maxAdvanceNanos(), Math::max);
            totals.merge("steadyOver40ms", timing.over40msPrimitives() + timing.over40msAdvances(), Long::sum);
        }));
        finish(helper, chain.thenRun(() -> {
            require(totals.getOrDefault("wet", 0L) > 0 && totals.getOrDefault("dry", 0L) > 0, "wet/dry coverage missing");
            require(totals.get("wet") + totals.get("dry") == 512, "wrong complete parity domain");
            System.out.println("S1C_ORACLE_PARITY seeds=4 columns=512 " + totals
                    + " scope=native-oracle-only-not-worldgen-acceptance");
        }));
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=20000)
    public void frozenFactoryOwnsItsLaneAndRejectsClosedForeignAndUnfinishedCursors(GameTestHelper helper) {
        var frozen = input(helper, generator(helper), 1);
        require(rejects(frozen::createOracle), "server owner must not construct the native oracle");
        if (!(helper.getLevel().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator)) {
            try {
                new CpuTerrainOracle(helper.getLevel(), HASH);
                helper.fail("flat production generator must not be accepted");
            } catch (WallFailure expected) {
                require(expected.code() == FailureCode.UNSUPPORTED_ADAPTER, "wrong flat rejection");
            }
        }
        var lane = new OracleLane(UUID.randomUUID(), 0, HASH, frozen::createOracle);
        var other = new OracleLane(UUID.randomUUID(), 1, HASH, frozen::createOracle);
        // Deliberate test-only cursor transfer exercises actual malicious cross-lane input.
        var result = lane.submit(value -> {
            var oracle = (CpuTerrainOracle) value;
            var cursor = oracle.begin(-17, 16, SampleKind.REFERENCE);
            require(rejects(() -> oracle.finish(cursor)), "unfinished finish accepted");
            require(rejects(() -> oracle.begin(0, 0, SampleKind.COARSE)), "unfinished overlap accepted");
            require(rejects(() -> oracle.advance(cursor, 0)), "zero limit accepted");
            require(rejects(() -> oracle.advance(cursor, 257)), "oversized limit accepted");
            require(rejects(() -> oracle.begin(Integer.MIN_VALUE, 0, SampleKind.COARSE)), "int-min abs overflow");
            require(rejects(() -> CpuTerrainOracle.freeze(helper.getLevel(), HASH)), "off-owner freeze accepted");
            require(rejects(frozen::createOracle), "sampling action must not permit a second initialization");
            oracle.advance(cursor, 1);
            return cursor;
        }).thenCompose(cursor -> other.submit(oracle -> {
            require(rejects(() -> oracle.advance(cursor, 1)), "foreign actual CPU cursor accepted");
            require(rejects(cursor::done), "foreign owner cursor accepted");
            return cursor;
        })).thenCompose(cursor -> lane.submit(oracle -> {
            oracle.close();
            require(rejects(cursor::done), "closed cursor returned default data");
            require(rejects(() -> oracle.finish(cursor)), "closed finish accepted");
            return (Void) null;
        }));
        result.whenComplete((value, error) -> { lane.close(); other.close(); });
        finish(helper, result.thenCompose(ignored -> CompletableFuture.allOf(lane.drain(), other.drain())));
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=20000)
    public void clippedOpenWaterIsCensoredAndRoofWaterIsNotExposed(GameTestHelper helper) {
        var base = generator(helper);
        int min = base.generatorSettings().value().noiseSettings().minY();
        int max = min + base.generatorSettings().value().noiseSettings().height();
        var flooded = input(helper, withDensity(base, DensityFunctions.yClampedGradient(0, 16, 1, -1), max), 1);
        var y = DensityFunctions.yClampedGradient(min, max, min, max);
        var roof = input(helper, withDensity(base, DensityFunctions.rangeChoice(y, 80, 88,
                DensityFunctions.constant(1), DensityFunctions.constant(-1)), 80), 1);
        finish(helper, onLane(flooded, oracle -> {
            var result = sample(oracle, 0, 0, SampleKind.REFERENCE);
            require(result.waterTopY().orElseThrow() == max, "upper bound flood counterexample disappeared");
            require(result.upperCensored() && result.quality() == Quality.CENSORED, "upper water must be censored");
        }).thenCompose(ignored -> onLane(roof, oracle -> {
            var result = sample(oracle, 0, 0, SampleKind.REFERENCE);
            require(result.groundTopY() == 88 && result.waterTopY().isEmpty() && result.bedTopY().isEmpty(),
                    "roof water became an exposed river");
            require(result.quality() == Quality.REFERENCE && !result.upperCensored(), "roof below ceiling was censored");
        })));
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=20000)
    public void coarseUsesAlignedFourBlockDensityAndDoesNotClaimFinalSurface(GameTestHelper helper) {
        var frozen = input(helper, generator(helper), 9282026);
        finish(helper, onLane(frozen, oracle -> {
            var reference = RandomState.create(frozen.settings(), frozen.noises(), frozen.seed()); // Test-only baseline.
            for (int x : new int[] {-1000000, -17, -16, -1, 0, 1, 16, 1000000}) {
                int z = -x, y = frozen.bounds().maxYExclusive() - 1;
                while (y >= frozen.bounds().minY() && reference.router().initialDensityWithoutJaggedness().compute(
                        new DensityFunction.SinglePointContext(Math.floorDiv(x, 4) * 4, y, Math.floorDiv(z, 4) * 4)) <= 0.390625) {
                    y -= frozen.settings().noiseSettings().getCellHeight();
                }
                var actual = sample(oracle, x, z, SampleKind.COARSE);
                require(actual.groundTopY() == (y < frozen.bounds().minY() ? frozen.bounds().minY() : y + 1), "coarse threshold/alignment");
                require(actual.quality() != Quality.REFERENCE, "coarse claimed final surface");
                require(actual.waterTopY().isEmpty() && actual.bedTopY().isEmpty(), "coarse invented water");
            }
        }));
    }

    private static CompletableFuture<Void> onLane(CpuTerrainOracle.FrozenInput input, Consumer<CpuTerrainOracle> action) {
        var lane = new OracleLane(UUID.randomUUID(), 0, input.fingerprint(), input::createOracle);
        var job = lane.submit(value -> { action.accept((CpuTerrainOracle) value); return (Void) null; });
        return job.handle((value, failure) -> {
            lane.close();
            return lane.drain().thenApply(ignored -> {
                if (failure != null) throw new CompletionException(failure);
                return (Void) null;
            });
        }).thenCompose(value -> value);
    }
    private static void finish(GameTestHelper helper, CompletableFuture<Void> job) {
        job.orTimeout(240, TimeUnit.SECONDS).whenComplete((value, failure) -> helper.getLevel().getServer().execute(
                () -> helper.runAfterDelay(1, () -> {
                    if (failure != null) helper.fail(failure.toString()); else helper.succeed();
                })));
    }
    private static ColumnSample sample(CpuTerrainOracle oracle, int x, int z, SampleKind kind) {
        var cursor = oracle.begin(x, z, kind);
        while (!cursor.done()) oracle.advance(cursor, 64);
        return oracle.finish(cursor);
    }
    private static ColumnSample compare(NoiseBasedChunkGenerator generator, CpuTerrainOracle.FrozenInput frozen,
            RandomState reference, CpuTerrainOracle oracle, int x, int z) {
        NoiseColumn nativeColumn = generator.getBaseColumn(x, z, LevelHeightAccessor.create(frozen.bounds().minY(),
                frozen.bounds().maxYExclusive() - frozen.bounds().minY()), reference); // Independent test reference.
        int top = frozen.bounds().minY(), solidY = Integer.MIN_VALUE;
        OptionalInt water = OptionalInt.empty();
        boolean open = true, censored = false;
        for (int y = frozen.bounds().maxYExclusive() - 1; y >= frozen.bounds().minY(); y--) {
            var state = nativeColumn.getBlock(y);
            if (state.getFluidState().is(FluidTags.WATER) && open) {
                if (water.isEmpty()) water = OptionalInt.of(y + 1);
                if (y == frozen.bounds().maxYExclusive() - 1) censored = true;
            } else if (!state.isAir() && state.getFluidState().isEmpty()) { top = y + 1; solidY = y; break; }
            else if (!state.getFluidState().isEmpty()) { open = false; censored = true; }
        }
        var support = new ArrayList<BlockSpec>();
        if (solidY != Integer.MIN_VALUE) for (int y = solidY - 1; y >= Math.max(frozen.bounds().minY(), solidY - 3); y--)
            support.add(BlockCatalog.specification(nativeColumn.getBlock(y)));
        if (support.size() != 3 || solidY == frozen.bounds().maxYExclusive() - 1) censored = true;
        String biome = frozen.biomeSource().getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(top, 4),
                Math.floorDiv(z, 4), reference.sampler()).unwrapKey().orElseThrow().location().toString();
        var expected = new ColumnSample(top, water, water.isPresent() && solidY != Integer.MIN_VALUE
                ? OptionalInt.of(top) : OptionalInt.empty(), biome, censored ? Quality.CENSORED : Quality.REFERENCE, censored);
        var cursor = oracle.begin(x, z, SampleKind.REFERENCE);
        int call = 0;
        while (!cursor.done()) {
            oracle.advance(cursor, new int[] {1, 2, 7, 256}[call++ % 4]);
            require(call <= 100000, "reference cursor did not terminate");
        }
        var actual = oracle.finish(cursor);
        require(expected.equals(actual), "native reduction mismatch seed=" + frozen.seed() + " xz=" + x + "," + z
                + " expected=" + expected + " actual=" + actual);
        require(support.equals(oracle.supportEvidence(cursor)), "three support states differ");
        return actual;
    }
    private static int[] point(int index) {
        int[] edge = {-1000000, -17, -16, -1, 0, 1, 16, 1000000};
        return index < 8 ? new int[] {edge[index], edge[7 - index]}
                : new int[] {-4096 + Math.floorMod(index * 977, 8192), -4096 + Math.floorMod(index * 619, 8192)};
    }
    private static boolean rejects(Runnable action) {
        try { action.run(); return false; }
        catch (IllegalStateException | IllegalArgumentException | WallFailure expected) { return true; }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static NoiseBasedChunkGenerator generator(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        return new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(registries
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)),
                registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD));
    }
    private static CpuTerrainOracle.FrozenInput input(GameTestHelper helper, NoiseBasedChunkGenerator generator, long seed) {
        var settings = generator.generatorSettings().value(); var noise = settings.noiseSettings();
        return new CpuTerrainOracle.FrozenInput(settings, helper.getLevel().registryAccess().registryOrThrow(Registries.NOISE).asLookup(),
                ((NoiseGeneratorAccess) (Object) generator).greatwall$fluidPicker().get(), generator.getBiomeSource(),
                new DimensionBounds(noise.minY(), noise.minY() + noise.height(), 30000000), seed, HASH);
    }
    private static NoiseBasedChunkGenerator withDensity(NoiseBasedChunkGenerator base, DensityFunction density, int seaLevel) {
        var s = base.generatorSettings().value(); var r = s.noiseRouter();
        var router = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(),
                r.lavaNoise(), r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(),
                r.initialDensityWithoutJaggedness(), density, r.veinToggle(), r.veinRidged(), r.veinGap());
        var settings = new NoiseGeneratorSettings(s.noiseSettings(), s.defaultBlock(), s.defaultFluid(), router,
                s.surfaceRule(), s.spawnTarget(), seaLevel, true, false, false, s.useLegacyRandomSource());
        return new NoiseBasedChunkGenerator(base.getBiomeSource(), Holder.direct(settings));
    }
}
