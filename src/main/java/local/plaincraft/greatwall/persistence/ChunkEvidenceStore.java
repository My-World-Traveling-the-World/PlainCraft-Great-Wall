package local.plaincraft.greatwall.persistence;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import local.plaincraft.greatwall.diagnostics.CodecIO;
import local.plaincraft.greatwall.diagnostics.CodecMemory;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.GroundExitDecision;
import local.plaincraft.greatwall.terrain.TerrainTile;

/** Authoritative evidence, not a regenerable cache. Every operation is synchronous on its IO owner.
 * Call inside CompiledAllocation.run/retainAll. Reads retain only their returned value; writes/verification
 * release all codec charges. Scope-free fixture calls preserve the same protocol and validation. */
public final class ChunkEvidenceStore {
    private static final int MAGIC = 0x50475430;
    private static final int LIMIT = 2 * 1024 * 1024;
    private static final int RECORD_LIMIT = 8 * 1024 * 1024;
    private final Path root;
    private final boolean partitioned;

    public ChunkEvidenceStore(Path root) { this(root, false); }
    private ChunkEvidenceStore(Path root, boolean partitioned) { this.root = root.toAbsolutePath().normalize(); this.partitioned = partitioned; }
    public static ChunkEvidenceStore production(Path root) { return new ChunkEvidenceStore(root, true); }
    public static long retainedBytes(Object value) { return CodecMemory.retainedBytes(value); }
    public Path path(TerrainTile.Key key) {
        return partitioned ? directory(new ChunkKey(key.dimension(), key.chunkX(), key.chunkZ())).resolve(Math.floorDiv(key.minY(), 128) * 128 + ".t0")
                : root.resolve(digestText(key.identity()) + ".t0");
    }

    /** Streams lengths only. This forecast is not a substitute for actual read admission. */
    public OptionalLong tileAllocationHint(TerrainTile.Key key) throws IOException {
        try (var memory = CodecMemory.open()) {
            var file = path(key);
            if (!Files.exists(file)) return OptionalLong.empty();
            long length = Files.size(file);
            if (length < 20 || length > LIMIT) throw new IOException("T0_CORRUPT: evidence length");
            try (var in = new DataInputStream(Files.newInputStream(file))) {
                if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("T0_CORRUPT: header");
                int raw = in.readInt(); if (raw < 0 || raw > LIMIT) throw new IOException("T0_CORRUPT: raw length");
                in.readLong();
                try (var payload = new DataInputStream(new InflaterInputStream(in))) {
                    long consumed = skipUtf(payload) + skipUtf(payload) + 16L;
                    payload.skipNBytes(16); consumed += skipUtf(payload) + 4L;
                    int count = payload.readInt();
                    if (count < 1 || count > key.volume()) throw new IOException("T0_CORRUPT: palette count");
                    long strings = 0, encodedTextBytes = 0;
                    for (int n = 0; n < count; n++) {
                        int bytes = payload.readUnsignedShort(); consumed += 2L + bytes;
                        encodedTextBytes += bytes;
                        strings = Math.addExact(strings, CodecMemory.stringBytes(bytes));
                        if (consumed + 4L * key.volume() > raw) throw new IOException("T0_CORRUPT: palette length");
                        payload.skipNBytes(bytes);
                    }
                    if (consumed + 4L * key.volume() != raw) throw new IOException("T0_CORRUPT: allocation hint length");
                    long retained = 512 + CodecMemory.stringBytes(key.dimension().length())
                            + 2 * CodecMemory.stringBytes(64) + CodecMemory.listBytes(count)
                            + CodecMemory.arrayBytes(4L * key.volume()) + strings;
                    // Retained result plus the store's Optional; this is not scratch headroom.
                    long legacyAllowance = 1024L + 4L * key.volume() + 128L * count + 4L * encodedTextBytes;
                    return OptionalLong.of(Math.max(retained + 64, legacyAllowance));
                }
            }
        }
    }
    private static long skipUtf(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort(); input.skipNBytes(length); return 2L + length;
    }
    public OptionalLong metadataAllocationHint(ChunkKey key) throws IOException {
        try (var memory = CodecMemory.open()) {
            var file = directory(key).resolve("coverage.bin");
            if (!Files.exists(file)) return OptionalLong.empty();
            long length = Files.size(file);
            if (length < 52 || length > RECORD_LIMIT + 52L) throw new IOException("authoritative metadata length");
            try (var in = new DataInputStream(Files.newInputStream(file))) {
                if (in.readInt() != 0x50474331 || in.readInt() != 1) throw new IOException("authoritative metadata header");
                int raw = in.readInt();
                if (raw < 0 || raw > RECORD_LIMIT || length != raw + 52L) throw new IOException("authoritative metadata length");
                // A protected point costs 12 wire bytes and at most 192 retained allowance bytes.
                return OptionalLong.of(16384L + 16L * raw);
            }
        }
    }

    public Optional<TerrainTile> read(TerrainTile.Key key, String recipe) throws IOException {
        try (var memory = CodecMemory.open()) {
            var file = path(key);
            if (!Files.exists(file)) return Optional.empty();
            if (Files.size(file) < 20 || Files.size(file) > LIMIT) throw new IOException("T0_CORRUPT: evidence length");
            try (var input = new DataInputStream(Files.newInputStream(file))) {
                if (input.readInt() != MAGIC || input.readInt() != 1) throw new IOException("T0_CORRUPT: header");
                int size = input.readInt(); long checksum = input.readLong();
                if (size < 0 || size > LIMIT) throw new IOException("T0_CORRUPT: length");
                try (var inflater = new InflaterInputStream(input)) {
                    var bounded = new CheckedWindow(inflater, size);
                    var payload = new DataInputStream(bounded);
                    String savedRecipe = CodecMemory.readModifiedUtf(payload);
                    CodecMemory.charge(64);
                    var savedKey = new TerrainTile.Key(CodecMemory.readModifiedUtf(payload), payload.readInt(), payload.readInt(), payload.readInt(), payload.readInt());
                    if (!savedKey.equals(key) || !savedRecipe.equals(recipe)) throw new IOException("T0_RECIPE_MISMATCH");
                    String hash = CodecMemory.readModifiedUtf(payload);
                    int count = payload.readInt();
                    if (count < 1 || count > key.volume()) throw new IOException("T0_CORRUPT: palette");
                    CodecMemory.charge(CodecMemory.listBytes(count));
                    try (var temporary = CodecMemory.scratch(64 + CodecMemory.arrayBytes(8L * count) + CodecMemory.arrayBytes(4L * key.volume()))) {
                        var palette = new ArrayList<String>(count); long hashScratch = 8192 + 256L * count;
                        for (int i = 0; i < count; i++) {
                            String token = CodecMemory.readModifiedUtf(payload); palette.add(token);
                            hashScratch = Math.addExact(hashScratch, 8L * token.length());
                        }
                        int[] states = new int[key.volume()];
                        for (int i = 0; i < states.length; i++) states[i] = payload.readInt();
                        if (payload.read() != -1 || bounded.count != size || inflater.read() != -1 || bounded.crc.getValue() != checksum)
                            throw new IOException("T0_CORRUPT: CRC/length");
                        CodecMemory.charge(512 + CodecMemory.arrayBytes(4L * key.volume()));
                        hashScratch = Math.addExact(hashScratch, 8L * (key.dimension().length() + recipe.length() + 128));
                        TerrainTile tile;
                        try (var hashing = CodecMemory.scratch(hashScratch)) { tile = new TerrainTile(key, recipe, palette, states); }
                        if (!tile.hash().equals(hash)) throw new IOException("T0_CORRUPT: hash");
                        return keepOptional(memory, tile);
                    }
                }
            }
        } catch (IllegalArgumentException invalid) { throw new IOException("T0_CORRUPT: invalid payload", invalid); }
    }

    /** Returns the borrowed input tile after verifying/persisting equivalent authoritative evidence. */
    public TerrainTile persistBeforeApply(TerrainTile tile) throws IOException {
        try (var memory = CodecMemory.open()) {
            try (var validation = CodecMemory.open()) {
                var prior = read(tile.key(), tile.recipe());
                if (prior.isPresent()) {
                    if (!prior.get().hash().equals(tile.hash())) throw new IOException("T0_CONFLICT: attempted overwrite");
                    return tile;
                }
            }
            var summary = CodecIO.summarize(LIMIT, out -> writeTilePayload(out, tile));
            Path file = path(tile.key()); Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "t0-", ".tmp");
            try (var scratch = CodecMemory.scratch(16384)) {
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    var output = new DataOutputStream(new LimitedOutput(new BufferedOutputStream(Channels.newOutputStream(channel), 8192), LIMIT));
                    output.writeInt(MAGIC); output.writeInt(1); output.writeInt(summary.length()); output.writeLong(summary.crc());
                    try (var deflater = new DeflaterOutputStream(output)) {
                        var actual = CodecIO.writeAndSummarize(deflater, LIMIT, out -> writeTilePayload(out, tile));
                        if (!actual.equals(summary)) throw new IOException("T0 changed during persistence");
                        deflater.finish(); output.flush(); channel.force(true);
                    }
                }
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
            return tile;
        }
    }
    private static void writeTilePayload(DataOutputStream out, TerrainTile tile) throws IOException {
        CodecMemory.writeModifiedUtf(out, tile.recipe()); CodecMemory.writeModifiedUtf(out, tile.key().dimension());
        out.writeInt(tile.key().chunkX()); out.writeInt(tile.key().chunkZ()); out.writeInt(tile.key().minY()); out.writeInt(tile.key().maxY());
        CodecMemory.writeModifiedUtf(out, tile.hash()); out.writeInt(tile.palette().size());
        for (String state : tile.palette()) CodecMemory.writeModifiedUtf(out, state);
        try (var scratch = CodecMemory.scratch(CodecMemory.arrayBytes(4L * tile.key().volume()))) {
            for (int state : tile.copyStates()) out.writeInt(state);
        }
    }

    public void persistCoverage(CaptureMetadata value) throws IOException {
        try (var memory = CodecMemory.open()) {
            EvidenceCodec.validateMetadata(value);
            persistRecord(directory(value.coverage().chunk()).resolve("coverage.bin"), 0x50474331,
                    out -> EvidenceCodec.metadataTo(out, value), value.hash());
        }
    }
    public void persistCoverageIntent(Coverage value) throws IOException {
        try (var memory = CodecMemory.open()) {
            persistRecord(directory(value.chunk()).resolve("capture-intent.bin"), 0x50474931,
                    out -> EvidenceCodec.coverageTo(out, value), value.hash());
        }
    }
    public Optional<Coverage> readCoverageIntent(ChunkKey key) throws IOException {
        try (var memory = CodecMemory.open()) {
            var bytes = readRecord(directory(key).resolve("capture-intent.bin"), 0x50474931);
            if (bytes.isEmpty()) return Optional.empty();
            var value = EvidenceCodec.readCoverage(bytes.get());
            if (!value.chunk().equals(key)) throw new IOException("capture intent owner mismatch");
            return keepOptional(memory, value);
        }
    }
    public Optional<CaptureMetadata> readCoverage(ChunkKey key) throws IOException {
        try (var memory = CodecMemory.open()) {
            var bytes = readRecord(directory(key).resolve("coverage.bin"), 0x50474331);
            if (bytes.isEmpty()) return Optional.empty();
            var value = EvidenceCodec.readMetadata(bytes.get());
            if (!value.coverage().chunk().equals(key)) throw new IOException("coverage owner mismatch");
            return keepOptional(memory, value);
        }
    }
    public void persistPlan(ChunkWritePlan plan) throws IOException {
        try (var memory = CodecMemory.open()) {
            CanonicalCodec.validateWritePlan(plan);
            persistRecord(directory(plan.owner()).resolve("build-proof.bin"), 0x50475031,
                    out -> CanonicalCodec.writePlanTo(out, plan), plan.hash());
        }
    }
    public Optional<ChunkWritePlan> readPlan(ChunkKey key) throws IOException {
        try (var memory = CodecMemory.open()) {
            var bytes = readRecord(directory(key).resolve("build-proof.bin"), 0x50475031);
            if (bytes.isEmpty()) return Optional.empty();
            var value = CanonicalCodec.readWritePlan(bytes.get());
            if (!value.owner().equals(key)) throw new IOException("proof owner mismatch");
            return keepOptional(memory, value);
        }
    }
    public void persistExitDecision(GroundExitDecision decision) throws IOException {
        try (var memory = CodecMemory.open()) {
            GroundExitCodec.validate(decision);
            if (!decision.hash().equals(GroundExitCodec.hash(decision))) throw new IOException("exit decision hash mismatch");
            try (var validation = CodecMemory.open()) { readExitDecision(decision.key()); }
            CodecMemory.charge(CodecMemory.arrayBytes(32));
            byte[] identity = HexFormat.of().parseHex(decision.hash());
            persistRecord(exitAuxPath(decision.key(), "intent"), 0x50474534, out -> out.write(identity), null);
            persistRecord(exitPath(decision.key()), 0x50474532, out -> GroundExitCodec.writeTo(out, decision), decision.hash());
            persistRecord(exitAuxPath(decision.key(), "sealed"), 0x50474535, out -> out.write(identity), null);
        }
    }
    public Optional<GroundExitDecision> readExitDecision(GroundExitDecision.Key key) throws IOException {
        try (var memory = CodecMemory.open()) {
            var intent = readRecord(exitAuxPath(key, "intent"), 0x50474534);
            var sealed = readRecord(exitAuxPath(key, "sealed"), 0x50474535);
            var bytes = readRecord(exitPath(key), 0x50474532);
            if (sealed.isPresent() && (bytes.isEmpty() || intent.isEmpty())) throw new IOException("sealed exit decision lost evidence");
            if (bytes.isEmpty()) return Optional.empty();
            if (intent.isEmpty()) throw new IOException("exit decision without intent");
            var decision = GroundExitCodec.decode(bytes.get());
            if (!decision.key().equals(key)) throw new IOException("exit decision identity mismatch");
            CodecMemory.charge(CodecMemory.arrayBytes(32));
            byte[] hash = HexFormat.of().parseHex(decision.hash());
            if (!Arrays.equals(intent.get(), hash) || sealed.isPresent() && !Arrays.equals(sealed.get(), hash))
                throw new IOException("exit decision seal/hash mismatch");
            return sealed.isPresent() ? keepOptional(memory, decision) : Optional.empty();
        }
    }
    public void persistExitProof(ChunkWritePlan plan, List<GroundExitDecision> decisions) throws IOException {
        try (var memory = CodecMemory.open(); var index = CodecMemory.scratch(64 + CodecMemory.mapBytes(decisions.size()))) {
            if (decisions.isEmpty()) throw new IOException("production proof lacks ground-exit decisions");
            var unique = new TreeMap<String, GroundExitDecision>();
            for (var decision : decisions) {
                if (!decision.key().recipeHash().equals(plan.recipeHash()) || !decision.key().dimension().equals(plan.owner().dimension()))
                    throw new IOException("foreign exit decision in owner proof");
                try (var validation = CodecMemory.open()) {
                    var saved = readExitDecision(decision.key()).orElseThrow(() -> new IOException("exit decision missing before owner proof"));
                    if (!saved.equals(decision)) throw new IOException("exit decision differs from durable evidence");
                }
                unique.put(decision.hash(), decision);
            }
            if (unique.size() > 4096) throw new IOException("exit proof count");
            persistRecord(directory(plan.owner()).resolve("build-exits.bin"), 0x50474533, out -> {
                CodecMemory.writeModifiedUtf(out, plan.hash()); out.writeInt(unique.size());
                for (var decision : unique.values()) {
                    CodecMemory.writeModifiedUtf(out, decision.key().recipeHash()); CodecMemory.writeModifiedUtf(out, decision.key().dimension());
                    out.writeLong(decision.key().range().fromQ8()); out.writeLong(decision.key().range().toQ8()); CodecMemory.writeModifiedUtf(out, decision.hash());
                }
            }, null);
        }
    }
    public void verifyExitProof(ChunkWritePlan plan) throws IOException {
        try (var memory = CodecMemory.open()) {
            var bytes = readRecord(directory(plan.owner()).resolve("build-exits.bin"), 0x50474533)
                    .orElseThrow(() -> new IOException("production owner lost exit proof"));
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (!CodecMemory.readModifiedUtf(in).equals(plan.hash())) throw new IOException("exit proof/write proof mismatch");
                int count = in.readInt(); if (count < 1 || count > 4096) throw new IOException("exit proof count");
                String previous = "";
                for (int n = 0; n < count; n++) {
                    String next;
                    try (var entry = CodecMemory.open()) {
                        CodecMemory.charge(128);
                        var key = new GroundExitDecision.Key(CodecMemory.readModifiedUtf(in), CodecMemory.readModifiedUtf(in), new ArcInterval(in.readLong(), in.readLong()));
                        String hash = CodecMemory.readModifiedUtf(in);
                        if (!key.recipeHash().equals(plan.recipeHash()) || !key.dimension().equals(plan.owner().dimension()) || hash.compareTo(previous) <= 0)
                            throw new IOException("exit proof identity/order");
                        var decision = readExitDecision(key).orElseThrow(() -> new IOException("referenced exit decision missing"));
                        if (!decision.hash().equals(hash)) throw new IOException("referenced exit decision changed");
                        next = entry.keep(hash, CodecMemory.stringBytes(hash.length()));
                    }
                    if (!previous.isEmpty()) CodecMemory.discard(CodecMemory.stringBytes(previous.length()));
                    previous = next;
                }
                if (in.read() != -1) throw new IOException("exit proof trailing bytes");
            } catch (IllegalArgumentException invalid) { throw new IOException("invalid exit proof", invalid); }
        }
    }

    private Path exitPath(GroundExitDecision.Key key) {
        return root.resolve(digestText(key.dimension())).resolve("ground-exits").resolve(key.recipeHash())
                .resolve(key.range().fromQ8() + "_" + key.range().toQ8() + ".bin");
    }
    private Path exitAuxPath(GroundExitDecision.Key key, String suffix) {
        Path path = exitPath(key); return path.resolveSibling(path.getFileName() + "." + suffix);
    }
    private Path directory(ChunkKey key) { return root.resolve(digestText(key.dimension())).resolve(key.x() + "_" + key.z()); }
    private static String digestText(String value) {
        try (var memory = CodecMemory.open(); var scratch = CodecMemory.scratch(CodecMemory.arrayBytes(3L * value.length()))) {
            String result = CodecIO.sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return memory.keep(result, CodecMemory.stringBytes(64));
        }
    }
    private static <T> Optional<T> keepOptional(CodecMemory memory, T value) {
        CodecMemory.charge(64);
        var result = Optional.of(value);
        return memory.keep(result, retainedBytes(result));
    }

    private void persistRecord(Path path, int magic, CodecIO.Writer writer, String expectedHash) throws IOException {
        try (var memory = CodecMemory.open()) {
            var summary = CodecIO.summarize(RECORD_LIMIT, writer);
            if (expectedHash != null && !summary.sha256().equals(expectedHash)) throw new IOException("authoritative evidence hash mismatch");
            if (Files.exists(path)) { verifyRecord(path, magic, summary); return; }
            Files.createDirectories(path.getParent()); Path temporary = Files.createTempFile(path.getParent(), "proof-", ".tmp");
            try (var scratch = CodecMemory.scratch(16384)) {
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    var out = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel), 8192));
                    out.writeInt(magic); out.writeInt(1); out.writeInt(summary.length()); out.writeLong(summary.crc());
                    out.write(HexFormat.of().parseHex(summary.sha256()));
                    var actual = CodecIO.writeAndSummarize(out, RECORD_LIMIT, writer);
                    if (!actual.equals(summary)) throw new IOException("authoritative payload changed between passes");
                    out.flush(); channel.force(true);
                }
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
        }
    }
    private Optional<byte[]> readRecord(Path path, int magic) throws IOException {
        try (var memory = CodecMemory.open()) {
            if (!Files.exists(path)) return Optional.empty();
            long length = Files.size(path);
            if (length < 52 || length > RECORD_LIMIT + 52L) throw new IOException("authoritative evidence length");
            try (var in = new DataInputStream(Files.newInputStream(path))) {
                if (in.readInt() != magic || in.readInt() != 1) throw new IOException("authoritative evidence header");
                int size = in.readInt(); long checksum = in.readLong();
                if (size < 0 || size > RECORD_LIMIT || length != size + 52L) throw new IOException("authoritative evidence length");
                byte[] sha = new byte[32]; in.readFully(sha);
                CodecMemory.charge(CodecMemory.arrayBytes(size));
                byte[] bytes = new byte[size]; in.readFully(bytes);
                var crc = new CRC32C(); crc.update(bytes);
                if (in.read() != -1 || crc.getValue() != checksum || !HexFormat.of().formatHex(sha).equals(CodecIO.sha256(bytes)))
                    throw new IOException("authoritative evidence CRC/SHA/length mismatch");
                return keepOptional(memory, bytes);
            }
        }
    }
    /** Validate existing records with a bounded scratch buffer, without loading another payload copy. */
    private void verifyRecord(Path path, int magic, CodecIO.Summary expected) throws IOException {
        try (var memory = CodecMemory.open(); var scratch = CodecMemory.scratch(CodecMemory.arrayBytes(8192))) {
            long length = Files.size(path);
            if (length < 52 || length > RECORD_LIMIT + 52L) throw new IOException("authoritative evidence length");
            try (var in = new DataInputStream(Files.newInputStream(path))) {
                if (in.readInt() != magic || in.readInt() != 1) throw new IOException("authoritative evidence header");
                int size = in.readInt(); long checksum = in.readLong();
                if (size < 0 || size > RECORD_LIMIT || length != size + 52L) throw new IOException("authoritative evidence length");
                byte[] sha = new byte[32]; in.readFully(sha);
                byte[] buffer = new byte[8192]; int remaining = size;
                var digest = CodecIO.digest(); var crc = new CRC32C();
                while (remaining > 0) {
                    int count = in.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (count < 0) throw new EOFException("authoritative evidence truncated");
                    if (count == 0) continue;
                    digest.update(buffer, 0, count); crc.update(buffer, 0, count); remaining -= count;
                }
                String hash = HexFormat.of().formatHex(digest.digest());
                if (in.read() != -1 || crc.getValue() != checksum || !HexFormat.of().formatHex(sha).equals(hash))
                    throw new IOException("authoritative evidence CRC/SHA/length mismatch");
                if (size != expected.length() || checksum != expected.crc() || !hash.equals(expected.sha256()))
                    throw new IOException("authoritative evidence overwrite refused");
            }
        }
    }
    private static final class CheckedWindow extends InputStream {
        final CRC32C crc = new CRC32C();
        final InputStream source;
        final int limit;
        int count;
        CheckedWindow(InputStream source, int limit) { this.source = source; this.limit = limit; }
        @Override public int read() throws IOException {
            if (count == limit) return -1;
            int value = source.read(); if (value >= 0) { count++; crc.update(value); } return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            if (count == limit) return -1;
            int got = source.read(bytes, offset, Math.min(length, limit - count));
            if (got > 0) { count += got; crc.update(bytes, offset, got); } return got;
        }
    }
    private static final class LimitedOutput extends FilterOutputStream {
        final int limit;
        int count;
        LimitedOutput(OutputStream target, int limit) { super(target); this.limit = limit; }
        private void admit(int length) throws IOException { if (length > limit - count) throw new IOException("T0 evidence exceeds bound"); count += length; }
        @Override public void write(int value) throws IOException { admit(1); out.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length); admit(length); out.write(bytes, offset, length);
        }
    }
}
