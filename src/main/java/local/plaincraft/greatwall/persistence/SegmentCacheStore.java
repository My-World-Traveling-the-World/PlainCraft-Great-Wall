package local.plaincraft.greatwall.persistence;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32C;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PrefixSummaryIndex;

/** Disposable cache only. Every filesystem method belongs on the caller's IO executor. */
public final class SegmentCacheStore {
    private static final int MIB = 1024 * 1024;
    private final Path root;
    private final WorldRecipe recipe;
    private final Path dimensionRoot;
    private final ConcurrentHashMap<String, Long> diagnostics = new ConcurrentHashMap<>();

    public SegmentCacheStore(Path root, WorldRecipe recipe) {
        this.root = root.toAbsolutePath().normalize();
        this.recipe = java.util.Objects.requireNonNull(recipe);
        this.dimensionRoot = this.root.resolve(recipe.recipeHash()).resolve(
                DeterministicHash.sha256(recipe.dimension().getBytes(StandardCharsets.UTF_8)));
    }

    public Optional<SegmentGeometry> readGeometry(SegmentKey key) throws IOException {
        checkKey(key);
        var bytes = readSlot(slot(key.index(), "geometry"), key.index(), 2 * MIB);
        if (bytes.isEmpty()) return Optional.empty();
        try {
            var value = CanonicalCodec.readGeometry(bytes.get());
            if (!value.key().equals(key)) throw new IOException("geometry key mismatch");
            return Optional.of(value);
        } catch (IOException malformed) { corrupt(); return Optional.empty(); }
    }

    public Optional<SegmentPlan> readPlan(SegmentKey key) throws IOException {
        checkKey(key);
        var bytes = readSlot(slot(key.index(), "plan"), key.index(), 4 * MIB);
        if (bytes.isEmpty()) return Optional.empty();
        try {
            var value = CanonicalCodec.readPlan(bytes.get());
            if (!value.geometry().key().equals(key)) throw new IOException("plan key mismatch");
            return Optional.of(value);
        } catch (IOException malformed) { corrupt(); return Optional.empty(); }
    }

    public void writeGeometry(SegmentGeometry value) throws IOException {
        checkKey(value.key());
        byte[] bytes = CanonicalCodec.geometry(value);
        checkHash(bytes, value.hash());
        writeSlot(slot(value.key().index(), "geometry"), value.key().index(), bytes);
    }

    public void writePlan(SegmentPlan value) throws IOException {
        checkKey(value.geometry().key());
        byte[] bytes = CanonicalCodec.plan(value);
        checkHash(bytes, value.hash());
        writeSlot(slot(value.geometry().key().index(), "plan"), value.geometry().key().index(), bytes);
    }

    public Optional<PrefixSummary> readPrefix(long index, String recipeHash) throws IOException {
        checkRecipe(recipeHash);
        if (index < 0 || index % 256 != 0) throw new IllegalArgumentException("prefix checkpoint must be 256-aligned");
        if (index == 0) return Optional.of(PrefixSummaryIndex.origin());
        if (readPrefixPayload(index).isEmpty()) return Optional.empty();
        // Start from the nearest earlier checkpoint instead of replaying from segment zero. A checkpoint is
        // a cumulative summary and is stored through the same hash-verified slot as everything else, so
        // re-deriving it from genesis proves nothing the slot's own hash does not - while costing one
        // geometry read per segment since the beginning of the wall. The replay still walks the last stretch
        // and still compares every checkpoint it crosses, so a checkpoint that disagrees with the segments
        // after it is still caught.
        long from = Math.max(0L, index - 256L);
        // The origin is not a persisted checkpoint: it is the empty summary, so a replay that starts at
        // segment zero starts from it rather than from a payload.
        var prefix = PrefixSummaryIndex.origin();
        if (from > 0) {
            var start = readPrefixPayload(from);
            if (start.isEmpty()) return Optional.empty();
            prefix = start.get();
            if (prefix.beforeSegment() != from) { corrupt(); return Optional.empty(); }
        }
        for (long segment = from; segment < index; segment++) {
            var geometry = readGeometry(new SegmentKey(recipeHash, recipe.dimension(), segment));
            if (geometry.isEmpty() || prefix.terminal().isPresent()) return Optional.empty();
            prefix = PrefixSummaryIndex.reduce(prefix, geometry.get());
            if (prefix.beforeSegment() % 256 == 0 && prefix.beforeSegment() > from) {
                var persisted = readPrefixPayload(prefix.beforeSegment());
                if (persisted.isEmpty() || !persisted.get().equals(prefix)) { corrupt(); return Optional.empty(); }
            }
        }
        return Optional.of(prefix);
    }

    public void writePrefix(PrefixSummary value, String recipeHash) throws IOException {
        checkRecipe(recipeHash);
        if (value.beforeSegment() % 256 != 0) throw new IllegalArgumentException("checkpoint must be 256-aligned");
        writeSlot(prefixSlot(value.beforeSegment()), value.beforeSegment(), CanonicalCodec.prefix(value));
    }

    public void invalidate(long fromInclusive, long toExclusive, String recipeHash) throws IOException {
        checkRecipe(recipeHash);
        if (fromInclusive < 0 || toExclusive < fromInclusive || toExclusive - fromInclusive > 4096) {
            throw new IllegalArgumentException("cache invalidation range exceeds 4096");
        }
        for (long i = fromInclusive; i < toExclusive; i++) {
            for (String kind : new String[] {"geometry", "plan"}) Files.deleteIfExists(checked(slot(i, kind)));
            Path volume = volume(i);
            if (!Files.exists(volume)) continue;
            try (var files = Files.newDirectoryStream(checked(volume), "segment_" + i + "_*.bin")) {
                for (Path path : files) Files.deleteIfExists(checked(path));
            }
        }
        Path prefixes = root.resolve(recipeHash).resolve("prefix");
        if (!Files.exists(prefixes)) return;
        try (var files = Files.newDirectoryStream(checked(prefixes))) {
            for (Path p : files) {
                String name = p.getFileName().toString();
                if (!name.startsWith("checkpoint_")) continue;
                String number = name.substring(11).split("[_.]", 2)[0];
                try {
                    if (Long.parseLong(number) > fromInclusive) Files.deleteIfExists(checked(p));
                } catch (NumberFormatException ignored) { /* Not a cache filename owned by this protocol. */ }
            }
        }
    }

    public long corruptCount() { return diagnostics.getOrDefault("corrupt", 0L); }
    public String recipeHash() { return recipe.recipeHash(); }

    private Optional<PrefixSummary> readPrefixPayload(long index) throws IOException {
        var bytes = readSlot(prefixSlot(index), index, 4096);
        if (bytes.isEmpty()) return Optional.empty();
        try {
            var value = CanonicalCodec.readPrefix(bytes.get());
            if (value.beforeSegment() != index) throw new IOException("checkpoint index mismatch");
            return Optional.of(value);
        } catch (IOException malformed) { corrupt(); return Optional.empty(); }
    }

    private Optional<byte[]> readSlot(Path slot, long index, int limit) throws IOException {
        byte[] pointer;
        try {
            if (Files.size(checked(slot)) != 64) { corrupt(); return Optional.empty(); }
            try (var input = Files.newInputStream(slot)) { pointer = input.readNBytes(65); }
        } catch (NoSuchFileException missing) { return Optional.empty(); }
        String hash = new String(pointer, StandardCharsets.US_ASCII);
        if (!hash.matches("[0-9a-f]{64}")) { corrupt(); return Optional.empty(); }
        Path file = blob(slot, index, hash);
        byte[] envelope;
        try {
            long size = Files.size(checked(file));
            if (size > limit + 65536L || size < 100) { corrupt(); return Optional.empty(); }
            try (var input = Files.newInputStream(file)) { envelope = input.readNBytes(limit + 65537); }
            if (envelope.length > limit + 65536) { corrupt(); return Optional.empty(); }
        } catch (NoSuchFileException missing) { return Optional.empty(); }
        try (var in = new DataInputStream(new ByteArrayInputStream(envelope))) {
            if (in.readInt() != 0x50475731 || in.readInt() != 1 || in.readInt() != recipe.config().algorithmVersion()
                    || in.readLong() != recipe.seed()) throw new IOException("cache header mismatch");
            int dimensionSize = in.readInt();
            byte[] dimension = recipe.dimension().getBytes(StandardCharsets.UTF_8);
            if (dimensionSize != dimension.length || !java.util.Arrays.equals(in.readNBytes(dimensionSize), dimension)
                    || !HexFormat.of().formatHex(in.readNBytes(32)).equals(recipe.recipeHash()) || in.readLong() != index) {
                throw new IOException("cache identity mismatch");
            }
            int rawLength = in.readInt(), compressedLength = in.readInt();
            long crc = in.readLong();
            String payloadHash = HexFormat.of().formatHex(in.readNBytes(32));
            if (rawLength < 0 || rawLength > limit || compressedLength < 0 || compressedLength > limit + 1024
                    || compressedLength != in.available() || !hash.equals(payloadHash)) throw new IOException("cache length/hash mismatch");
            byte[] compressed = in.readNBytes(compressedLength);
            var crc32 = new CRC32C(); crc32.update(compressed);
            if (crc32.getValue() != crc) throw new IOException("cache CRC32C mismatch");
            byte[] raw;
            try (var inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                raw = inflater.readNBytes(rawLength + 1);
            }
            if (raw.length != rawLength || !DeterministicHash.sha256(raw).equals(payloadHash)) throw new IOException("cache payload mismatch");
            return Optional.of(raw);
        } catch (IOException | IllegalArgumentException malformed) { corrupt(); return Optional.empty(); }
    }

    private void writeSlot(Path slot, long index, byte[] raw) throws IOException {
        String hash = DeterministicHash.sha256(raw);
        var compressedBuffer = new ByteArrayOutputStream();
        try (var deflate = new DeflaterOutputStream(compressedBuffer)) { deflate.write(raw); }
        byte[] compressed = compressedBuffer.toByteArray();
        var crc = new CRC32C(); crc.update(compressed);
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            out.writeInt(0x50475731); out.writeInt(1); out.writeInt(recipe.config().algorithmVersion()); out.writeLong(recipe.seed());
            byte[] dim = recipe.dimension().getBytes(StandardCharsets.UTF_8);
            out.writeInt(dim.length); out.write(dim); out.write(HexFormat.of().parseHex(recipe.recipeHash())); out.writeLong(index);
            out.writeInt(raw.length); out.writeInt(compressed.length); out.writeLong(crc.getValue());
            out.write(HexFormat.of().parseHex(hash)); out.write(compressed);
        }
        atomicWrite(blob(slot, index, hash), buffer.toByteArray());
        atomicWrite(slot, hash.getBytes(StandardCharsets.US_ASCII));
    }

    private void atomicWrite(Path destination, byte[] bytes) throws IOException {
        checked(destination);
        Files.createDirectories(destination.getParent());
        checked(destination);
        Path temp = checked(destination.resolveSibling(destination.getFileName() + "." + UUID.randomUUID() + ".tmp"));
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private Path checked(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IOException("cache path escapes cache root");
        for (Path current = normalized; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("cache path contains a symbolic link");
        }
        if (Files.exists(root) && Files.exists(normalized)
                && !normalized.toRealPath().startsWith(root.toRealPath())) throw new IOException("cache path escapes real root");
        return normalized;
    }

    private Path volume(long index) { return dimensionRoot.resolve("volume_" + index / 256); }
    private Path slot(long index, String kind) { return volume(index).resolve("segment_" + index + "." + kind); }
    private Path prefixSlot(long index) { return root.resolve(recipe.recipeHash()).resolve("prefix").resolve("checkpoint_" + index + ".pointer"); }
    private static Path blob(Path slot, long index, String hash) {
        return slot.resolveSibling((slot.getFileName().toString().startsWith("checkpoint_") ? "checkpoint_" : "segment_") + index + "_" + hash + ".bin");
    }
    private void checkRecipe(String hash) { if (!recipe.recipeHash().equals(hash)) throw new IllegalArgumentException("cache recipe mismatch"); }
    private void checkKey(SegmentKey key) {
        checkRecipe(key.recipeHash());
        if (!recipe.dimension().equals(key.dimension())) throw new IllegalArgumentException("cache dimension mismatch");
    }
    private static void checkHash(byte[] bytes, String hash) {
        if (!DeterministicHash.sha256(bytes).equals(hash)) throw new IllegalArgumentException("unverified canonical hash");
    }
    private void corrupt() { diagnostics.merge("corrupt", 1L, Math::addExact); }
}
