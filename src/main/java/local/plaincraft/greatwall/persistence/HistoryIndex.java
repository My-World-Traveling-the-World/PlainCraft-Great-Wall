package local.plaincraft.greatwall.persistence;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;
import local.plaincraft.greatwall.persistence.HistoryTypes.*;

/** Two immutable B+ trees. Traversal retains references, not a directory or ancestor pages. */
final class HistoryIndex {
    static final long CACHE_BYTES = 8L << 20;
    static final int MAX_HEIGHT = 16;
    static final Comparator<String> UTF8 = HistoryCodec.UTF8;
    private final Path root;
    private final ConcurrentHashMap<BlobRef, IndexPage> pages = new ConcurrentHashMap<>();
    private final LinkedHashMap<BlobRef, Long> lru = new LinkedHashMap<>(16, .75f, true);
    private final Map<BlobRef, Integer> sorted = new HashMap<>();
    private long resident;

    HistoryIndex(Path root) { this.root = root; }
    static String key(DurableLog.Record record, boolean byKey) {
        return byKey ? record.entry().type() + '\0' + record.entry().eventKey() : sequenceKey(record.sequence());
    }
    static String sequenceKey(long sequence) {
        String value = Long.toString(sequence);
        return "0".repeat(19 - value.length()) + value;
    }

    Optional<DurableLog.Record> lookup(BlobRef rootRef, String key, boolean byKey) throws IOException {
        if (rootRef == null) return Optional.empty();
        BlobRef ref = rootRef; IndexBranch expected = null; int expectedLevel = -1;
        for (int depth = 0; depth < MAX_HEIGHT; depth++) {
            IndexPage page = checked(ref, byKey, expected, expectedLevel);
            if (page.level() == 0) {
                int low = 0, high = page.records().size() - 1;
                while (low <= high) {
                    int mid = (low + high) >>> 1;
                    var record = page.records().get(mid); int cmp = UTF8.compare(key(record, byKey), key);
                    if (cmp == 0) return Optional.of(record);
                    if (cmp < 0) low = mid + 1; else high = mid - 1;
                }
                return Optional.empty();
            }
            int low = 0, high = page.branches().size() - 1; IndexBranch child = null;
            while (low <= high) {
                int mid = (low + high) >>> 1; var branch = page.branches().get(mid);
                if (UTF8.compare(key, branch.firstKey()) < 0) high = mid - 1;
                else if (UTF8.compare(key, branch.lastKey()) > 0) low = mid + 1;
                else { child = branch; break; }
            }
            if (child == null) return Optional.empty();
            expected = child; expectedLevel = page.level() - 1; ref = child.child();
        }
        throw new IOException("history index exceeds height limit");
    }

    Iterator<DurableLog.Record> iterator(BlobRef ref, boolean byKey) { return iterator(ref, byKey, null); }
    Iterator<DurableLog.Record> iterator(BlobRef ref, boolean byKey, String afterKey) {
        return new Iterator<>() {
            private record Cursor(BlobRef ref, int offset, IndexBranch expected, int level) {}
            private final ArrayDeque<Cursor> stack = new ArrayDeque<>();
            private IndexPage leaf;
            private int offset;
            private boolean initialized;
            private DurableLog.Record next;
            private void advance() {
                try {
                    if (!initialized) { initialized = true; if (ref != null) stack.push(new Cursor(ref, 0, null, -1)); }
                    while (next == null) {
                        if (leaf != null && offset < leaf.records().size()) {
                            var candidate = leaf.records().get(offset++);
                            if (afterKey == null || UTF8.compare(key(candidate, byKey), afterKey) > 0) next = candidate;
                            continue;
                        }
                        leaf = null;
                        if (stack.isEmpty()) break;
                        var cursor = stack.pop();
                        var page = checked(cursor.ref(), byKey, cursor.expected(), cursor.level());
                        if (page.level() == 0) { leaf = page; offset = 0; continue; }
                        int i = cursor.offset();
                        while (i < page.branches().size() && afterKey != null
                                && UTF8.compare(page.branches().get(i).lastKey(), afterKey) <= 0) i++;
                        if (i == page.branches().size()) continue;
                        var child = page.branches().get(i);
                        if (i + 1 < page.branches().size())
                            stack.push(new Cursor(cursor.ref(), i + 1, cursor.expected(), cursor.level()));
                        stack.push(new Cursor(child.child(), 0, child, page.level() - 1));
                        if (stack.size() > MAX_HEIGHT) throw new IOException("invalid history index height");
                    }
                } catch (IOException error) { throw new UncheckedIOException(error); }
            }
            public boolean hasNext() { advance(); return next != null; }
            public DurableLog.Record next() {
                advance(); if (next == null) throw new NoSuchElementException();
                var value = next; next = null; return value;
            }
        };
    }

    BlobRef build(Iterator<DurableLog.Record> source, boolean byKey) throws IOException {
        var builder = new Builder(byKey);
        var leaf = new ArrayList<DurableLog.Record>(); int size = 24;
        String previous = null;
        while (source.hasNext()) {
            var record = source.next(); String key = key(record, byKey);
            if (previous != null && UTF8.compare(previous, key) >= 0) throw new IOException("duplicate/unsorted history index key");
            int bytes = HistoryCodec.record(record).length + 4;
            if (!leaf.isEmpty() && (leaf.size() == 1024 || size + bytes > HistoryCodec.PAGE_LIMIT)) {
                builder.leaf(leaf); leaf = new ArrayList<>(); size = 24;
            }
            leaf.add(record); size += bytes; previous = key;
        }
        if (!leaf.isEmpty()) builder.leaf(leaf);
        return builder.finish();
    }

    private final class Builder {
        private final boolean byKey;
        private final List<ArrayList<IndexBranch>> levels = new ArrayList<>();
        private final List<Integer> sizes = new ArrayList<>();
        Builder(boolean byKey) { this.byKey = byKey; }
        void leaf(List<DurableLog.Record> records) throws IOException {
            var ref = write(new IndexPage(0, List.of(), records, "0".repeat(64)));
            push(0, new IndexBranch(key(records.getFirst(), byKey), key(records.getLast(), byKey), ref));
        }
        void push(int level, IndexBranch branch) throws IOException {
            if (level >= MAX_HEIGHT) throw new WallFailure(FailureCode.CAPACITY, "history tree exceeds height 16");
            while (levels.size() <= level) { levels.add(new ArrayList<>()); sizes.add(24); }
            int bytes = branchBytes(branch);
            var children = levels.get(level);
            if (!children.isEmpty() && (children.size() == 128 || sizes.get(level) + bytes > HistoryCodec.PAGE_LIMIT))
                promote(level);
            children.add(branch); sizes.set(level, sizes.get(level) + bytes);
        }
        void promote(int level) throws IOException {
            var children = levels.get(level);
            if (level + 1 >= MAX_HEIGHT) throw new WallFailure(FailureCode.CAPACITY, "history tree exceeds height 16");
            var parent = write(new IndexPage(level + 1, children, List.of(), "0".repeat(64)));
            var first = children.getFirst().firstKey(); var last = children.getLast().lastKey();
            children.clear(); sizes.set(level, 24);
            push(level + 1, new IndexBranch(first, last, parent));
        }
        BlobRef finish() throws IOException {
            if (levels.isEmpty()) return null;
            for (int level = 0; level < levels.size(); level++) {
                var children = levels.get(level);
                if (children.isEmpty()) continue;
                if (level == levels.size() - 1 && children.size() == 1) return children.getFirst().child();
                promote(level);
            }
            throw new IOException("empty index root");
        }
    }

    private static void branch(DataOutputStream out, IndexBranch branch) throws IOException {
        HistoryCodec.text(out, branch.firstKey(), HistoryCodec.KEY_LIMIT);
        HistoryCodec.text(out, branch.lastKey(), HistoryCodec.KEY_LIMIT);
        HistoryCodec.ref(out, branch.child());
    }
    private static int branchBytes(IndexBranch branch) throws IOException {
        return HistoryCodec.bytes(out -> branch(out, branch)).length;
    }
    private BlobRef write(IndexPage page) throws IOException {
        byte[] bytes = HistoryCodec.frame(HistoryCodec.bytes(out -> {
            out.writeInt(1); out.writeInt(page.level());
            if (page.level() == 0) {
                out.writeInt(page.records().size());
                for (var record : page.records()) {
                    byte[] encoded = HistoryCodec.record(record); out.writeInt(encoded.length); out.write(encoded);
                }
            } else {
                out.writeInt(page.branches().size());
                for (var branch : page.branches()) branch(out, branch);
            }
        }));
        String hash = AtomicFiles.sha256(bytes), path = "history/index/" + hash.substring(0, 2) + "/" + hash + ".bin";
        HistoryCodec.immutable(root, path, bytes);
        var ref = new BlobRef(path, bytes.length, hash);
        read(ref);
        return ref;
    }

    private IndexPage checked(BlobRef ref, boolean byKey, IndexBranch expected, int level) throws IOException {
        IndexPage page = read(ref);
        if (level >= 0 && page.level() != level) throw new IOException("history index child level mismatch");
        String first, last;
        if (page.level() == 0) {
            int flag = byKey ? 1 : 2;
            if ((sorted.getOrDefault(ref, 0) & flag) == 0) {
                String previous = null;
                for (var record : page.records()) {
                    String key = key(record, byKey);
                    if (previous != null && UTF8.compare(previous, key) >= 0) throw new IOException("unsorted history leaf");
                    previous = key;
                }
                if (pages.containsKey(ref)) sorted.merge(ref, flag, (a, b) -> a | b);
            }
            first = key(page.records().getFirst(), byKey); last = key(page.records().getLast(), byKey);
        } else { first = page.branches().getFirst().firstKey(); last = page.branches().getLast().lastKey(); }
        if (expected != null && (!expected.firstKey().equals(first) || !expected.lastKey().equals(last)))
            throw new IOException("history index child range mismatch");
        return page;
    }
    private IndexPage read(BlobRef ref) throws IOException {
        if (!ref.relativePath().startsWith("history/index/") || ref.length() > HistoryCodec.PAGE_LIMIT)
            throw new IOException("invalid history page reference");
        var existing = pages.get(ref);
        if (existing != null) { lru.get(ref); return existing; }
        byte[] bytes = HistoryCodec.read(root, ref.relativePath(), HistoryCodec.PAGE_LIMIT, ref.sha256());
        if (bytes.length != ref.length()) throw new IOException("history page length mismatch");
        IndexPage page;
        try (var in = HistoryCodec.body(bytes)) {
            if (in.readInt() != 1) throw new IOException("new index schema");
            int level = in.readInt(), count = in.readInt();
            if (level < 0 || level >= MAX_HEIGHT || count < 1 || count > (level == 0 ? 1024 : 128))
                throw new IOException("invalid index page");
            var records = new ArrayList<DurableLog.Record>(); var branches = new ArrayList<IndexBranch>();
            for (int i = 0; i < count; i++) {
                if (level == 0) {
                    int length = in.readInt();
                    if (length < 12 || length > HistoryCodec.RECORD_LIMIT) throw new IOException("invalid record length");
                    records.add(HistoryCodec.record(in.readNBytes(length)));
                } else {
                    var branch = new IndexBranch(HistoryCodec.text(in, HistoryCodec.KEY_LIMIT),
                            HistoryCodec.text(in, HistoryCodec.KEY_LIMIT), HistoryCodec.ref(in));
                    if (UTF8.compare(branch.firstKey(), branch.lastKey()) > 0
                            || (!branches.isEmpty() && UTF8.compare(branches.getLast().lastKey(), branch.firstKey()) >= 0))
                        throw new IOException("overlapping history branches");
                    branches.add(branch);
                }
            }
            HistoryCodec.end(in); page = new IndexPage(level, branches, records, ref.sha256());
        }
        long retained = 512 + bytes.length;
        for (var record : page.records()) retained += HistoryCodec.retained(record);
        for (var branch : page.branches()) retained += 256 + HistoryCodec.stringBytes(branch.firstKey())
                + HistoryCodec.stringBytes(branch.lastKey()) + HistoryCodec.stringBytes(branch.child().relativePath());
        while (resident + retained > CACHE_BYTES && !lru.isEmpty()) {
            var eldest = lru.entrySet().iterator().next(); resident -= eldest.getValue();
            pages.remove(eldest.getKey()); sorted.remove(eldest.getKey()); lru.remove(eldest.getKey());
        }
        if (retained <= CACHE_BYTES) { pages.put(ref, page); lru.put(ref, retained); resident += retained; }
        return page;
    }
    void clear() { pages.clear(); lru.clear(); sorted.clear(); resident = 0; }
    long residentBytes() { return resident; }
}
