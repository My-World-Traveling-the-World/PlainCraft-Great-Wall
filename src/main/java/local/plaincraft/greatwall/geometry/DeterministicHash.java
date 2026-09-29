package local.plaincraft.greatwall.geometry;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import local.plaincraft.greatwall.model.WallTypes.SiteId;

/** Stateless domain-separated hashing; never depends on task or map iteration order. */
public final class DeterministicHash {
    private DeterministicHash() {}

    public static long mix64(long value) {
        // Two's-complement overflow is part of splitmix64, not coordinate arithmetic.
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static long fields(long seed, String domain, long... values) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(values, "values");
        byte[] bytes = domain.getBytes(StandardCharsets.UTF_8);
        long hash = mix64(mix64(seed) ^ mix64(bytes.length));
        for (byte value : bytes) hash = mix64(hash ^ (value & 255));
        hash = mix64(hash ^ mix64(values.length));
        for (long value : values) hash = mix64(hash ^ mix64(value));
        return hash;
    }

    public static int bounded(long hash, int boundExclusive) {
        if (boundExclusive <= 0) throw new IllegalArgumentException("bound must be positive");
        return (int) Long.remainderUnsigned(hash, boundExclusive);
    }

    public static String sha256(byte[] canonical) {
        return HexFormat.of().formatHex(digest(Objects.requireNonNull(canonical, "canonical")));
    }

    public static UUID entityUuid(SiteId site, int slot) {
        Objects.requireNonNull(site, "site");
        if (slot < 0) throw new IllegalArgumentException("slot must be nonnegative");
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            string(out, site.wallId());
            string(out, site.kind().name());
            out.writeLong(site.ordinal());
            out.writeInt(slot);
            string(out, "garrison-uuid");
        } catch (IOException impossible) {
            throw new AssertionError("memory encoding failed", impossible);
        }
        byte[] bytes = digest(buffer.toByteArray());
        bytes[6] = (byte) ((bytes[6] & 15) | 0x50);
        bytes[8] = (byte) ((bytes[8] & 63) | 0x80);
        var data = ByteBuffer.wrap(bytes);
        return new UUID(data.getLong(), data.getLong());
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) throw new IllegalArgumentException("canonical string exceeds 4096 bytes");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("JDK lacks SHA-256", impossible);
        }
    }
}
