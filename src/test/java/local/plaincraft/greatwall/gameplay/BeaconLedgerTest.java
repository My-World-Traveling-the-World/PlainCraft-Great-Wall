package local.plaincraft.greatwall.gameplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.model.WallTypes.BeaconId;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.persistence.DurableLog;
import local.plaincraft.greatwall.persistence.HistoryTypes;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link BeaconLedger}, including the invariant its own comment states: "The durable receipt is
 * the only thing that increments the count."
 *
 * <h2>Why this exists</h2>
 * The ledger decides whether a player's beacon ignition counts toward the wall. It had no test at
 * all, and its counting rule is the kind that fails silently: if the increment moved off the receipt,
 * every ignition would appear credited even when nothing was durable, and the wall would look
 * complete on a world where the write never landed.
 *
 * <p>Pure logic: the only collaborator is {@link DurableLog}, stubbed the same way
 * {@code RoadAdapterStateTest} already does.
 */
class BeaconLedgerTest {
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String WALL = "wall-fixture";
    private static final Point3i PIT = new Point3i(10, 64, 20);

    private static GameplayTypes.BeaconProgress empty() {
        return new GameplayTypes.BeaconProgress(0L, 0L, 3L, false, false);
    }

    private static GameplayTypes.VerifiedIgnition lit(long ordinal) {
        return new GameplayTypes.VerifiedIgnition(new BeaconId(WALL, ordinal), PLAYER, 100L, PIT,
                true, false, true, "minecraft:flint_and_steel");
    }

    private static GameplayTypes.VerifiedIgnition ignition(long ordinal, boolean lit, boolean waterlogged,
            boolean accepted) {
        return new GameplayTypes.VerifiedIgnition(new BeaconId(WALL, ordinal), PLAYER, 100L, PIT,
                lit, waterlogged, accepted, "minecraft:flint_and_steel");
    }

    @Test void anAcceptedIgnitionCommitsAndCounts() throws Exception {
        var log = new FakeLog();
        var ledger = new BeaconLedger(SESSION, WALL, log, Runnable::run, empty());

        var result = ledger.credit(lit(1)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(GameplayTypes.CreditStatus.COMMITTED, result.status());
        assertTrue(result.durable(), "a committed credit must be durable");
        assertEquals(1L, result.committedCount());
        assertEquals(1L, ledger.progress().credited());
        assertEquals(1, log.entries.size(), "exactly one durable append is expected");
        assertEquals("beacon/" + WALL + "/1", log.entries.getFirst().eventKey());
    }

    @Test void theCountOnlyMovesOnADurableReceipt() throws Exception {
        var log = new FakeLog();
        log.defer = true;
        var ledger = new BeaconLedger(SESSION, WALL, log, Runnable::run, empty());

        var pending = ledger.credit(lit(1)).toCompletableFuture();
        assertFalse(pending.isDone(), "an unresolved append must leave the credit in flight");
        assertEquals(0L, ledger.progress().credited(),
                "the count must not move before the durable receipt arrives");

        log.receipts.removeFirst().complete(new DurableLog.Receipt(7L, "beacon/" + WALL + "/1", "a".repeat(64)));
        var result = pending.get(10, TimeUnit.SECONDS);

        assertEquals(GameplayTypes.CreditStatus.COMMITTED, result.status());
        assertEquals(7L, result.sequence(), "the receipt's sequence is what the result reports");
        assertEquals(1L, ledger.progress().credited());
    }

    @Test void aFailedAppendDefersWithoutCounting() throws Exception {
        var log = new FakeLog();
        log.failure = new java.io.IOException("disk full");
        var ledger = new BeaconLedger(SESSION, WALL, log, Runnable::run, empty());

        var result = ledger.credit(lit(1)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(GameplayTypes.CreditStatus.DEFERRED_IO, result.status());
        assertFalse(result.durable(), "a deferred credit must not claim durability");
        assertEquals(0L, ledger.progress().credited(),
                "a failed append must never increment the count");
        assertTrue(ledger.pendingError() != null && ledger.pendingError().contains("disk full"),
                "the failure must be recorded rather than swallowed: " + ledger.pendingError());
    }

    @Test void theSameBeaconIsNeverCreditedTwice() throws Exception {
        var log = new FakeLog();
        var ledger = new BeaconLedger(SESSION, WALL, log, Runnable::run, empty());

        assertEquals(GameplayTypes.CreditStatus.COMMITTED,
                ledger.credit(lit(1)).toCompletableFuture().get(10, TimeUnit.SECONDS).status());
        var second = ledger.credit(lit(1)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(GameplayTypes.CreditStatus.ALREADY_COMMITTED, second.status());
        assertEquals(1L, ledger.progress().credited(), "the count must not double");
        assertEquals(1, log.entries.size(), "no second durable append may be written");
    }

    @Test void aConcurrentSecondCreditForTheSameBeaconIsReportedNotDuplicated() throws Exception {
        var log = new FakeLog();
        log.defer = true;
        var ledger = new BeaconLedger(SESSION, WALL, log, Runnable::run, empty());

        var first = ledger.credit(lit(1)).toCompletableFuture();
        var second = ledger.credit(lit(1)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(GameplayTypes.CreditStatus.ALREADY_PENDING, second.status(),
                "a second credit while one is in flight must be reported, not queued as a duplicate write");
        assertEquals(1, log.entries.size());

        log.receipts.removeFirst().complete(new DurableLog.Receipt(1L, "beacon/" + WALL + "/1", "a".repeat(64)));
        assertEquals(GameplayTypes.CreditStatus.COMMITTED, first.get(10, TimeUnit.SECONDS).status());
        assertEquals(1L, ledger.progress().credited());
    }

    @Test void anIgnitionThatWasNotAnAcceptedLitPitIsRefused() throws Exception {
        var log = new FakeLog();
        var ledger = new BeaconLedger(SESSION, WALL, log, Runnable::run, empty());

        assertRefused(log, ledger, ignition(1, false, false, true), "not lit");
        assertRefused(log, ledger, ignition(2, true, true, true), "waterlogged");
        assertRefused(log, ledger, ignition(3, true, false, false), "not accepted");

        assertEquals(0, log.entries.size(), "a refused ignition must write nothing durable");
        assertEquals(0L, ledger.progress().credited());
    }

    @Test void aBeaconFromAnotherWallIsRefused() throws Exception {
        var log = new FakeLog();
        var ledger = new BeaconLedger(SESSION, WALL, log, Runnable::run, empty());
        var foreign = new GameplayTypes.VerifiedIgnition(new BeaconId("other-wall", 1), PLAYER, 100L, PIT,
                true, false, true, "minecraft:flint_and_steel");

        var result = ledger.credit(foreign).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(GameplayTypes.CreditStatus.REFUSED_SESSION, result.status());
        assertEquals(0, log.entries.size(), "another wall's beacon must not be written into this log");
    }

    @Test void closingRefusesFurtherCreditsAndSettlesInFlightOnes() throws Exception {
        var log = new FakeLog();
        log.defer = true;
        var ledger = new BeaconLedger(SESSION, WALL, log, Runnable::run, empty());

        var inFlight = ledger.credit(lit(1)).toCompletableFuture();
        ledger.close();

        assertEquals(GameplayTypes.CreditStatus.REFUSED_SESSION,
                inFlight.get(10, TimeUnit.SECONDS).status(),
                "closing must settle an in-flight credit rather than leave it hanging");
        assertEquals(GameplayTypes.CreditStatus.REFUSED_SESSION,
                ledger.credit(lit(2)).toCompletableFuture().get(10, TimeUnit.SECONDS).status());
        assertEquals(0L, ledger.progress().credited());
    }

    @Test void anUnreadBeaconReportsUnknownRatherThanUncredited() {
        var log = new FakeLog();
        var ledger = new BeaconLedger(SESSION, WALL, log, Runnable::run, empty());

        // Empty means "not yet read", never "false": conflating the two would let a caller treat an
        // unread beacon as permanently denied.
        assertTrue(ledger.known(new BeaconId(WALL, 1)).isEmpty(),
                "an unread beacon must report unknown, not false");
        assertInstanceOf(Optional.class, ledger.known(new BeaconId(WALL, 1)));
    }

    /**
     * A refusal that needs no disk access reports {@code durable=true}.
     *
     * <p>That reads backwards at first — this assertion originally expected {@code false} and failed.
     * The flag means "this verdict is final and consistent with the durable state", not "a write
     * happened": a not-lit ignition is refused without touching the log, and returning false would
     * wrongly suggest a retry might succeed. Only {@code DEFERRED_IO} reports false, and that is
     * asserted separately in {@link #aFailedAppendDefersWithoutCounting}.
     */
    private static void assertRefused(FakeLog log, BeaconLedger ledger,
            GameplayTypes.VerifiedIgnition ignition, String why) throws Exception {
        var result = ledger.credit(ignition).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(GameplayTypes.CreditStatus.REFUSED_NOT_LIT, result.status(), why);
        assertTrue(result.durable(), why + ": a refusal needs no write and is final, so it reports durable");
        assertEquals(0L, result.sequence(), why + ": a refusal must carry no durable sequence");
    }

    /** Minimal DurableLog stub, mirroring the one already used by RoadAdapterStateTest. */
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
            return CompletableFuture.completedFuture(
                    new Receipt(entries.size(), entry.eventKey(), "a".repeat(64)));
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
