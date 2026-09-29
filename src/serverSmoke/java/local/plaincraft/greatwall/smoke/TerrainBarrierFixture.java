package local.plaincraft.greatwall.smoke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.mixin.TerrainChunkAccess;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;

public final class TerrainBarrierFixture {
    private static final TicketType<String> TYPE = TicketType.create("greatwall_s0_full_fixture", Comparator.<String>naturalOrder());
    private record Request(ChunkPos pos, CompletableFuture<ChunkResult<ChunkAccess>> future) {}
    private record Round(int index, List<Request> requests) {}
    private final List<Round> pending = new ArrayList<>();
    private final List<Integer> order = new ArrayList<>();
    private int next;
    private int completed;
    private boolean initialized;
    private boolean crashReady;

    public static void register() {
        var id = ResourceLocation.fromNamespaceAndPath("greatwall_server_smoke", "neighbor_stripe");
        Registry.register(BuiltInRegistries.FEATURE, id, new NeighborStripeFeature());
        if (Boolean.getBoolean("greatwall.s0.terrain")) {
            BiomeModifications.addFeature(BiomeSelectors.all(), GenerationStep.Decoration.TOP_LAYER_MODIFICATION,
                    ResourceKey.create(Registries.PLACED_FEATURE, id));
        }
    }

    public boolean tick(MinecraftServer server, int ticks) {
        if (crashReady) return false;
        if (ticks > 10000) throw new IllegalStateException("S0-03 fixture exceeded tick deadline");
        var level = server.overworld();
        var coordinator = WorldServices.find(server).barrier(level);
        if (coordinator == null) throw new IllegalStateException("Missing terrain barrier");
        var cache = (TerrainChunkAccess) level.getChunkSource();
        int concurrency = Integer.getInteger("greatwall.s0.terrainConcurrency", 1);
        if (!List.of(1, 2, 8, 16).contains(concurrency)) throw new IllegalStateException("Invalid concurrency");
        if (!initialized) {
            initialized = true;
            if (Boolean.getBoolean("greatwall.s0.terrainFixedWorkers")) {
                boolean c2me = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("c2me-rewrites-chunk-system");
                int actual = c2me ? (int) Thread.getAllStackTraces().keySet().stream()
                        .filter(thread -> thread.getName().matches("c2me-worker-\\d+")).count()
                        : ((java.util.concurrent.ForkJoinPool) net.minecraft.Util.backgroundExecutor()).getParallelism();
                if (actual != concurrency) throw new IllegalStateException("Actual worker count differs: " + actual + " != " + concurrency);
                GreatWallMod.LOGGER.info("[GreatWall:S0-03] FIXED_ENGINE_WORKERS actual={} requested={} testOnly=true", actual, concurrency);
            }
            for (int i = 0; i < coordinator.layout().rounds(); i++) order.add(i);
            Collections.shuffle(order, new Random(Integer.getInteger("greatwall.s0.terrainOrder", 1)));
            GreatWallMod.LOGGER.info("[GreatWall:S0-03] FIXTURE_BEGIN rounds={} concurrency={} engineWorkersProperty={}",
                    order.size(), concurrency, System.getProperty("max.bg.threads"));
        }
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            var round = iterator.next();
            if (!round.requests().stream().allMatch(request -> request.future().isDone())) continue;
            if (!round.requests().stream().allMatch(request -> coordinator.results().containsKey(request.pos()))) {
                for (var request : round.requests()) if (request.future().isCompletedExceptionally()) request.future().resultNow();
                continue;
            }
            for (var request : round.requests()) {
                var chunk = request.future().resultNow().orElseThrow(() -> new IllegalStateException("Native FULL request failed"));
                var result = coordinator.results().get(request.pos());
                if (result == null || !result.t0Hash().equals(chunk.getAttached(TerrainMarkers.T0))
                        || !result.realizationHash().equals(chunk.getAttached(TerrainMarkers.APPLIED))) {
                    throw new IllegalStateException("Missing native marker after ProtoChunk promotion: " + request.pos());
                }
                if (!Boolean.getBoolean("greatwall.s0.terrainResume")) {
                    var original = coordinator.revisitAfterApply(request.pos());
                    if (!original.isDone() || !original.resultNow().hash().equals(result.t0Hash())) {
                        throw new IllegalStateException("Late neighbor failed to reuse original immutable T0");
                    }
                }
                coordinator.verifyFinalWindow(chunk);
                cache.greatwall$distanceManager().removeTicket(TYPE, request.pos(), ChunkLevel.byStatus(ChunkStatus.FULL), "fixture");
            }
            completed++;
            iterator.remove();
        }
        while (pending.size() < concurrency && next < order.size()) {
            int index = order.get(next++);
            var owners = new ArrayList<>(coordinator.layout().owners(index));
            Collections.shuffle(owners, new Random(index * 7919L + Integer.getInteger("greatwall.s0.terrainOrder", 1)));
            var requests = new ArrayList<Request>();
            for (var owner : owners) {
                cache.greatwall$distanceManager().addTicket(TYPE, owner, ChunkLevel.byStatus(ChunkStatus.FULL), "fixture");
                requests.add(new Request(owner, cache.greatwall$request(owner.x, owner.z, ChunkStatus.FULL, true)));
            }
            pending.add(new Round(index, List.copyOf(requests)));
        }
        if (completed != order.size()) return false;
        boolean resume = Boolean.getBoolean("greatwall.s0.terrainResume");
        if (coordinator.results().size() != order.size() * 4 || (!resume && coordinator.counts().getOrDefault("T0_CAPTURED", 0L) != order.size() * 4L)
                || (!resume && coordinator.counts().getOrDefault("T0_REUSED", 0L) < order.size() * 4L)
                || coordinator.counts().getOrDefault("WAIT_WHILE_LOCKED", 0L) != 0L) {
            throw new IllegalStateException("Barrier counts disagree with actual completed fixture: " + coordinator.counts());
        }
        coordinator.export();
        if (Boolean.getBoolean("greatwall.s0.terrainCrashAfterSave")) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "save-all flush");
            crashReady = true;
            GreatWallMod.LOGGER.info("[GreatWall:S0-03] CRASH_READY_AFTER_SAVE owners={}", coordinator.results().size());
            return false;
        }
        GreatWallMod.LOGGER.info("[GreatWall:S0-03] FIXTURE_PASS rounds={} owners={} counts={}",
                completed, coordinator.results().size(), coordinator.counts());
        return true;
    }
}
