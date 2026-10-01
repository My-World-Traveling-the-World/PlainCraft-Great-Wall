package local.plaincraft.greatwall.persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import local.plaincraft.greatwall.compat.road.RoadTypes;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.GateId;
import local.plaincraft.greatwall.model.WallTypes.Point3i;

/**
 * Permanent gate history. Every state change appends to the durable log
 * before publishing; the in-memory map only ever holds immutable records.
 * All mutations are serialized on the server owner.
 */
public final class GateLedger {
    private static final int MAX_ACTIVE = 4096;

    private final ConcurrentHashMap<GateId, RoadTypes.GateRecord> records = new ConcurrentHashMap<>();
    private final DurableLog journal;
    private final Executor serverOwner;
    /** Per-gate serial chain so two asynchronous appends never derive from one stale revision. */
    private final ConcurrentHashMap<GateId, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();

    public GateLedger(DurableLog journal, Executor serverOwner) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.serverOwner = Objects.requireNonNull(serverOwner, "serverOwner");
    }

    public CompletionStage<RoadTypes.GateRecord> propose(RoadTypes.GateContract planned,
            List<RoadTypes.Approach> approaches, List<String> completeBatchRoadIds,
            long expectedRevision) {
        Objects.requireNonNull(planned, "planned");
        Objects.requireNonNull(approaches, "approaches");
        Objects.requireNonNull(completeBatchRoadIds, "completeBatchRoadIds");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision >= 0 required");
        // Recovery must have loaded this gate's journal history before mutation.
        return chain(planned.id(), () -> {
            RoadTypes.GateRecord current = records.get(planned.id());
            long revision = current == null ? 0L : current.revision();
            if (revision != expectedRevision) {
                throw new IllegalStateException("gate revision mismatch for " + planned.id()
                        + ": expected " + expectedRevision + " but found " + revision);
            }
            if (current != null && (current.state() == RoadTypes.GateState.FROZEN
                    || current.state() == RoadTypes.GateState.PENDING_CHUNKS
                    || current.state() == RoadTypes.GateState.APPLIED)) {
                // Later roads adapt to the frozen contract; the gate itself never changes height.
                return CompletableFuture.completedFuture(current);
            }
            List<RoadTypes.Approach> sorted = new ArrayList<>(approaches);
            sorted.sort(Comparator.comparing(RoadTypes.Approach::roadId));
            List<RoadTypes.GateRecord> prior = current == null ? List.of() : List.of(current);
            List<RoadTypes.Approach> merged = mergeApproaches(prior, sorted);
            List<String> batches = new ArrayList<>(completeBatchRoadIds);
            batches.sort(String::compareTo);
            long journalSeq = current == null ? 0L : current.journalSeq() + 1L;
            RoadTypes.GateRecord next = new RoadTypes.GateRecord(planned, revision + 1L,
                    RoadTypes.GateState.PROPOSED, merged, batches,
                    current == null ? Map.of() : current.progress(), journalSeq);
            String eventKey = "gate/proposed/" + planned.id().wallId() + "/"
                    + planned.id().namespace() + "/" + planned.id().ordinal() + "/" + next.revision();
            return append(eventKey, HistoryTypes.Kind.GATE_PROPOSED, next);
        });
    }

    public CompletionStage<RoadTypes.GateRecord> freeze(GateId id, long expectedRevision) {
        Objects.requireNonNull(id, "id");
        return chain(id, () -> {
            RoadTypes.GateRecord current = require(id);
            if (current.revision() != expectedRevision) {
                throw new IllegalStateException("gate revision mismatch for " + id);
            }
            if (current.state() != RoadTypes.GateState.PROPOSED) return CompletableFuture.completedFuture(current);
            if (!batchComplete(current)) {
                RoadTypes.GateRecord conflicted = new RoadTypes.GateRecord(current.contract(),
                        current.revision() + 1L, RoadTypes.GateState.CONFLICT, current.proposals(),
                        current.batchRoadIds(), current.progress(), current.journalSeq() + 1L);
                return append("gate/conflict/" + id.ordinal() + "/" + conflicted.revision(),
                        HistoryTypes.Kind.GATE_CONFLICT, conflicted);
            }
            RoadTypes.GateRecord frozen = new RoadTypes.GateRecord(current.contract(),
                    current.revision() + 1L, RoadTypes.GateState.FROZEN, current.proposals(),
                    current.batchRoadIds(), current.progress(), current.journalSeq() + 1L);
            return append("gate/frozen/" + id.ordinal() + "/" + frozen.revision(),
                    HistoryTypes.Kind.GATE_FROZEN, frozen);
        });
    }

    public CompletionStage<RoadTypes.GateRecord> ack(GateId id, ChunkKey chunk, long revision,
            int cursor, String inputHash, List<Point3i> conflicts) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(inputHash, "inputHash");
        Objects.requireNonNull(conflicts, "conflicts");
        return chain(id, () -> {
            RoadTypes.GateRecord current = require(id);
            if (current.revision() != revision) {
                throw new IllegalStateException("gate ack revision mismatch for " + id);
            }
            Map<ChunkKey, RoadTypes.ChunkProgress> progress = new LinkedHashMap<>(current.progress());
            RoadTypes.ChunkProgress previous = progress.get(chunk);
            if (previous != null && cursor < previous.cursor()) {
                throw new IllegalStateException("gate ack cursor must not regress for " + id);
            }
            RoadTypes.ChunkGateState state = conflicts.isEmpty()
                    ? RoadTypes.ChunkGateState.ACKED : RoadTypes.ChunkGateState.CONFLICT;
            progress.put(chunk, new RoadTypes.ChunkProgress(chunk, cursor, revision, inputHash,
                    state, conflicts));
            boolean allAcked = current.contract().affected().stream()
                    .allMatch(owner -> progress.get(owner) != null
                            && progress.get(owner).state() == RoadTypes.ChunkGateState.ACKED);
            RoadTypes.GateState gateState = conflicts.isEmpty()
                    ? (allAcked ? RoadTypes.GateState.APPLIED : RoadTypes.GateState.PENDING_CHUNKS)
                    : RoadTypes.GateState.CONFLICT;
            RoadTypes.GateRecord next = new RoadTypes.GateRecord(current.contract(),
                    current.revision() + 1L, gateState, current.proposals(), current.batchRoadIds(),
                    progress, current.journalSeq() + 1L);
            String eventKey = "gate/" + (conflicts.isEmpty() ? "ack" : "conflict") + "/"
                    + id.ordinal() + "/" + chunk.x() + "/" + chunk.z() + "/" + next.revision();
            return append(eventKey, conflicts.isEmpty()
                    ? HistoryTypes.Kind.GATE_ACK : HistoryTypes.Kind.GATE_CONFLICT, next);
        });
    }

    public Optional<RoadTypes.GateRecord> find(GateId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(records.get(id));
    }

    public Map<GateId, RoadTypes.GateRecord> activeSnapshot() {
        return Map.copyOf(records);
    }

    /** Test/inspection only: loads a record into memory without publishing a new journal event. */
    void adopt(RoadTypes.GateRecord record) {
        Objects.requireNonNull(record, "record");
        records.put(record.contract().id(), record);
    }

    private CompletionStage<RoadTypes.GateRecord> append(String eventKey, HistoryTypes.Kind kind,
            RoadTypes.GateRecord next) {
        if (records.size() >= MAX_ACTIVE && !records.containsKey(next.contract().id())) {
            throw new IllegalStateException("gate ledger active set is full: " + MAX_ACTIVE);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("gate", next.contract().id().wallId() + "/" + next.contract().id().namespace()
                + "/" + next.contract().id().ordinal());
        fields.put("revision", Long.toString(next.revision()));
        fields.put("state", next.state().name());
        fields.put("contractHash", next.contract().hash());
        fields.put("payloadHash", payloadHash(next));
        DurableLog.Entry entry = new DurableLog.Entry(eventKey, kind.name(), 1, fields);
        return journal.append(entry).thenApplyAsync(receipt -> {
            records.put(next.contract().id(), next);
            return next;
        }, serverOwner);
    }

    private CompletionStage<RoadTypes.GateRecord> chain(GateId id,
            java.util.function.Supplier<CompletionStage<RoadTypes.GateRecord>> action) {
        CompletableFuture<Void> previous = chains.getOrDefault(id, CompletableFuture.completedFuture(null));
        CompletableFuture<RoadTypes.GateRecord> result = previous.thenComposeAsync(ignored -> {
            try {
                return action.get().toCompletableFuture();
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }, serverOwner);
        chains.put(id, result.handle((value, failure) -> null));
        return result;
    }

    private RoadTypes.GateRecord require(GateId id) {
        RoadTypes.GateRecord current = records.get(id);
        if (current == null) throw new IllegalStateException("unknown gate: " + id);
        return current;
    }

    private static boolean batchComplete(RoadTypes.GateRecord record) {
        if (record.batchRoadIds().isEmpty()) return false;
        java.util.Set<String> proposed = new java.util.HashSet<>();
        for (RoadTypes.Approach approach : record.proposals()) proposed.add(approach.roadId());
        return proposed.containsAll(record.batchRoadIds());
    }

    private static List<RoadTypes.Approach> mergeApproaches(List<RoadTypes.GateRecord> prior,
            List<RoadTypes.Approach> incoming) {
        Map<String, RoadTypes.Approach> merged = new LinkedHashMap<>();
        for (RoadTypes.GateRecord record : prior) {
            for (RoadTypes.Approach approach : record.proposals()) merged.put(approach.roadId(), approach);
        }
        for (RoadTypes.Approach approach : incoming) {
            RoadTypes.Approach existing = merged.get(approach.roadId());
            if (existing != null && !existing.points().equals(approach.points())) {
                throw new IllegalStateException("same road id with different approach profile: " + approach.roadId());
            }
            merged.putIfAbsent(approach.roadId(), approach);
        }
        return List.copyOf(merged.values());
    }

    private static String payloadHash(RoadTypes.GateRecord record) {
        StringBuilder text = new StringBuilder();
        text.append(record.contract().hash()).append('|').append(record.revision()).append('|')
                .append(record.state().name());
        for (RoadTypes.Approach approach : record.proposals()) {
            text.append('|').append(approach.roadId()).append(':').append(approach.points().size());
        }
        return DeterministicHash.sha256(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
