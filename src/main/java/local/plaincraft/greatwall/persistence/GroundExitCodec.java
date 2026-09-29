package local.plaincraft.greatwall.persistence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Optional;
import local.plaincraft.greatwall.diagnostics.CodecIO;
import local.plaincraft.greatwall.diagnostics.CodecMemory;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.GroundExitDecision;
import local.plaincraft.greatwall.terrain.GroundExitDecision.*;

/** Unchanged schema-2 certificate bytes, with exact buffers and allocation-free payload hashing. */
public final class GroundExitCodec {
    public static final int LIMIT = 8 * 1024 * 1024;
    private GroundExitCodec() {}
    public static byte[] encode(GroundExitDecision value) { return CodecIO.encode(LIMIT, out -> body(out, value)); }
    public static String hash(GroundExitDecision value) { return CodecIO.hash(LIMIT, out -> body(out, value)); }
    public static void writeTo(OutputStream target, GroundExitDecision value) throws IOException { CodecIO.write(target, LIMIT, out -> body(out, value)); }

    private static void body(DataOutputStream out, GroundExitDecision value) throws IOException {
        out.writeInt(0x50474531); out.writeInt(2);
        utf(out, value.key().recipeHash()); utf(out, value.key().dimension()); range(out, value.key().range());
        exit(out, value.left()); out.writeBoolean(value.right().isPresent());
        if (value.right().isPresent()) exit(out, value.right().get());
        out.writeLong(value.terminalSQ8()); utf(out, value.t0Hash());
        out.writeInt(value.breaks().size());
        for (var gap : value.breaks()) { range(out, gap.range()); utf(out, gap.reason().name()); utf(out, gap.scope().name()); }
        out.writeInt(value.exclusions().size()); for (var r : value.exclusions()) range(out, r);
        out.writeInt(value.caps().size());
        for (var cap : value.caps()) {
            site(out, cap.site()); out.writeLong(cap.edge().sQ8()); out.writeBoolean(cap.edge().reverse()); range(out, cap.edge().allowed());
            out.writeLong(cap.actualSQ8()); utf(out, cap.componentHash()); range(out, cap.footprint());
        }
    }

    public static GroundExitDecision hashed(GroundExitDecision value) {
        try (var memory = CodecMemory.open()) {
            CodecMemory.charge(128);
            var hash = hash(value);
            var result = new GroundExitDecision(value.key(), value.left(), value.right(), value.terminalSQ8(),
                    value.breaks(), value.exclusions(), value.caps(), value.t0Hash(), hash);
            return memory.keep(result, 128 + CodecMemory.stringBytes(64));
        }
    }

    public static GroundExitDecision decode(byte[] bytes) throws IOException {
        try (var memory = CodecMemory.open()) {
            var value = CodecIO.decode(bytes, LIMIT, in -> {
                if (in.readInt() != 0x50474531 || in.readInt() != 2) throw new IOException("exit certificate header");
                CodecMemory.charge(192);
                var key = new Key(utf(in), utf(in), range(in));
                var left = exit(in);
                Optional<Exit> right = Optional.empty();
                if (bool(in)) { CodecMemory.charge(64); right = Optional.of(exit(in)); }
                long terminal = in.readLong(); String t0 = utf(in);
                var breaks = CodecIO.list(in, 16384, i -> {
                    CodecMemory.charge(64);
                    return new BreakInterval(range(i), enumeration(i, BreakReason.class), enumeration(i, BreakScope.class));
                });
                var exclusions = CodecIO.list(in, 16384, GroundExitCodec::range);
                var caps = CodecIO.list(in, 16384, i -> {
                    CodecMemory.charge(128);
                    return new Cap(site(i), new Edge(i.readLong(), bool(i), range(i)), i.readLong(), utf(i), range(i));
                });
                return new GroundExitDecision(key, left, right, terminal, breaks, exclusions, caps, t0, CodecIO.sha256(bytes));
            });
            return memory.keep(value, CodecMemory.retainedBytes(value));
        }
    }
    public static void validate(GroundExitDecision value) throws IOException {
        if (value.breaks().size() > 16384 || value.exclusions().size() > 16384 || value.caps().size() > 16384)
            throw new IOException("exit list bound");
    }
    private static boolean bool(DataInputStream in) throws IOException { int value = in.readUnsignedByte(); if (value > 1) throw new IOException("boolean"); return value == 1; }
    private static void range(DataOutputStream out, ArcInterval value) throws IOException { out.writeLong(value.fromQ8()); out.writeLong(value.toQ8()); }
    private static ArcInterval range(DataInputStream in) throws IOException { CodecMemory.charge(64); return new ArcInterval(in.readLong(), in.readLong()); }
    private static void site(DataOutputStream out, SiteId value) throws IOException { utf(out, value.wallId()); utf(out, value.kind().name()); out.writeLong(value.ordinal()); }
    private static SiteId site(DataInputStream in) throws IOException { CodecMemory.charge(64); return new SiteId(utf(in), enumeration(in, SiteKind.class), in.readLong()); }
    private static void exit(DataOutputStream out, Exit value) throws IOException { site(out, value.site()); out.writeLong(value.sQ8()); range(out, value.footprint()); utf(out, value.componentHash()); utf(out, value.t0Hash()); }
    private static Exit exit(DataInputStream in) throws IOException { CodecMemory.charge(64); return new Exit(site(in), in.readLong(), range(in), utf(in), utf(in)); }
    private static void utf(DataOutputStream out, String value) throws IOException { CodecMemory.writeModifiedUtf(out, value); }
    private static String utf(DataInputStream in) throws IOException { return CodecMemory.readModifiedUtf(in); }
    private static <E extends Enum<E>> E enumeration(DataInputStream in, Class<E> type) throws IOException {
        String name = utf(in);
        try { return Enum.valueOf(type, name); }
        catch (IllegalArgumentException invalid) { throw new IOException("invalid exit enum", invalid); }
        finally { CodecMemory.discard(CodecMemory.stringBytes(name.length())); }
    }
}
