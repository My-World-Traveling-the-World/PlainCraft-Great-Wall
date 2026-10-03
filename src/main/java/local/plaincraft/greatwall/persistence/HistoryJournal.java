package local.plaincraft.greatwall.persistence;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;
import local.plaincraft.greatwall.persistence.HistoryTypes.*;

/** One bounded IO writer, a forced binary WAL, and two immutable streaming indexes. */
public final class HistoryJournal implements DurableLog {
    private static final int MAX_PENDING = 128, MAX_TAIL = 1024, MAX_BLOB_BYTES = 16 << 20;
    private static final long TASK_BYTES = 512, MAX_TAIL_BYTES = 8L << 20;
    private static final long MAX_QUEUED_BYTES = (16L << 20) + MAX_PENDING * TASK_BYTES;
    private static final String ZERO_HASH = "0".repeat(64), SNAPSHOT = "state";
    private static final Set<String> BLOB_KINDS = Set.of("gate", "gate_contract", "gate_proof", "beacon", "slot");
    private final Path root;
    private final UUID world;
    private final ExecutorService ioOwner;
    private final IoHooks hooks;
    private final HistoryIndex index;
    private final ConcurrentHashMap<String, Admission> admission = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Owner> owners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Receipt>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, State> snapshots = new ConcurrentHashMap<>();
    private final TreeMap<String, Record> tailByKey = new TreeMap<>(HistoryIndex.UTF8);
    private final ArrayList<Record> tailBySequence = new ArrayList<>();
    private boolean opened;
    private long tailBytes, lastSequence, beaconCount;
    private String manifestHash = ZERO_HASH;
    private Head head;
    private FileChannel wal, lockChannel;
    private FileLock writerLock;

    interface IoHooks {
        default void force(FileChannel channel) throws IOException { channel.force(true); }
        default void beforeHead(Head next) throws IOException {}
    }
    @FunctionalInterface private interface IoAction<T> { T run() throws IOException; }
    private record Task<T>(IoAction<T> action, CompletableFuture<T> result, long bytes,
            Health failureHealth, String key) {}
    private record Admission(List<Task<?>> queue, Map<String, Entry> entries, int pending,
            long bytes, boolean draining, boolean closing) {
        private Admission {
            queue = List.copyOf(queue); entries = Map.copyOf(entries);
        }
    }
    private record Owner(long threadId) {}
    private record Decision(CompletableFuture<?> result, FailureCode failure, String message, boolean schedule) {}

    public HistoryJournal(Path root, UUID world, ExecutorService ioOwner) {
        this(root, world, ioOwner, new IoHooks() {});
    }
    HistoryJournal(Path root, UUID world, ExecutorService ioOwner, IoHooks hooks) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.world = Objects.requireNonNull(world, "world");
        this.ioOwner = Objects.requireNonNull(ioOwner, "ioOwner");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.index = new HistoryIndex(this.root);
        admission.put(SNAPSHOT, new Admission(List.of(), Map.of(), 0, 0, false, false));
        snapshots.put(SNAPSHOT, new State(world, ZERO_HASH, 0, 0, 0, Health.PENDING_IO, ZERO_HASH));
    }

    public CompletionStage<Recovery> open() { return submit(this::openOnOwner, 0, Health.CORRUPT); }

    /** Source compatibility only: legacy placeholder roots/identity are never trusted. */
    public CompletionStage<Recovery> open(Head expected) {
        Objects.requireNonNull(expected, "expected");
        return open().thenApply(recovery -> {
            if (!recovery.head().equals(expected))
                throw new WallFailure(FailureCode.RECIPE_MISMATCH, "supplied head differs from verified history/HEAD");
            return recovery;
        });
    }

    private Recovery openOnOwner() throws IOException {
        if (opened && state().health() == Health.READY) return new Recovery(head, tailBySequence, false);
        releaseChannels();
        tailByKey.clear(); tailBySequence.clear(); tailBytes = 0; index.clear(); opened = false;
        readManifest();
        if (Files.exists(root.resolve("journal/history.log"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(root.resolve("journal/head.json"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("legacy journal is not migrated or adopted as empty history");
        Path directory = HistoryCodec.path(root, "history/.writer.lock").getParent();
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(HistoryCodec.path(root, "history/.writer.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { writerLock = lockChannel.tryLock(); }
        catch (OverlappingFileLockException busy) { throw new IOException("history already has an IO writer", busy); }
        if (writerLock == null) throw new IOException("history already has an IO writer");
        boolean missingHead = !Files.exists(HistoryCodec.path(root, "history/HEAD"), LinkOption.NOFOLLOW_LINKS);
        if (missingHead) {
            long generation = newest("checkpoint_", Long.MAX_VALUE);
            if (generation < 0) {
                try (var files = Files.newDirectoryStream(directory)) {
                    for (Path file : files) if (!file.getFileName().toString().equals(".writer.lock"))
                        throw new IOException("nonempty history has no recoverable HEAD/checkpoint");
                }
                initialize(); missingHead = false;
            } else head = readHead(checkpointPath(generation));
        } else head = readHead("history/HEAD");
        if (!world.equals(head.world())) throw new IOException("HEAD belongs to a different manifest.instance");
        if (!head.equals(readHead(checkpointPath(head.generation())))) throw new IOException("HEAD/checkpoint mismatch");
        validateHead(head); validateRetainedChain(head);
        lastSequence = head.sequence(); beaconCount = head.beaconCount();
        wal = FileChannel.open(HistoryCodec.path(root, walPath(head.generation())),
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        boolean archived = readWal(wal, head.generation(), head.sequence(), record -> {
            if (findOnOwner(record.entry().type(), record.entry().eventKey()).isPresent())
                throw new IOException("WAL repeats a committed event key");
            addTail(record); lastSequence = record.sequence();
            if (isBeacon(record)) beaconCount++;
        }, true);
        wal.position(wal.size());
        if (missingHead) replaceHead(HistoryCodec.head(head));
        opened = true; publish(Health.READY);
        return new Recovery(head, tailBySequence, archived);
    }

    private void readManifest() throws IOException {
        Path directory = root.resolve("manifest");
        var recipe = new WorldManifest(directory).read();
        Path creation = directory.resolve("creation.json");
        if (!Files.isRegularFile(creation, LinkOption.NOFOLLOW_LINKS)) throw new IOException("creation token required for history");
        byte[] bytes;
        try (var input = Files.newInputStream(creation)) { bytes = input.readNBytes(65537); }
        if (bytes.length > 65536) throw new IOException("oversized creation token");
        try {
            var payload = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("payload");
            if (!UUID.fromString(payload.get("instance").getAsString()).equals(world))
                throw new IOException("history world differs from manifest.instance");
        } catch (RuntimeException invalid) { throw new IOException("invalid creation identity", invalid); }
        if (recipe.isPresent() && !recipe.get().instance().equals(world)) throw new IOException("frozen recipe instance mismatch");
        manifestHash = recipe.isPresent()
                ? AtomicFiles.sha256(AtomicFiles.readChecked(root, "manifest/world.json", 65536, null))
                : AtomicFiles.sha256(bytes);
    }

    private void initialize() throws IOException {
        try (var channel = FileChannel.open(HistoryCodec.path(root, walPath(0)),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { hooks.force(channel); }
        byte[] bytes = HistoryCodec.head(new Head(world, 0, 0, 0, Map.of(), ZERO_HASH));
        HistoryCodec.immutable(root, checkpointPath(0), bytes);
        replaceHead(bytes); head = HistoryCodec.head(bytes);
    }
    private Head readHead(String path) throws IOException {
        return HistoryCodec.head(HistoryCodec.read(root, path, 65536, null));
    }
    private void validateHead(Head candidate) throws IOException {
        if (!candidate.world().equals(world)) throw new IOException("checkpoint world mismatch");
        long sequence = 0, credits = 0, keys = 0;
        var records = index.iterator(candidate.roots().get("by_sequence"), false);
        while (records.hasNext()) {
            var record = records.next();
            if (record.sequence() != ++sequence) throw new IOException("history sequence gap");
            if (!index.lookup(candidate.roots().get("by_key"), HistoryIndex.key(record, true), true).equals(Optional.of(record)))
                throw new IOException("dual history indexes disagree");
            if (isBeacon(record)) credits++;
        }
        records = index.iterator(candidate.roots().get("by_key"), true);
        String previous = null;
        while (records.hasNext()) {
            var record = records.next(); String key = HistoryIndex.key(record, true);
            if (previous != null && HistoryIndex.UTF8.compare(previous, key) >= 0) throw new IOException("global key order/duplicate");
            if (!index.lookup(candidate.roots().get("by_sequence"), HistoryIndex.key(record, false), false).equals(Optional.of(record)))
                throw new IOException("dual history indexes disagree");
            previous = key; keys++;
        }
        if (sequence != candidate.sequence() || keys != sequence || credits != candidate.beaconCount())
            throw new IOException("history HEAD count mismatch");
    }
    private void validateRetainedChain(Head current) throws IOException {
        if (current.generation() == 0) return;
        long generation = newest("checkpoint_", current.generation()); boolean linked = false;
        while (generation >= 0) {
            var previous = readHead(checkpointPath(generation)); validateHead(previous);
            if (previous.sequence() > current.sequence()) throw new IOException("checkpoint sequence regression");
            var records = index.iterator(previous.roots().get("by_sequence"), false);
            while (records.hasNext()) {
                var record = records.next();
                if (!index.lookup(current.roots().get("by_sequence"), HistoryIndex.key(record, false), false).equals(Optional.of(record)))
                    throw new IOException("contradictory retained checkpoint");
            }
            long[] through = {previous.sequence()}, credits = {previous.beaconCount()};
            try (var channel = FileChannel.open(HistoryCodec.path(root, walPath(generation)), StandardOpenOption.READ)) {
                readWal(channel, generation, previous.sequence(), record -> {
                    if (record.sequence() > current.sequence()
                            || !index.lookup(current.roots().get("by_sequence"), HistoryIndex.key(record, false), false).equals(Optional.of(record)))
                        throw new IOException("retained WAL disagrees with checkpoint");
                    through[0] = record.sequence(); if (isBeacon(record)) credits[0]++;
                }, false);
            }
            if (through[0] == current.sequence() && credits[0] == current.beaconCount()) linked = true;
            generation = newest("checkpoint_", generation);
        }
        if (!linked) throw new IOException("no continuous retained checkpoint/WAL chain");
    }

    @FunctionalInterface private interface RecordConsumer { void accept(Record record) throws IOException; }
    private boolean readWal(FileChannel channel, long generation, long base, RecordConsumer consumer,
            boolean repairTail) throws IOException {
        long through = base; channel.position(0);
        while (channel.position() < channel.size()) {
            long offset = channel.position(), remaining = channel.size() - offset;
            if (remaining < 4) return torn(channel, generation, offset, repairTail);
            ByteBuffer lengthBytes = ByteBuffer.allocate(4); readFully(channel, lengthBytes); lengthBytes.flip();
            int length = lengthBytes.getInt();
            if (length < 16 || length > HistoryCodec.RECORD_LIMIT - 12) throw new IOException("invalid complete WAL length header");
            if (remaining < length + 12L) return torn(channel, generation, offset, repairTail);
            ByteBuffer frame = ByteBuffer.allocate(length + 12); frame.putInt(length); readFully(channel, frame);
            var record = HistoryCodec.record(frame.array());
            if (through == Long.MAX_VALUE || record.sequence() != through + 1) throw new IOException("WAL sequence gap");
            through = record.sequence(); consumer.accept(record);
        }
        return false;
    }
    private boolean torn(FileChannel channel, long generation, long offset, boolean repair) throws IOException {
        if (!repair) throw new IOException("retained WAL has an incomplete tail");
        int length = Math.toIntExact(channel.size() - offset);
        ByteBuffer tail = ByteBuffer.allocate(length); channel.position(offset); readFully(channel, tail);
        byte[] bytes = tail.array();
        String relative = "history/torn/wal_" + generation + "_" + offset + "_" + AtomicFiles.sha256(bytes) + ".bin";
        HistoryCodec.immutable(root, relative, bytes);
        if (!HistoryCodec.path(root, relative).toFile().setReadOnly()) throw new IOException("cannot make torn-tail evidence read-only");
        channel.truncate(offset); hooks.force(channel); channel.position(offset); return true;
    }
    private static void readFully(FileChannel channel, ByteBuffer target) throws IOException {
        while (target.hasRemaining()) if (channel.read(target) < 0) throw new EOFException("WAL changed during recovery");
    }

    @Override public CompletionStage<Receipt> append(Entry original) {
        Objects.requireNonNull(original, "entry");
        Entry entry = new Entry(original.eventKey(), original.type(), original.schema(), original.fields());
        byte[] bytes;
        try { bytes = HistoryCodec.entry(entry); }
        catch (IOException invalid) {
            return CompletableFuture.failedFuture(new WallFailure(FailureCode.CAPACITY, "invalid/oversized history entry", invalid));
        }
        String key = entry.type() + '\0' + entry.eventKey();
        long charge = bytes.length + retained(new Record(1, entry, ZERO_HASH));
        return admit(new Task<>(() -> appendOnOwner(entry, AtomicFiles.sha256(bytes)),
                new CompletableFuture<Receipt>(), charge, Health.DEGRADED, key), entry, false);
    }
    private Receipt appendOnOwner(Entry entry, String hash) throws IOException {
        ready(); Optional<Record> prior;
        try { prior = findOnOwner(entry.type(), entry.eventKey()); }
        catch (IOException | UncheckedIOException corrupt) { mark(Health.CORRUPT); throw corrupt; }
        if (prior.isPresent()) {
            if (!prior.get().entry().equals(entry)) throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT, "same committed key with different payload");
            return receipt(prior.get());
        }
        if (lastSequence == Long.MAX_VALUE) throw new WallFailure(FailureCode.CAPACITY, "history sequence exhausted");
        var record = new Record(lastSequence + 1, entry, hash);
        if (tailBySequence.size() == MAX_TAIL || tailBytes + retained(record) > MAX_TAIL_BYTES) checkpointOnOwner();
        byte[] encoded = HistoryCodec.record(record); ByteBuffer buffer = ByteBuffer.wrap(encoded);
        while (buffer.hasRemaining()) wal.write(buffer);
        hooks.force(wal);
        addTail(record); lastSequence = record.sequence(); if (isBeacon(record)) beaconCount++;
        publish(Health.READY);
        if (tailBySequence.size() == MAX_TAIL || tailBytes >= MAX_TAIL_BYTES) {
            try { checkpointOnOwner(); }
            catch (IOException | RuntimeException failure) { mark(Health.DEGRADED); }
        }
        return receipt(record);
    }
    private void addTail(Record record) throws IOException {
        long retained = retained(record);
        if (tailBySequence.size() >= MAX_TAIL || tailBytes + retained > MAX_TAIL_BYTES)
            throw new IOException("WAL tail exceeds bounded writer contract");
        if (tailByKey.putIfAbsent(HistoryIndex.key(record, true), record) != null) throw new IOException("duplicate WAL event key");
        tailBySequence.add(record); tailBytes += retained;
    }
    private static long retained(Record record) {
        return HistoryCodec.retained(record) + HistoryCodec.stringBytes(HistoryIndex.key(record, true)) + 160;
    }
    private static boolean isBeacon(Record record) { return record.entry().type().equals(Kind.BEACON_CREDIT.name()); }
    private static Receipt receipt(Record record) { return new Receipt(record.sequence(), record.entry().eventKey(), record.payloadHash()); }

    @Override public CompletionStage<Optional<Record>> lookup(String type, String eventKey) {
        Objects.requireNonNull(type); Objects.requireNonNull(eventKey);
        try { HistoryCodec.identity(type, eventKey); }
        catch (IOException invalid) { return CompletableFuture.failedFuture(invalid); }
        return submit(() -> { ready(); return findOnOwner(type, eventKey); }, 0, Health.CORRUPT);
    }
    private Optional<Record> findOnOwner(String type, String eventKey) throws IOException {
        String key = type + '\0' + eventKey; Record tail = tailByKey.get(key);
        return tail != null ? Optional.of(tail) : index.lookup(head.roots().get("by_key"), key, true);
    }

    @Override public CompletionStage<BlobRef> storeBlob(String kind, byte[] original) {
        Objects.requireNonNull(kind); Objects.requireNonNull(original);
        if (!BLOB_KINDS.contains(kind)) return failed(FailureCode.INVALID_RESOURCE, "unsupported history blob kind");
        if (original.length > MAX_BLOB_BYTES) return failed(FailureCode.CAPACITY, "history blob exceeds 16MiB");
        byte[] bytes = original.clone();
        return submit(() -> {
            ready(); String hash = AtomicFiles.sha256(bytes);
            String path = "history/blobs/" + kind + "/" + hash.substring(0, 2) + "/" + hash + ".bin";
            HistoryCodec.immutable(root, path, bytes); return new BlobRef(path, bytes.length, hash);
        }, bytes.length, Health.DEGRADED);
    }
    @Override public CompletionStage<byte[]> loadBlob(BlobRef ref) {
        Objects.requireNonNull(ref);
        if (ref.length() > MAX_BLOB_BYTES || !ref.relativePath().startsWith("history/blobs/"))
            return failed(FailureCode.INVALID_RESOURCE, "invalid history blob reference");
        return submit(() -> {
            ready(); byte[] bytes = HistoryCodec.read(root, ref.relativePath(), MAX_BLOB_BYTES, ref.sha256());
            if (bytes.length != ref.length()) throw new IOException("blob length mismatch");
            return bytes;
        }, 0, Health.CORRUPT);
    }

    @Override public List<Record> replay(String type, long afterSequence, int limit) {
        if (!isOwner()) throw new IllegalStateException("history replay belongs to the IO owner");
        if (afterSequence < 0 || limit < 1 || limit > MAX_TAIL) throw new IllegalArgumentException("replay requires after>=0, limit1..1024");
        Objects.requireNonNull(type); ready();
        try {
            var records = index.iterator(head.roots().get("by_sequence"), false, HistoryIndex.sequenceKey(afterSequence));
            var result = new ArrayList<Record>(limit);
            while (records.hasNext() && result.size() < limit) {
                var record = records.next(); if (record.entry().type().equals(type)) result.add(record);
            }
            for (var record : tailBySequence) {
                if (result.size() == limit) break;
                if (record.sequence() > afterSequence && record.entry().type().equals(type)) result.add(record);
            }
            return List.copyOf(result);
        } catch (UncheckedIOException corrupt) {
            mark(Health.CORRUPT); throw new WallFailure(FailureCode.T0_CORRUPT, "history replay failed", corrupt);
        }
    }

    public CompletionStage<Head> checkpoint() { return submit(this::checkpointOnOwner, 0, Health.DEGRADED); }
    private Head checkpointOnOwner() throws IOException {
        ready(); hooks.force(wal);
        if (tailBySequence.isEmpty()) return head;
        BlobRef byKey = index.build(merge(index.iterator(head.roots().get("by_key"), true), tailByKey.values().iterator(), true), true);
        BlobRef bySequence = index.build(merge(index.iterator(head.roots().get("by_sequence"), false), tailBySequence.iterator(), false), false);
        long generation = Math.max(newest("checkpoint_", Long.MAX_VALUE), newest("wal_", Long.MAX_VALUE)) + 1;
        byte[] bytes = HistoryCodec.head(new Head(world, generation, lastSequence, beaconCount,
                Map.of("by_key", byKey, "by_sequence", bySequence), ZERO_HASH));
        Head next = HistoryCodec.head(bytes);
        FileChannel nextWal = FileChannel.open(HistoryCodec.path(root, walPath(generation)),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
        boolean switched = false;
        try {
            hooks.force(nextWal); HistoryCodec.immutable(root, checkpointPath(generation), bytes);
            if (!next.equals(readHead(checkpointPath(generation)))) throw new IOException("new checkpoint verification failed");
            hooks.beforeHead(next); replaceHead(bytes);
            if (!next.equals(readHead("history/HEAD"))) throw new IOException("new HEAD verification failed");
            long previousGeneration = head.generation();
            FileChannel oldWal = wal; wal = nextWal; head = next; switched = true;
            tailByKey.clear(); tailBySequence.clear(); tailBytes = 0; index.clear();
            oldWal.close(); publish(Health.READY);
            try { retireOlder(previousGeneration); } catch (IOException cleanupFailure) { mark(Health.DEGRADED); }
            return head;
        } finally { if (!switched) nextWal.close(); }
    }
    private static Iterator<Record> merge(Iterator<Record> old, Iterator<Record> tail, boolean byKey) {
        return new Iterator<>() {
            private Record left, right;
            public boolean hasNext() {
                if (left == null && old.hasNext()) left = old.next();
                if (right == null && tail.hasNext()) right = tail.next();
                return left != null || right != null;
            }
            public Record next() {
                if (!hasNext()) throw new NoSuchElementException();
                int comparison = left == null ? 1 : right == null ? -1
                        : HistoryIndex.UTF8.compare(HistoryIndex.key(left, byKey), HistoryIndex.key(right, byKey));
                if (comparison == 0) {
                    if (!left.entry().equals(right.entry()) || !left.payloadHash().equals(right.payloadHash()))
                        throw new UncheckedIOException(new IOException("conflicting checkpoint payload"));
                    Record result = left.sequence() <= right.sequence() ? left : right; left = null; right = null; return result;
                }
                if (comparison < 0) { Record result = left; left = null; return result; }
                Record result = right; right = null; return result;
            }
        };
    }
    private void retireOlder(long previousGeneration) throws IOException {
        if (previousGeneration == 0) return;
        try (var files = Files.newDirectoryStream(root.resolve("history"))) {
            for (Path file : files) {
                String name = file.getFileName().toString(); long generation = generation(name, "checkpoint_");
                if (generation < 0) generation = generation(name, "wal_");
                if (generation >= 0 && generation < previousGeneration) Files.delete(HistoryCodec.path(root, "history/" + name));
            }
        }
    }
    private long newest(String prefix, long exclusive) throws IOException {
        long newest = -1;
        try (var files = Files.newDirectoryStream(root.resolve("history"))) {
            for (Path file : files) {
                long generation = generation(file.getFileName().toString(), prefix);
                if (generation >= 0 && generation < exclusive && generation > newest) newest = generation;
            }
        }
        return newest;
    }
    private static long generation(String name, String prefix) throws IOException {
        if (!name.startsWith(prefix) || !name.endsWith(".bin")) return -1;
        String raw = name.substring(prefix.length(), name.length() - 4);
        try {
            long value = Long.parseLong(raw);
            if (value < 0 || !Long.toString(value).equals(raw)) throw new NumberFormatException();
            if (value == Long.MAX_VALUE) throw new WallFailure(FailureCode.CAPACITY, "history generation exhausted");
            return value;
        } catch (NumberFormatException malformed) { throw new IOException("invalid permanent history generation name", malformed); }
    }
    private static String walPath(long generation) { return "history/wal_" + generation + ".bin"; }
    private static String checkpointPath(long generation) { return "history/checkpoint_" + generation + ".bin"; }
    private void replaceHead(byte[] bytes) throws IOException {
        try { AtomicFiles.replacePointer(root, "history/HEAD", bytes); }
        catch (WallFailure failure) { throw new IOException("atomic history HEAD replace failed", failure); }
    }

    public State state() { return snapshots.get(SNAPSHOT); }
    public long sequence() { return state().sequence(); }
    public Path root() { return root; }
    @Override public CompletionStage<Void> flush() {
        return submit(() -> { ready(); hooks.force(wal); return null; }, 0, Health.DEGRADED);
    }
    private void ready() {
        if (!opened || state().health() != Health.READY)
            throw new WallFailure(FailureCode.IO_FAILED, "history is not READY: " + state().health());
    }
    private void publish(Health health) {
        snapshots.compute(SNAPSHOT, (key, previous) -> new State(world, manifestHash, lastSequence, beaconCount,
                previous.revision() + 1, health, head == null ? ZERO_HASH : head.hash()));
    }
    private void mark(Health health) {
        snapshots.compute(SNAPSHOT, (key, previous) -> {
            if (previous.health() == Health.CLOSED || (previous.health() == Health.CORRUPT && health != Health.CLOSED)) return previous;
            return new State(world, previous.manifestHash(), previous.sequence(), previous.beaconCount(),
                    previous.revision() + 1, health, previous.checkpointHash());
        });
    }

    private <T> CompletionStage<T> submit(IoAction<T> action, long bytes, Health failureHealth) {
        return admit(new Task<>(action, new CompletableFuture<T>(), bytes, failureHealth, null), null, false);
    }
    private <T> CompletionStage<T> admit(Task<T> task, Entry entry, boolean close) {
        // The decision holder is caller-local. No IO, completion, or callback runs under compute.
        var decisions = new ArrayList<Decision>(1);
        admission.compute(SNAPSHOT, (key, previous) -> {
            if (previous.closing()) {
                decisions.add(new Decision(null, FailureCode.CLOSED, "history is closing", false)); return previous;
            }
            if (entry != null && previous.entries().containsKey(task.key())) {
                if (!entry.equals(previous.entries().get(task.key())))
                    decisions.add(new Decision(null, FailureCode.OWNERSHIP_CONFLICT, "same pending key with different payload", false));
                else decisions.add(new Decision(inFlight.get(task.key()), null, null, false));
                return previous;
            }
            if (!close && (previous.pending() >= MAX_PENDING || previous.bytes() + task.bytes() + TASK_BYTES > MAX_QUEUED_BYTES)) {
                decisions.add(new Decision(null, FailureCode.CAPACITY, "history queue is full; retain pending request", false)); return previous;
            }
            var queue = new ArrayList<Task<?>>(previous.queue()); queue.add(task);
            var entries = new HashMap<>(previous.entries());
            if (entry != null) {
                entries.put(task.key(), entry);
                inFlight.putIfAbsent(task.key(), receiptPromise(task.result()));
            }
            decisions.add(new Decision(task.result(), null, null, !previous.draining()));
            return new Admission(queue, entries, previous.pending() + 1, previous.bytes() + task.bytes() + TASK_BYTES, true, close);
        });
        Decision decision = decisions.getFirst();
        if (decision.failure() != null) return failed(decision.failure(), decision.message());
        if (decision.schedule()) scheduleDrain();
        return callerView(decision.result());
    }
    @SuppressWarnings("unchecked")
    private static CompletableFuture<Receipt> receiptPromise(CompletableFuture<?> result) {
        return (CompletableFuture<Receipt>) result; // Only append tasks have a key/Entry.
    }
    @SuppressWarnings("unchecked")
    private static <T> CompletionStage<T> callerView(CompletableFuture<?> result) {
        return ((CompletableFuture<T>) result).thenApply(value -> value);
    }
    private static <T> CompletionStage<T> failed(FailureCode code, String message) {
        return CompletableFuture.failedFuture(new WallFailure(code, message));
    }
    private void scheduleDrain() {
        try { ioOwner.execute(this::drain); }
        catch (RejectedExecutionException rejected) {
            var abandoned = new ArrayList<Task<?>>();
            admission.compute(SNAPSHOT, (key, previous) -> {
                abandoned.addAll(previous.queue());
                for (var task : previous.queue()) if (task.key() != null) inFlight.remove(task.key(), task.result());
                return new Admission(List.of(), Map.of(), 0, 0, false, previous.closing());
            });
            mark(Health.DEGRADED);
            for (var task : abandoned) task.result().completeExceptionally(rejected);
        }
    }
    private void drain() {
        owners.put(SNAPSHOT, new Owner(Thread.currentThread().threadId()));
        while (true) {
            var selected = new ArrayList<Task<?>>(1);
            admission.compute(SNAPSHOT, (key, previous) -> {
                if (previous.queue().isEmpty())
                    return new Admission(List.of(), previous.entries(), previous.pending(), previous.bytes(), false, previous.closing());
                selected.add(previous.queue().getFirst());
                return new Admission(previous.queue().subList(1, previous.queue().size()), previous.entries(),
                        previous.pending(), previous.bytes(), true, previous.closing());
            });
            if (selected.isEmpty()) return;
            execute(selected.getFirst());
        }
    }
    private <T> void execute(Task<T> task) {
        T value = null; Throwable failure = null;
        try { value = task.action().run(); }
        catch (Throwable error) {
            failure = error;
            if (error instanceof IOException || error instanceof UncheckedIOException
                    || error instanceof WallFailure wall && (wall.code() == FailureCode.T0_CORRUPT || wall.code() == FailureCode.T0_MISSING))
                mark(task.failureHealth());
        }
        admission.compute(SNAPSHOT, (key, previous) -> {
            var entries = new HashMap<>(previous.entries());
            if (task.key() != null) {
                inFlight.remove(task.key(), task.result()); entries.remove(task.key());
            }
            return new Admission(previous.queue(), entries, previous.pending() - 1,
                    previous.bytes() - task.bytes() - TASK_BYTES, previous.draining(), previous.closing());
        });
        if (failure == null) task.result().complete(value); else task.result().completeExceptionally(failure);
    }

    @Override public void close() {
        admit(new Task<>(() -> {
            try { if (wal != null) hooks.force(wal); }
            finally {
                try { releaseChannels(); }
                finally {
                    opened = false; index.clear(); tailByKey.clear(); tailBySequence.clear(); tailBytes = 0;
                    mark(Health.CLOSED); ioOwner.shutdown();
                }
            }
            return null;
        }, new CompletableFuture<Void>(), 0, Health.DEGRADED, null), null, true);
    }
    private void releaseChannels() throws IOException {
        IOException failure = null;
        for (var resource : new AutoCloseable[] {wal, writerLock, lockChannel}) {
            if (resource == null) continue;
            try { resource.close(); }
            catch (Exception error) {
                IOException wrapped = error instanceof IOException io ? io : new IOException(error);
                if (failure == null) failure = wrapped; else failure.addSuppressed(wrapped);
            }
        }
        wal = null; writerLock = null; lockChannel = null;
        if (failure != null) throw failure;
    }

    private boolean isOwner() {
        Owner owner = owners.get(SNAPSHOT);
        return owner != null && owner.threadId() == Thread.currentThread().threadId();
    }
    long residentPageBytes() { requireOwner(); return index.residentBytes(); }
    long residentTailBytes() { requireOwner(); return tailBytes; }
    int residentTailCount() { requireOwner(); return tailBySequence.size(); }
    private void requireOwner() { if (!isOwner()) throw new IllegalStateException("history metric belongs to IO owner"); }
    int inFlightCount() { return inFlight.size(); }
}
