package local.plaincraft.greatwall.persistence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import local.plaincraft.greatwall.diagnostics.CodecIO;
import local.plaincraft.greatwall.diagnostics.CodecMemory;
import local.plaincraft.greatwall.model.WallTypes.*;

/** The original bounded metadata protocol, with streaming hashes and pre-admitted allocations. */
public final class EvidenceCodec {
    private static final int LIMIT = 8 * 1024 * 1024;
    private static final Comparator<Point3i> POS = Comparator.comparingInt(Point3i::y).thenComparingInt(Point3i::z).thenComparingInt(Point3i::x);
    private EvidenceCodec() {}

    public static byte[] coverage(Coverage value) { return CodecIO.encode(8192, out -> coverageBody(out, value)); }
    public static String coverageHash(Coverage value) { return CodecIO.hash(8192, out -> coverageBody(out, value)); }
    public static void coverageTo(OutputStream target, Coverage value) throws IOException {
        CodecIO.write(target, 8192, out -> coverageBody(out, value));
    }
    private static void coverageBody(DataOutputStream out, Coverage value) throws IOException {
        string(out, value.chunk().dimension()); out.writeInt(value.chunk().x()); out.writeInt(value.chunk().z());
        out.writeInt(value.tileBases().size()); for (int base : value.tileBases()) out.writeInt(base);
        bounds(out, value.readBounds());
    }

    public static Coverage readCoverage(byte[] bytes) throws IOException {
        try (var memory = CodecMemory.open()) {
            var value = CodecIO.decode(bytes, 8192, in -> {
                CodecMemory.charge(128);
                var key = new ChunkKey(string(in), in.readInt(), in.readInt());
                var bases = CodecIO.list(in, 64, i -> { CodecMemory.charge(32); return i.readInt(); });
                return new Coverage(key, bases, bounds(in), CodecIO.sha256(bytes));
            });
            return memory.keep(value, CodecMemory.retainedBytes(value));
        }
    }

    public static byte[] metadata(CaptureMetadata value) { return CodecIO.encode(LIMIT, out -> metadataBody(out, value)); }
    public static String metadataHash(CaptureMetadata value) { return CodecIO.hash(LIMIT, out -> metadataBody(out, value)); }
    public static void metadataTo(OutputStream target, CaptureMetadata value) throws IOException {
        CodecIO.write(target, LIMIT, out -> metadataBody(out, value));
    }
    private static void metadataBody(DataOutputStream out, CaptureMetadata value) throws IOException {
        var coverage = value.coverage();
        coverageBody(out, coverage); string(out, coverage.hash());
        try (var scratch = CodecMemory.scratch(128 + 32L * value.columns().size())) {
            var columns = value.columns().stream().sorted(Comparator.comparingInt(ColumnEvidence::z).thenComparingInt(ColumnEvidence::x)).toList();
            out.writeInt(columns.size());
            for (var column : columns) {
                out.writeInt(column.x()); out.writeInt(column.z()); out.writeInt(column.highestNonPlantFeet()); out.writeBoolean(column.upperCensored());
            }
        }
        try (var scratch = CodecMemory.scratch(128 + 32L * value.protectedStructures().size())) {
            var boxes = value.protectedStructures().stream().sorted(Comparator.comparingInt(Bounds::minY).thenComparingInt(Bounds::minZ)
                    .thenComparingInt(Bounds::minX).thenComparingInt(Bounds::maxYExclusive).thenComparingInt(Bounds::maxZExclusive).thenComparingInt(Bounds::maxXExclusive)).toList();
            out.writeInt(boxes.size()); for (var box : boxes) bounds(out, box);
        }
        points(out, value.blockEntities()); points(out, value.immutableBlocks());
    }

    public static CaptureMetadata readMetadata(byte[] bytes) throws IOException {
        try (var memory = CodecMemory.open()) {
            var value = CodecIO.decode(bytes, LIMIT, in -> {
                CodecMemory.charge(192);
                var key = new ChunkKey(string(in), in.readInt(), in.readInt());
                var bases = CodecIO.list(in, 64, i -> { CodecMemory.charge(32); return i.readInt(); });
                var coverage = new Coverage(key, bases, bounds(in), string(in));
                var columns = CodecIO.list(in, 256, i -> {
                    CodecMemory.charge(64);
                    return new ColumnEvidence(i.readInt(), i.readInt(), i.readInt(), i.readBoolean());
                });
                if (columns.size() != 256) throw new IOException("capture needs all 256 column summaries");
                try (var scratch = CodecMemory.scratch(CodecMemory.mapBytes(256))) {
                    var seen = new HashSet<Long>();
                    for (var column : columns) if (Math.floorDiv(column.x(), 16) != key.x() || Math.floorDiv(column.z(), 16) != key.z()
                            || !seen.add((long) column.x() << 32 | (column.z() & 0xffffffffL)))
                        throw new IOException("foreign or duplicate column summary");
                }
                var boxes = CodecIO.list(in, 4096, EvidenceCodec::bounds);
                return new CaptureMetadata(coverage, columns, boxes, points(in, key), points(in, key), CodecIO.sha256(bytes));
            });
            return memory.keep(value, CodecMemory.retainedBytes(value));
        }
    }

    public static CaptureMetadata hashed(CaptureMetadata value) {
        try (var memory = CodecMemory.open()) {
            CodecMemory.charge(64);
            var hash = metadataHash(value);
            var result = new CaptureMetadata(value.coverage(), value.columns(), value.protectedStructures(),
                    value.blockEntities(), value.immutableBlocks(), hash);
            return memory.keep(result, 64 + CodecMemory.stringBytes(64));
        }
    }

    /** Validation equivalent to readMetadata for an already-decoded immutable value. */
    public static void validateMetadata(CaptureMetadata value) throws IOException {
        try (var memory = CodecMemory.open(); var scratch = CodecMemory.scratch(CodecMemory.mapBytes(256))) {
            var key = value.coverage().chunk();
            if (value.coverage().tileBases().size() > 64 || value.columns().size() != 256 || value.protectedStructures().size() > 4096
                    || value.blockEntities().size() > 655360 || value.immutableBlocks().size() > 655360) throw new IOException("metadata count bound");
            var seen = new HashSet<Long>();
            for (var column : value.columns()) if (Math.floorDiv(column.x(), 16) != key.x() || Math.floorDiv(column.z(), 16) != key.z()
                    || !seen.add((long) column.x() << 32 | (column.z() & 0xffffffffL))) throw new IOException("foreign or duplicate column summary");
            for (var point : value.blockEntities()) checkOwner(point, key);
            for (var point : value.immutableBlocks()) checkOwner(point, key);
        }
    }

    private static void points(DataOutputStream out, Set<Point3i> values) throws IOException {
        try (var scratch = CodecMemory.scratch(128 + 32L * values.size())) {
            out.writeInt(values.size());
            for (var point : values.stream().sorted(POS).toList()) { out.writeInt(point.x()); out.writeInt(point.y()); out.writeInt(point.z()); }
        }
    }
    private static Set<Point3i> points(DataInputStream in, ChunkKey key) throws IOException {
        int count = CodecIO.count(in, 655360);
        CodecMemory.charge(CodecMemory.mapBytes(count));
        try (var scratch = CodecMemory.scratch(64 + CodecMemory.mapBytes(count))) {
            var points = new HashSet<Point3i>();
            for (int i = 0; i < count; i++) {
                CodecMemory.charge(64);
                var point = new Point3i(in.readInt(), in.readInt(), in.readInt());
                checkOwner(point, key);
                if (!points.add(point)) throw new IOException("duplicate protected position");
            }
            return local.plaincraft.greatwall.model.ImmutablePointSet.copyOf(points);
        }
    }
    private static void checkOwner(Point3i point, ChunkKey key) throws IOException {
        if (Math.floorDiv(point.x(), 16) != key.x() || Math.floorDiv(point.z(), 16) != key.z()) throw new IOException("foreign protected position");
    }
    private static void bounds(DataOutputStream out, Bounds value) throws IOException {
        out.writeInt(value.minX()); out.writeInt(value.minY()); out.writeInt(value.minZ());
        out.writeInt(value.maxXExclusive()); out.writeInt(value.maxYExclusive()); out.writeInt(value.maxZExclusive());
    }
    private static Bounds bounds(DataInputStream in) throws IOException {
        CodecMemory.charge(64);
        return new Bounds(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        if (value.length() > 4096) throw new IOException("string too long");
        CodecMemory.writeUtf8(out, value, 4096);
    }
    private static String string(DataInputStream in) throws IOException { return CodecMemory.readUtf8(in, 4096); }
}
