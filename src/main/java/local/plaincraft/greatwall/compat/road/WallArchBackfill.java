package local.plaincraft.greatwall.compat.road;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import local.plaincraft.greatwall.generation.ChunkWallWriter;
import local.plaincraft.greatwall.generation.OwnershipGuard;
import local.plaincraft.greatwall.model.WallTypes.BlockProof;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan;
import local.plaincraft.greatwall.model.WallTypes.GateId;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.SiteId;
import local.plaincraft.greatwall.model.WallTypes.SiteKind;
import local.plaincraft.greatwall.model.WallTypes.VoxelIntent;
import local.plaincraft.greatwall.model.WallTypes.WriteMetadata;
import local.plaincraft.greatwall.model.WallTypes.WriteMode;
import local.plaincraft.greatwall.persistence.GateLedger;
import net.minecraft.server.level.ServerLevel;

/**
 * Loaded-owner arch backfill for a frozen gate.
 *
 * <p>The write set is never synthesised here: it is taken from the durable
 * build-proof that the wall already persisted when the owner chunk was
 * generated ({@code ChunkEvidenceStore.persistPlan}). Only the voxels owned by
 * this gate's own component are extracted, and every one of them is re-verified
 * against the live before-state and the ownership guard before a single block
 * is written. A durable ACK is only ever published for chunks that were
 * actually loaded and verified: an unloaded owner stays PENDING, and the load
 * callback re-queues it instead of pretending it completed.</p>
 */
public final class WallArchBackfill {
    /** Maximum writes performed by a single applyLoaded call. */
    public static final int MAX_BLOCKS_PER_CALL = 512;

    /** Durable plan source. Called off the server owner; must not touch world state. */
    @FunctionalInterface
    public interface DurablePlans {
        Optional<ChunkWritePlan> read(ChunkKey owner) throws Exception;
    }

    private final ServerLevel level;
    private final GateLedger gates;
    private final Executor io;
    private final DurablePlans durable;
    private final ChunkWallWriter writer = new ChunkWallWriter();
    private final ConcurrentHashMap<GateId, List<ChunkWritePlan>> prepared = new ConcurrentHashMap<>();
    /** Chunks whose owner plan could not be read yet; they stay PENDING, never ACKed. */
    private final ConcurrentHashMap<GateId, List<ChunkKey>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<GateId, Integer> cursors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<GateId, Long> ackedRevision = new ConcurrentHashMap<>();
    private volatile boolean closed;
    /** Why the loaded-owner write path is unavailable; reported once, never thrown per tick. */
    private static volatile String gateBackfillUnsupported;

    public WallArchBackfill(ServerLevel level, GateLedger gates) {
        this(level, gates, Runnable::run, owner -> Optional.empty());
    }

    public WallArchBackfill(ServerLevel level, GateLedger gates, Executor io, DurablePlans durable) {
        this.level = Objects.requireNonNull(level, "level");
        this.gates = Objects.requireNonNull(gates, "gates");
        this.io = Objects.requireNonNull(io, "io");
        this.durable = Objects.requireNonNull(durable, "durable");
    }

    /**
     * Reads the durable owner proofs off the server thread, then verifies every
     * loaded affected chunk before publishing any plan. A conflict aborts the
     * whole gate before a single block is removed, so the wall is never left
     * half-cut; a missing proof leaves that chunk PENDING rather than failing
     * the gate, because the owner may legitimately not be generated yet.
     */
    public CompletionStage<Void> prepare(RoadTypes.GateRecord frozen) {
        Objects.requireNonNull(frozen, "frozen");
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("backfill is closed"));
        if (frozen.state() != RoadTypes.GateState.FROZEN && frozen.state() != RoadTypes.GateState.PENDING_CHUNKS) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "only a frozen gate can be prepared, found " + frozen.state()));
        }
        var contract = frozen.contract();
        var affected = sortedAffected(contract.affected());
        // 1. Durable IO stays off the server owner.
        return CompletableFuture.supplyAsync(() -> {
            var plans = new LinkedHashMap<ChunkKey, ChunkWritePlan>();
            var missing = new ArrayList<ChunkKey>();
            for (ChunkKey owner : affected) {
                try {
                    var plan = durable.read(owner);
                    if (plan.isEmpty()) missing.add(owner);
                    else plans.put(owner, plan.get());
                } catch (Exception failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }
            return new ReadResult(plans, missing);
        }, io).thenComposeAsync(read -> {
            // 2. Owner-side verification happens on the server thread.
            //
            // The continuation must run on the injected executor, not on the default one. An
            // executor-less thenComposeAsync hops to ForkJoinPool.commonPool(), which both breaks the
            // single-owner threading contract this class documents and makes the result of a rejected
            // turn arrive after the caller has already inspected it.
            var result = new CompletableFuture<Void>();
            var server = level.getServer();
            Runnable verifyOnOwner = () -> {
                try {
                    if (closed) throw new IllegalStateException("backfill is closed");
                    buildPlans(frozen, read);
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            };
            try {
                if (server.isSameThread()) verifyOnOwner.run();
                else server.execute(verifyOnOwner);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
            return result;
        }, io);
    }

    private record ReadResult(Map<ChunkKey, ChunkWritePlan> plans, List<ChunkKey> missing) {}

    private void buildPlans(RoadTypes.GateRecord frozen, ReadResult read) {
        var id = frozen.contract().id();
        var siteId = gateSite(id);
        List<ChunkWritePlan> plans = new ArrayList<>();
        List<ChunkKey> stillPending = new ArrayList<>();
        for (ChunkKey owner : sortedAffected(frozen.contract().affected())) {
            var durablePlan = read.plans().get(owner);
            if (durablePlan == null) {
                // No durable proof yet: the owner has not been generated. Stay PENDING.
                stillPending.add(owner);
                continue;
            }
            var arch = extractArch(durablePlan, siteId, frozen.contract().hash(), frozen.revision());
            if (arch == null) {
                // The persisted proof carries no voxel for this gate component; the
                // owner did not include this gate, so there is nothing to backfill.
                stillPending.add(owner);
                continue;
            }
            var chunk = level.getChunkSource().getChunkNow(owner.x(), owner.z());
            if (chunk == null) {
                stillPending.add(owner);
                continue;
            }
            // Full before-state / block-entity / tombstone check before any write.
            for (BlockProof proof : arch.proofs()) {
                var blockPos = new net.minecraft.core.BlockPos(proof.pos().x(), proof.pos().y(), proof.pos().z());
                String current = chunk.getBlockState(blockPos).toString();
                var decision = OwnershipGuard.decide(WriteMode.GATE_BACKFILL, owner, proof.pos(),
                        current, chunk.getBlockEntity(blockPos) != null, proof, false);
                if (decision == local.plaincraft.greatwall.model.WallTypes.WriteDecision.CONFLICT) {
                    throw new IllegalStateException("gate " + id.ordinal()
                            + " conflicts on " + proof.pos() + " before any write");
                }
            }
            plans.add(arch);
        }
        if (plans.isEmpty() && stillPending.isEmpty()) {
            // Every owner was read and none carried this gate: nothing to do.
            prepared.remove(id);
            pending.remove(id);
            return;
        }
        if (plans.isEmpty()) {
            // Nothing writable yet; keep the gate PENDING and publish no plan.
            prepared.remove(id);
            pending.put(id, List.copyOf(stillPending));
            return;
        }
        prepared.put(id, List.copyOf(plans));
        pending.put(id, List.copyOf(stillPending));
        cursors.putIfAbsent(id, 0);
        // A durable ACK is only published for the chunks we actually verified, and
        // only after the plans exist. Unloaded owners are never ACKed.
        ackVerified(id, frozen, plans);
    }

    /**
     * Extracts this gate's own voxels from a durable owner proof. Returns null
     * when the proof contains no voxel owned by the gate component (so the caller
     * keeps the owner PENDING instead of fabricating an empty write set).
     */
    private ChunkWritePlan extractArch(ChunkWritePlan durable, SiteId gate, String coverageHash, long revision) {
        List<VoxelIntent> voxels = new ArrayList<>();
        for (VoxelIntent voxel : durable.voxels()) {
            if (voxel.component().equals(gate)) voxels.add(voxel);
        }
        if (voxels.isEmpty()) return null;
        var wanted = new java.util.HashSet<Point3i>();
        for (VoxelIntent voxel : voxels) wanted.add(voxel.pos());
        List<BlockProof> proofs = new ArrayList<>();
        Map<Point3i, BlockProof> byPos = new HashMap<>();
        for (BlockProof proof : durable.proofs()) byPos.put(proof.pos(), proof);
        for (Point3i pos : wanted) {
            BlockProof proof = byPos.get(pos);
            if (proof == null) {
                throw new IllegalStateException("durable proof is missing a gate voxel at " + pos);
            }
            proofs.add(proof);
        }
        proofs.sort(Comparator.comparingInt((BlockProof p) -> p.pos().y())
                .thenComparingInt(p -> p.pos().z()).thenComparingInt(p -> p.pos().x()));
        voxels.sort(Comparator.comparingInt((VoxelIntent v) -> v.pos().y())
                .thenComparingInt(v -> v.pos().z()).thenComparingInt(v -> v.pos().x()));
        var metadata = new WriteMetadata(coverageHash, revision, List.of(), Map.of());
        var plan = new ChunkWritePlan(durable.owner(), durable.recipeHash(), durable.inputHash(),
                voxels, proofs, List.of(), List.of(), List.of(), List.of(), metadata, "0".repeat(64));
        // Re-use the canonical plan hash so the durable identity matches the store's copy.
        String hash = local.plaincraft.greatwall.geometry.CanonicalCodec.writePlanHash(plan);
        return new ChunkWritePlan(plan.owner(), plan.recipeHash(), plan.inputHash(), plan.voxels(),
                plan.proofs(), plan.blockEntities(), plan.postprocess(), plan.fluidTicks(),
                plan.walkwayFeet(), plan.metadata(), hash);
    }

    private void ackVerified(GateId id, RoadTypes.GateRecord frozen, List<ChunkWritePlan> verified) {
        Long already = ackedRevision.get(id);
        if (already != null && already == frozen.revision()) return;
        // ACK one verified owner at a time; the ledger validates the revision itself.
        ChunkKey first = verified.getFirst().owner();
        gates.ack(id, first, frozen.revision(), 0, frozen.contract().hash(), List.of());
        ackedRevision.put(id, frozen.revision());
    }

    private static SiteId gateSite(GateId id) {
        return new SiteId(id.wallId(), SiteKind.GATE, id.ordinal());
    }

    private static List<ChunkKey> sortedAffected(List<ChunkKey> affected) {
        var sorted = new ArrayList<>(affected);
        sorted.sort(Comparator.comparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z));
        return sorted;
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
            int waiting = pending.getOrDefault(id, List.of()).size();
            return new RoadTypes.BackfillResult(id, 0, waiting, List.of(), false);
        }
        int cursor = cursors.getOrDefault(id, 0);
        int written = 0;
        int pendingChunks = 0;
        List<Point3i> conflicts = new ArrayList<>();
        // Rotate the cursor so an unloaded head never starves later chunks.
        for (int step = 0; step < plans.size() && written < blockBudget; step++) {
            ChunkWritePlan plan = plans.get((cursor + step) % plans.size());
            var chunk = level.getChunkSource().getChunkNow(plan.owner().x(), plan.owner().z());
            if (chunk == null) {
                pendingChunks++;
                continue;
            }
            // ChunkWallWriter only writes proto chunks: it rejects anything not in WriteMode.INITIAL
            // ("loaded backfill requires the S2 loaded-owner writer"). Calling it here could only throw
            // from inside a tick, so the gate stays PENDING and the reason is reported once instead.
            if (true) {
                if (gateBackfillUnsupported == null) {
                    gateBackfillUnsupported = "loaded-owner arch backfill is not implemented: "+
                            "ChunkWallWriter accepts proto-chunk INITIAL writes only";
                    local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                            "[GreatWall:S1C] GATE_BACKFILL_UNSUPPORTED {}; gate stays PENDING",
                            gateBackfillUnsupported);
                }
                pendingChunks++;
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
        pendingChunks += pending.getOrDefault(id, List.of()).size();
        boolean complete = pendingChunks == 0 && conflicts.isEmpty();
        return new RoadTypes.BackfillResult(id, written, pendingChunks, List.copyOf(conflicts), complete);
    }

    public void onChunkLoaded(ChunkKey chunk) {
        Objects.requireNonNull(chunk, "chunk");
        if (closed) return;
        // Queue only the gates that touch this chunk; never scan the whole table.
        for (Map.Entry<GateId, List<ChunkKey>> entry : pending.entrySet()) {
            if (entry.getValue().contains(chunk)) {
                cursors.putIfAbsent(entry.getKey(), 0);
            }
        }
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
        closed = true;
        prepared.clear();
        pending.clear();
        cursors.clear();
        ackedRevision.clear();
    }
}
