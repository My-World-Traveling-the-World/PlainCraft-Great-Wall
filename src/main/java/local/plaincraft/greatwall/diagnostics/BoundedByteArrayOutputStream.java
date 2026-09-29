package local.plaincraft.greatwall.diagnostics;

import java.io.ByteArrayOutputStream;

/** Output buffer that rejects a write before the buffer can grow beyond its fixed bound. */
public final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
    private static final byte[] EMPTY = new byte[0];
    private final int limit;
    private boolean released;

    public BoundedByteArrayOutputStream(int limit) {
        this(limit, Math.min(Math.max(limit, 1), 8192));
    }

    public BoundedByteArrayOutputStream(int limit, int initialCapacity) {
        super(admit(limit, initialCapacity));
        this.limit = limit;
    }

    private static int admit(int limit, int initialCapacity) {
        if (limit <= 0 || initialCapacity < 0 || initialCapacity > limit)
            throw new IllegalArgumentException("invalid bounded output capacity");
        CodecMemory.charge(64 + CodecMemory.arrayBytes(initialCapacity));
        return initialCapacity;
    }

    @Override public synchronized void write(int value) {
        ensure(1);
        buf[count++] = (byte) value;
    }

    @Override public synchronized void write(byte[] bytes, int offset, int length) {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        ensure(length);
        System.arraycopy(bytes, offset, buf, count, length);
        count += length;
    }

    @Override public synchronized void writeBytes(byte[] bytes) {
        if (bytes == null) throw new NullPointerException("bytes");
        write(bytes, 0, bytes.length);
    }

    private void ensure(int length) {
        if (released) throw new IllegalStateException("output buffer was released");
        if (length > limit - count) throw new IllegalArgumentException("bounded output exceeds " + limit + " bytes");
        int needed = count + length;
        if (needed > buf.length) {
            int capacity = (int) Math.min(limit, Math.max((long) needed, (long) buf.length * 2));
            long oldBytes = CodecMemory.arrayBytes(buf.length), newBytes = CodecMemory.arrayBytes(capacity);
            CodecMemory.charge(newBytes);
            try { buf = java.util.Arrays.copyOf(buf, capacity); }
            catch (Throwable error) { CodecMemory.discard(newBytes); throw error; }
            CodecMemory.discard(oldBytes);
        }
    }

    @Override public synchronized byte[] toByteArray() {
        if (released) throw new IllegalStateException("output buffer was released");
        long bytes = CodecMemory.arrayBytes(count);
        CodecMemory.charge(bytes);
        try { return java.util.Arrays.copyOf(buf, count); }
        catch (Throwable error) { CodecMemory.discard(bytes); throw error; }
    }

    /** Transfers an exactly-sized buffer without a second payload-sized allocation. */
    public synchronized byte[] takeBytes() {
        if (released) throw new IllegalStateException("output buffer was released");
        if (buf.length != count) {
            byte[] result = toByteArray();
            releaseBuffer();
            return result;
        }
        byte[] result = buf;
        buf = EMPTY;
        count = 0;
        released = true;
        CodecMemory.discard(64);
        return result;
    }

    /** Explicit disposal; close() retains the compatible ByteArrayOutputStream no-op semantics. */
    public synchronized void releaseBuffer() {
        if (released) return;
        long bytes = 64 + CodecMemory.arrayBytes(buf.length);
        buf = EMPTY;
        count = 0;
        released = true;
        CodecMemory.discard(bytes);
    }

    int capacity() { return buf.length; }
}
