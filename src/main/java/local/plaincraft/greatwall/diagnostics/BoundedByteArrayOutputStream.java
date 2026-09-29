package local.plaincraft.greatwall.diagnostics;

import java.io.ByteArrayOutputStream;

/** Output buffer that rejects a write before the buffer can grow beyond its fixed bound. */
public final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
    private final int limit;

    public BoundedByteArrayOutputStream(int limit) {
        super(Math.min(Math.max(limit, 1), 8192));
        if (limit <= 0) throw new IllegalArgumentException("positive output limit required");
        this.limit = limit;
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
        if (length > limit - count) throw new IllegalArgumentException("bounded output exceeds " + limit + " bytes");
        int needed = count + length;
        if (needed > buf.length) {
            int capacity = (int) Math.min(limit, Math.max((long) needed, (long) buf.length * 2));
            buf = java.util.Arrays.copyOf(buf, capacity);
        }
    }

    int capacity() { return buf.length; }
}
