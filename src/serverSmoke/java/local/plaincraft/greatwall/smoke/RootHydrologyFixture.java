package local.plaincraft.greatwall.smoke;

import com.google.gson.GsonBuilder;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.lifecycle.ProductionInputs;
import local.plaincraft.greatwall.lifecycle.RootSearch;
import local.plaincraft.greatwall.lifecycle.SpawnDomain;
import local.plaincraft.greatwall.lifecycle.WorldBootstrap;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.AnchorField;
import local.plaincraft.greatwall.planning.RidgePlanner;
import local.plaincraft.greatwall.terrain.CpuTerrainOracle;
import local.plaincraft.greatwall.terrain.HydrologyClassifier;
import local.plaincraft.greatwall.terrain.OracleLane;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

/** One actual new world, original root/routes/hydrology, no planner prewarm and no wall pipeline. */
public final class RootHydrologyFixture implements AutoCloseable {
    private record SpawnObservation(SpawnDomain domain, ConcurrentHashMap<String, Point3i> chosen) {}
    private record RootResult(RootSearch.Selection selection, RootSearch.Progress progress,
            List<RootSearch.Audit> audit, CpuTerrainOracle.TimingSnapshot timing) {}
    private record PhaseStats(long batches, long operations, long maxBatchNanos, long over40msBatches) {}
    private record RouteResult(RouteShape shape, PhaseStats stats, CpuTerrainOracle.TimingSnapshot timing) {}
    private record HydroResult(List<WaterEvidence> water, PhaseStats stats, int columns,
            CpuTerrainOracle.TimingSnapshot timing) {}
    private record Completion(Map<String, Object> row) {}

    private static final ConcurrentHashMap<MinecraftServer, SpawnObservation> SPAWN = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> state = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Completion> completed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OracleLane> lanes = new ConcurrentHashMap<>();
    private ExecutorService worker;
    private MinecraftServer server;

    public static boolean enabled() { return Boolean.getBoolean("greatwall.s1c.seedSurvey"); }

    /** Same HEAD and native FULL dependency radius as the production SpawnProbeMixin. */
    public static void recordProbe(ServerLevel level, ChunkPos pos) {
        if (!enabled() || !level.dimension().location().toString().equals("minecraft:overworld")) return;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("spawn observation outside server owner");
        var observation = SPAWN.computeIfAbsent(level.getServer(),
                ignored -> new SpawnObservation(new SpawnDomain(), new ConcurrentHashMap<>()));
        int radius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL).accumulatedDependencies().getRadius();
        observation.domain().authorize(new ChunkKey("minecraft:overworld", pos.x, pos.z), radius);
    }

    /** Same RETURN as production; never substitute a synthetic spawn or probe the world again. */
    public static void recordChosen(ServerLevel level) {
        if (!enabled() || !level.dimension().location().toString().equals("minecraft:overworld")) return;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("spawn observation outside server owner");
        var observation = SPAWN.computeIfAbsent(level.getServer(),
                ignored -> new SpawnObservation(new SpawnDomain(), new ConcurrentHashMap<>()));
        var p = level.getSharedSpawnPos();
        if (observation.chosen().putIfAbsent("spawn", new Point3i(p.getX(), p.getY(), p.getZ())) != null) {
            throw new IllegalStateException("survey initial spawn observed twice");
        }
    }

    public boolean tick(MinecraftServer current) {
        if (!enabled()) throw new IllegalStateException("survey flag required");
        if (state.putIfAbsent("started", true) == null) {
            server = current;
            if (Boolean.getBoolean("greatwall.production") || Boolean.getBoolean("greatwall.s1c.productionFixture")
                    || Boolean.getBoolean("greatwall.s0.probe") || WorldServices.find(current) != null) {
                throw new IllegalStateException("survey is separate from all world-writing services");
            }
            if (!System.getProperty("greatwall.smoke.profile", "").equals("vanilla")
                    || FabricLoader.getInstance().isModLoaded("tectonic")
                    || FabricLoader.getInstance().isModLoaded("c2me")
                    || FabricLoader.getInstance().isModLoaded("tanshugetrees")) {
                throw new IllegalStateException("this fixed survey requires vanilla profile");
            }
            long seed = current.overworld().getSeed();
            if (seed != Long.getLong("greatwall.s1c.surveySeed", Long.MIN_VALUE) || seed < 0 || seed > 39) {
                throw new IllegalStateException("actual seed must equal the fixed 0..39 input");
            }
            var observation = SPAWN.get(current);
            if (observation == null || observation.chosen().get("spawn") == null) {
                throw new IllegalStateException("native spawn hooks were not observed");
            }
            final ProductionInputs inputs;
            try { inputs = ProductionInputs.freeze(current.overworld()); }
            catch (Exception failure) { throw new IllegalStateException("cannot freeze original production inputs", failure); }
            Point3i spawn = observation.chosen().get("spawn");
            List<ChunkKey> early = observation.domain().snapshot();
            if (early.isEmpty()) throw new IllegalStateException("empty actual spawn domain");
            worker = Executors.newSingleThreadExecutor(task -> {
                var thread = new Thread(task, "pgw-test-root-hydrology"); thread.setDaemon(true); return thread;
            });
            CompletableFuture.supplyAsync(() -> run(seed, spawn, early, inputs.bootstrap()), worker)
                    .whenComplete((row, error) -> {
                        if (error == null) completed.put("done", new Completion(row));
                        else completed.put("done", new Completion(Map.of("seed", seed, "category", "OTHER",
                                "observationStatus", "INSTRUMENTATION_ERROR", "failure", stack(error),
                                "productionWiring", "OFF", "noWallWrites", true)));
                        worker.shutdown();
                    });
        }
        var done = completed.get("done");
        if (done == null) return false;
        if (state.putIfAbsent("persisted", true) == null) {
            if (WorldServices.find(current) != null
                    || Files.exists(current.getWorldPath(LevelResource.ROOT).resolve("greatwall/evidence"))) {
                throw new IllegalStateException("survey unexpectedly installed a wall pipeline");
            }
            try {
                Files.writeString(path("seed-survey-result.json"),
                        new GsonBuilder().setPrettyPrinting().create().toJson(done.row()));
            } catch (Exception failure) { throw new IllegalStateException("cannot persist survey row", failure); }
            GreatWallMod.LOGGER.info("[GreatWall:survey] OBSERVED seed={} category={} noWallWrites=true",
                    current.overworld().getSeed(), done.row().get("category"));
        }
        return true;
    }

    private Map<String, Object> run(long seed, Point3i spawn, List<ChunkKey> early, WorldBootstrap.Frozen inputs) {
        if (!Thread.currentThread().getName().equals("pgw-test-root-hydrology")) {
            throw new IllegalStateException("blocking diagnostic orchestration requires its dedicated test thread");
        }
        UUID session = UUID.randomUUID();
        var row = new TreeMap<String, Object>();
        row.put("seed", seed); row.put("profile", "vanilla"); row.put("spawn", spawn); row.put("earlyDomain", early);
        row.put("dimensionBounds", inputs.bounds()); row.put("terrainHash", inputs.terrainHash());
        row.put("productionWiring", "OFF"); row.put("noWallWrites", true);
        row.put("purpose", "root-and-segment0-hydrology-survey-not-wall-acceptance");
        row.put("onlyHydrologySegment", 0); row.put("route1Purpose", "original 128-block next-route halo");
        var lane = new OracleLane(session, 0, inputs.terrainHash(), inputs.factory());
        lanes.put("lane", lane);
        String phase = "ROOT";
        long start = System.nanoTime();
        try {
            RootResult root = lane.submit(oracle -> {
                var search = new RootSearch(spawn, inputs.bounds(), early, inputs.foundation());
                try {
                    while (search.advance(oracle, 64) != WorkState.DONE) checkRunning();
                    return new RootResult(search.finish(), search.progress(), search.audit(), ((CpuTerrainOracle) oracle).timings());
                } finally {
                    snapshots.put("rootProgress", search.progress());
                    snapshots.put("rootAudit", search.audit());
                }
            }).get(18, TimeUnit.MINUTES);
            row.put("root", root); row.put("rootSucceeded", true);
            var config = WorldBootstrap.defaultConfig();
            String zero = "0".repeat(64);
            var raw = new WorldRecipe(session, zero, seed, "minecraft:overworld", spawn, root.selection().axis(),
                    inputs.bounds(), config, inputs.terrainHash(), inputs.paletteHash(), inputs.templatesHash(), zero);
            String hash = DeterministicHash.sha256(CanonicalCodec.recipe(raw));
            var recipe = new WorldRecipe(session, hash, seed, raw.dimension(), spawn, raw.axis(), raw.bounds(), config,
                    raw.terrainHash(), raw.paletteHash(), raw.templatesHash(), hash);
            row.put("recipe", recipe); row.put("validatedAxisColumns", axisColumns(recipe.axis()));
            var anchors = new AnchorField(recipe);
            row.put("boundary1LateralQ8", anchors.lateral(1));
            phase = "ROUTE_0";
            RouteResult first = route(lane, recipe, 0);
            snapshots.put("route0", routeSummary(first, anchors, 0));
            Files.write(path("survey-route0.bin"), CanonicalCodec.route(first.shape()));
            phase = "ROUTE_1_HALO_DEPENDENCY";
            RouteResult second = route(lane, recipe, 1);
            snapshots.put("route1", routeSummary(second, anchors, 1));
            Files.write(path("survey-route1.bin"), CanonicalCodec.route(second.shape()));
            phase = "HYDROLOGY_0";
            HydroResult water = lane.submit(oracle -> {
                var classifier = new HydrologyClassifier();
                var cursor = classifier.begin(new RouteNeighborhood(first.shape(), Optional.empty(),
                        Optional.of(second.shape())), oracle, 24000);
                try {
                    return batches(() -> classifier.advance(cursor, 1) == WorkState.DONE,
                            stats -> new HydroResult(classifier.finish(cursor), stats, cursor.logicalColumns(),
                                    ((CpuTerrainOracle) oracle).timings()));
                } catch (Throwable failure) {
                    snapshots.put("hydrologyFailure", inspectFailure(cursor, first.shape(), recipe.axis()));
                    throw failure;
                }
            }).get(18, TimeUnit.MINUTES);
            row.put("water", water); row.put("segment0HydrologyFinished", true);
            OptionalLong terminal = water.water().stream().filter(w -> w.classification() == WaterClass.WIDE
                            && w.crossing().fromQ8() >= 0 && w.crossing().fromQ8() < first.shape().localSQ8().getLast())
                    .mapToLong(w -> w.crossing().fromQ8()).min();
            row.put("oceanTerminalSQ8", terminal.isPresent() ? terminal.getAsLong() : -1L);
            row.put("oceanBefore1200", terminal.isPresent() && terminal.getAsLong() < 1200L * 256);
            row.put("category", "3_SEGMENT0_HYDROLOGY_FINISHED");
            phase = "DONE";
        } catch (Throwable error) {
            FailureCode code = code(error);
            row.put("failurePhase", phase); row.put("failureCode", code == null ? "NON_WALL_FAILURE" : code.name());
            row.put("failure", stack(error));
            row.put("category", phase.equals("ROOT") && code == FailureCode.ROOT_NOT_FOUND ? "1_ROOT_NOT_FOUND"
                    : phase.equals("HYDROLOGY_0") && (code == FailureCode.HYDROLOGY_UNRESOLVED || code == FailureCode.ROOT_NOT_FOUND)
                            ? "2_SEGMENT0_HYDROLOGY_REJECTED" : "OTHER");
        } finally {
            lane.close();
            try { lane.drain().get(2, TimeUnit.MINUTES); row.put("oracleDrained", true); }
            catch (Exception error) { row.put("drainFailure", stack(error)); row.put("oracleDrained", false); }
            row.put("lane", lane.diagnostics()); row.put("phaseSnapshots", Map.copyOf(snapshots));
            row.put("elapsedSeconds", (System.nanoTime() - start) / 1_000_000_000.0);
            row.put("observationStatus", "OBSERVATION_COMPLETE");
        }
        return Map.copyOf(row);
    }

    private RouteResult route(OracleLane lane, WorldRecipe recipe, long segment) throws Exception {
        return lane.submit(oracle -> {
            var planner = new RidgePlanner(recipe, new AnchorField(recipe));
            var cursor = planner.begin(new SegmentKey(recipe.recipeHash(), recipe.dimension(), segment), oracle);
            return batches(() -> planner.advance(cursor, 1) == WorkState.DONE,
                    stats -> new RouteResult(planner.finish(cursor), stats, ((CpuTerrainOracle) oracle).timings()));
        }).get(18, TimeUnit.MINUTES);
    }

    private <T> T batches(BooleanSupplier step, java.util.function.Function<PhaseStats, T> finish) {
        long count = 0, operations = 0, maximum = 0, over40 = 0;
        while (true) {
            checkRunning();
            long start = System.nanoTime();
            boolean done = false;
            T result = null;
            for (int op = 0; op < 64; op++) {
                operations++;
                if (step.getAsBoolean()) { done = true; break; }
                if (System.nanoTime() - start >= 25_000_000L) break;
            }
            if (done) result = finish.apply(new PhaseStats(count + 1, operations, maximum, over40));
            long elapsed = System.nanoTime() - start;
            maximum = Math.max(maximum, elapsed); if (elapsed > 40_000_000L) over40++; count++;
            if (count % 16384 == 0) {
                snapshots.put("lastBatchProgress", Map.of("batches", count, "operations", operations,
                        "maxBatchNanos", maximum, "over40msBatches", over40));
            }
            if (done) {
                // Replace the provisional timing record after measuring value/publish as production does.
                var stats = new PhaseStats(count, operations, maximum, over40);
                if (result instanceof RouteResult r) return cast(new RouteResult(r.shape(), stats, r.timing()));
                if (result instanceof HydroResult h) return cast(new HydroResult(h.water(), stats, h.columns(), h.timing()));
                throw new IllegalStateException("unknown survey phase result");
            }
        }
    }

    @SuppressWarnings("unchecked") private static <T> T cast(Object value) { return (T) value; }

    private static Map<String, Object> routeSummary(RouteResult result, AnchorField anchors, long segment) {
        var shape = result.shape(); boolean same = true;
        for (int u = 0; u <= 2048; u++) if (!shape.points().get(u).equals(anchors.carrier(segment, u * 256))) { same = false; break; }
        return Map.of("hash", shape.hash(), "arcLengthSQ8", shape.localSQ8().getLast(),
                "degradation", shape.degradation(), "searchColumns", shape.searchColumns(),
                "boundaryColumns", shape.boundaryColumns(), "equalsCarrierAtAllU", same, "stats", result.stats(),
                "timing", result.timing());
    }

    /** Read-only own-mod cursor inspection on its owning lane; never used as classification input. */
    private static Map<String, Object> inspectFailure(HydrologyClassifier.Cursor cursor, RouteShape route, Axis axis) {
        var result = new TreeMap<String, Object>();
        result.put("logicalColumns", cursor.logicalColumns());
        try {
            int phase = (int) field(cursor, "phase");
            result.put("cursorPhase", phase);
            ArcInterval crossing = phase >= 3 ? (ArcInterval) field(cursor, "crossing") : null;
            if (crossing == null && phase == 2 && field(cursor, "run") != null) {
                var run = field(cursor, "run"); var trace = (List<?>) field(cursor, "trace");
                int a = (int) field(run, "from"), b = (int) field(run, "to");
                long from = (long) field(trace.get(a), "s");
                long to = (long) field(b < trace.size() ? trace.get(b) : trace.getLast(), "s");
                if (to > from) crossing = new ArcInterval(from, to);
            }
            if (crossing != null) {
                result.put("crossing", crossing);
                result.put("location", SeedSurveyLocations.locate(route, axis, crossing));
            }
            result.put("crossingLocated", crossing != null);
            result.put("waterMismatch", field(cursor, "waterMismatch"));
            result.put("waterY", field(cursor, "waterY"));
            result.put("widthQ8", field(cursor, "width"));
            Object[][][] sides = (Object[][][]) field(cursor, "results");
            var table = new ArrayList<Map<String, Object>>();
            if (sides != null) for (int center = 0; center < sides.length; center++) for (int k = 0; k < 16; k++) {
                Object left = sides[center][k][0], right = sides[center][k][1];
                if (left == null || right == null) continue;
                table.add(Map.of("center", center, "direction", k,
                        "leftBank", field(left, "bank"), "rightBank", field(right, "bank"),
                        "leftExtentQ8", field(left, "extent"), "rightExtentQ8", field(right, "extent"),
                        "leftLowerQ8", field(left, "lower"), "rightLowerQ8", field(right, "lower")));
            }
            result.put("transects", List.copyOf(table));
        } catch (Exception error) {
            result.put("diagnosticReadFailure", stack(error));
            result.putIfAbsent("crossingLocated", false);
        }
        return Map.copyOf(result);
    }

    private static Object field(Object value, String name) throws ReflectiveOperationException {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }

    private static List<Map<String, Integer>> axisColumns(Axis axis) {
        var result = new ArrayList<Map<String, Integer>>();
        for (int distance : new int[] {256, 512, 1024}) for (int across : new int[] {0, -48, 48}) {
            int x = Math.toIntExact(axis.origin().x() + FixedMath.roundDiv(
                    (long) distance * axis.exQ30() - (long) across * axis.ezQ30(), 1L << 30));
            int z = Math.toIntExact(axis.origin().z() + FixedMath.roundDiv(
                    (long) distance * axis.ezQ30() + (long) across * axis.exQ30(), 1L << 30));
            result.add(Map.of("axisDistance", distance, "across", across, "x", x, "z", z));
        }
        return List.copyOf(result);
    }

    private void checkRunning() {
        if (state.containsKey("closed") || Thread.currentThread().isInterrupted()) {
            throw new WallFailure(FailureCode.CLOSED, "survey diagnostic stopping");
        }
    }
    private static FailureCode code(Throwable error) {
        for (Throwable value = error; value != null; value = value.getCause()) {
            if (value instanceof WallFailure failure) return failure.code();
        }
        return null;
    }
    private static String stack(Throwable error) {
        var text = new StringWriter(); error.printStackTrace(new PrintWriter(text)); return text.toString();
    }
    private static Path path(String name) { return FabricLoader.getInstance().getGameDir().resolve(name); }
    @Override public void close() {
        if (state.putIfAbsent("closed", true) != null) return;
        lanes.values().forEach(OracleLane::close);
        if (worker != null) worker.shutdown();
        if (server != null) SPAWN.remove(server);
    }
}
