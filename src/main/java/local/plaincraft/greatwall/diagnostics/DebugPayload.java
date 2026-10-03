package local.plaincraft.greatwall.diagnostics;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.BuildContract;
import local.plaincraft.greatwall.model.WallTypes.Bounds;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded server-to-client diagnostics; positions are three independent signed ints. */
public record DebugPayload(UUID session, long revision, int page, int totalPages,
        List<DebugPayload.Line> lines, List<DebugPayload.Box> boxes, List<DebugPayload.Label> labels)
        implements CustomPacketPayload {
    private static final int LINE_LIMIT = 256;
    private static final int BOX_LIMIT = 64;
    private static final int LABEL_LIMIT = 32;
    private static final int TEXT_LIMIT = 128;
    private static final int PAGE_LIMIT = 256;
    private static final int BYTE_LIMIT = 32768;
    private static final ConcurrentHashMap<String, Boolean> REGISTRATION = new ConcurrentHashMap<>();

    public record Line(Point3i from, Point3i to, int argb) {
        public Line {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    public record Box(Bounds bounds, int argb) {
        public Box {
            Objects.requireNonNull(bounds, "bounds");
        }
    }

    public record Label(Point3i pos, String text) {
        public Label {
            Objects.requireNonNull(pos, "pos");
            labelBytes(text);
        }
    }

    public static final CustomPacketPayload.Type<DebugPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BuildContract.MOD_ID, "debug"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DebugPayload> CODEC =
            StreamCodec.of(DebugPayload::encode, DebugPayload::decode);

    public DebugPayload {
        Objects.requireNonNull(session, "session");
        requirePage(page, totalPages);
        lines = boundedCopy(lines, LINE_LIMIT, "lines");
        boxes = boundedCopy(boxes, BOX_LIMIT, "boxes");
        labels = boundedCopy(labels, LABEL_LIMIT, "labels");
        requireEncodedSize(lines, boxes, labels);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void registerType() {
        REGISTRATION.computeIfAbsent("playS2C", key -> {
            PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
            return Boolean.TRUE;
        });
    }

    private static void encode(RegistryFriendlyByteBuf buffer, DebugPayload value) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(value, "value");
        requireEncodedSize(value.lines(), value.boxes(), value.labels());
        buffer.writeLong(value.session().getMostSignificantBits());
        buffer.writeLong(value.session().getLeastSignificantBits());
        buffer.writeLong(value.revision());
        buffer.writeInt(value.page());
        buffer.writeInt(value.totalPages());
        buffer.writeInt(value.lines().size());
        for (Line line : value.lines()) {
            writePoint(buffer, line.from());
            writePoint(buffer, line.to());
            buffer.writeInt(line.argb());
        }
        buffer.writeInt(value.boxes().size());
        for (Box box : value.boxes()) {
            Bounds bounds = box.bounds();
            buffer.writeInt(bounds.minX());
            buffer.writeInt(bounds.minY());
            buffer.writeInt(bounds.minZ());
            buffer.writeInt(bounds.maxXExclusive());
            buffer.writeInt(bounds.maxYExclusive());
            buffer.writeInt(bounds.maxZExclusive());
            buffer.writeInt(box.argb());
        }
        buffer.writeInt(value.labels().size());
        for (Label label : value.labels()) {
            writePoint(buffer, label.pos());
            byte[] text = labelBytes(label.text());
            buffer.writeInt(text.length);
            buffer.writeBytes(text);
        }
    }

    private static DebugPayload decode(RegistryFriendlyByteBuf buffer) {
        Objects.requireNonNull(buffer, "buffer");
        // StreamCodec consumes one value, not unrelated trailing fields in the buffer.
        int start = buffer.readerIndex();
        requireReadable(buffer, start, 32);
        UUID session = new UUID(buffer.readLong(), buffer.readLong());
        long revision = buffer.readLong();
        int page = buffer.readInt();
        int totalPages = buffer.readInt();
        requirePage(page, totalPages);

        int lineCount = readCount(buffer, start, LINE_LIMIT, 28);
        var lines = new ArrayList<Line>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            Point3i from = readPoint(buffer, start);
            Point3i to = readPoint(buffer, start);
            requireReadable(buffer, start, 4);
            lines.add(new Line(from, to, buffer.readInt()));
        }
        int boxCount = readCount(buffer, start, BOX_LIMIT, 28);
        var boxes = new ArrayList<Box>(boxCount);
        for (int i = 0; i < boxCount; i++) {
            requireReadable(buffer, start, 28);
            Bounds bounds = new Bounds(buffer.readInt(), buffer.readInt(), buffer.readInt(),
                    buffer.readInt(), buffer.readInt(), buffer.readInt());
            boxes.add(new Box(bounds, buffer.readInt()));
        }
        int labelCount = readCount(buffer, start, LABEL_LIMIT, 16);
        var labels = new ArrayList<Label>(labelCount);
        for (int i = 0; i < labelCount; i++) {
            Point3i pos = readPoint(buffer, start);
            requireReadable(buffer, start, 4);
            int length = buffer.readInt();
            if (length < 0 || length > TEXT_LIMIT) {
                throw new IllegalArgumentException("label UTF-8 length must be 0..128");
            }
            requireReadable(buffer, start, length);
            byte[] text = new byte[length];
            buffer.readBytes(text);
            try {
                String decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(text)).toString();
                labels.add(new Label(pos, decoded));
            } catch (CharacterCodingException error) {
                throw new IllegalArgumentException("invalid label UTF-8", error);
            }
        }
        return new DebugPayload(session, revision, page, totalPages, lines, boxes, labels);
    }

    private static void writePoint(RegistryFriendlyByteBuf buffer, Point3i point) {
        buffer.writeInt(point.x());
        buffer.writeInt(point.y());
        buffer.writeInt(point.z());
    }

    private static Point3i readPoint(RegistryFriendlyByteBuf buffer, int start) {
        requireReadable(buffer, start, 12);
        return new Point3i(buffer.readInt(), buffer.readInt(), buffer.readInt());
    }

    private static int readCount(RegistryFriendlyByteBuf buffer, int start, int limit, int entryBytes) {
        requireReadable(buffer, start, 4);
        int count = buffer.readInt();
        if (count < 0 || count > limit) throw new IllegalArgumentException("debug count out of bounds");
        requireReadable(buffer, start, Math.multiplyExact(count, entryBytes));
        return count;
    }

    private static void requireReadable(RegistryFriendlyByteBuf buffer, int start, int bytes) {
        int consumed = buffer.readerIndex() - start;
        if (bytes < 0 || bytes > BYTE_LIMIT - consumed || bytes > buffer.readableBytes()) {
            throw new IllegalArgumentException("truncated or oversized debug page");
        }
    }

    private static void requirePage(int page, int totalPages) {
        if (page < 0 || totalPages < 1 || totalPages > PAGE_LIMIT || page >= totalPages) {
            throw new IllegalArgumentException("0 <= page < totalPages <= 256 required");
        }
    }

    private static <T> List<T> boundedCopy(List<T> values, int limit, String name) {
        Objects.requireNonNull(values, name);
        if (values.size() > limit) throw new IllegalArgumentException(name + " exceeds page limit");
        List<T> copy = List.copyOf(values);
        if (copy.size() > limit) throw new IllegalArgumentException(name + " exceeds page limit");
        return copy;
    }

    private static byte[] labelBytes(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() > TEXT_LIMIT) throw new IllegalArgumentException("label exceeds 128 UTF-8 bytes");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
            if (encoded.remaining() > TEXT_LIMIT) {
                throw new IllegalArgumentException("label exceeds 128 UTF-8 bytes");
            }
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("invalid label Unicode", error);
        }
    }

    private static void requireEncodedSize(List<Line> lines, List<Box> boxes, List<Label> labels) {
        int bytes = 44 + Math.multiplyExact(lines.size() + boxes.size(), 28);
        for (Label label : labels) bytes = Math.addExact(bytes, 16 + labelBytes(label.text()).length);
        if (bytes > BYTE_LIMIT) throw new IllegalArgumentException("debug page exceeds 32768 bytes");
    }
}
