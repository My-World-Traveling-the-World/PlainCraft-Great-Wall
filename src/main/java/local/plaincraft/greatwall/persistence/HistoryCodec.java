package local.plaincraft.greatwall.persistence;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.persistence.HistoryTypes.*;

/** Canonical bounded frames. A complete bad frame is never a recoverable torn tail. */
final class HistoryCodec {
    static final int PAGE_LIMIT = 1 << 20, ENTRY_LIMIT = 64 << 10, TEXT_LIMIT = 4096;
    static final int RECORD_LIMIT = ENTRY_LIMIT + 20, KEY_LIMIT = TEXT_LIMIT * 2 + 1;
    static final Comparator<String> UTF8 = (a, b) -> Arrays.compareUnsigned(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    private HistoryCodec() {}
    interface Encoder { void write(DataOutputStream out) throws IOException; }

    static byte[] bytes(Encoder encoder) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { encoder.write(out); }
        return bytes.toByteArray();
    }

    static void text(DataOutputStream out, String value) throws IOException { text(out, value, TEXT_LIMIT); }
    static String text(DataInputStream in) throws IOException { return text(in, TEXT_LIMIT); }
    static void text(DataOutputStream out, String value, int limit) throws IOException {
        if (value.length() > limit) throw new IOException("oversized history string");
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > limit || !value.equals(new String(encoded, StandardCharsets.UTF_8)))
            throw new IOException("invalid or oversized history string");
        out.writeInt(encoded.length); out.write(encoded);
    }
    static String text(DataInputStream in, int limit) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > limit) throw new IOException("invalid history string length");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("truncated history string");
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) throw new IOException("invalid UTF-8");
        return value;
    }

    static void identity(String type, String key) throws IOException {
        if (type.isEmpty() || key.isEmpty() || type.indexOf('\0') >= 0 || key.indexOf('\0') >= 0)
            throw new IOException("empty/NUL history identity");
        try { Kind.valueOf(type); } catch (IllegalArgumentException invalid) {
            throw new IOException("unknown history event type", invalid);
        }
        bytes(out -> { text(out, type); text(out, key); });
    }

    static byte[] entry(DurableLog.Entry entry) throws IOException {
        identity(entry.type(), entry.eventKey());
        long size = 16L + entry.eventKey().getBytes(StandardCharsets.UTF_8).length
                + entry.type().getBytes(StandardCharsets.UTF_8).length;
        if (entry.fields().size() > ENTRY_LIMIT / 8) throw new IOException("too many history fields");
        for (var field : entry.fields().entrySet()) {
            if (field.getKey().length() > TEXT_LIMIT || field.getValue().length() > TEXT_LIMIT)
                throw new IOException("oversized history field");
            size += 8L + field.getKey().getBytes(StandardCharsets.UTF_8).length
                    + field.getValue().getBytes(StandardCharsets.UTF_8).length;
            if (size > ENTRY_LIMIT) throw new IOException("history entry exceeds 64KiB");
        }
        byte[] encoded = bytes(out -> { out.writeInt(entry.schema()); fields(out, entry); });
        if (encoded.length > ENTRY_LIMIT) throw new IOException("history entry exceeds 64KiB");
        return encoded;
    }
    private static void fields(DataOutputStream out, DurableLog.Entry entry) throws IOException {
        text(out, entry.eventKey()); text(out, entry.type());
        var fields = new TreeMap<String, String>(UTF8); fields.putAll(entry.fields());
        out.writeInt(fields.size());
        for (var field : fields.entrySet()) { text(out, field.getKey()); text(out, field.getValue()); }
    }
    private static DurableLog.Entry fields(DataInputStream in, int schema) throws IOException {
        String key = text(in), type = text(in); identity(type, key);
        int count = in.readInt();
        if (count < 0 || count > ENTRY_LIMIT / 8) throw new IOException("invalid field count");
        var fields = new LinkedHashMap<String, String>(); String previous = null;
        for (int i = 0; i < count; i++) {
            String name = text(in), value = text(in);
            if (previous != null && UTF8.compare(previous, name) >= 0)
                throw new IOException("duplicate/unsorted history field");
            fields.put(name, value); previous = name;
        }
        try { return new DurableLog.Entry(key, type, schema, fields); }
        catch (IllegalArgumentException invalid) { throw new IOException("invalid history entry", invalid); }
    }

    static byte[] record(DurableLog.Record record) throws IOException {
        byte[] entry = entry(record.entry());
        if (!AtomicFiles.sha256(entry).equals(record.payloadHash())) throw new IOException("invalid payload hash");
        return frame(bytes(out -> {
            out.writeInt(1); out.writeLong(record.sequence()); fields(out, record.entry());
        }));
    }
    static DurableLog.Record record(byte[] frame) throws IOException {
        if (frame.length > RECORD_LIMIT) throw new IOException("oversized history record");
        try (var in = body(frame)) {
            int schema = in.readInt(); long sequence = in.readLong();
            if (schema != 1 || sequence <= 0) throw new IOException("invalid record schema/sequence");
            var entry = fields(in, schema); end(in);
            return new DurableLog.Record(sequence, entry, AtomicFiles.sha256(entry(entry)));
        }
    }

    static byte[] frame(byte[] body) throws IOException {
        if (body.length + 12 > PAGE_LIMIT) throw new IOException("history frame exceeds 1MiB");
        var crc = new CRC32C(); crc.update(body);
        return bytes(out -> { out.writeInt(body.length); out.write(body); out.writeLong(crc.getValue()); });
    }
    static DataInputStream body(byte[] frame) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(frame))) {
            int length = in.readInt();
            if (length < 0 || length > PAGE_LIMIT - 12 || frame.length != length + 12)
                throw new IOException("bad frame length");
            byte[] body = in.readNBytes(length); long expected = in.readLong();
            var crc = new CRC32C(); crc.update(body);
            if (crc.getValue() != expected) throw new IOException("complete history frame CRC mismatch");
            return new DataInputStream(new ByteArrayInputStream(body));
        }
    }

    static void ref(DataOutputStream out, BlobRef ref) throws IOException {
        text(out, ref.relativePath()); out.writeLong(ref.length()); text(out, ref.sha256());
    }
    static BlobRef ref(DataInputStream in) throws IOException {
        try { return new BlobRef(text(in), in.readLong(), text(in)); }
        catch (IllegalArgumentException invalid) { throw new IOException("invalid history blob reference", invalid); }
    }
    static void end(DataInputStream in) throws IOException {
        if (in.available() != 0) throw new IOException("trailing history bytes");
    }
    static byte[] head(Head head) throws IOException {
        if (head.beaconCount() > head.sequence()) throw new IOException("invalid beacon count");
        return frame(bytes(out -> {
            out.writeInt(1); out.writeLong(head.world().getMostSignificantBits()); out.writeLong(head.world().getLeastSignificantBits());
            out.writeLong(head.generation()); out.writeLong(head.sequence()); out.writeLong(head.beaconCount());
            out.writeInt(head.roots().size());
            if (head.sequence() > 0) { ref(out, head.roots().get("by_key")); ref(out, head.roots().get("by_sequence")); }
        }));
    }
    static Head head(byte[] bytes) throws IOException {
        try (var in = body(bytes)) {
            if (in.readInt() != 1) throw new IOException("new history schema");
            var world = new UUID(in.readLong(), in.readLong());
            long generation = in.readLong(), sequence = in.readLong(), count = in.readLong();
            int roots = in.readInt();
            if (roots != (sequence == 0 ? 0 : 2) || count > sequence) throw new IOException("invalid history roots/count");
            var refs = roots == 0 ? Map.<String, BlobRef>of() : Map.of("by_key", ref(in), "by_sequence", ref(in));
            end(in);
            try { return new Head(world, generation, sequence, count, refs, AtomicFiles.sha256(bytes)); }
            catch (IllegalArgumentException invalid) { throw new IOException("invalid history head", invalid); }
        }
    }

    static Path path(Path root, String relative) throws IOException {
        if (!relative.startsWith("history/") || relative.contains("\\") || relative.contains(":"))
            throw new IOException("history path is not canonical/confined");
        for (String part : relative.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("invalid history path segment");
        Path result = AtomicFiles.resolveUnder(root, relative);
        for (Path cursor = result; cursor != null; cursor = cursor.getParent())
            if (Files.isSymbolicLink(cursor)) throw new IOException("history path contains a symlink");
        return result;
    }
    static byte[] read(Path root, String relative, int limit, String hash) throws IOException {
        Path path = path(root, relative);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing history file: " + relative);
        byte[] bytes;
        try (var in = Files.newInputStream(path)) { bytes = in.readNBytes(limit + 1); }
        if (bytes.length > limit) throw new IOException("oversized history file: " + relative);
        if (hash != null && !AtomicFiles.sha256(bytes).equals(hash)) throw new IOException("history SHA mismatch: " + relative);
        return bytes;
    }
    static void immutable(Path root, String relative, byte[] bytes) throws IOException {
        Path path = path(root, relative);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Arrays.equals(bytes, read(root, relative, bytes.length, null)))
                throw new IOException("immutable history file differs: " + relative);
            return;
        }
        try { AtomicFiles.writeImmutable(root, relative, bytes); }
        catch (WallFailure failure) { throw new IOException("immutable history write failed", failure); }
    }
    static long retained(DurableLog.Record record) {
        long bytes = 256 + stringBytes(record.entry().type()) + stringBytes(record.entry().eventKey()) + stringBytes(record.payloadHash());
        for (var field : record.entry().fields().entrySet())
            bytes += 160 + stringBytes(field.getKey()) + stringBytes(field.getValue());
        return bytes;
    }
    static long stringBytes(String value) { return 96L + 2L * value.length(); }
}
