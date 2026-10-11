package local.plaincraft.greatwall.generation;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.mixin.ChunkMapAccess;
import local.plaincraft.greatwall.mixin.TerrainChunkAccess;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Exact FEATURES futures with retained level tickets, scheduled on the native chunk event loop. */
public final class FeaturesLease implements AutoCloseable {
    private static final TicketType<String> TYPE = TicketType.create("greatwall_s0_features", Comparator.<String>naturalOrder());
    private final ServerLevel level;
    private final List<ChunkPos> positions;
    private final String id = UUID.randomUUID().toString();
    private final ConcurrentHashMap<String, Boolean> state = new ConcurrentHashMap<>();
    private final CompletableFuture<Map<ChunkPos, ChunkAccess>> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();

    public FeaturesLease(ServerLevel level, List<ChunkPos> positions, StageTrace trace) {
        if (positions.isEmpty() || positions.size() > 81) throw new IllegalArgumentException("Unbounded FEATURES lease");
        this.level = level;
        this.positions = positions.stream().distinct().toList();
        trace.assertOutsideLock();
        // Global server queue so the road side's managedBlock (which skips shouldRun) drains it;
        // the world-local chunk executor would starve. See StructureLease.
        level.getServer().execute(() -> {
            if (state.containsKey("closed")) { ready.cancel(false); drained.complete(null); return; }
            var chunks = (TerrainChunkAccess) level.getChunkSource();
            int ticketLevel = ChunkLevel.byStatus(ChunkStatus.FEATURES);
            try {
                for (ChunkPos pos : this.positions) chunks.greatwall$distanceManager().addTicket(TYPE, pos, ticketLevel, id);
                state.put("installed", true);
                var futures = this.positions.stream().map(pos -> {
                    trace.record("PREPARE_FEATURES", level.dimension().location().toString(), pos.x, pos.z,
                            "features", "none:ticket=" + ticketLevel);
                    return chunks.greatwall$request(pos.x, pos.z, ChunkStatus.FEATURES, true).thenApply(result ->
                            Map.entry(pos, result.orElseThrow(() -> new IllegalStateException("FEATURES unavailable: " + result.getError()))));
                }).toList();
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
                    // A lease closed while its chunks were loading must not build the result map,
                    // or it would retain all 81 ChunkAccess references long after the caller
                    // stopped caring. Cancelling is enough: the tickets are removed by close().
                    if (state.containsKey("closed")) {
                        ready.cancel(false);
                        drained.complete(null);
                        return;
                    }
                    if (error != null) ready.completeExceptionally(error);
                    else {
                        var result = new java.util.HashMap<ChunkPos, ChunkAccess>();
                        for (var future : futures) {
                            var entry = future.resultNow();
                            result.put(entry.getKey(), entry.getValue());
                        }
                        ready.complete(Map.copyOf(result));
                    }
                });
            } catch (Throwable error) { ready.completeExceptionally(error); close(); }
        });
    }
    public CompletableFuture<Map<ChunkPos, ChunkAccess>> ready() { return ready.copy(); }
    /** Completes once the lease is fully torn down, tickets included, for callers that must wait. */
    public CompletableFuture<Void> drained() { return drained.copy(); }
    @Override public void close() {
        if (state.putIfAbsent("closed", true) != null) return;
        ready.cancel(false);
        level.getServer().execute(() -> {
            if (state.remove("installed") != null) {
                var chunks = (TerrainChunkAccess) level.getChunkSource();
                for (ChunkPos pos : positions) chunks.greatwall$distanceManager()
                        .removeTicket(TYPE, pos, ChunkLevel.byStatus(ChunkStatus.FEATURES), id);
            }
            drained.complete(null);
        });
    }
}
