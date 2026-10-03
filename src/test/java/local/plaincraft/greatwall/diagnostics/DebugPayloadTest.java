package local.plaincraft.greatwall.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import local.plaincraft.greatwall.BuildContract;
import local.plaincraft.greatwall.model.WallTypes.Bounds;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DebugPayloadTest {
    private static final UUID SESSION = new UUID(Long.MIN_VALUE, Long.MAX_VALUE);
    private static final Point3i POINT = new Point3i(-1_000_000, 5999, 1_000_000);
    private static final DebugPayload.Line LINE = new DebugPayload.Line(POINT,
            new Point3i(Integer.MAX_VALUE, 6000, Integer.MIN_VALUE), 0xff00ff00);
    private static final DebugPayload.Box BOX = new DebugPayload.Box(
            new Bounds(-16, 5999, -16, 16, 6001, 16), 0x8000ffff);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void coordinatesUseThreeSignedIntsAndRoundTripAbovePackedYRange() {
        var value = payload(List.of(LINE), List.of(BOX), List.of(new DebugPayload.Label(POINT, "edge")));
        var buffer = buffer();
        try {
            DebugPayload.CODEC.encode(buffer, value);
            assertEquals(120, buffer.writerIndex());
            assertEquals(SESSION.getMostSignificantBits(), buffer.getLong(0));
            assertEquals(SESSION.getLeastSignificantBits(), buffer.getLong(8));
            assertEquals(123L, buffer.getLong(16));
            assertEquals(POINT.x(), buffer.getInt(36));
            assertEquals(5999, buffer.getInt(40));
            assertEquals(POINT.z(), buffer.getInt(44));
            assertEquals(Integer.MAX_VALUE, buffer.getInt(48));
            assertEquals(6000, buffer.getInt(52));
            assertEquals(Integer.MIN_VALUE, buffer.getInt(56));
            assertEquals(value, DebugPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
            assertSame(DebugPayload.TYPE, value.type());
            assertEquals(BuildContract.MOD_ID, DebugPayload.TYPE.id().getNamespace());
        } finally {
            buffer.release();
        }
    }

    @Test
    void maximumCountsAndUtf8BytesFitWithinOneBoundedPage() {
        var value = new DebugPayload(SESSION, Long.MAX_VALUE, 255, 256,
                Collections.nCopies(256, LINE), Collections.nCopies(64, BOX),
                Collections.nCopies(32, new DebugPayload.Label(POINT, "x".repeat(128))));
        var buffer = buffer();
        try {
            DebugPayload.CODEC.encode(buffer, value);
            assertEquals(13_612, buffer.readableBytes());
            assertTrue(buffer.readableBytes() <= 32_768);
            assertEquals(value, DebugPayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void encodingLimitIsPerPayloadNotPriorBufferContents() {
        var value = payload(List.of(), List.of(), List.of());
        var buffer = buffer();
        try {
            buffer.writeZero(40_000);
            int start = buffer.writerIndex();
            DebugPayload.CODEC.encode(buffer, value);
            assertEquals(44, buffer.writerIndex() - start);
            buffer.readerIndex(start);
            assertEquals(value, DebugPayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void revisionPreservesTheFrozenLongWithoutAnInventedNumericRange() {
        for (long revision : new long[] {Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
            var value = new DebugPayload(SESSION, revision, 0, 1, List.of(), List.of(), List.of());
            var buffer = buffer();
            try {
                DebugPayload.CODEC.encode(buffer, value);
                assertEquals(revision, buffer.getLong(16));
                assertEquals(value, DebugPayload.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void constructorRejectsOverLimitCollectionsBeforeCopyingTheirContents() {
        assertThrows(IllegalArgumentException.class,
                () -> payload(Collections.nCopies(257, LINE), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> payload(List.of(), Collections.nCopies(65, BOX), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> payload(List.of(), List.of(),
                        Collections.nCopies(33, new DebugPayload.Label(POINT, ""))));
        assertThrows(IllegalArgumentException.class,
                () -> payload(Collections.nCopies(Integer.MAX_VALUE, LINE), List.of(), List.of()));
    }

    @Test
    void pageBoundsAreCheckedBeforeReadingAnyCollections() {
        for (int[] invalid : new int[][] {{-1, 1}, {0, 0}, {1, 1}, {256, 256}, {0, 257}}) {
            assertThrows(IllegalArgumentException.class, () -> new DebugPayload(SESSION, 1,
                    invalid[0], invalid[1], List.of(), List.of(), List.of()));
            var buffer = buffer();
            try {
                header(buffer, invalid[0], invalid[1]);
                assertThrows(IllegalArgumentException.class, () -> DebugPayload.CODEC.decode(buffer));
                assertEquals(32, buffer.readerIndex());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void decoderRejectsNegativeExcessiveAndHugeCountsBeforeAllocatingLists() {
        int[] limits = {256, 64, 32};
        for (int section = 0; section < limits.length; section++) {
            for (int count : new int[] {-1, limits[section] + 1, Integer.MAX_VALUE}) {
                var buffer = buffer();
                try {
                    header(buffer, 0, 1);
                    for (int previous = 0; previous < section; previous++) buffer.writeInt(0);
                    buffer.writeInt(count);
                    assertThrows(IllegalArgumentException.class, () -> DebugPayload.CODEC.decode(buffer));
                    assertEquals(36 + section * 4, buffer.readerIndex());
                } finally {
                    buffer.release();
                }
            }
        }
    }

    @Test
    void decoderConsumesOnlyOneValueAndLeavesLargeTrailingStreamFieldsUntouched() {
        var value = payload(List.of(LINE), List.of(BOX), List.of(new DebugPayload.Label(POINT, "edge")));
        var buffer = buffer();
        try {
            DebugPayload.CODEC.encode(buffer, value);
            int end = buffer.writerIndex();
            buffer.writeInt(0x12345678);
            buffer.writeZero(32_769);
            assertEquals(value, DebugPayload.CODEC.decode(buffer));
            assertEquals(end, buffer.readerIndex());
            assertEquals(32_773, buffer.readableBytes());
            assertEquals(0x12345678, buffer.readInt());
            assertEquals(32_769, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void decoderRejectsLabelLengthsBeforeAllocatingText() {
        for (int length : new int[] {-1, 129, Integer.MAX_VALUE}) {
            var buffer = buffer();
            try {
                singleLabelHeader(buffer);
                buffer.writeInt(length);
                assertThrows(IllegalArgumentException.class, () -> DebugPayload.CODEC.decode(buffer));
                assertEquals(60, buffer.readerIndex());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void labelsAreLimitedByUtf8BytesRatherThanJavaCharacterCount() {
        assertDoesNotThrow(() -> new DebugPayload.Label(POINT, "x".repeat(128)));
        assertThrows(IllegalArgumentException.class, () -> new DebugPayload.Label(POINT, "x".repeat(129)));
        assertDoesNotThrow(() -> new DebugPayload.Label(POINT, "\u754c".repeat(42)));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugPayload.Label(POINT, "\u754c".repeat(43)));
        assertDoesNotThrow(() -> new DebugPayload.Label(POINT, "\ud83d\ude00".repeat(32)));
        assertThrows(IllegalArgumentException.class,
                () -> new DebugPayload.Label(POINT, "\ud83d\ude00".repeat(33)));
        for (String text : List.of("", "\u754c".repeat(42), "\ud83d\ude00".repeat(32))) {
            var value = payload(List.of(), List.of(), List.of(new DebugPayload.Label(POINT, text)));
            var buffer = buffer();
            try {
                DebugPayload.CODEC.encode(buffer, value);
                assertEquals(60 + text.getBytes(StandardCharsets.UTF_8).length, buffer.writerIndex());
                assertEquals(value, DebugPayload.CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void malformedUnicodeAndMalformedWireUtf8CannotSilentlyChangeLabels() {
        assertThrows(IllegalArgumentException.class, () -> new DebugPayload.Label(POINT, "\ud800"));
        assertThrows(IllegalArgumentException.class, () -> new DebugPayload.Label(POINT, "\udc00"));
        var buffer = buffer();
        try {
            singleLabelHeader(buffer);
            buffer.writeInt(2);
            buffer.writeByte(0xc3);
            buffer.writeByte(0x28);
            assertThrows(IllegalArgumentException.class, () -> DebugPayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void everyTruncatedPrefixFailsInsteadOfReturningAPartialPage() {
        var value = payload(List.of(LINE), List.of(BOX), List.of(new DebugPayload.Label(POINT, "edge")));
        var complete = buffer();
        try {
            DebugPayload.CODEC.encode(complete, value);
            for (int size = 0; size < complete.writerIndex(); size++) {
                var truncated = buffer();
                try {
                    truncated.writeBytes(complete, 0, size);
                    assertThrows(IllegalArgumentException.class, () -> DebugPayload.CODEC.decode(truncated),
                            "truncated size=" + size);
                } finally {
                    truncated.release();
                }
            }
        } finally {
            complete.release();
        }
    }

    @Test
    void decoderRejectsAnInvalidBoundsRecord() {
        var buffer = buffer();
        try {
            header(buffer, 0, 1);
            buffer.writeInt(0);
            buffer.writeInt(1);
            buffer.writeInt(0).writeInt(5999).writeInt(0);
            buffer.writeInt(0).writeInt(6000).writeInt(1);
            buffer.writeInt(0xffffffff);
            buffer.writeInt(0);
            assertThrows(IllegalArgumentException.class, () -> DebugPayload.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void payloadCollectionsAreDetachedAndNestedValuesCannotBeNull() {
        var lines = new ArrayList<>(List.of(LINE));
        var boxes = new ArrayList<>(List.of(BOX));
        var labels = new ArrayList<>(List.of(new DebugPayload.Label(POINT, "edge")));
        var value = payload(lines, boxes, labels);
        lines.clear();
        boxes.clear();
        labels.clear();
        assertEquals(1, value.lines().size());
        assertEquals(1, value.boxes().size());
        assertEquals(1, value.labels().size());
        assertThrows(UnsupportedOperationException.class, () -> value.lines().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.boxes().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.labels().clear());
        assertThrows(NullPointerException.class, () -> new DebugPayload.Line(null, POINT, 0));
        assertThrows(NullPointerException.class, () -> new DebugPayload.Line(POINT, null, 0));
        assertThrows(NullPointerException.class, () -> new DebugPayload.Box(null, 0));
        assertThrows(NullPointerException.class, () -> new DebugPayload.Label(null, "text"));
        assertThrows(NullPointerException.class, () -> new DebugPayload.Label(POINT, null));
        assertThrows(NullPointerException.class,
                () -> new DebugPayload(null, 0, 0, 1, List.of(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> payload(null, List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> payload(List.of(), null, List.of()));
        assertThrows(NullPointerException.class, () -> payload(List.of(), List.of(), null));
        assertThrows(NullPointerException.class,
                () -> payload(Collections.singletonList(null), List.of(), List.of()));
        assertThrows(NullPointerException.class,
                () -> payload(List.of(), Collections.singletonList(null), List.of()));
        assertThrows(NullPointerException.class,
                () -> payload(List.of(), List.of(), Collections.singletonList(null)));
    }

    @Test
    void registrationIsIdempotentAndKeepsTheSameTypeAndCodec() {
        assertDoesNotThrow(DebugPayload::registerType);
        assertDoesNotThrow(DebugPayload::registerType);
        assertSame(DebugPayload.TYPE, payload(List.of(), List.of(), List.of()).type());
        assertNotNull(DebugPayload.CODEC);
    }

    private static DebugPayload payload(List<DebugPayload.Line> lines, List<DebugPayload.Box> boxes,
            List<DebugPayload.Label> labels) {
        return new DebugPayload(SESSION, 123, 0, 1, lines, boxes, labels);
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static void header(RegistryFriendlyByteBuf buffer, int page, int totalPages) {
        buffer.writeLong(SESSION.getMostSignificantBits());
        buffer.writeLong(SESSION.getLeastSignificantBits());
        buffer.writeLong(123);
        buffer.writeInt(page);
        buffer.writeInt(totalPages);
    }

    private static void singleLabelHeader(RegistryFriendlyByteBuf buffer) {
        header(buffer, 0, 1);
        buffer.writeInt(0);
        buffer.writeInt(0);
        buffer.writeInt(1);
        buffer.writeInt(POINT.x());
        buffer.writeInt(POINT.y());
        buffer.writeInt(POINT.z());
    }
}
