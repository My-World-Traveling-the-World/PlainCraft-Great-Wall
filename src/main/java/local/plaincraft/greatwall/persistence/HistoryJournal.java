package local.plaincraft.greatwall.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;

/**
 * Append-only history journal. One record is one line of canonical text so a
 * torn tail is visible rather than silently absorbed. A record is committed
 * only after its force(true) returns.
 */
public final class HistoryJournal implements DurableLog {
    private static final int MAX_RECORD_BYTES = 1 << 20;
    private static final long MAX_BLOB_BYTES = 256L << 20;

    private final Path root;
    private final Executor ioOwner;
    private final AtomicLong sequence = new AtomicLong();
    private final List<Record> records = new ArrayList<>();
    private final ConcurrentHashMap<String, Long> bySequence = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> byKey = new ConcurrentHashMap<>();

    public HistoryJournal(Path root, Executor ioOwner) {
        this.root = Objects.requireNonNull(root, "root");
        this.ioOwner = Objects.requireNonNull(ioOwner, "ioOwner");
    }

    private String journalPath() {
        return "journal/history.log";
    }

    /** Opens the verified chain. A torn tail is preserved, never silently dropped. */
    public CompletionStage<HistoryTypes.Recovery> open(HistoryTypes.Head expected) {
        Objects.requireNonNull(expected, "expected");
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path file = AtomicFiles.resolveUnder(root, journalPath());
                if (!Files.isRegularFile(file)) {
                    return new HistoryTypes.Recovery(expected, List.of(), false);
                }
                List<Record> loaded = new ArrayList<>();
                long last = 0L;
                boolean archivedPartialTail = false;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    Record parsed = decode(line);
                    if (parsed == null) {
                        archivedPartialTail = true;
                        break;
                    }
                    if (parsed.sequence() != last + 1) {
                        throw new WallFailure(FailureCode.T0_CORRUPT,
                                "journal sequence gap at " + parsed.sequence());
                    }
                    last = parsed.sequence();
                    loaded.add(parsed);
                }
                synchronized (this) {
                    records.clear();
                    records.addAll(loaded);
                    bySequence.clear();
                    byKey.clear();
                    for (Record record : records) {
                        bySequence.put(Long.toString(record.sequence()), record.sequence());
                        byKey.put(record.entry().type() + '\u0000' + record.entry().eventKey(), record.sequence());
                    }
                    sequence.set(last);
                }
                List<Record> tail = loaded.stream().skip(expected.sequence()).toList();
                return new HistoryTypes.Recovery(expected, tail, archivedPartialTail);
            } catch (IOException failure) {
                throw new WallFailure(FailureCode.IO_FAILED, "cannot open the history journal", failure);
            }
        }, ioOwner);
    }

    @Override public CompletionStage<Receipt> append(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        return CompletableFuture.supplyAsync(() -> {
            long next = sequence.incrementAndGet();
            String payloadHash = payloadHash(entry);
            Record record = new Record(next, entry, payloadHash);
            String line = encode(record);
            if (line.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES) {
                sequence.decrementAndGet();
                throw new WallFailure(FailureCode.CAPACITY, "journal record exceeds the size limit");
            }
            // Idempotence: an identical event key must never create a second record.
            Long existing = byKey.get(entry.type() + '\u0000' + entry.eventKey());
            if (existing != null) {
                Record prior = records.stream()
                        .filter(candidate -> candidate.sequence() == existing)
                        .findFirst()
                        .orElseThrow();
                sequence.decrementAndGet();
                return new Receipt(prior.sequence(), prior.entry().eventKey(), prior.payloadHash());
            }
            try {
                Path file = AtomicFiles.resolveUnder(root, journalPath());
                Files.createDirectories(file.getParent());
                appendForced(file, line);
            } catch (IOException failure) {
                sequence.decrementAndGet();
                throw new WallFailure(FailureCode.IO_FAILED, "journal append failed", failure);
            }
            synchronized (this) {
                records.add(record);
                bySequence.put(Long.toString(next), next);
                byKey.put(entry.type() + '\u0000' + entry.eventKey(), next);
            }
            return new Receipt(next, entry.eventKey(), payloadHash);
        }, ioOwner);
    }

    @Override public CompletionStage<Optional<Record>> lookup(String type, String eventKey) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(eventKey, "eventKey");
        return CompletableFuture.supplyAsync(() -> {
            Long found = byKey.get(type + '\u0000' + eventKey);
            if (found == null) return Optional.empty();
            synchronized (this) {
                return records.stream().filter(record -> record.sequence() == found).findFirst();
            }
        }, ioOwner);
    }

    @Override public CompletionStage<HistoryTypes.BlobRef> storeBlob(String kind, byte[] bytes) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_BLOB_BYTES) {
            throw new WallFailure(FailureCode.CAPACITY, "blob exceeds the size limit");
        }
        return CompletableFuture.supplyAsync(() -> {
            String hash = AtomicFiles.sha256(bytes);
            String relative = "blobs/" + kind + "/" + hash.substring(0, 2) + "/" + hash + ".bin";
            try {
                AtomicFiles.writeImmutable(root, relative, bytes);
            } catch (IOException failure) {
                throw new WallFailure(FailureCode.IO_FAILED, "blob write failed", failure);
            }
            return new HistoryTypes.BlobRef(relative, bytes.length, hash);
        }, ioOwner);
    }

    @Override public CompletionStage<byte[]> loadBlob(HistoryTypes.BlobRef ref) {
        Objects.requireNonNull(ref, "ref");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return AtomicFiles.readChecked(root, ref.relativePath(),
                        (int) Math.min(Integer.MAX_VALUE, ref.length()), ref.sha256());
            } catch (IOException failure) {
                throw new WallFailure(FailureCode.IO_FAILED, "blob read failed", failure);
            }
        }, ioOwner);
    }

    @Override public CompletionStage<Void> flush() {
        return CompletableFuture.runAsync(() -> {
            try {
                Path file = AtomicFiles.resolveUnder(root, journalPath());
                if (!Files.isRegularFile(file)) return;
                try (var channel = java.nio.channels.FileChannel.open(file,
                        java.nio.file.StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
            } catch (IOException failure) {
                throw new WallFailure(FailureCode.IO_FAILED, "journal flush failed", failure);
            }
        }, ioOwner);
    }

    @Override public List<Record> replay(String type, long afterSequence, int limit) {
        Objects.requireNonNull(type, "type");
        if (limit <= 0) return List.of();
        synchronized (this) {
            List<Record> result = new ArrayList<>();
            for (Record record : records) {
                if (record.sequence() <= afterSequence) continue;
                if (!record.entry().type().equals(type)) continue;
                result.add(record);
                if (result.size() >= limit) break;
            }
            return List.copyOf(result);
        }
    }

    public long sequence() { return sequence.get(); }

    public Path root() { return root; }

    @Override public void close() {
        try {
            flush().toCompletableFuture().join();
        } catch (Throwable ignored) {
            // Closing must not mask an earlier operator-visible error.
        }
    }

    private static void appendForced(Path file, String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try (var channel = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static String encode(Record record) {
        StringBuilder text = new StringBuilder();
        text.append("seq=").append(record.sequence())
                .append('\t').append("type=").append(record.entry().type())
                .append('\t').append("key=").append(escape(record.entry().eventKey()))
                .append('\t').append("schema=").append(record.entry().schema())
                .append('\t').append("hash=").append(record.payloadHash())
                .append('\t').append("fields=");
        List<Map.Entry<String, String>> sorted = new ArrayList<>(record.entry().fields().entrySet());
        sorted.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, String> field : sorted) {
            text.append(escape(field.getKey())).append('=').append(escape(field.getValue())).append(';');
        }
        return text.toString();
    }

    private static Record decode(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length != 6) return null;
        Long seq = number(parts[0], "seq=");
        String type = value(parts[1], "type=");
        String key = value(parts[2], "key=");
        Long schema = number(parts[3], "schema=");
        String hash = value(parts[4], "hash=");
        if (seq == null || type == null || key == null || schema == null || hash == null) return null;
        if (schema != 1L) return null;
        String fieldsText = value(parts[5], "fields=");
        if (fieldsText == null) return null;
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : fieldsText.split(";", -1)) {
            if (pair.isEmpty()) continue;
            int split = pair.indexOf('=');
            if (split < 0) return null;
            fields.put(unescape(pair.substring(0, split)), unescape(pair.substring(split + 1)));
        }
        try {
            return new Record(seq, new Entry(unescape(key), type, schema.intValue(), fields), hash);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static Long number(String part, String prefix) {
        String raw = value(part, prefix);
        if (raw == null) return null;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static String value(String part, String prefix) {
        return part.startsWith(prefix) ? part.substring(prefix.length()) : null;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace(";", "\\s")
                .replace("=", "\\e").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) { out.append(c); continue; }
            char next = value.charAt(++i);
            switch (next) {
                case '\\' -> out.append('\\');
                case 's' -> out.append(';');
                case 'e' -> out.append('=');
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    private static String payloadHash(Entry entry) {
        StringBuilder text = new StringBuilder(entry.type()).append('|').append(entry.eventKey());
        List<Map.Entry<String, String>> sorted = new ArrayList<>(entry.fields().entrySet());
        sorted.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, String> field : sorted) {
            text.append('|').append(field.getKey()).append('=').append(field.getValue());
        }
        return DeterministicHash.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String recordHashForTests(Entry entry) { return payloadHash(entry); }

    static UUID syntheticId() { return UUID.randomUUID(); }
}
