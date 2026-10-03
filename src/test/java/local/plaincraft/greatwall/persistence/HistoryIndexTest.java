package local.plaincraft.greatwall.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import local.plaincraft.greatwall.persistence.DurableLog.Entry;
import local.plaincraft.greatwall.persistence.DurableLog.Record;
import local.plaincraft.greatwall.persistence.HistoryTypes.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryIndexTest {
    @TempDir Path root;
    private static Record record(long sequence, String key) throws Exception {
        var entry = new Entry(key, Kind.GATE_ACK.name(), 1, Map.of());
        return new Record(sequence, entry, AtomicFiles.sha256(HistoryCodec.entry(entry)));
    }
    private BlobRef page(int level, List<Record> records, List<IndexBranch> branches) throws Exception {
        byte[] bytes = HistoryCodec.frame(HistoryCodec.bytes(out -> {
            out.writeInt(1); out.writeInt(level);
            out.writeInt(level == 0 ? records.size() : branches.size());
            if (level == 0) for (var record : records) {
                byte[] encoded = HistoryCodec.record(record); out.writeInt(encoded.length); out.write(encoded);
            } else for (var branch : branches) {
                HistoryCodec.text(out, branch.firstKey(), HistoryCodec.KEY_LIMIT);
                HistoryCodec.text(out, branch.lastKey(), HistoryCodec.KEY_LIMIT);
                HistoryCodec.ref(out, branch.child());
            }
        }));
        String hash = AtomicFiles.sha256(bytes), path = "history/index/test/" + hash + ".bin";
        HistoryCodec.immutable(root, path, bytes);
        return new BlobRef(path, bytes.length, hash);
    }

    @Test void emptyTreeHasNoFileAndByteSortedKeysStayExact() throws Exception {
        var index = new HistoryIndex(root);
        assertNull(index.build(Collections.emptyIterator(), true));
        assertTrue(index.lookup(null, "absent", true).isEmpty());
        Record first = record(1, "\uE000"), second = record(2, "\uD800\uDC00");
        BlobRef ref = index.build(List.of(first, second).iterator(), true);
        assertEquals(Optional.of(first), index.lookup(ref, HistoryIndex.key(first, true), true));
        assertEquals(Optional.of(second), index.lookup(ref, HistoryIndex.key(second, true), true));
        assertTrue(index.lookup(ref, "GATE_ACK\0missing", true).isEmpty());
    }

    @Test void unsortedLeavesOverlapsFalseRangesAndWrongLevelsAreCorrupt() throws Exception {
        Record first = record(1, "a"), second = record(2, "z");
        String a = HistoryIndex.key(first, true), z = HistoryIndex.key(second, true);
        BlobRef leaf = page(0, List.of(first, second), List.of());
        assertThrows(IOException.class, () -> new HistoryIndex(root).lookup(
                page(0, List.of(second, first), List.of()), a, true));
        assertThrows(IOException.class, () -> new HistoryIndex(root).lookup(
                page(1, List.of(), List.of(new IndexBranch(a, z, leaf), new IndexBranch(z, z, leaf))), a, true));
        assertThrows(IOException.class, () -> new HistoryIndex(root).lookup(
                page(1, List.of(), List.of(new IndexBranch(a, a, leaf))), a, true));
        assertThrows(IOException.class, () -> new HistoryIndex(root).lookup(
                page(2, List.of(), List.of(new IndexBranch(a, z, leaf))), a, true));
    }

    @Test void missingCompleteBadHashAndWrongLengthAreNotAbsence() throws Exception {
        Record record = record(1, "a"); BlobRef ref = page(0, List.of(record), List.of());
        assertThrows(IOException.class, () -> new HistoryIndex(root).lookup(
                new BlobRef(ref.relativePath(), ref.length() + 1, ref.sha256()), HistoryIndex.key(record, true), true));
        assertThrows(IOException.class, () -> new HistoryIndex(root).lookup(
                new BlobRef(ref.relativePath(), ref.length(), "0".repeat(64)), HistoryIndex.key(record, true), true));
        Files.delete(root.resolve(ref.relativePath()));
        assertThrows(IOException.class, () -> new HistoryIndex(root).lookup(ref, HistoryIndex.key(record, true), true));
    }

    @Test void longKeysSplitInternalPagesByBytesAndSeekSkipsEarlierLeaves() throws Exception {
        var records = new ArrayList<Record>();
        for (int i = 0; i < 520; i++) records.add(record(i + 1, String.format(Locale.ROOT, "%04d", i) + "x".repeat(4088)));
        var index = new HistoryIndex(root); BlobRef byKey = index.build(records.iterator(), true);
        assertEquals(Optional.of(records.getLast()), index.lookup(byKey, HistoryIndex.key(records.getLast(), true), true));
        BlobRef bySequence = index.build(records.iterator(), false);
        var cursor = index.iterator(bySequence, false, HistoryIndex.sequenceKey(517));
        for (int i = 518; i <= 520; i++) assertEquals(i, cursor.next().sequence());
        assertFalse(cursor.hasNext());
        assertTrue(index.residentBytes() <= 8L << 20);
        try (var files = Files.walk(root.resolve("history/index"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) assertTrue(Files.size(file) <= 1 << 20);
        }
    }

    @Test void pathsCannotEscapeOrUseAliases() throws Exception {
        for (String path : List.of("other/a", "history/../a", "history//a", "history/./a", "history\\a", "history/C:/a"))
            assertThrows(IOException.class, () -> HistoryCodec.path(root, path));
    }
}
