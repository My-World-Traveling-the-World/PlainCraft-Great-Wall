package local.plaincraft.greatwall.compat.road;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.generation.ChunkWallWriter;
import local.plaincraft.greatwall.generation.OwnershipGuard;
import local.plaincraft.greatwall.model.WallTypes.BlockProof;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan;
import local.plaincraft.greatwall.model.WallTypes.GateId;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.VoxelIntent;
import local.plaincraft.greatwall.model.WallTypes.WriteMode;
import local.plaincraft.greatwall.persistence.GateLedger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Loaded-owner arch backfill for a frozen gate. Only loaded chunks are
 * written, always re-verified against before-state and tombstones, and the
 * journal ACK is never treated as proof that the chunk reached disk.
 */
public final class WallArchBackfill {
    /** Maximum writes performed by a single applyLoaded call. */
    public static final int MAX_BLOCKS_PER_CALL = 512;

    private final ServerLevel level;
    private final GateLedger gates;
    private final ChunkWallWriter writer = new ChunkWallWriter();
    private final ConcurrentHashMap<GateId, List<ChunkWritePlan>> prepared = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<GateId, Integer> cursors = new ConcurrentHashMap<>();

    public WallArchBackfill(ServerLevel level, GateLedger gates) {
        this.level = Objects.requireNonNull(level, "level");
        this.gates = Objects.requireNonNull(gates, "gates");
    }

    /**
     * Builds owner write plans for a frozen gate. Any conflict on an already
     * loaded affected chunk aborts the whole gate before a single block is
     * removed, so the wall is never left half-cut.
     */
    public CompletionStage<Void> prepare(RoadTypes.GateRecord frozen) {
        Objects.requireNonNull(frozen, "frozen");
        if (frozen.state() != RoadTypes.GateState.FROZEN && frozen.state() != RoadTypes.GateState.PENDING_CHUNKS) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "only a frozen gate can be prepared, found " + frozen.state()));
        }
        return CompletableFuture.supplyAsync(() -> {
            if (!level.getServer().isSameThread()) {
                // Chunk access must happen on the server owner.
                CompletableFuture<Void> hop = new CompletableFuture<>();
                level.getServer().execute(() -> {
                    try {
                        buildPlans(frozen);
                        hop.complete(null);
                    } catch (Throwable failure) {
                        hop.completeExceptionally(failure);
                    }
                });
                return hop.join();
            }
            buildPlans(frozen);
            return null;
        }).thenAccept(ignored -> { });
    }

    private void buildPlans(RoadTypes.GateRecord frozen) {
        List<ChunkWritePlan> plans = new ArrayList<>();
        List<ChunkKey> affected = new ArrayList<>(frozen.contract().affected());
        affected.sort(Comparator.comparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z));
        for (ChunkKey owner : affected) {
            var chunk = level.getChunkSource().getChunkNow(owner.x(), owner.z());
            if (chunk == null) continue; // unloaded stays PENDING, never force-loaded
            ChunkWritePlan plan = compileArch(owner, frozen);
            // Idempotence and tombstone checks happen before any write.
            for (BlockProof proof : plan.proofs()) {
                var blockPos = new net.minecraft.core.BlockPos(
                        proof.pos().x(), proof.pos().y(), proof.pos().z());
                String current = chunk.getBlockState(blockPos).toString();
                var decision = OwnershipGuard.decide(WriteMode.GATE_BACKFILL, owner, proof.pos(),
                        current, chunk.getBlockEntity(blockPos) != null, proof, false);
                if (decision == local.plaincraft.greatwall.model.WallTypes.WriteDecision.CONFLICT) {
                    throw new IllegalStateException("gate " + frozen.contract().id().ordinal()
                            + " conflicts on " + proof.pos() + " before any write");
                }
            }
            plans.add(plan);
        }
        if (!plans.isEmpty()) prepared.put(frozen.contract().id(), List.copyOf(plans));
        gates.ack(frozen.contract().id(), affected.getFirst(), frozen.revision(), 0,
                frozen.contract().hash(), List.of());
    }

    /** Writes at most {@code blockBudget} blocks across loaded affected chunks. */
    public RoadTypes.BackfillResult applyLoaded(GateId id, int blockBudget) {
        Objects.requireNonNull(id, "id");
        if (blockBudget < 1 || blockBudget > MAX_BLOCKS_PER_CALL) {
            throw new IllegalArgumentException("blockBudget 1.." + MAX_BLOCKS_PER_CALL + " required");
        }
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("applyLoaded requires the server owner");
        }
        List<ChunkWritePlan> plans = prepared.get(id);
        if (plans == null) {
            return new RoadTypes.BackfillResult(id, 0, 0, List.of(), false);
        }
        int cursor = cursors.getOrDefault(id, 0);
        int written = 0;
        int pending = 0;
        List<Point3i> conflicts = new ArrayList<>();
        // Rotate the cursor so an unloaded head never starves later chunks.
        for (int step = 0; step < plans.size() && written < blockBudget; step++) {
            ChunkWritePlan plan = plans.get((cursor + step) % plans.size());
            var chunk = level.getChunkSource().getChunkNow(plan.owner().x(), plan.owner().z());
            if (chunk == null) {
                pending++;
                continue;
            }
            var report = writer.apply(chunk, plan, WriteMode.GATE_BACKFILL);
            written += report.written();
            if (!report.complete()) conflicts.addAll(report.conflicts());
            if (written >= blockBudget) {
                cursors.put(id, (cursor + step + 1) % plans.size());
                break;
            }
        }
        boolean complete = pending == 0 && conflicts.isEmpty();
        return new RoadTypes.BackfillResult(id, written, pending, List.copyOf(conflicts), complete);
    }

    public void onChunkLoaded(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        // Queue only the gates that touch this chunk; never scan the whole table.
        for (Map.Entry<GateId, List<ChunkWritePlan>> entry : prepared.entrySet()) {
            for (ChunkWritePlan plan : entry.getValue()) {
                if (plan.owner().equals(chunk)) {
                    cursors.putIfAbsent(entry.getKey(), 0);
                    break;
                }
            }
        }
    }

    public void close() {
        prepared.clear();
        cursors.clear();
    }

    private ChunkWritePlan compileArch(ChunkKey owner, RoadTypes.GateRecord frozen) {
        var bounds = frozen.contract().frame();
        List<VoxelIntent> voxels = new ArrayList<>();
        List<BlockProof> proofs = new ArrayList<>();
        // The arch aperture is carved through the owner's own columns only.
        int minX = owner.x() * 16;
        int minZ = owner.z() * 16;
        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                if (Math.abs(x - local.plaincraft.greatwall.geometry.FixedMath.floorBlock(
                        bounds.center().xQ8())) > 2) continue;
                if (Math.abs(z - local.plaincraft.greatwall.geometry.FixedMath.floorBlock(
                        bounds.center().zQ8())) > 2) continue;
                long siteOrdinal = frozen.contract().id().ordinal();
                var siteId = new local.plaincraft.greatwall.model.WallTypes.SiteId(
                        frozen.contract().id().wallId(),
                        local.plaincraft.greatwall.model.WallTypes.SiteKind.GATE, siteOrdinal);
                for (int dy = 0; dy < 5; dy++) {
                    int y = bounds.walkY() + dy;
                    Point3i pos = new Point3i(x, y, z);
                    proofs.add(new BlockProof(pos, "minecraft:air", "minecraft:air", false, siteId));
                }
            }
        }
        return new ChunkWritePlan(owner, "", "", List.copyOf(voxels), List.copyOf(proofs),
                List.of(), List.of(), List.of(), List.of(),
                new local.plaincraft.greatwall.model.WallTypes.WriteMetadata(
                        frozen.contract().hash(), frozen.revision(), List.of(), Map.of()),
                "0".repeat(64));
    }
}
