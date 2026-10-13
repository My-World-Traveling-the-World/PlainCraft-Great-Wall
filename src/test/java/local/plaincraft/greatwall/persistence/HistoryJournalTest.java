package local.plaincraft.greatwall.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;
import local.plaincraft.greatwall.persistence.DurableLog.Entry;
import local.plaincraft.greatwall.persistence.DurableLog.Record;
import local.plaincraft.greatwall.persistence.HistoryTypes.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryJournalTest {
    @TempDir Path temp;
    private static final String HASH = "a".repeat(64), TYPE = Kind.BEACON_CREDIT.name();
    private final List<Running> journals = new ArrayList<>();
    private record Running(HistoryJournal journal, ExecutorService io) {}

    private UUID authorize(Path root) throws Exception {
        return new WorldManifest(root.resolve("manifest")).authorize(false, 17, HASH, HASH).instance();
    }
    private Running create(Path root, UUID world, HistoryJournal.IoHooks hooks) {
        var io = Executors.newSingleThreadExecutor();
        var running = new Running(new HistoryJournal(root, world, io, hooks), io);
        journals.add(running); return running;
    }
    @Test void reopenedJournalReportsTheReplayedBeaconCountNotJustTheCheckpoint() throws Exception {
        // A WAL tail that was never checkpointed is still verified history. Recovery must report the
        // post-replay beacon total: seeding services from the checkpoint alone would undercount
        // beacons that provably exist in the log.
        var running = create(temp, authorize(temp), new HistoryJournal.IoHooks() {});
        await(running.journal().open());
        for (int i = 1; i <= 3; i++) await(running.journal().append(credit(i)));
        await(running.journal().checkpoint());
        // Two more beacon records land after the checkpoint, so they exist only in the WAL tail.
        for (int i = 4; i <= 5; i++) await(running.journal().append(credit(i)));
        awaitClose(running);

        var restarted = create(temp, authorize(temp), new HistoryJournal.IoHooks() {});
        var recovery = await(restarted.journal().open());
        assertEquals(3, recovery.head().beaconCount(),
                "the durable checkpoint describes only the checkpointed beacons");
        assertEquals(5, recovery.replayedBeaconCount(),
                "recovery must report every beacon the verified log contains, including the WAL tail");
        assertEquals(5, recovery.replayedSequence());
        assertTrue(recovery.replayedBeyondHead(),
                "the tail carried records beyond the checkpoint, which must be visible");
        // The public state accessor must agree with the replayed total, not the checkpoint.
        assertEquals(5, restarted.journal().state().beaconCount());
    }

    @Test void recoveryWithoutATailReportsTheCheckpointUnchanged() throws Exception {
        var running = create(temp, authorize(temp), new HistoryJournal.IoHooks() {});
        await(running.journal().open());
        await(running.journal().append(credit(1)));
        await(running.journal().checkpoint());
        awaitClose(running);

        var restarted = create(temp, authorize(temp), new HistoryJournal.IoHooks() {});
        var recovery = await(restarted.journal().open());
        assertFalse(recovery.replayedBeyondHead(), "a fully checkpointed history has nothing left to replay");
        assertEquals(recovery.head().beaconCount(), recovery.replayedBeaconCount());
        assertEquals(1, recovery.replayedBeaconCount());
    }

    private Running open(Path root) throws Exception {
        var running = create(root, authorize(root), new HistoryJournal.IoHooks() {});
        await(running.journal().open()); return running;
    }
    private static Entry credit(long ordinal) {
        return new Entry("beacon/" + HASH + "/" + ordinal, TYPE, 1,
                Map.of("wallId", HASH, "ordinal", Long.toString(ordinal), "pitY", "6000"));
    }
    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
    private static Throwable failure(CompletionStage<?> stage) {
        return assertThrows(ExecutionException.class, () -> await(stage)).getCause();
    }
    private static void awaitClose(Running running) throws Exception {
        running.journal().close();
        assertTrue(running.io().awaitTermination(30, TimeUnit.SECONDS), "IO close did not finish");
    }
    @AfterEach void closeJournals() throws Exception {
        for (var running : journals) awaitClose(running);
        Path evidence = temp.resolve("history/torn");
        if (Files.isDirectory(evidence)) try (var files = Files.newDirectoryStream(evidence)) {
            for (Path path : files) assertTrue(path.toFile().setWritable(true));
        }
    }

    @Test void constructorDoesNotTouchDiskAndUnopenedCallsFailClosed() throws Exception {
        Path absent = temp.resolve("absent");
        var running = create(absent, UUID.randomUUID(), new HistoryJournal.IoHooks() {});
        assertFalse(Files.exists(absent));
        assertEquals(Health.PENDING_IO, running.journal().state().health());
        assertInstanceOf(WallFailure.class, failure(running.journal().append(credit(1))));
        assertFalse(Files.exists(absent));
    }

    @Test void appendIsDurableAndIdempotent() throws Exception {
        var running = open(temp);
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        running.io().execute(() -> {
            blocked.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blocked.await(10, TimeUnit.SECONDS));
        var promises = new ArrayList<CompletionStage<DurableLog.Receipt>>();
        try {
            for (int i = 0; i < 100; i++) promises.add(running.journal().append(credit(1)));
            var conflicting = new Entry(credit(1).eventKey(), TYPE, 1, Map.of("ordinal", "2"));
            assertEquals(FailureCode.OWNERSHIP_CONFLICT, ((WallFailure) failure(running.journal().append(conflicting))).code());
            assertEquals(1, running.journal().inFlightCount());
        } finally { release.countDown(); }
        var receipt = await(promises.getFirst());
        for (var promise : promises) assertEquals(receipt, await(promise));
        assertEquals(1, running.journal().state().sequence());
        assertEquals(1, running.journal().state().beaconCount());
        assertEquals(0, running.journal().inFlightCount());
        awaitClose(running);
        var restarted = open(temp);
        assertEquals(receipt, await(restarted.journal().append(credit(1))));
        assertEquals(1, restarted.journal().state().beaconCount());
        assertInstanceOf(WallFailure.class, failure(restarted.journal().append(
                new Entry(credit(1).eventKey(), TYPE, 1, Map.of("ordinal", "3")))));
        assertEquals(Health.READY, restarted.journal().state().health());
    }

    @Test void twoCallerOwnersSerializeWithoutDuplicateSequence() throws Exception {
        var running = open(temp);
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> await(running.journal().append(credit(1))));
            var second = callers.submit(() -> await(running.journal().append(credit(1))));
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, running.journal().state().sequence());
    }

    @Test void cancelledCallerDoesNotCancelAcceptedWrite() throws Exception {
        var running = open(temp);
        var pending = running.journal().append(credit(1)).toCompletableFuture();
        pending.cancel(false);
        await(running.journal().flush());
        assertEquals(1, await(running.journal().lookup(TYPE, credit(1).eventKey())).orElseThrow().sequence());
    }

    @Test void completeBadCrcIsNotTruncated() throws Exception {
        var running = open(temp);
        await(running.journal().append(credit(1))); awaitClose(running);
        Path file = temp.resolve("history/wal_0.bin");
        byte[] bytes = Files.readAllBytes(file); bytes[bytes.length - 1] ^= 1; Files.write(file, bytes);
        var restarted = create(temp, authorize(temp), new HistoryJournal.IoHooks() {});
        assertInstanceOf(IOException.class, failure(restarted.journal().open()));
        assertEquals(Health.CORRUPT, restarted.journal().state().health());
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertFalse(Files.exists(temp.resolve("history/torn")));
        assertInstanceOf(WallFailure.class, failure(restarted.journal().append(credit(2))));
    }

    @Test void partialTailIsArchivedReadOnlyBeforeForcedTruncate() throws Exception {
        var running = open(temp);
        await(running.journal().append(credit(1))); awaitClose(running);
        Path file = temp.resolve("history/wal_0.bin"); byte[] committed = Files.readAllBytes(file);
        byte[] next = HistoryCodec.record(new Record(2, credit(2), AtomicFiles.sha256(HistoryCodec.entry(credit(2)))));
        byte[] torn = Arrays.copyOf(next, next.length / 2);
        Files.write(file, torn, StandardOpenOption.APPEND);
        var restarted = create(temp, authorize(temp), new HistoryJournal.IoHooks() {});
        assertTrue(await(restarted.journal().open()).archivedPartialTail());
        assertEquals(1, restarted.journal().state().beaconCount());
        assertArrayEquals(committed, Files.readAllBytes(file));
        try (var files = Files.list(temp.resolve("history/torn"))) {
            Path evidence = files.findFirst().orElseThrow();
            assertArrayEquals(torn, Files.readAllBytes(evidence));
            if (System.getProperty("os.name").startsWith("Windows"))
                assertTrue(Files.readAttributes(evidence, java.nio.file.attribute.DosFileAttributes.class).isReadOnly());
        }
        assertEquals(2, await(restarted.journal().append(credit(2))).sequence());
    }

    @Test void sequenceGapAndInvalidLengthAreHardFailures() throws Exception {
        for (String mode : List.of("gap", "length")) {
            Path root = temp.resolve(mode);
            var running = open(root); awaitClose(running);
            byte[] bytes = mode.equals("gap")
                    ? HistoryCodec.record(new Record(2, credit(2), AtomicFiles.sha256(HistoryCodec.entry(credit(2)))))
                    : ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE).array();
            Files.write(root.resolve("history/wal_0.bin"), bytes, StandardOpenOption.APPEND);
            var restarted = create(root, authorize(root), new HistoryJournal.IoHooks() {});
            failure(restarted.journal().open());
            assertEquals(Health.CORRUPT, restarted.journal().state().health());
            assertArrayEquals(bytes, Files.readAllBytes(root.resolve("history/wal_0.bin")));
        }
    }

    @Test void failedFsyncNeverPublishesAndVerifiedRetryReusesActualTail() throws Exception {
        var armed = new ConcurrentHashMap<String, Boolean>();
        var running = create(temp, authorize(temp), new HistoryJournal.IoHooks() {
            public void force(FileChannel channel) throws IOException {
                if (armed.remove("armed") != null) throw new IOException("injected force failure");
                channel.force(true);
            }
        });
        await(running.journal().open()); armed.put("armed", true);
        assertInstanceOf(IOException.class, failure(running.journal().append(credit(1))));
        assertEquals(0, running.journal().state().sequence());
        assertEquals(0, running.journal().state().beaconCount());
        assertEquals(Health.DEGRADED, running.journal().state().health());
        assertInstanceOf(WallFailure.class, failure(running.journal().append(credit(2))));
        awaitClose(running);
        var restarted = open(temp);
        assertEquals(1, restarted.journal().state().beaconCount());
        assertEquals(1, await(restarted.journal().append(credit(1))).sequence());
        assertEquals(2, await(restarted.journal().append(credit(2))).sequence());
    }

    @Test void checkpointDoesNotDropConcurrentTail() throws Exception {
        CountDownLatch cutting = new CountDownLatch(1), release = new CountDownLatch(1);
        var running = create(temp, authorize(temp), new HistoryJournal.IoHooks() {
            public void beforeHead(Head next) throws IOException {
                cutting.countDown();
                try { if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("test HEAD barrier expired"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
        });
        await(running.journal().open());
        for (int i = 1; i <= 10; i++) await(running.journal().append(credit(i)));
        var checkpoint = running.journal().checkpoint();
        var tail = new ArrayList<CompletionStage<DurableLog.Receipt>>();
        try {
            assertTrue(cutting.await(10, TimeUnit.SECONDS));
            for (int i = 11; i <= 20; i++) tail.add(running.journal().append(credit(i)));
            assertFalse(tail.getFirst().toCompletableFuture().isDone());
        } finally { release.countDown(); }
        assertEquals(10, await(checkpoint).sequence());
        for (int i = 0; i < tail.size(); i++) assertEquals(11 + i, await(tail.get(i)).sequence());
        awaitClose(running);
        var restarted = open(temp);
        assertEquals(20, restarted.journal().state().sequence());
        for (int i = 1; i <= 20; i++) assertEquals(i,
                await(restarted.journal().lookup(TYPE, credit(i).eventKey())).orElseThrow().sequence());
    }

    @Test void checkpointFailureLeavesOriginalHeadAndRecoveryCanContinue() throws Exception {
        var armed = new ConcurrentHashMap<String, Boolean>();
        var running = create(temp, authorize(temp), new HistoryJournal.IoHooks() {
            public void beforeHead(Head next) throws IOException {
                if (armed.remove("armed") != null) throw new IOException("injected pre-HEAD failure");
            }
        });
        await(running.journal().open()); await(running.journal().append(credit(1)));
        byte[] oldHead = Files.readAllBytes(temp.resolve("history/HEAD")); armed.put("armed", true);
        failure(running.journal().checkpoint());
        assertArrayEquals(oldHead, Files.readAllBytes(temp.resolve("history/HEAD")));
        assertEquals(Health.DEGRADED, running.journal().state().health());
        awaitClose(running);
        var restarted = open(temp);
        assertEquals(1, restarted.journal().state().beaconCount());
        await(restarted.journal().append(credit(2))); await(restarted.journal().checkpoint()); awaitClose(restarted);
        var again = open(temp);
        assertEquals(2, again.journal().state().beaconCount());
    }

    @Test void pagedHistoryKeepsHeadAndResidentMemoryBounded() throws Exception {
        var running = open(temp);
        for (int i = 1; i <= 8193; i++) await(running.journal().append(credit(i)));
        var head = await(running.journal().checkpoint());
        assertEquals(Set.of("by_key", "by_sequence"), head.roots().keySet());
        assertTrue(Files.size(temp.resolve("history/HEAD")) < 4096);
        for (int ordinal : new int[] {1, 4096, 4097, 8192, 8193}) assertEquals(ordinal,
                await(running.journal().lookup(TYPE, credit(ordinal).eventKey())).orElseThrow().sequence());
        assertTrue(await(running.journal().lookup(TYPE, credit(8194).eventKey())).isEmpty());
        await(running.journal().append(credit(8194))); await(running.journal().checkpoint());
        long after = 0;
        while (after < 8194) {
            long from = after;
            List<Record> page = running.io().submit(() -> running.journal().replay(TYPE, from, 137)).get(30, TimeUnit.SECONDS);
            assertFalse(page.isEmpty());
            for (var record : page) assertEquals(++after, record.sequence());
        }
        assertEquals(8194, after);
        long pages = running.io().submit(running.journal()::residentPageBytes).get(10, TimeUnit.SECONDS);
        long tail = running.io().submit(running.journal()::residentTailBytes).get(10, TimeUnit.SECONDS);
        assertTrue(pages <= 8L << 20); assertTrue(tail <= 8L << 20);
        awaitClose(running);
        var restarted = open(temp);
        assertEquals(8194, restarted.journal().state().beaconCount());
        assertEquals(8194, await(restarted.journal().lookup(TYPE, credit(8194).eventKey())).orElseThrow().sequence());
    }

    @Test void missingHeadRecoversOnlyAConsistentRetainedChain() throws Exception {
        var running = open(temp);
        await(running.journal().append(credit(1))); await(running.journal().checkpoint());
        await(running.journal().append(credit(2))); awaitClose(running);
        Files.delete(temp.resolve("history/HEAD"));
        var restarted = open(temp);
        assertEquals(2, restarted.journal().state().beaconCount());
        assertTrue(Files.isRegularFile(temp.resolve("history/HEAD")));
    }

    @Test void damagedIndexMissingWalAndWrongWorldNeverBecomeEmpty() throws Exception {
        for (String mode : List.of("index", "wal", "world")) {
            Path root = temp.resolve(mode);
            var running = open(root); await(running.journal().append(credit(1)));
            var head = await(running.journal().checkpoint()); awaitClose(running);
            UUID world = authorize(root);
            if (mode.equals("index")) Files.delete(root.resolve(head.roots().get("by_key").relativePath()));
            if (mode.equals("wal")) Files.delete(root.resolve("history/wal_" + head.generation() + ".bin"));
            var restarted = create(root, mode.equals("world") ? UUID.randomUUID() : world, new HistoryJournal.IoHooks() {});
            failure(restarted.journal().open());
            assertEquals(Health.CORRUPT, restarted.journal().state().health());
            assertNotEquals(Health.READY, restarted.journal().state().health());
        }
    }

    @Test void unauthorizedAndLegacyHistoriesAreRejectedWithoutMigration() throws Exception {
        var unauthorized = create(temp, UUID.randomUUID(), new HistoryJournal.IoHooks() {});
        failure(unauthorized.journal().open());
        assertFalse(Files.exists(temp.resolve("history/HEAD")));
        Path legacy = temp.resolve("legacy"); UUID world = authorize(legacy);
        Files.createDirectories(legacy.resolve("journal")); Files.writeString(legacy.resolve("journal/history.log"), "old evidence");
        var old = create(legacy, world, new HistoryJournal.IoHooks() {});
        failure(old.journal().open());
        assertEquals("old evidence", Files.readString(legacy.resolve("journal/history.log")));
        assertFalse(Files.exists(legacy.resolve("history/HEAD")));
    }

    @Test void queueIsBoundedAndCloseDrainsAcceptedWritesWithoutBlocking() throws Exception {
        var running = open(temp);
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        running.io().execute(() -> {
            blocked.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blocked.await(10, TimeUnit.SECONDS));
        var accepted = new ArrayList<CompletionStage<DurableLog.Receipt>>();
        try {
            for (int i = 1; i <= 128; i++) accepted.add(running.journal().append(credit(i)));
            assertEquals(FailureCode.CAPACITY, ((WallFailure) failure(running.journal().append(credit(129)))).code());
            assertTimeout(Duration.ofSeconds(1), running.journal()::close);
            assertEquals(FailureCode.CLOSED, ((WallFailure) failure(running.journal().append(credit(130)))).code());
            assertFalse(accepted.getLast().toCompletableFuture().isDone());
        } finally { release.countDown(); }
        for (int i = 0; i < accepted.size(); i++) assertEquals(i + 1, await(accepted.get(i)).sequence());
        assertTrue(running.io().awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(Health.CLOSED, running.journal().state().health());
        var restarted = open(temp);
        assertEquals(128, restarted.journal().state().beaconCount());
    }

    @Test void blobCopiesAtAdmissionAndChecksLengthHashAndKind() throws Exception {
        var running = open(temp);
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        running.io().execute(() -> {
            blocked.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blocked.await(10, TimeUnit.SECONDS));
        byte[] bytes = {1, 2, 3}; CompletionStage<BlobRef> future;
        try { future = running.journal().storeBlob("gate_contract", bytes); bytes[0] = 9; }
        finally { release.countDown(); }
        BlobRef ref = await(future);
        assertArrayEquals(new byte[] {1, 2, 3}, await(running.journal().loadBlob(ref)));
        byte[] loaded = await(running.journal().loadBlob(ref)); loaded[0] = 8;
        assertArrayEquals(new byte[] {1, 2, 3}, await(running.journal().loadBlob(ref)));
        assertEquals(FailureCode.INVALID_RESOURCE, ((WallFailure) failure(
                running.journal().storeBlob("../escape", new byte[0]))).code());
        assertEquals(FailureCode.CAPACITY, ((WallFailure) failure(
                running.journal().storeBlob("gate", new byte[(16 << 20) + 1]))).code());
        failure(running.journal().loadBlob(new BlobRef(ref.relativePath(), ref.length() + 1, ref.sha256())));
        assertEquals(Health.CORRUPT, running.journal().state().health());
    }

    @Test void entryLimitIs64KiBNotThePublicAbsoluteLimit() throws Exception {
        var running = open(temp); var fields = new HashMap<String, String>();
        for (int i = 0; i < 17; i++) fields.put("field" + i, "x".repeat(4096));
        assertEquals(FailureCode.CAPACITY, ((WallFailure) failure(
                running.journal().append(new Entry("large", TYPE, 1, fields)))).code());
        assertEquals(0, running.journal().state().sequence());
        assertEquals(Health.READY, running.journal().state().health());
    }

    @Test void replayOnlyRunsOnIoOwnerAndStrictlyChecksLimits() throws Exception {
        var running = open(temp);
        assertThrows(IllegalStateException.class, () -> running.journal().replay(TYPE, 0, 1));
        running.io().submit(() -> {
            assertThrows(IllegalArgumentException.class, () -> running.journal().replay(TYPE, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> running.journal().replay(TYPE, 0, 1025));
            assertThrows(IllegalArgumentException.class, () -> running.journal().replay(TYPE, -1, 1));
        }).get(10, TimeUnit.SECONDS);
    }

    @Test void secondJournalWriterIsRejectedWithoutDisturbingFirst() throws Exception {
        var first = open(temp);
        var second = create(temp, authorize(temp), new HistoryJournal.IoHooks() {});
        assertInstanceOf(IOException.class, failure(second.journal().open()));
        assertEquals(1, await(first.journal().append(credit(1))).sequence());
    }

    @Test void concurrentAdmissionCancellationFlushAndRepeatedCloseSettleExactlyAcceptedRequests() throws Exception {
        var running = open(temp);
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1), start = new CountDownLatch(1);
        running.io().execute(() -> {
            blocked.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blocked.await(10, TimeUnit.SECONDS));
        var first = running.journal().append(credit(1));
        record Attempt(Entry entry, CompletableFuture<DurableLog.Receipt> view, boolean accepted) {}
        var attempts = new ArrayList<Attempt>();
        try (var callers = Executors.newFixedThreadPool(6)) {
            var calls = new ArrayList<Future<Attempt>>();
            for (int i = 2; i <= 97; i++) {
                int ordinal = i;
                calls.add(callers.submit(() -> {
                    start.await();
                    Entry entry = credit(ordinal);
                    var view = running.journal().append(entry).toCompletableFuture();
                    boolean accepted = !view.isDone();
                    if (accepted && ordinal % 2 == 0) view.cancel(false);
                    return new Attempt(entry, view, accepted);
                }));
            }
            var closer = callers.submit(() -> {
                start.await(); running.journal().close(); running.journal().close(); return null;
            });
            start.countDown();
            for (var call : calls) attempts.add(call.get(10, TimeUnit.SECONDS));
            closer.get(10, TimeUnit.SECONDS);
            assertTrue(running.journal().flush().toCompletableFuture().isCompletedExceptionally());
        } finally { start.countDown(); release.countDown(); }
        assertEquals(1, await(first).sequence());
        assertTrue(running.io().awaitTermination(30, TimeUnit.SECONDS));
        long count = 1;
        for (var attempt : attempts) {
            assertTrue(attempt.view().isDone(), "accepted/rejected/cancelled caller was left pending");
            if (attempt.accepted()) count++;
            else assertEquals(FailureCode.CLOSED, ((WallFailure) failure(attempt.view())).code());
        }
        var restarted = open(temp);
        assertEquals(count, restarted.journal().state().beaconCount());
        for (var attempt : attempts) assertEquals(attempt.accepted(),
                await(restarted.journal().lookup(TYPE, attempt.entry().eventKey())).isPresent());
    }
}
