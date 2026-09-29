package local.plaincraft.greatwall.diagnostics;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UTFDataFormatException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.GroundExitDecision;
import local.plaincraft.greatwall.terrain.TerrainTile;

/** Synchronous, invocation-owned codec accounting inside the caller's CompiledAllocation frame.
 * Weights are conservative live-object allowances, not measurements of JVM heap usage.
 * A scope/charge must not escape to another thread or another CompiledAllocation.run invocation. */
public final class CodecMemory implements AutoCloseable {
    private static final ThreadLocal<CodecMemory> CURRENT = new ThreadLocal<>();
    private static final long OVERHEAD = 4096;
    private final CodecMemory parent;
    private long charged;
    private long retained;
    private boolean closed;

    private CodecMemory(CodecMemory parent) { this.parent = parent; this.charged = OVERHEAD; }

    public static CodecMemory open() {
        CompiledAllocation.charge(OVERHEAD);
        var memory = new CodecMemory(CURRENT.get());
        CURRENT.set(memory);
        return memory;
    }

    public static void charge(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("negative codec allocation");
        CompiledAllocation.charge(bytes);
        var memory = CURRENT.get();
        if (memory != null) memory.charged = Math.addExact(memory.charged, bytes);
    }

    public static void discard(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("negative codec release");
        var memory = CURRENT.get();
        if (memory != null && bytes > memory.charged) throw new IllegalStateException("codec accounting underflow");
        CompiledAllocation.discard(bytes);
        if (memory != null) memory.charged -= bytes;
    }

    public static Charge scratch(long bytes) {
        charge(bytes);
        return new Charge(CURRENT.get(), bytes);
    }

    public <T> T keep(T value, long bytes) {
        if (closed || bytes < 0 || bytes > charged) throw new IllegalStateException("unreserved codec result");
        retained = bytes;
        return value;
    }

    public <T> T keepAll(T value) { return keep(value, charged - OVERHEAD); }

    @Override public void close() {
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("codec scopes must close in nesting order");
        try {
            CompiledAllocation.discard(charged - retained);
            if (parent != null) parent.charged = Math.addExact(parent.charged, retained);
        } finally {
            closed = true;
            if (parent == null) CURRENT.remove(); else CURRENT.set(parent);
        }
    }

    public static final class Charge implements AutoCloseable {
        private final CodecMemory owner;
        private final long bytes;
        private boolean closed;
        private Charge(CodecMemory owner, long bytes) { this.owner = owner; this.bytes = bytes; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (owner != null && owner.closed) return;
            if (owner != null && bytes > owner.charged) throw new IllegalStateException("codec charge underflow");
            CompiledAllocation.discard(bytes);
            if (owner != null) owner.charged -= bytes;
        }
    }

    public static long arrayBytes(long payloadBytes) {
        if (payloadBytes < 0) throw new IllegalArgumentException("negative array length");
        return Math.addExact(payloadBytes, 47) & ~15L;
    }
    public static long stringBytes(long characters) { return Math.addExact(64, arrayBytes(Math.multiplyExact(2, characters))); }
    public static long listBytes(long count) { return count == 0 ? 0 : Math.addExact(64, arrayBytes(Math.multiplyExact(8, count))); }
    public static long mapBytes(long count) { return count == 0 ? 0 : Math.addExact(128, Math.multiplyExact(128, count)); }

    /** Conservative, deterministic retained size for store results. Shared references are not deduplicated. */
    public static long retainedBytes(Object value) {
        if (value == null || value instanceof Enum<?>) return 0;
        if (value instanceof Optional<?> v) return v.isEmpty() ? 0 : 64 + retainedBytes(v.get());
        if (value instanceof byte[] v) return arrayBytes(v.length);
        if (value instanceof String v) return stringBytes(v.length());
        if (value instanceof Integer || value instanceof Long) return 32;
        if (value instanceof List<?> v) {
            long bytes = listBytes(v.size());
            for (var item : v) bytes = Math.addExact(bytes, retainedBytes(item));
            return bytes;
        }
        if (value instanceof Set<?> v) {
            long bytes = mapBytes(v.size());
            for (var item : v) bytes = Math.addExact(bytes, retainedBytes(item));
            return bytes;
        }
        if (value instanceof Map<?, ?> v) {
            long bytes = mapBytes(v.size());
            for (var e : v.entrySet()) bytes = Math.addExact(bytes, retainedBytes(e.getKey()) + retainedBytes(e.getValue()));
            return bytes;
        }
        if (value instanceof Point3i || value instanceof Point2q || value instanceof Bounds || value instanceof ArcInterval
                || value instanceof ColumnEvidence || value instanceof Terminal) return 64;
        if (value instanceof ChunkKey v) return 64 + retainedBytes(v.dimension());
        if (value instanceof SiteId v) return 64 + retainedBytes(v.wallId());
        if (value instanceof BlockSpec v) return 64 + retainedBytes(v.id()) + retainedBytes(v.properties());
        if (value instanceof VoxelIntent v) return 64 + retainedBytes(v.pos()) + retainedBytes(v.state()) + retainedBytes(v.component());
        if (value instanceof BlockProof v) return 64 + retainedBytes(v.pos()) + retainedBytes(v.beforeState())
                + retainedBytes(v.afterState()) + retainedBytes(v.owner());
        if (value instanceof BlockEntityIntent v) return 64 + retainedBytes(v.pos()) + retainedBytes(v.type())
                + retainedBytes(v.site()) + retainedBytes(v.fields());
        if (value instanceof SpawnSlot v) return 64 + retainedBytes(v.site()) + retainedBytes(v.feet()) + retainedBytes(v.entityType());
        if (value instanceof WriteMetadata v) return v == WriteMetadata.EMPTY ? 0 : 64 + retainedBytes(v.coverageHash())
                + retainedBytes(v.spawnSlots()) + retainedBytes(v.realizedStates());
        if (value instanceof ChunkWritePlan v) return 128 + retainedBytes(v.owner()) + retainedBytes(v.recipeHash())
                + retainedBytes(v.inputHash()) + retainedBytes(v.voxels()) + retainedBytes(v.proofs())
                + retainedBytes(v.blockEntities()) + retainedBytes(v.postprocess()) + retainedBytes(v.fluidTicks())
                + retainedBytes(v.walkwayFeet()) + retainedBytes(v.metadata()) + retainedBytes(v.hash());
        if (value instanceof Coverage v) return 64 + retainedBytes(v.chunk()) + retainedBytes(v.tileBases())
                + retainedBytes(v.readBounds()) + retainedBytes(v.hash());
        if (value instanceof CaptureMetadata v) return 64 + retainedBytes(v.coverage()) + retainedBytes(v.columns())
                + retainedBytes(v.protectedStructures()) + retainedBytes(v.blockEntities())
                + retainedBytes(v.immutableBlocks()) + retainedBytes(v.hash());
        if (value instanceof TerrainTile v) return 512 + retainedBytes(v.key().dimension()) + retainedBytes(v.recipe())
                + retainedBytes(v.hash()) + retainedBytes(v.palette()) + arrayBytes(4L * v.key().volume());
        if (value instanceof GroundExitDecision.Key v) return 64 + retainedBytes(v.recipeHash())
                + retainedBytes(v.dimension()) + retainedBytes(v.range());
        if (value instanceof GroundExitDecision.Exit v) return 64 + retainedBytes(v.site()) + retainedBytes(v.footprint())
                + retainedBytes(v.componentHash()) + retainedBytes(v.t0Hash());
        if (value instanceof GroundExitDecision.Edge v) return 64 + retainedBytes(v.allowed());
        if (value instanceof GroundExitDecision.Cap v) return 64 + retainedBytes(v.site()) + retainedBytes(v.edge())
                + retainedBytes(v.componentHash()) + retainedBytes(v.footprint());
        if (value instanceof BreakInterval v) return 64 + retainedBytes(v.range());
        if (value instanceof GroundExitDecision v) return 128 + retainedBytes(v.key()) + retainedBytes(v.left())
                + retainedBytes(v.right()) + retainedBytes(v.breaks()) + retainedBytes(v.exclusions())
                + retainedBytes(v.caps()) + retainedBytes(v.t0Hash()) + retainedBytes(v.hash());
        throw new IllegalArgumentException("no codec retained-size contract for " + value.getClass().getName());
    }

    public static int utf8Length(String value) throws IOException {
        long size = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) size++;
            else if (c < 0x800) size += 2;
            else if (Character.isHighSurrogate(c)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) { i++; size += 4; }
                else size++; // String.getBytes(UTF_8) replaces each malformed surrogate with '?'.
            } else if (Character.isLowSurrogate(c)) size++;
            else size += 3;
        }
        if (size > Integer.MAX_VALUE) throw new UTFDataFormatException("UTF-8 length overflow");
        return (int) size;
    }

    public static void writeUtf8(DataOutputStream out, String value, int limit) throws IOException {
        int length = utf8Length(value);
        if (length > limit) throw new IOException("string exceeds " + limit + " bytes");
        out.writeInt(length);
        for (int i = 0; i < value.length();) {
            int c = value.codePointAt(i); i += Character.charCount(c);
            if (c >= 0xd800 && c <= 0xdfff) c = '?';
            if (c < 0x80) out.writeByte(c);
            else if (c < 0x800) { out.writeByte(0xc0 | c >> 6); out.writeByte(0x80 | c & 63); }
            else if (c < 0x10000) { out.writeByte(0xe0 | c >> 12); out.writeByte(0x80 | c >> 6 & 63); out.writeByte(0x80 | c & 63); }
            else { out.writeByte(0xf0 | c >> 18); out.writeByte(0x80 | c >> 12 & 63); out.writeByte(0x80 | c >> 6 & 63); out.writeByte(0x80 | c & 63); }
        }
    }

    public static String readUtf8(DataInputStream in, int limit) throws IOException {
        int bytes = in.readInt();
        if (bytes < 0 || bytes > limit || bytes > in.available()) throw new IOException("UTF-8 length");
        charge(stringBytes(bytes));
        try (var scratch = scratch(arrayBytes(2L * bytes) + arrayBytes(bytes))) {
            char[] chars = new char[bytes]; int at = 0, count = 0;
            while (at < bytes) {
                int first = in.readUnsignedByte(); at++;
                int next, minimum, code;
                if (first < 0x80) { chars[count++] = (char) first; continue; }
                if (first >= 0xc2 && first <= 0xdf) { next = 1; minimum = 0x80; code = first & 31; }
                else if (first >= 0xe0 && first <= 0xef) { next = 2; minimum = 0x800; code = first & 15; }
                else if (first >= 0xf0 && first <= 0xf4) { next = 3; minimum = 0x10000; code = first & 7; }
                else throw new UTFDataFormatException("invalid UTF-8 lead");
                if (at + next > bytes) throw new EOFException("truncated UTF-8");
                for (int n = 0; n < next; n++) {
                    int b = in.readUnsignedByte(); at++;
                    if ((b & 0xc0) != 0x80) throw new UTFDataFormatException("invalid UTF-8 continuation");
                    code = code << 6 | b & 63;
                }
                if (code < minimum || code > 0x10ffff || code >= 0xd800 && code <= 0xdfff)
                    throw new UTFDataFormatException("invalid UTF-8 code point");
                if (code < 0x10000) chars[count++] = (char) code;
                else { chars[count++] = Character.highSurrogate(code); chars[count++] = Character.lowSurrogate(code); }
            }
            String result = new String(chars, 0, count);
            discard(stringBytes(bytes) - stringBytes(count));
            return result;
        }
    }

    public static void writeModifiedUtf(DataOutputStream out, String value) throws IOException {
        int length = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i); length += c >= 1 && c <= 127 ? 1 : c <= 2047 ? 2 : 3;
            if (length > 65535) throw new UTFDataFormatException("modified UTF exceeds 65535 bytes");
        }
        out.writeShort(length);
        for (int i = 0; i < value.length(); i++) {
            int c = value.charAt(i);
            if (c >= 1 && c <= 127) out.writeByte(c);
            else if (c <= 2047) { out.writeByte(0xc0 | c >> 6); out.writeByte(0x80 | c & 63); }
            else { out.writeByte(0xe0 | c >> 12); out.writeByte(0x80 | c >> 6 & 63); out.writeByte(0x80 | c & 63); }
        }
    }

    public static String readModifiedUtf(DataInputStream in) throws IOException {
        int bytes = in.readUnsignedShort();
        charge(stringBytes(bytes));
        try (var scratch = scratch(arrayBytes(2L * bytes) + arrayBytes(bytes))) {
            char[] chars = new char[bytes]; int at = 0, count = 0;
            while (at < bytes) {
                int c = in.readUnsignedByte(); at++;
                if (c <= 127) { chars[count++] = (char) c; continue; }
                int n = c >> 4;
                if (n != 12 && n != 13 && n != 14) throw new UTFDataFormatException("modified UTF lead");
                int needed = n == 14 ? 2 : 1;
                if (at + needed > bytes) throw new UTFDataFormatException("partial modified UTF character");
                int code = c & (needed == 2 ? 15 : 31);
                for (int j = 0; j < needed; j++) {
                    int b = in.readUnsignedByte(); at++;
                    if ((b & 0xc0) != 0x80) throw new UTFDataFormatException("modified UTF continuation");
                    code = code << 6 | b & 63;
                }
                chars[count++] = (char) code;
            }
            String result = new String(chars, 0, count);
            discard(stringBytes(bytes) - stringBytes(count));
            return result;
        }
    }
}
