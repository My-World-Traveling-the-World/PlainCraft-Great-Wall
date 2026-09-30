package local.plaincraft.greatwall.diagnostics;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32C;

/** Streaming protocol primitives; output limits are independent of live allocation admission. */
public final class CodecIO {
    private CodecIO() {}
    @FunctionalInterface public interface Writer { void write(DataOutputStream out) throws IOException; }
    @FunctionalInterface public interface Reader<T> { T read(DataInputStream in) throws IOException; }
    public record Summary(int length, long crc, String sha256) {}

    public static byte[] encode(int limit, Writer writer) {
        try (var memory = CodecMemory.open()) {
            int size = size(limit, writer);
            var bytes = new BoundedByteArrayOutputStream(limit, size);
            try {
                var out = new DataOutputStream(bytes);
                writer.write(out); out.flush();
                if (bytes.size() != size) throw new IOException("canonical input changed between passes");
                return memory.keep(bytes.takeBytes(), CodecMemory.arrayBytes(size));
            } finally { bytes.releaseBuffer(); }
        } catch (IOException invalid) { throw new IllegalArgumentException("invalid canonical value", invalid); }
    }

    public static int size(int limit, Writer writer) throws IOException {
        try (var memory = CodecMemory.open()) {
            var sink = new Sink(limit, null, false);
            writer.write(new DataOutputStream(sink));
            return sink.count;
        }
    }

    public static Summary summarize(int limit, Writer writer) throws IOException {
        try (var memory = CodecMemory.open()) {
            return writeAndSummarizeInScope(null, limit, writer, memory);
        }
    }

    public static Summary writeAndSummarize(OutputStream target, int limit, Writer writer) throws IOException {
        try (var memory = CodecMemory.open()) {
            return writeAndSummarizeInScope(target, limit, writer, memory);
        }
    }

    public static String hash(int limit, Writer writer) {
        try (var memory = CodecMemory.open()) {
            var summary = writeAndSummarizeInScope(null, limit, writer, memory);
            return memory.keep(summary.sha256(), CodecMemory.stringBytes(64));
        } catch (IOException invalid) { throw new IllegalArgumentException("invalid canonical value", invalid); }
    }

    public static String sha256(byte[] bytes) {
        try (var memory = CodecMemory.open()) {
            CodecMemory.charge(CodecMemory.stringBytes(64));
            var digest = digest();
            return memory.keep(HexFormat.of().formatHex(digest.digest(bytes)), CodecMemory.stringBytes(64));
        }
    }

    public static void write(OutputStream target, int limit, Writer writer) throws IOException {
        try (var memory = CodecMemory.open()) {
            var sink = new Sink(limit, target, false);
            writer.write(new DataOutputStream(sink));
            sink.flush();
        }
    }

    private static Summary writeAndSummarizeInScope(OutputStream target, int limit, Writer writer,
            CodecMemory memory) throws IOException {
        var sink = new Sink(limit, target, true);
        writer.write(new DataOutputStream(sink));
        sink.flush();
        long retained = 128 + CodecMemory.stringBytes(64);
        CodecMemory.charge(retained);
        var result = new Summary(sink.count, sink.crc.getValue(), HexFormat.of().formatHex(sink.digest.digest()));
        return memory.keep(result, retained);
    }

    public static <T> T decode(byte[] payload, int limit, Reader<T> reader) throws IOException {
        if (payload.length > limit) throw new IOException("canonical payload exceeds limit");
        try (var memory = CodecMemory.open(); var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            T result = reader.read(in);
            if (in.read() != -1) throw new IOException("trailing canonical bytes");
            return memory.keepAll(result);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("invalid canonical object", invalid);
        }
    }

    public static int count(DataInputStream in, int max) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max || count > in.available()) throw new IOException("invalid canonical length " + count);
        return count;
    }

    public static <T> List<T> list(DataInputStream in, int max, Reader<T> reader) throws IOException {
        int count = count(in, max);
        CodecMemory.charge(CodecMemory.listBytes(count));
        // ArrayList storage and its toArray copy coexist with List.copyOf's retained array.
        try (var scratch = CodecMemory.scratch(64 + 2 * CodecMemory.arrayBytes(8L * count))) {
            var values = new ArrayList<T>(count);
            for (int i = 0; i < count; i++) values.add(reader.read(in));
            return List.copyOf(values);
        }
    }

    public static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static final class Sink extends OutputStream {
        private final int limit;
        private final OutputStream target;
        private final MessageDigest digest;
        private final CRC32C crc;
        private final byte[] buffer;
        private int buffered;
        private int count;
        Sink(int limit, OutputStream target, boolean hash) {
            if (limit <= 0) throw new IllegalArgumentException("positive byte limit required");
            this.limit = limit; this.target = target;
            if(hash||target!=null){
                CodecMemory.charge(CodecMemory.arrayBytes(8192));
                this.buffer=new byte[8192];
            }else this.buffer=null;
            this.digest = hash ? digest() : null; this.crc = hash ? new CRC32C() : null;
        }
        private void admit(int bytes) throws IOException {
            if (bytes < 0 || bytes > limit - count) throw new IOException("canonical payload exceeds " + limit);
            count += bytes;
        }
        @Override public void write(int value) throws IOException {
            admit(1);
            if(buffer==null)return;
            buffer[buffered++]=(byte)value;
            if(buffered==buffer.length)flush();
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            admit(length);
            if(buffer==null)return;
            while(length>0){
                if(buffered==0&&length>=buffer.length){direct(bytes,offset,length);return;}
                int n=Math.min(length,buffer.length-buffered);
                System.arraycopy(bytes,offset,buffer,buffered,n);
                offset+=n;length-=n;buffered+=n;
                if(buffered==buffer.length)flush();
            }
        }
        @Override public void flush() throws IOException {
            if(buffered==0)return;
            direct(buffer,0,buffered);buffered=0;
        }
        private void direct(byte[] bytes,int offset,int length)throws IOException{
            if (digest != null) { digest.update(bytes, offset, length); crc.update(bytes, offset, length); }
            if (target != null) target.write(bytes, offset, length);
        }
    }
}
