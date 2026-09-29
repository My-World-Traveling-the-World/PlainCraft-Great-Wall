package local.plaincraft.greatwall.geometry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Version-one semantic bytes. Only the root object's self hash is omitted. */
public final class CanonicalCodec {
    private static final int MIB = 1024 * 1024;
    private static final String ZERO = "0".repeat(64);
    private static final Comparator<String> UTF8 = (a, b) -> Arrays.compareUnsigned(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    private CanonicalCodec() {}

    public static byte[] route(RouteShape value) {
        return encode(2 * MIB, out -> {
            key(out, value.key());
            list(out, value.points(), CanonicalCodec::point);
            list(out, value.localSQ8(), DataOutputStream::writeLong);
            boundary(out, value.start()); boundary(out, value.end());
            out.writeInt(value.searchColumns()); out.writeInt(value.boundaryColumns());
            out.writeInt(value.degradation());
        });
    }

    public static byte[] geometry(SegmentGeometry value) {
        return encode(2 * MIB, out -> geometryBody(out, value));
    }

    public static SegmentGeometry readGeometry(byte[] payload) throws IOException {
        return decode(payload, 2 * MIB, in -> withHash(geometryBody(in), DeterministicHash.sha256(payload)));
    }

    public static byte[] plan(SegmentPlan value) {
        return encode(4 * MIB, out -> {
            geometryBody(out, value.geometry()); string(out, value.geometry().hash());
            prefixBody(out, value.prefix());
            stationsBody(out, value.stations()); string(out, value.stations().hash());
            list(out, value.sites(), CanonicalCodec::site);
        });
    }

    public static SegmentPlan readPlan(byte[] payload) throws IOException {
        return decode(payload, 4 * MIB, in -> {
            var geometry = geometryBody(in);
            String geometryHash = string(in);
            requireHash(geometryHash, DeterministicHash.sha256(geometry(geometry)));
            geometry = withHash(geometry, geometryHash);
            var prefix = prefixBody(in);
            var stations = stationsBody(in);
            String stationHash = string(in);
            requireHash(stationHash, DeterministicHash.sha256(stations(stations)));
            stations = new StationTable(stations.stations(), stations.breaks(), stationHash);
            return new SegmentPlan(geometry, prefix, stations, list(in, 64, CanonicalCodec::site),
                    DeterministicHash.sha256(payload));
        });
    }

    public static byte[] recipe(WorldRecipe value) {
        return encode(MIB, out -> {
            out.writeLong(value.seed()); string(out, value.dimension()); point3(out, value.frozenSpawn());
            point3(out, value.axis().origin()); out.writeInt(value.axis().exQ30()); out.writeInt(value.axis().ezQ30());
            out.writeInt(value.bounds().minY()); out.writeInt(value.bounds().maxYExclusive());
            out.writeInt(value.bounds().horizontalLimit());
            var c = value.config();
            out.writeInt(c.algorithmVersion()); out.writeInt(c.templateVersion()); out.writeInt(c.segmentBlocks());
            out.writeInt(c.stationBlocks()); out.writeInt(c.columnBudget()); out.writeInt(c.carrierAmplitude());
            out.writeInt(c.corridorHalfWidth()); out.writeInt(c.maxGeometryInflight());
            out.writeLong(c.segmentBytes()); out.writeLong(c.terrainBytes()); out.writeLong(c.compiledBytes());
            string(out, value.terrainHash()); string(out, value.paletteHash()); string(out, value.templatesHash());
        });
    }

    public static byte[] stations(StationTable value) {
        return encode(2 * MIB, out -> stationsBody(out, value));
    }

    public static byte[] prefix(PrefixSummary value) {
        return encode(4096, out -> prefixBody(out, value));
    }

    public static PrefixSummary readPrefix(byte[] payload) throws IOException {
        return decode(payload, 4096, CanonicalCodec::prefixBody);
    }

    public static byte[] component(ComponentVoxels value) {
        return encode(8 * MIB, out -> {
            siteId(out, value.id()); bounds(out, value.bounds()); list(out, value.voxels(), CanonicalCodec::voxel);
            list(out, value.blockEntities(), CanonicalCodec::blockEntity);
            list(out, value.spawnSlots(), (o, s) -> {
                siteId(o, s.site()); o.writeInt(s.slot()); point3(o, s.feet()); string(o, s.entityType());
            });
            list(out, value.requiredRoutes(), (o, points) -> list(o, points, CanonicalCodec::point3));
            enumeration(out,value.realizedState());
        });
    }

    public static byte[] writePlan(ChunkWritePlan value) {
        return encode(8 * MIB, out -> {
            chunk(out, value.owner()); string(out, value.recipeHash()); string(out, value.inputHash());
            list(out, value.voxels(), CanonicalCodec::voxel); list(out, value.proofs(), CanonicalCodec::proof);
            list(out, value.blockEntities(), CanonicalCodec::blockEntity);
            list(out, value.postprocess(), CanonicalCodec::point3); list(out, value.fluidTicks(), CanonicalCodec::point3);
            list(out, value.walkwayFeet(), CanonicalCodec::point3);
            if (!value.metadata().equals(WriteMetadata.EMPTY)) {
                out.writeInt(0x5047574d);
                string(out,value.metadata().coverageHash());out.writeLong(value.metadata().gateRevision());
                list(out,value.metadata().spawnSlots(),(o,s)->{
                    siteId(o,s.site());o.writeInt(s.slot());point3(o,s.feet());string(o,s.entityType());
                });
                var entries=value.metadata().realizedStates().entrySet().stream()
                        .sorted(Comparator.comparing((Map.Entry<SiteId,DecayState> e)->e.getKey().wallId(),UTF8)
                                .thenComparing(e->e.getKey().kind().name()).thenComparingLong(e->e.getKey().ordinal())).toList();
                list(out,entries,(o,e)->{siteId(o,e.getKey());enumeration(o,e.getValue());});
            }
        });
    }

    public static ChunkWritePlan readWritePlan(byte[] payload) throws IOException {
        return decode(payload, 8 * MIB, in -> {
            var owner = chunk(in);
            String recipe = string(in), input = string(in);
            var voxels = list(in, 8 * MIB / 12, CanonicalCodec::voxel);
            var proofs = list(in, 8 * MIB / 12, CanonicalCodec::proof);
            var entities = list(in, 8 * MIB / 12, CanonicalCodec::blockEntity);
            var postprocess = list(in, 8 * MIB / 12, CanonicalCodec::point3);
            var fluids = list(in, 8 * MIB / 12, CanonicalCodec::point3);
            var feet = list(in, 8 * MIB / 12, CanonicalCodec::point3);
            WriteMetadata metadata=WriteMetadata.EMPTY;
            if(in.available()>0) {
                if(in.readInt()!=0x5047574d) throw new IOException("unknown write metadata extension");
                String coverage=string(in);long revision=in.readLong();
                var slots=list(in,4096,i->new SpawnSlot(siteId(i),i.readInt(),point3(i),string(i)));
                var states=new LinkedHashMap<SiteId,DecayState>();
                int count=count(in,4096);
                for(int n=0;n<count;n++) if(states.put(siteId(in),enumeration(in,DecayState.class))!=null)
                    throw new IOException("duplicate realized state");
                metadata=new WriteMetadata(coverage,revision,slots,states);
            }
            var result = new ChunkWritePlan(owner, recipe, input, voxels, proofs, entities, postprocess, fluids, feet,
                    metadata,DeterministicHash.sha256(payload));
            validatePositions(result, null);
            return result;
        });
    }

    public static ChunkWritePlan readWritePlan(byte[] payload, DimensionBounds bounds) throws IOException {
        if (bounds == null) throw new NullPointerException("bounds");
        var result = readWritePlan(payload);
        validatePositions(result, bounds);
        return result;
    }

    private static void validatePositions(ChunkWritePlan value, DimensionBounds bounds) throws IOException {
        long capacity = bounds == null ? Long.MAX_VALUE : 256L * ((long) bounds.maxYExclusive() - bounds.minY());
        if (value.voxels().size() > capacity || value.proofs().size() > capacity
                || value.blockEntities().size() > capacity || value.postprocess().size() > capacity
                || value.fluidTicks().size() > capacity || value.walkwayFeet().size() > capacity) {
            throw new IOException("owner column capacity exceeded");
        }
        for (var v : value.voxels()) validatePosition(v.pos(), value.owner(), bounds);
        for (var v : value.proofs()) validatePosition(v.pos(), value.owner(), bounds);
        for (var v : value.blockEntities()) validatePosition(v.pos(), value.owner(), bounds);
        for (var v : value.postprocess()) validatePosition(v, value.owner(), bounds);
        for (var v : value.fluidTicks()) validatePosition(v, value.owner(), bounds);
        for (var v : value.walkwayFeet()) validatePosition(v, value.owner(), bounds);
        for (var v : value.metadata().spawnSlots()) validatePosition(v.feet(),value.owner(),bounds);
    }

    private static void validatePosition(Point3i p, ChunkKey owner, DimensionBounds bounds) throws IOException {
        if (Math.floorDiv(p.x(), 16) != owner.x() || Math.floorDiv(p.z(), 16) != owner.z()
                || bounds != null && (p.y() < bounds.minY() || p.y() >= bounds.maxYExclusive()
                || Math.abs((long) p.x()) > bounds.horizontalLimit() || Math.abs((long) p.z()) > bounds.horizontalLimit())) {
            throw new IOException("write position outside owner or dimension");
        }
    }

    private static void geometryBody(DataOutputStream out, SegmentGeometry value) throws IOException {
        key(out, value.key()); list(out, value.points(), CanonicalCodec::point);
        list(out, value.localSQ8(), DataOutputStream::writeLong);
        list(out, value.water(), CanonicalCodec::water); optional(out, value.terminal(), CanonicalCodec::terminal);
        boundary(out, value.start()); boundary(out, value.end());
        out.writeInt(value.logicalColumns()); out.writeInt(value.degradation());
    }

    private static SegmentGeometry geometryBody(DataInputStream in) throws IOException {
        var key = key(in);
        var points = list(in, 8193, CanonicalCodec::point);
        var lengths = list(in, 8193, DataInputStream::readLong);
        var water = list(in, 4096, CanonicalCodec::water);
        var terminal = optional(in, CanonicalCodec::terminal);
        var start = boundary(in); var end = boundary(in);
        var result = new SegmentGeometry(key, points, lengths, water, terminal, start, end,
                in.readInt(), in.readInt(), ZERO);
        if (start.index() != key.index() || end.index() != Math.addExact(key.index(), 1)
                || !points.getFirst().equals(start.point()) || !points.getLast().equals(end.point())
                || !lengths.equals(ArcLength.accumulate(points))) throw new IOException("geometry chain mismatch");
        if (terminal.isPresent() && (terminal.get().segment() != key.index() || terminal.get().localSQ8() < 0
                || terminal.get().localSQ8() > lengths.getLast())) throw new IOException("terminal outside segment");
        return result;
    }

    private static SegmentGeometry withHash(SegmentGeometry v, String hash) {
        return new SegmentGeometry(v.key(), v.points(), v.localSQ8(), v.water(), v.terminal(), v.start(), v.end(),
                v.logicalColumns(), v.degradation(), hash);
    }

    private static void stationsBody(DataOutputStream out, StationTable value) throws IOException {
        list(out, value.stations(), (o, s) -> {
            o.writeLong(s.globalSQ8()); point(o, s.point()); o.writeInt(s.walkY()); o.writeInt(s.flags());
        });
        list(out, value.breaks(), (o, b) -> { interval(o, b.range()); enumeration(o, b.reason()); enumeration(o, b.scope()); });
    }

    private static StationTable stationsBody(DataInputStream in) throws IOException {
        var stations = list(in, 16384, i -> new Station(i.readLong(), point(i), i.readInt(), i.readInt()));
        var breaks = list(in, 4096, i -> new BreakInterval(interval(i), enumeration(i, BreakReason.class),
                enumeration(i, BreakScope.class)));
        return new StationTable(stations, breaks, ZERO);
    }

    private static void prefixBody(DataOutputStream out, PrefixSummary value) throws IOException {
        out.writeLong(value.beforeSegment()); out.writeLong(value.lengthBeforeQ8());
        optional(out, value.terminal(), CanonicalCodec::terminal); string(out, value.proofHash());
    }

    private static PrefixSummary prefixBody(DataInputStream in) throws IOException {
        return new PrefixSummary(in.readLong(), in.readLong(), optional(in, CanonicalCodec::terminal), string(in));
    }

    private static void site(DataOutputStream out, SiteRecord value) throws IOException {
        siteId(out, value.id()); out.writeLong(value.globalSQ8()); frame(out, value.frame()); bounds(out, value.bounds());
        enumeration(out, value.state()); optional(out, value.absorbedBy(), CanonicalCodec::siteId);
        optional(out, value.reason(), CanonicalCodec::enumeration);
    }

    private static SiteRecord site(DataInputStream in) throws IOException {
        return new SiteRecord(siteId(in), in.readLong(), frame(in), bounds(in), enumeration(in, SiteState.class),
                optional(in, CanonicalCodec::siteId), optional(in, i -> enumeration(i, BreakReason.class)));
    }

    private static void voxel(DataOutputStream out, VoxelIntent v) throws IOException {
        point3(out, v.pos()); enumeration(out, v.kind()); enumeration(out, v.part());
        string(out, v.state().id()); map(out, v.state().properties()); siteId(out, v.component());
        out.writeInt(v.priority()); out.writeBoolean(v.required()); out.writeBoolean(v.walkable());
    }

    private static VoxelIntent voxel(DataInputStream in) throws IOException {
        return new VoxelIntent(point3(in), enumeration(in, IntentKind.class), enumeration(in, Part.class),
                new BlockSpec(string(in), map(in)), siteId(in), in.readInt(), bool(in), bool(in));
    }

    private static void proof(DataOutputStream out, BlockProof v) throws IOException {
        point3(out, v.pos()); string(out, v.beforeState()); string(out, v.afterState());
        out.writeBoolean(v.beforeHasBlockEntity()); siteId(out, v.owner());
    }

    private static BlockProof proof(DataInputStream in) throws IOException {
        return new BlockProof(point3(in), string(in), string(in), bool(in), siteId(in));
    }

    private static void blockEntity(DataOutputStream out, BlockEntityIntent v) throws IOException {
        point3(out, v.pos()); string(out, v.type()); siteId(out, v.site()); out.writeInt(v.localId()); map(out, v.fields());
    }

    private static BlockEntityIntent blockEntity(DataInputStream in) throws IOException {
        return new BlockEntityIntent(point3(in), string(in), siteId(in), in.readInt(), map(in));
    }

    private static void water(DataOutputStream out, WaterEvidence v) throws IOException {
        interval(out, v.crossing()); enumeration(out, v.classification()); out.writeLong(v.channelWidthQ8());
        out.writeLong(v.crossingSpanQ8()); out.writeInt(v.waterTopY());
        list(out, v.banks(), CanonicalCodec::point3); list(out, v.piers(), CanonicalCodec::point3); string(out, v.inputHash());
    }

    private static WaterEvidence water(DataInputStream in) throws IOException {
        return new WaterEvidence(interval(in), enumeration(in, WaterClass.class), in.readLong(), in.readLong(),
                in.readInt(), list(in, 4096, CanonicalCodec::point3), list(in, 4096, CanonicalCodec::point3), string(in));
    }

    private static void key(DataOutputStream out, SegmentKey v) throws IOException {
        string(out, v.recipeHash()); string(out, v.dimension()); out.writeLong(v.index());
    }
    private static SegmentKey key(DataInputStream in) throws IOException { return new SegmentKey(string(in), string(in), in.readLong()); }
    private static void chunk(DataOutputStream out, ChunkKey v) throws IOException { string(out, v.dimension()); out.writeInt(v.x()); out.writeInt(v.z()); }
    private static ChunkKey chunk(DataInputStream in) throws IOException { return new ChunkKey(string(in), in.readInt(), in.readInt()); }
    private static void siteId(DataOutputStream out, SiteId v) throws IOException { string(out, v.wallId()); enumeration(out, v.kind()); out.writeLong(v.ordinal()); }
    private static SiteId siteId(DataInputStream in) throws IOException { return new SiteId(string(in), enumeration(in, SiteKind.class), in.readLong()); }
    private static void point(DataOutputStream out, Point2q p) throws IOException { out.writeLong(p.xQ8()); out.writeLong(p.zQ8()); }
    private static Point2q point(DataInputStream in) throws IOException { return new Point2q(in.readLong(), in.readLong()); }
    private static void point3(DataOutputStream out, Point3i p) throws IOException { out.writeInt(p.x()); out.writeInt(p.y()); out.writeInt(p.z()); }
    private static Point3i point3(DataInputStream in) throws IOException { return new Point3i(in.readInt(), in.readInt(), in.readInt()); }
    private static void bounds(DataOutputStream out, Bounds v) throws IOException {
        out.writeInt(v.minX()); out.writeInt(v.minY()); out.writeInt(v.minZ());
        out.writeInt(v.maxXExclusive()); out.writeInt(v.maxYExclusive()); out.writeInt(v.maxZExclusive());
    }
    private static Bounds bounds(DataInputStream in) throws IOException { return new Bounds(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt()); }
    private static void frame(DataOutputStream out, Frame v) throws IOException {
        point(out, v.center()); out.writeInt(v.txQ30()); out.writeInt(v.tzQ30()); out.writeInt(v.walkY()); out.writeBoolean(v.mirror());
    }
    private static Frame frame(DataInputStream in) throws IOException { return new Frame(point(in), in.readInt(), in.readInt(), in.readInt(), bool(in)); }
    private static void boundary(DataOutputStream out, Boundary v) throws IOException { out.writeLong(v.index()); point(out, v.point()); out.writeInt(v.walkY()); string(out, v.identity()); }
    private static Boundary boundary(DataInputStream in) throws IOException { return new Boundary(in.readLong(), point(in), in.readInt(), string(in)); }
    private static void interval(DataOutputStream out, ArcInterval v) throws IOException { out.writeLong(v.fromQ8()); out.writeLong(v.toQ8()); }
    private static ArcInterval interval(DataInputStream in) throws IOException { return new ArcInterval(in.readLong(), in.readLong()); }
    private static void terminal(DataOutputStream out, Terminal v) throws IOException { out.writeLong(v.segment()); out.writeLong(v.localSQ8()); enumeration(out, v.kind()); }
    private static Terminal terminal(DataInputStream in) throws IOException { return new Terminal(in.readLong(), in.readLong(), enumeration(in, TerminalKind.class)); }

    private static void string(DataOutputStream out, String value) throws IOException {
        var bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
        if (bytes.remaining() > 4096) throw new IOException("string exceeds 4096 bytes");
        out.writeInt(bytes.remaining());
        while (bytes.hasRemaining()) out.writeByte(bytes.get());
    }

    private static String string(DataInputStream in) throws IOException {
        int count = count(in, 4096);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(in.readNBytes(count))).toString();
    }

    private static void enumeration(DataOutputStream out, Enum<?> v) throws IOException { string(out, v.name()); }
    private static <E extends Enum<E>> E enumeration(DataInputStream in, Class<E> type) throws IOException {
        try { return Enum.valueOf(type, string(in)); }
        catch (IllegalArgumentException invalid) { throw new IOException("unknown " + type.getSimpleName(), invalid); }
    }
    private static boolean bool(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) throw new IOException("invalid boolean");
        return value == 1;
    }
    private static <T> void optional(DataOutputStream out, Optional<T> v, Writer<T> writer) throws IOException {
        out.writeBoolean(v.isPresent()); if (v.isPresent()) writer.write(out, v.get());
    }
    private static <T> Optional<T> optional(DataInputStream in, Reader<T> reader) throws IOException {
        return bool(in) ? Optional.of(reader.read(in)) : Optional.empty();
    }
    private static <T> void list(DataOutputStream out, List<T> v, Writer<T> writer) throws IOException {
        out.writeInt(v.size()); for (T item : v) writer.write(out, item);
    }
    private static <T> List<T> list(DataInputStream in, int limit, Reader<T> reader) throws IOException {
        int count = count(in, limit);
        var result = new ArrayList<T>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) result.add(reader.read(in));
        return List.copyOf(result);
    }
    private static int count(DataInputStream in, int limit) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > limit || count > in.available()) throw new IOException("invalid canonical length " + count);
        return count;
    }
    private static void map(DataOutputStream out, Map<String, String> value) throws IOException {
        var keys = new ArrayList<>(value.keySet()); keys.sort(UTF8);
        out.writeInt(keys.size());
        for (String key : keys) { string(out, key); string(out, value.get(key)); }
    }
    private static Map<String, String> map(DataInputStream in) throws IOException {
        int count = count(in, 4096);
        var result = new LinkedHashMap<String, String>();
        String previous = null;
        for (int i = 0; i < count; i++) {
            String key = string(in);
            if (previous != null && UTF8.compare(previous, key) >= 0) throw new IOException("unsorted/duplicate map key");
            result.put(key, string(in)); previous = key;
        }
        return Map.copyOf(result);
    }
    private static void requireHash(String actual, String expected) throws IOException {
        if (!expected.equals(actual)) throw new IOException("nested hash mismatch");
    }
    private static byte[] encode(int limit, Encoder writer) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1); writer.write(out);
            if (bytes.size() > limit) throw new IllegalArgumentException("canonical payload exceeds limit");
            return bytes.toByteArray();
        } catch (IOException invalid) { throw new IllegalArgumentException("invalid canonical value", invalid); }
    }
    private static <T> T decode(byte[] payload, int limit, Reader<T> reader) throws IOException {
        if (payload.length > limit) throw new IOException("canonical payload exceeds limit");
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != 1) throw new IOException("unknown canonical schema");
            T result = reader.read(in);
            if (in.available() != 0) throw new IOException("trailing canonical bytes");
            return result;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("invalid canonical object", invalid);
        }
    }
    @FunctionalInterface private interface Encoder { void write(DataOutputStream out) throws IOException; }
    @FunctionalInterface private interface Writer<T> { void write(DataOutputStream out, T value) throws IOException; }
    @FunctionalInterface private interface Reader<T> { T read(DataInputStream in) throws IOException; }
}
