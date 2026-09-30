package local.plaincraft.greatwall.smoke;

import com.google.gson.GsonBuilder;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.lifecycle.GenerationResourceFingerprint;
import local.plaincraft.greatwall.lifecycle.WorldRecipeGuard;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import local.plaincraft.greatwall.terrain.CpuTerrainOracle;
import local.plaincraft.greatwall.terrain.NoiseCursorBridge;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Test-only actual-mod certification, not wall generation and not the S0 terrain matrix. */
public final class OracleProfileFixture implements AutoCloseable {
    private record Completion(Map<String, Object> report, String failure) {}
    private static final List<Long> SEEDS = List.of(0L, 1L, 9282026L, Long.MIN_VALUE);
    private final ConcurrentHashMap<String, Completion> completed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> state = new ConcurrentHashMap<>();
    private ExecutorService worker;

    public boolean tick(MinecraftServer server) {
        if (state.containsKey("closed")) throw new IllegalStateException("native oracle fixture is closed");
        if (state.putIfAbsent("started", true) == null) {
            var level = server.overworld();
            var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
            final CpuTerrainOracle.FrozenInput input;
            try {
                String fingerprint = WorldRecipeGuard.fingerprint(GenerationResourceFingerprint.capture(level, server.getResourceManager()));
                input = CpuTerrainOracle.freeze(level, fingerprint);
            } catch (Exception failure) { throw new IllegalStateException("Cannot freeze actual profile inputs", failure); }
            int expectedMax = Integer.getInteger("greatwall.s1c.expectedMaxY", input.bounds().maxYExclusive());
            if (expectedMax != input.bounds().maxYExclusive()) {
                throw new IllegalStateException("actual dimension maxY differs from requested profile: " + input.bounds());
            }
            var mods = new TreeMap<String, String>();
            FabricLoader.getInstance().getAllMods().forEach(mod ->
                    mods.put(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString()));
            Map<String, String> frozenMods = Map.copyOf(mods);
            var lane = Executors.newSingleThreadExecutor(task -> {
                var thread = new Thread(task, "pgw-test-native-oracle-profile"); thread.setDaemon(true); return thread;
            });
            worker = lane;
            CompletableFuture.supplyAsync(() -> {
                try { return new Completion(compare(input, generator, frozenMods), null); }
                catch (Exception failure) {
                    var text = new StringWriter(); failure.printStackTrace(new PrintWriter(text));
                    return new Completion(Map.of("status", "FAIL", "purpose", "native-oracle-only",
                            "minY", input.bounds().minY(), "maxYExclusive", input.bounds().maxYExclusive(),
                            "fingerprint", input.fingerprint()), text.toString());
                }
            }, lane).whenComplete((result, error) -> {
                if (!state.containsKey("closed")) {
                    if (error != null) {
                        var text = new StringWriter(); error.printStackTrace(new PrintWriter(text));
                        completed.put("done", new Completion(Map.of("status", "FAIL"), text.toString()));
                    } else completed.put("done", result);
                }
                lane.shutdown();
            });
        }
        var outcome = completed.get("done");
        if (outcome == null) return false;
        if (state.putIfAbsent("persisted", true) == null) {
            var report = new TreeMap<String, Object>(outcome.report());
            if (outcome.failure() != null) report.put("failure", outcome.failure());
            try {
                Files.writeString(FabricLoader.getInstance().getGameDir().resolve("oracle-profile.json"),
                        new GsonBuilder().setPrettyPrinting().create().toJson(report));
            } catch (Exception failure) { throw new IllegalStateException("Cannot persist native oracle certificate", failure); }
            if (outcome.failure() != null) throw new IllegalStateException("Native oracle profile failed:\n" + outcome.failure());
            GreatWallMod.LOGGER.info("[GreatWall:S1C] ORACLE_PROFILE_RESULT {}", report);
        }
        return true;
    }

    @Override public void close() {
        if (state.putIfAbsent("closed", true) == null && worker != null) worker.shutdownNow();
        completed.clear();
    }

    private static Map<String, Object> compare(CpuTerrainOracle.FrozenInput base, NoiseBasedChunkGenerator generator,
            Map<String, String> mods) throws Exception {
        var expectedDigest = MessageDigest.getInstance("SHA-256");
        var actualDigest = MessageDigest.getInstance("SHA-256");
        var tokens = new HashMap<BlockState, byte[]>();
        var timing = new TreeMap<String, Long>();
        var initializations = new ArrayList<Map<String, Long>>();
        var threadTiming = ManagementFactory.getThreadMXBean();
        long blocks = 0;
        int columns = 0, wet = 0, dry = 0;
        var height = LevelHeightAccessor.create(base.bounds().minY(), base.bounds().maxYExclusive() - base.bounds().minY());
        var noise = base.settings().noiseSettings().clampToHeightAccessor(height);
        for (long seed : SEEDS) {
            var frozen = new CpuTerrainOracle.FrozenInput(base.settings(), base.noises(), base.fluidPicker(),
                    base.biomeSource(), base.bounds(), seed, base.fingerprint());
            var reference = RandomState.create(base.settings(), base.noises(), seed);
            var slicedState = RandomState.create(base.settings(), base.noises(), seed);
            long cpuStart = threadTiming.isCurrentThreadCpuTimeSupported() && threadTiming.isThreadCpuTimeEnabled()
                    ? threadTiming.getCurrentThreadCpuTime() : -1;
            long constructStart = System.nanoTime();
            try (var oracle = frozen.createOracle()) {
                long constructNanos = System.nanoTime() - constructStart;
                long cpuEnd = cpuStart >= 0 ? threadTiming.getCurrentThreadCpuTime() : -1;
                initializations.add(Map.of("seed", seed, "oracleConstructorWallNanos", constructNanos,
                        "oracleConstructorCpuNanos", cpuStart >= 0 && cpuEnd >= 0 ? cpuEnd - cpuStart : -1L,
                        "nativeRandomStateWallNanos", oracle.timings().randomStateNanos()));
                for (int index = 0; index < 128; index++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("native oracle diagnostic stopped");
                    int x, z;
                    if (index < 8) {
                        int[] edge = {-1000000, -17, -16, -1, 0, 1, 16, 1000000};
                        x = edge[index]; z = edge[7 - index];
                    } else { x = -4096 + Math.floorMod(index * 977, 8192); z = -4096 + Math.floorMod(index * 619, 8192); }
                    NoiseColumn nativeColumn = generator.getBaseColumn(x, z, height, reference); // Test-only independent baseline.
                    long start = System.nanoTime();
                    int width = noise.getCellWidth(), cellHeight = noise.getCellHeight();
                    var chunk = new NoiseChunk(1, slicedState, Math.floorDiv(x, width) * width, Math.floorDiv(z, width) * width,
                            noise, DensityFunctions.BeardifierMarker.INSTANCE, base.settings(), base.fluidPicker(), Blender.empty());
                    timing.merge("maxBridgeConstructNanos", System.nanoTime() - start, Math::max);
                    var bridge = (NoiseCursorBridge) (Object) chunk; bridge.greatwall$beginSlices();
                    try {
                        int call = 0;
                        while (true) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("native oracle slices stopped");
                            int limit = new int[] {1, 2, 7, 256}[call++ % 4];
                            start = System.nanoTime(); boolean done = bridge.greatwall$advanceSlices(limit);
                            if (limit == 1) timing.merge("maxBridgePointNanos", System.nanoTime() - start, Math::max);
                            if (done) break;
                        }
                        for (int cy = noise.height() / cellHeight - 1; cy >= 0; cy--) {
                            start = System.nanoTime(); chunk.selectCellYZ(cy, 0);
                            timing.merge("maxBridgeCellNanos", System.nanoTime() - start, Math::max);
                            for (int inner = cellHeight - 1; inner >= 0; inner--) {
                                int y = (Math.floorDiv(noise.minY(), cellHeight) + cy) * cellHeight + inner;
                                chunk.updateForY(y, (double) inner / cellHeight);
                                chunk.updateForX(x, (double) Math.floorMod(x, width) / width);
                                chunk.updateForZ(z, (double) Math.floorMod(z, width) / width);
                                var actual = bridge.greatwall$state(); if (actual == null) actual = base.settings().defaultBlock();
                                var expected = nativeColumn.getBlock(y);
                                if (!actual.equals(expected)) throw new IllegalStateException("state mismatch seed=" + seed
                                        + " index=" + index + " xyz=" + x + "," + y + "," + z + " native=" + expected + " sliced=" + actual);
                                expectedDigest.update(token(tokens, expected)); actualDigest.update(token(tokens, actual)); blocks++;
                            }
                        }
                    } finally { chunk.stopInterpolation(); }
                    var cursor = oracle.begin(x, z, SampleKind.REFERENCE);
                    while (!cursor.done()) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("native oracle sample stopped");
                        oracle.advance(cursor, 7);
                    }
                    var actual = oracle.finish(cursor);
                    var expected = reduce(nativeColumn, frozen, reference, x, z);
                    if (!actual.equals(expected)) throw new IllegalStateException("reduction mismatch seed=" + seed
                            + " index=" + index + " expected=" + expected + " actual=" + actual);
                    if (actual.waterTopY().isPresent()) wet++; else dry++;
                    columns++;
                    if (index % 32 == 31) System.out.println("S1C_ORACLE_PROFILE_PROGRESS seed=" + seed
                            + " columns=" + columns + "/512 states=" + blocks + " minY=" + noise.minY() + " height=" + noise.height());
                }
                var t = oracle.timings();
                timing.merge("maxOracleRandomStateNanos", t.randomStateNanos(), Math::max);
                timing.merge("maxOracleConstructNanos", t.maxConstructNanos(), Math::max);
                timing.merge("maxOracleCellNanos", t.maxCellNanos(), Math::max);
                timing.merge("maxOraclePointNanos", t.maxSlicePointNanos(), Math::max);
                timing.merge("maxOracleBlockNanos", t.maxBlockNanos(), Math::max);
                timing.merge("maxOracleBiomeNanos", t.maxBiomeNanos(), Math::max);
                timing.merge("oracleOver40msPrimitives", t.over40msPrimitives(), Math::addExact);
            }
        }
        String nativeHash = HexFormat.of().formatHex(expectedDigest.digest()), slicedHash = HexFormat.of().formatHex(actualDigest.digest());
        if (!nativeHash.equals(slicedHash) || columns != 512) throw new IllegalStateException("native oracle aggregate mismatch");
        var report = new TreeMap<String, Object>();
        boolean performance = timing.entrySet().stream().filter(e -> e.getKey().startsWith("max"))
                .allMatch(e -> e.getValue() <= 40_000_000L) && timing.get("oracleOver40msPrimitives") == 0;
        report.put("status", performance ? "PASS_NATIVE_ORACLE" : "BLOCKED_PERFORMANCE");
        report.put("correctness", "PASS"); report.put("performance", performance ? "PASS_OBSERVED_INPUTS" : "BLOCKED");
        report.put("purpose", "native-oracle-only-not-production-wall-acceptance");
        report.put("profile", System.getProperty("greatwall.smoke.profile")); report.put("mods", mods);
        report.put("minY", noise.minY()); report.put("maxYExclusive", base.bounds().maxYExclusive());
        report.put("fingerprint", base.fingerprint()); report.put("seeds", SEEDS);
        report.put("columns", columns); report.put("states", blocks); report.put("wet", wet); report.put("dry", dry);
        report.put("nativeSha256", nativeHash); report.put("slicedSha256", slicedHash); report.put("timing", Map.copyOf(timing));
        report.put("oracleInitializations", List.copyOf(initializations));
        int low = base.bounds().minY();
        report.put("actualFluidAtMinPlus9", BlockCatalog.specification(base.fluidPicker().computeFluid(0, low + 9, 0).at(low + 9)));
        report.put("actualFluidAtMinPlus10", BlockCatalog.specification(base.fluidPicker().computeFluid(0, low + 10, 0).at(low + 10)));
        report.put("belowSeaIsWater", base.fluidPicker().computeFluid(0, base.settings().seaLevel() - 1, 0)
                .at(base.settings().seaLevel() - 1).is(Blocks.WATER));
        return Map.copyOf(report);
    }

    private static byte[] token(HashMap<BlockState, byte[]> tokens, BlockState state) {
        return tokens.computeIfAbsent(state, key -> (BlockCatalog.token(key, false) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static ColumnSample reduce(NoiseColumn column, CpuTerrainOracle.FrozenInput frozen, RandomState reference, int x, int z) {
        int ground = frozen.bounds().minY(), solid = Integer.MIN_VALUE;
        boolean open = true, censored = false;
        OptionalInt water = OptionalInt.empty();
        for (int y = frozen.bounds().maxYExclusive() - 1; y >= frozen.bounds().minY(); y--) {
            var state = column.getBlock(y);
            if (state.getFluidState().is(FluidTags.WATER) && open) {
                if (water.isEmpty()) water = OptionalInt.of(y + 1);
                if (y == frozen.bounds().maxYExclusive() - 1) censored = true;
            } else if (!state.isAir() && state.getFluidState().isEmpty()) { ground = y + 1; solid = y; break; }
            else if (!state.getFluidState().isEmpty()) { open = false; censored = true; }
        }
        if (solid == Integer.MIN_VALUE || (long) solid - 3 < frozen.bounds().minY()
                || solid == frozen.bounds().maxYExclusive() - 1) censored = true;
        String biome = frozen.biomeSource().getNoiseBiome(Math.floorDiv(x, 4), Math.floorDiv(ground, 4),
                Math.floorDiv(z, 4), reference.sampler()).unwrapKey().orElseThrow().location().toString();
        return new ColumnSample(ground, water, water.isPresent() && solid != Integer.MIN_VALUE
                ? OptionalInt.of(ground) : OptionalInt.empty(), biome, censored ? Quality.CENSORED : Quality.REFERENCE, censored);
    }
}
