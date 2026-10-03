package local.plaincraft.greatwall.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import local.plaincraft.greatwall.persistence.DurableLog.Entry;
import local.plaincraft.greatwall.persistence.DurableLog.Record;
import local.plaincraft.greatwall.persistence.HistoryTypes.*;
import org.junit.jupiter.api.Test;

class HistoryCodecTest {
    private static final String TYPE = Kind.GATE_ACK.name();
    private static Record record(long sequence, Map<String, String> fields) throws Exception {
        var entry = new Entry("gate/key", TYPE, 1, fields);
        return new Record(sequence, entry, AtomicFiles.sha256(HistoryCodec.entry(entry)));
    }

    @Test void canonicalFieldsUseUnsignedUtf8NotMapOrUtf16Order() throws Exception {
        var a = new LinkedHashMap<String, String>();
        a.put("\uD800\uDC00", "supplementary"); a.put("\uE000", "bmp");
        var b = new LinkedHashMap<String, String>();
        b.put("\uE000", "bmp"); b.put("\uD800\uDC00", "supplementary");
        Record first = record(1, a), second = record(1, b);
        assertArrayEquals(HistoryCodec.entry(first.entry()), HistoryCodec.entry(second.entry()));
        assertArrayEquals(HistoryCodec.record(first), HistoryCodec.record(second));
        assertEquals(first, HistoryCodec.record(HistoryCodec.record(first)));
    }

    @Test void walUsesFrozenSchemaSequenceKeyTypeFieldsOrder() throws Exception {
        Record record = record(7, Map.of("a", "b"));
        try (var in = HistoryCodec.body(HistoryCodec.record(record))) {
            assertEquals(1, in.readInt()); assertEquals(7, in.readLong());
            assertEquals("gate/key", HistoryCodec.text(in)); assertEquals(TYPE, HistoryCodec.text(in));
            assertEquals(1, in.readInt()); assertEquals("a", HistoryCodec.text(in)); assertEquals("b", HistoryCodec.text(in));
            HistoryCodec.end(in);
        }
    }

    @Test void malformedUtf8SurrogatesNulAndUnknownTypesAreRejected() throws Exception {
        byte[] invalid = ByteBuffer.allocate(6).putInt(2).put((byte) 0xC0).put((byte) 0xAF).array();
        assertThrows(IOException.class, () -> HistoryCodec.text(new DataInputStream(new ByteArrayInputStream(invalid))));
        assertThrows(IOException.class, () -> HistoryCodec.entry(new Entry("x\uD800", TYPE, 1, Map.of())));
        assertThrows(IOException.class, () -> HistoryCodec.entry(new Entry("x\0y", TYPE, 1, Map.of())));
        assertThrows(IOException.class, () -> HistoryCodec.entry(new Entry("x", "UNKNOWN", 1, Map.of())));
    }

    @Test void stringLimitIs4096Utf8BytesAndPayloadHashIsValidated() throws Exception {
        assertThrows(IOException.class, () -> HistoryCodec.entry(new Entry("x", TYPE, 1,
                Map.of("wide", "\u4E2D".repeat(1366)))));
        assertThrows(IOException.class, () -> HistoryCodec.record(new Record(1, record(1, Map.of()).entry(), "0".repeat(64))));
        byte[] frame = HistoryCodec.record(record(1, Map.of()));
        frame[frame.length - 1] ^= 1;
        assertThrows(IOException.class, () -> HistoryCodec.record(frame));
    }

    @Test void headRoundTripsOnlyTwoRootsAndDetectsCrcDamage() throws Exception {
        var ref = new BlobRef("history/index/aa/" + "a".repeat(64) + ".bin", 24, "a".repeat(64));
        var head = new Head(new UUID(1, 2), 3, 5, 2,
                Map.of("by_key", ref, "by_sequence", ref), "0".repeat(64));
        byte[] bytes = HistoryCodec.head(head);
        Head decoded = HistoryCodec.head(bytes);
        assertEquals(head.world(), decoded.world()); assertEquals(head.roots(), decoded.roots());
        assertEquals(AtomicFiles.sha256(bytes), decoded.hash());
        bytes[bytes.length - 1] ^= 1;
        assertThrows(IOException.class, () -> HistoryCodec.head(bytes));
        assertThrows(IOException.class, () -> HistoryCodec.head(new Head(new UUID(1, 2), 0, 0, 1, Map.of(), "0".repeat(64))));
    }
}
