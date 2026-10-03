package local.plaincraft.greatwall.compat.road;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.Frame;
import local.plaincraft.greatwall.model.WallTypes.GateId;
import local.plaincraft.greatwall.model.WallTypes.Point2q;
import local.plaincraft.greatwall.persistence.DurableLog;
import local.plaincraft.greatwall.persistence.GateLedger;
import local.plaincraft.greatwall.persistence.HistoryTypes;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes;
import org.junit.jupiter.api.Test;

/** Pure owner/DTO tests, not certification of RoadWeaver storage or physical backfill. */
class RoadAdapterStateTest {
    private static final UUID SESSION = new UUID(1, 2);
    private static final String WALL = "1234567890abcdef" + "a".repeat(48);
    private static final String HASH = "0".repeat(64);

    @Test void fullGateEncodingDoesNotCollideOnSharedSixteenCharacterPrefix() {
        GateId a = new GateId(WALL, "planned", Long.MAX_VALUE);
        GateId b = new GateId("1234567890abcdef" + "b".repeat(48), "planned", Long.MAX_VALUE);
        assertNotEquals(RoadAdapterState.encode(a), RoadAdapterState.encode(b));
        assertEquals(a, RoadAdapterState.decode(RoadAdapterState.encode(a)));
        assertEquals(b, RoadAdapterState.decode(RoadAdapterState.encode(b)));
        for (String namespace : List.of("planned", "fort", "negotiated")) {
            GateId id = new GateId("wall/id:with separators", namespace, 0);
            assertEquals(id, RoadAdapterState.decode(RoadAdapterState.encode(id)));
        }
    }

    @Test void noncanonicalAndTruncatedGateIdsAreRejected() {
        String canonical = RoadAdapterState.encode(new GateId(WALL, "planned", 1));
        assertThrows(IllegalArgumentException.class, () -> RoadAdapterState.decode(
                "plaincraft_great_wall:" + WALL.substring(0, 16) + "/planned/1"));
        assertThrows(IllegalArgumentException.class, () -> RoadAdapterState.decode(canonical + "/extra"));
        assertThrows(IllegalArgumentException.class, () -> RoadAdapterState.decode(
                canonical.substring(0, canonical.length() - 1) + "01"));
        assertThrows(IllegalArgumentException.class, () -> RoadAdapterState.decode(
                "plaincraft_great_wall:v1/c0af/planned/0"));
        assertThrows(IllegalArgumentException.class, () -> RoadAdapterState.encode(
                new GateId("\uD800", "planned", 0)));
    }

    @Test void readinessCompletionQueuesEffectsOnTheOwner() {
        Fixture f = new Fixture();
        var completion = f.state.registerEndpoint(gate(1, -1, 70));
        f.owner.drain();
        assertEquals(0, f.port.registrations);
        assertFalse(completion.isDone());
        f.port.ready.complete(null);
        assertEquals(0, f.port.registrations);
        f.owner.drain();
        assertEquals(1, f.port.registrations);
        assertTrue(completion.isDone());
        assertFalse(completion.isCompletedExceptionally());
        assertTrue(f.state.pendingSideEffects().toCompletableFuture().isDone());
    }

    @Test void closeBeforeReadyRejectsWorkWithoutCancellingSharedReadiness() {
        Fixture f = new Fixture();
        var completion = f.state.registerEndpoint(gate(1, 0, 70));
        assertTrue(f.state.stop());
        assertFalse(f.state.stop());
        assertTrue(completion.isCompletedExceptionally());
        assertFalse(f.port.ready.isCancelled());
        f.port.ready.complete(null);
        f.owner.drain();
        assertEquals(0, f.port.registrations);
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> f.state.registerEndpoint(gate(2, 10, 70)));
    }

    @Test void closeAfterReadyButBeforeOwnerDispatchAlsoPreventsEffects() {
        Fixture f = new Fixture();
        var completion = f.state.registerEndpoint(gate(1, 0, 70));
        f.port.ready.complete(null);
        assertTrue(f.state.stop());
        f.owner.drain();
        assertEquals(0, f.port.registrations);
        assertTrue(completion.isCompletedExceptionally());
    }

    @Test void changedBindingIsRecheckedOnOwnerAndReported() {
        Fixture f = new Fixture();
        var completion = f.state.registerEndpoint(gate(1, 0, 70));
        f.port.ready.complete(null);
        f.port.bound = false;
        f.owner.drain();
        assertEquals(0, f.port.registrations);
        assertTrue(completion.isCompletedExceptionally());
        assertTrue(f.state.pendingSideEffects().toCompletableFuture().isCompletedExceptionally());
        assertEquals(1, f.port.failures.size());
    }

    @Test void failedReadinessNeverRegistersAnEndpoint() {
        Fixture f = new Fixture();
        var completion = f.state.registerEndpoint(gate(1, 0, 70));
        f.port.ready.completeExceptionally(new IllegalStateException("level closed"));
        f.owner.drain();
        assertTrue(completion.isCompletedExceptionally());
        assertEquals(0, f.port.registrations);
        assertEquals(1, f.port.failures.size());
    }

    @Test void unnegotiatedPlannedGateRegistryBackpressuresInsteadOfGrowingForever() {
        Fixture f = new Fixture();
        f.port.ready.complete(null);
        for (int i = 1; i <= RoadAdapterState.MAX_PLANNED_GATES; i++) {
            f.state.registerEndpoint(gate(i, 0, 70));
            f.owner.drain();
        }
        assertThrows(IllegalStateException.class,
                () -> f.state.registerEndpoint(gate(RoadAdapterState.MAX_PLANNED_GATES + 1, 0, 70)));
        assertEquals(RoadAdapterState.MAX_PLANNED_GATES, f.port.registrations);
        assertTrue(f.state.stop());
    }

    @Test void reversedConnectionsReachThePortInTheSameCanonicalOrder() {
        Fixture f = new Fixture();
        var a = gate(1, 20, 70);
        var b = gate(2, -20, 70);
        f.state.ensureConnection(a, b);
        f.state.ensureConnection(b, a);
        f.port.ready.complete(null);
        f.owner.drain();
        assertEquals(List.of("2/1", "2/1"), f.port.connections);
        assertEquals(4, f.port.registrations,
                "storage idempotence belongs to the Road port, not an adapter memory set");
        assertEquals(2, f.port.storedEndpoints.size());
    }

    @Test void proposalCopiesInputAndDoesNotWaitForRoadReadinessOrPublishAPlaceholder() {
        Fixture f = new Fixture();
        var planned = gate(1, 0, 70);
        f.state.registerEndpoint(planned);
        var approaches = new ArrayList<>(List.of(approach("road-b", 71), approach("road-a", 70)));
        var batch = new ArrayList<>(List.of("road-b", "road-a", "road-b"));
        var result = f.state.propose(SESSION, RoadAdapterState.encode(planned.id()), 0, approaches, batch);
        approaches.clear();
        batch.clear();
        f.owner.drain();
        var record = f.ledger.find(planned.id()).orElseThrow();
        assertEquals(70, record.contract().groundY());
        assertEquals(RoadTypes.GateState.PROPOSED, record.state());
        assertEquals(List.of("road-a", "road-b"), record.batchRoadIds());
        assertEquals(List.of("road-a", "road-b"), record.proposals().stream()
                .map(RoadTypes.Approach::roadId).toList());
        assertThrows(UnsupportedOperationException.class, () -> record.proposals().clear());
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());
        assertTrue(failure(result).getMessage().contains("durable sharedProfile unavailable"));
        assertEquals(List.of("GATE_PROPOSED"), f.log.entries.stream().map(DurableLog.Entry::type).toList());
        assertFalse(f.port.ready.isDone(), "wall negotiation must not introduce a startup readiness cycle");
        assertEquals(0, f.port.registrations);
    }

    @Test void receiptMustPrecedeLedgerPublicationAndClosingDoesNotCancelTheDurableAppend() {
        Fixture f = new Fixture();
        f.log.defer = true;
        var planned = gate(1, 0, 70);
        f.state.registerEndpoint(planned);
        var result = f.state.propose(SESSION, RoadAdapterState.encode(planned.id()), 0,
                List.of(approach("road", 70)), List.of("road"));
        f.owner.drain();
        assertFalse(result.toCompletableFuture().isDone());
        assertTrue(f.ledger.find(planned.id()).isEmpty());
        f.state.stop();
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());
        assertFalse(f.log.receipts.getFirst().isCancelled());
        f.log.receipts.removeFirst().complete(new DurableLog.Receipt(1, f.log.entries.getFirst().eventKey(), HASH));
        f.owner.drain();
        assertEquals(RoadTypes.GateState.PROPOSED, f.ledger.find(planned.id()).orElseThrow().state());
        assertEquals(1, f.log.entries.size());
    }

    @Test void journalFailureNeverPublishesOrFreezesGate() {
        Fixture f = new Fixture();
        f.log.failure = new IOException("disk unavailable");
        var planned = gate(1, 0, 70);
        f.state.registerEndpoint(planned);
        var result = f.state.propose(SESSION, RoadAdapterState.encode(planned.id()), 0,
                List.of(approach("road", 70)), List.of("road"));
        f.owner.drain();
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());
        assertInstanceOf(IOException.class, failure(result));
        assertTrue(f.ledger.find(planned.id()).isEmpty());
        assertEquals(1, f.log.entries.size());
    }

    @Test void invalidSessionUnknownGateAndForeignWallNeverAppend() {
        Fixture f = new Fixture();
        var planned = gate(1, 0, 70);
        String id = RoadAdapterState.encode(planned.id());
        assertTrue(f.state.propose(new UUID(2, 3), id, 0,
                List.of(approach("road", 70)), List.of("road")).toCompletableFuture().isCompletedExceptionally());
        var unknown = f.state.propose(SESSION, id, 0,
                List.of(approach("road", 70)), List.of("road"));
        f.owner.drain();
        assertTrue(unknown.toCompletableFuture().isCompletedExceptionally());
        String foreign = RoadAdapterState.encode(new GateId("another-wall", "planned", 1));
        assertTrue(f.state.propose(SESSION, foreign, 0,
                List.of(approach("road", 70)), List.of("road")).toCompletableFuture().isCompletedExceptionally());
        assertThrows(IllegalStateException.class, () -> f.state.frozen(new UUID(2, 3), id));
        assertTrue(f.log.entries.isEmpty());
    }

    @Test void closedBatchCannotBeReplacedAndIncompleteBatchIsNotFrozen() {
        Fixture f = new Fixture();
        var planned = gate(1, 0, 70);
        f.state.registerEndpoint(planned);
        f.state.propose(SESSION, RoadAdapterState.encode(planned.id()), 0,
                List.of(approach("road-a", 70)), List.of("road-a", "road-b"));
        f.owner.drain();
        var changed = f.state.propose(SESSION, RoadAdapterState.encode(planned.id()), 1,
                List.of(approach("road-a", 70)), List.of("road-a"));
        f.owner.drain();
        assertTrue(changed.toCompletableFuture().isCompletedExceptionally());
        assertEquals(1, f.log.entries.size());
        assertEquals(RoadTypes.GateState.PROPOSED, f.ledger.find(planned.id()).orElseThrow().state());
    }

    @Test void appliedStateWithoutDurableProfileOrPhysicalProofIsNotAnAgreement() {
        Fixture f = new Fixture();
        var planned = gate(1, 0, 70);
        f.ledger.propose(planned, List.of(new RoadTypes.Approach("road",
                List.of(new local.plaincraft.greatwall.model.WallTypes.Point3i(0, 70, 0)))),
                List.of("road"), 0);
        f.owner.drain();
        f.ledger.freeze(planned.id(), 1);
        f.owner.drain();
        f.ledger.ack(planned.id(), planned.affected().getFirst(), 2, 0, HASH, List.of());
        f.owner.drain();
        var record = f.ledger.find(planned.id()).orElseThrow();
        assertEquals(RoadTypes.GateState.APPLIED, record.state());
        assertThrows(IllegalStateException.class, () -> f.state.frozen(SESSION, RoadAdapterState.encode(planned.id())));
        assertThrows(IllegalStateException.class, () -> f.state.adoptFrozen(record, 70, List.of(70)));
        assertThrows(IllegalArgumentException.class, () -> f.state.adoptFrozen(record, 0, List.of(0)));
    }

    private static Throwable failure(CompletionStage<?> stage) {
        var observed = new AtomicReference<Throwable>();
        stage.whenComplete((value, error) -> observed.set(error));
        Throwable failure = observed.get();
        assertNotNull(failure);
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private static RoadTypes.GateContract gate(long ordinal, int x, int ground) {
        return new RoadTypes.GateContract(new GateId(WALL, "planned", ordinal), ordinal * 256,
                new Frame(new Point2q((long) x * 256, 0), 1 << 30, 0, 80, false), ground, 4, 5,
                List.of(new ChunkKey("minecraft:overworld", Math.floorDiv(x, 16), 0)), HASH);
    }

    private static WallInteropTypes.Approach approach(String road, int y) {
        return new WallInteropTypes.Approach(road, List.of(new WallInteropTypes.Pos(0, y, 0)));
    }

    private static final class ManualOwner implements Executor {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        boolean executing;
        @Override public void execute(Runnable action) { tasks.addLast(action); }
        void drain() {
            while (!tasks.isEmpty()) {
                executing = true;
                try { tasks.removeFirst().run(); }
                finally { executing = false; }
            }
        }
    }

    private static final class FakePort implements RoadAdapterState.Port {
        final ManualOwner owner;
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final Set<GateId> storedEndpoints = new HashSet<>();
        final List<String> connections = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        boolean bound = true;
        int registrations;
        FakePort(ManualOwner owner) { this.owner = owner; }
        @Override public void validateSession() {
            if (!bound) throw new IllegalStateException("binding changed");
        }
        @Override public CompletionStage<Void> whenReady() { return ready.copy(); }
        @Override public void registerEndpoint(RoadTypes.GateContract gate) {
            assertTrue(owner.executing);
            registrations++;
            storedEndpoints.add(gate.id());
        }
        @Override public void ensureConnection(RoadTypes.GateContract from, RoadTypes.GateContract to) {
            assertTrue(owner.executing);
            connections.add(from.id().ordinal() + "/" + to.id().ordinal());
        }
        @Override public void failed(Throwable failure) { failures.add(failure); }
    }

    private static final class Fixture {
        final ManualOwner owner = new ManualOwner();
        final FakeLog log = new FakeLog();
        final GateLedger ledger = new GateLedger(log, owner);
        final FakePort port = new FakePort(owner);
        final RoadAdapterState state = new RoadAdapterState(SESSION, WALL, ledger, owner, port);
    }

    private static final class FakeLog implements DurableLog {
        final List<Entry> entries = new ArrayList<>();
        final Deque<CompletableFuture<Receipt>> receipts = new ArrayDeque<>();
        boolean defer;
        Throwable failure;
        @Override public CompletionStage<Receipt> append(Entry entry) {
            entries.add(entry);
            if (failure != null) return CompletableFuture.failedFuture(failure);
            if (defer) {
                var receipt = new CompletableFuture<Receipt>();
                receipts.addLast(receipt);
                return receipt;
            }
            return CompletableFuture.completedFuture(new Receipt(entries.size(), entry.eventKey(), HASH));
        }
        @Override public CompletionStage<Optional<Record>> lookup(String type, String key) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        @Override public CompletionStage<HistoryTypes.BlobRef> storeBlob(String kind, byte[] bytes) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
        @Override public CompletionStage<byte[]> loadBlob(HistoryTypes.BlobRef ref) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
        @Override public CompletionStage<Void> flush() { return CompletableFuture.completedFuture(null); }
        @Override public List<Record> replay(String type, long after, int limit) { return List.of(); }
        @Override public void close() {}
    }
}
