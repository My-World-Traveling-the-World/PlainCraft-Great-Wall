package local.plaincraft.greatwall.smoke;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.diagnostics.WalkwayCollisionCheck;
import local.plaincraft.greatwall.generation.CoveragePlanner;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import local.plaincraft.greatwall.mixin.TerrainChunkAccess;

/** Ordinary dedicated-server traversal. All mutable traversal state belongs to its server owner. */
public final class RealTerrainFixture implements AutoCloseable {
    private static final TicketType<ChunkPos> TICKET = TicketType.create("pgw_s1c_real_fixture",
            Comparator.comparingInt((ChunkPos p) -> p.x).thenComparingInt(p -> p.z));
    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "pgw-real-fixture-evidence"); thread.setDaemon(true); return thread;
    });
    private final List<Map<String, Object>> owners = new ArrayList<>();
    private final List<Map<String, Object>> nonOwners = new ArrayList<>();
    private final List<ChunkPos> positions = new ArrayList<>();
    private final List<Long> latency = new ArrayList<>();
    private final ConcurrentHashMap<String, Boolean> closed = new ConcurrentHashMap<>();
    private PlanLease<SegmentPlan> planning;
    private PlanWindow planWindow;
    private CompletableFuture<Void> progress;
    private WorldRecipe recipe;
    private List<Map<String, Object>> firstRun = List.of();
    private Throwable failure;
    private int next;
    private long started;
    private long voxels, feet;
    private boolean begun, finished;
    private final boolean reopen = Boolean.getBoolean("greatwall.s1c.reopen");
    private final CoveragePlanner coverage = new CoveragePlanner();

    public static CompletableFuture<Void> run(ServerLevel level, long seed) {
        if (level.getSeed() != seed) return CompletableFuture.failedFuture(new IllegalArgumentException("actual world seed differs"));
        var fixture = new RealTerrainFixture();
        fixture.begin(level.getServer());
        return fixture.progress.copy().whenComplete((value, error) -> fixture.close());
    }

    boolean tick(MinecraftServer server) {
        if (!begun) begin(server);
        if (failure != null) throw new IllegalStateException("real wall traversal failed; production remains OFF", failure);
        if (System.nanoTime() - started > TimeUnit.MINUTES.toNanos(39)) {
            throw new IllegalStateException("real wall traversal exceeded its finite 39-minute budget");
        }
        return finished;
    }

    private void begin(MinecraftServer server) {
        begun = true; started = System.nanoTime(); progress = new CompletableFuture<>();
        var services = WorldServices.find(server);
        if (services == null || !services.productionFixture()) throw new IllegalStateException("separate S1C fixture session required");
        services.recipeReady().thenComposeAsync(value -> {
            recipe = value;
            planning = services.planning().requirePlan(0, Priority.PLAYER);
            return planning.future();
        }, server).thenAcceptAsync(plan -> {
            try {
                select(plan, server);
                generateNext(server);
            } catch (Throwable error) { failed(server, error); }
            finally { if (planning != null) planning.close(); }
        }, server).exceptionally(error -> { server.execute(() -> failed(server, error)); return null; });
    }

    /** Representative-sample floor. Unchanged by default; only an explicit
     *  diagnostic run may lower it, and the lowered value is reported. */
    private static long representativeLengthQ8() {
        long blocks = Long.getLong("greatwall.s1c.representativeBlocks", 1200L);
        if (blocks < 64L) throw new IllegalStateException("representative length floor is at least 64 blocks");
        return blocks * 256L;
    }

    private void select(SegmentPlan plan, MinecraftServer server) throws IOException {
        planWindow = new PlanWindow(List.of(plan));
        long length = plan.prefix().lengthBeforeQ8() + plan.geometry().localSQ8().getLast();
        if (plan.geometry().terminal().isPresent()) {
            var end = plan.geometry().terminal().get();
            length = Math.min(length, plan.prefix().lengthBeforeQ8() + end.localSQ8());
        }
        if (length < representativeLengthQ8()) {
            throw new IllegalStateException("fixed representative seed terminates before "
                    + (representativeLengthQ8() / 256) + " blocks; preserve terminal, do not substitute seed");
        }
        var ordered = new LinkedHashSet<ChunkPos>();
        for (var station : plan.stations().stations()) {
            if (station.globalSQ8() > 1248L * 256) break;
            int x = FixedMath.floorBlock(station.point().xQ8()), z = FixedMath.floorBlock(station.point().zQ8());
            // The 36-block radius contains every S1 wall, scheduled site and potential rotated cap/ramp.
            for (int cx = Math.floorDiv(x - 36, 16); cx <= Math.floorDiv(x + 36, 16); cx++) {
                for (int cz = Math.floorDiv(z - 36, 16); cz <= Math.floorDiv(z + 36, 16); cz++) ordered.add(new ChunkPos(cx, cz));
            }
        }
        for (var site : plan.sites()) {
            if (site.globalSQ8() > 1248L * 256 || site.state() == SiteState.SUPPRESSED || site.state() == SiteState.ABSORBED) continue;
            var box = site.bounds();
            for (int cx = Math.floorDiv(box.minX(), 16); cx <= Math.floorDiv(box.maxXExclusive() - 1, 16); cx++) {
                for (int cz = Math.floorDiv(box.minZ(), 16); cz <= Math.floorDiv(box.maxZExclusive() - 1, 16); cz++) ordered.add(new ChunkPos(cx, cz));
            }
        }
        if (ordered.isEmpty() || ordered.size() > 4096) throw new IllegalStateException("finite traversal owner count");
        positions.addAll(ordered);
        if (reopen) {
            var path = reportPath("generated");
            if (!Files.isRegularFile(path)) throw new IllegalStateException("reopen needs the first traversal certificate");
            var tree = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!tree.get("recipeHash").getAsString().equals(recipe.recipeHash())
                    || tree.get("seed").getAsLong() != server.overworld().getSeed()) throw new IllegalStateException("reopen recipe changed");
            var baseline = new ArrayList<Map<String, Object>>();
            for (var entry : tree.getAsJsonArray("owners")) {
                var item = entry.getAsJsonObject();
                var row = new HashMap<String, Object>();
                row.put("x", item.get("x").getAsInt());
                row.put("z", item.get("z").getAsInt());
                row.put("status", item.has("status") ? item.get("status").getAsString() : "OWNER");
                if (item.has("plan")) row.put("plan", item.get("plan").getAsString());
                if (item.has("actualHash")) row.put("actualHash", item.get("actualHash").getAsString());
                baseline.add(Map.copyOf(row));
            }
            firstRun = List.copyOf(baseline);
            if (firstRun.size() != positions.size()) throw new IllegalStateException("reopen owner count changed");
        }
        GreatWallMod.LOGGER.info("[GreatWall:S1C] REAL_WALL_TRAVERSAL_BEGIN seed={} owners={} reopen={} production=OFF",
                server.overworld().getSeed(), positions.size(), reopen);
    }

    private void generateNext(MinecraftServer server) {
        if (closed.containsKey("closed")) return;
        if (next == positions.size()) { finish(server); return; }
        var pos = positions.get(next);
        var level = server.overworld();
        var source = level.getChunkSource();
        source.addRegionTicket(TICKET, pos, 0, pos);
        long requested = System.nanoTime();
        ((TerrainChunkAccess) source).greatwall$request(pos.x, pos.z, ChunkStatus.FULL, true)
                .thenComposeAsync(result -> {
                    var chunk = result.orElseThrow(() -> new IllegalStateException("FULL unavailable " + pos + ": " + result.getError()));
                    var key = new ChunkKey(recipe.dimension(), pos.x, pos.z);
                    var marker = chunk.getAttached(TerrainMarkers.PRODUCTION);
                    if (coverage.cellsForChunk(key, planWindow).isEmpty()) {
                        if (marker != null) throw new IllegalStateException("NON_OWNER has a wall marker " + pos);
                        var row = Map.<String, Object>of("x", pos.x, "z", pos.z,
                                "status", "NON_OWNER", "voxels", 0L, "feet", 0L);
                        nonOwners.add(row);
                        if (reopen) {
                            var prior = firstRun.get(next);
                            if (!"NON_OWNER".equals(prior.get("status"))
                                    || !prior.get("x").equals(pos.x) || !prior.get("z").equals(pos.z))
                                throw new IllegalStateException("reopened owner-domain classification changed " + pos);
                        }
                        return CompletableFuture.completedFuture(row);
                    }
                    if (marker == null || !marker.complete() || !marker.recipe().equals(recipe.recipeHash())) {
                        throw new IllegalStateException("missing/foreign completed production marker in traversed owner " + pos);
                    }
                    var store = ChunkEvidenceStore.production(server.getWorldPath(LevelResource.ROOT).resolve("greatwall/evidence"));
                    return CompletableFuture.supplyAsync(() -> {
                        try { return store.readPlan(new ChunkKey(recipe.dimension(), pos.x, pos.z)).orElseThrow(); }
                        catch (IOException error) { throw new CompletionException(error); }
                    }, io).thenApplyAsync(plan -> validate(level, chunk, marker, plan), server);
                }, server).whenCompleteAsync((result, error) -> {
                    source.removeRegionTicket(TICKET, pos, 0, pos);
                    if (error != null) { failed(server, error); return; }
                    if (closed.containsKey("closed")) return;
                    long elapsed = System.nanoTime() - requested;
                    latency.add(elapsed); owners.add(result);
                    voxels += (long) result.get("voxels"); feet += (long) result.get("feet");
                    next++;
                    if (next == 1 || next % 16 == 0 || next == positions.size()) {
                        GreatWallMod.LOGGER.info("[GreatWall:S1C] REAL_WALL_OWNER_PROGRESS owners={}/{} voxels={} feet={} lastFullMs={}",
                                next, positions.size(), voxels, feet, elapsed / 1_000_000.0);
                        writeProgress(server);
                    }
                    generateNext(server);
                }, server);
    }

    private Map<String, Object> validate(ServerLevel level, ChunkAccess chunk, TerrainMarkers.Marker marker, ChunkWritePlan plan) {
        if (!plan.hash().equals(marker.planHash()) || !plan.metadata().coverageHash().equals(marker.coverageHash())
                || !plan.recipeHash().equals(recipe.recipeHash())) throw new IllegalStateException("durable proof and native marker differ");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var intent : plan.voxels()) {
                var p = new BlockPos(intent.pos().x(), intent.pos().y(), intent.pos().z());
                var state = chunk.getBlockState(p);
                if (!BlockCatalog.specification(state).id().equals(intent.state().id())) {
                    throw new IllegalStateException("missing/replaced real voxel " + p + " expected=" + intent.state() + " actual=" + state);
                }
                // Native postprocessing may change stair/fence neighbor properties; hash the actual final state.
                digest.update((intent.pos() + ":" + BlockCatalog.token(state, chunk.getBlockEntitiesPos().contains(p)) + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            for (var foot : marker.walkwayFeet()) {
                var p = new BlockPos(foot.x(), foot.y(), foot.z());
                var result = WalkwayCollisionCheck.inspect(chunk.getBlockState(p).getCollisionShape(level, p),
                        chunk.getBlockState(p.above()).getCollisionShape(level, p.above()),
                        chunk.getBlockState(p.below()).getCollisionShape(level, p.below()));
                if (!result.valid()) {
                    throw new IllegalStateException("real walkway lacks ground or two-block clearance " + p
                            + " " + result + " below=" + chunk.getBlockState(p.below())
                            + " feet=" + chunk.getBlockState(p) + " head=" + chunk.getBlockState(p.above()));
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (reopen) {
                var prior = firstRun.get(next);
                if (!prior.get("x").equals(chunk.getPos().x) || !prior.get("z").equals(chunk.getPos().z)
                        || !"OWNER".equals(prior.getOrDefault("status", "OWNER"))
                        || !prior.get("plan").equals(plan.hash()) || !prior.get("actualHash").equals(actual)) {
                    throw new IllegalStateException("reopened owner lost/changed geometry " + chunk.getPos());
                }
            }
            return Map.of("x", chunk.getPos().x, "z", chunk.getPos().z, "status", "OWNER",
                    "plan", plan.hash(), "actualHash", actual,
                    "voxels", (long) plan.voxels().size(), "feet", (long) marker.walkwayFeet().size());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void finish(MinecraftServer server) {
        try {
            if (voxels == 0 || feet == 0) throw new IllegalStateException("markers without a physically generated wall cannot pass");
            var services = WorldServices.find(server);
            if (services.counts().getOrDefault("WAIT_WHILE_LOCKED", 0L) != 0
                    || services.counts().getOrDefault("TRACE_TRUNCATED", 0L) != 0) throw new IllegalStateException("native gate sentinel incomplete/failed");
            if (FabricLoader.getInstance().isModLoaded("c2me-rewrites-chunk-system")
                    && services.counts().getOrDefault("WRITE_LOCK_ACQUIRED", 0L) == 0) throw new IllegalStateException("C2ME regional lock witness not hit");
            var sorted = latency.stream().sorted().toList();
            var report = new TreeMap<String, Object>();
            report.put("status", "PASS_OBSERVED_REAL_WALL"); report.put("productionWiring", "OFF");
            report.put("purpose", "programmatic-traversal-not-human-walking-not-full-S1C-acceptance");
            report.put("seed", server.overworld().getSeed()); report.put("recipeHash", recipe.recipeHash());
            report.put("runtime", services.productionDiagnostics().get("runtime").toString()); report.put("reopen", reopen);
            report.put("arcDomain", "S=[0,1248], representative planned land at least1200, intentional realized breaks are not omitted owners");
            report.put("minY", server.overworld().getMinBuildHeight()); report.put("maxYExclusive", server.overworld().getMaxBuildHeight());
            report.put("ownerCount", positions.size()); report.put("owners", List.copyOf(owners)); report.put("voxels", voxels); report.put("walkwayFeet", feet);
            report.put("nonOwnerCount", nonOwners.size()); report.put("nonOwners", List.copyOf(nonOwners));
            report.put("fullRequestMedianMs", sorted.get(sorted.size() / 2) / 1_000_000.0);
            report.put("fullRequestP95Ms", sorted.get((int) Math.ceil(sorted.size() * .95) - 1) / 1_000_000.0);
            report.put("fullRequestMaxMs", sorted.getLast() / 1_000_000.0);
            report.put("session", services.productionDiagnostics());
            report.put("NOT_RUN", List.of("human walking", "height6000", "nondefaultMinY", "Golden", "S5/24h"));
            report.put("BLOCKED", List.of("Tan's Huge Trees", "TYG write-set certification"));
            Files.writeString(reportPath(reopen ? "reopened" : "generated"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
            finished = true; progress.complete(null);
            GreatWallMod.LOGGER.info("[GreatWall:S1C] REAL_WALL_TRAVERSAL_PASS owners={} voxels={} feet={} reopen={} production=OFF",
                    positions.size(), voxels, feet, reopen);
        } catch (Throwable error) { failed(server, error); }
    }
    private void failed(MinecraftServer server, Throwable error) {
        if (failure != null || closed.containsKey("closed")) return;
        failure = error; progress.completeExceptionally(error); writeProgress(server);
        WorldServices.find(server).failRequiredPlan(error);
    }
    private void writeProgress(MinecraftServer server) {
        try {
            var report = Map.of("completedOwners", next, "totalOwners", positions.size(), "voxels", voxels,
                    "walkwayFeet", feet, "nonOwnerCount", nonOwners.size(), "reopen", reopen,
                    "failure", failure == null ? "" : failure.toString(),
                    "elapsedSeconds", (System.nanoTime() - started) / 1_000_000_000.0,
                    "session", WorldServices.find(server).productionDiagnostics());
            Files.writeString(reportPath(reopen ? "reopen-progress" : "progress"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch (IOException error) { throw new IllegalStateException("cannot preserve real-world traversal diagnostics", error); }
    }
    private static Path reportPath(String stage) { return FabricLoader.getInstance().getGameDir().resolve("real-wall-" + stage + ".json"); }
    @Override public void close() {
        if (closed.putIfAbsent("closed", true) != null) return;
        if (planning != null) planning.close();
        if (progress != null && !progress.isDone()) progress.completeExceptionally(new CancellationException("fixture stopping"));
        io.shutdown();
    }
}
