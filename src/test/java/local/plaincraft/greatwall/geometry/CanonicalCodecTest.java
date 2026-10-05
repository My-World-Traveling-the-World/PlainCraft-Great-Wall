package local.plaincraft.greatwall.geometry;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class CanonicalCodecTest {
    private static final String H = "a".repeat(64);

    private static SegmentGeometry geometry() {
        var points = List.of(new Point2q(-256, 0), new Point2q(0, 0), new Point2q(768, 1024));
        var value = new SegmentGeometry(new SegmentKey(H, "minecraft:overworld", 0), points,
                ArcLength.accumulate(points), List.of(new WaterEvidence(new ArcInterval(-128, 256),
                        WaterClass.NARROW, 256, 384, 63, List.of(new Point3i(-1, 63, 0)), List.of(), H)),
                Optional.of(new Terminal(0, 1500, TerminalKind.OCEAN)),
                new Boundary(0, points.getFirst(), 84, H), new Boundary(1, points.getLast(), 85, H), 17, 0, H);
        return new SegmentGeometry(value.key(), value.points(), value.localSQ8(), value.water(), value.terminal(),
                value.start(), value.end(), value.logicalColumns(), value.degradation(),
                DeterministicHash.sha256(CanonicalCodec.geometry(value)));
    }

    @Test void geometryRoundTripsWithStableCanonicalBytes() throws Exception {
        var value = geometry();
        byte[] bytes = CanonicalCodec.geometry(value);
        assertEquals(value, CanonicalCodec.readGeometry(bytes));
        assertArrayEquals(bytes, CanonicalCodec.geometry(CanonicalCodec.readGeometry(bytes)));
        assertEquals(1, ByteBuffer.wrap(bytes).getInt());
        assertEquals(1536L, value.localSQ8().getLast());
    }

    @Test void nestedHashesAreRetainedAndVerified() throws Exception {
        var s = new StationTable(List.of(new Station(0, new Point2q(0, 0), 84, 2)), List.of(), H);
        s = new StationTable(s.stations(), s.breaks(), DeterministicHash.sha256(CanonicalCodec.stations(s)));
        var p = new PrefixSummary(0, 0, Optional.empty(), H);
        var value = new SegmentPlan(geometry(), p, s, List.of(), H);
        byte[] bytes = CanonicalCodec.plan(value);
        var decoded = CanonicalCodec.readPlan(bytes);
        assertEquals(geometry(), decoded.geometry());
        assertEquals(s, decoded.stations());
        assertEquals(p, CanonicalCodec.readPrefix(CanonicalCodec.prefix(p)));
        byte[] corrupt = bytes.clone();
        int nestedHashStart = CanonicalCodec.geometry(geometry()).length + 4;
        corrupt[nestedHashStart] = corrupt[nestedHashStart] == 'a' ? (byte) 'b' : (byte) 'a';
        assertThrows(IOException.class, () -> CanonicalCodec.readPlan(corrupt));
    }

    @Test void rejectsTruncationTrailingBytesAndHugeLengths() {
        byte[] bytes = CanonicalCodec.geometry(geometry());
        for (int i = 0; i < bytes.length; i++) {
            byte[] truncated = Arrays.copyOf(bytes, i);
            assertThrows(IOException.class, () -> CanonicalCodec.readGeometry(truncated), "prefix=" + i);
        }
        assertThrows(IOException.class, () -> CanonicalCodec.readGeometry(Arrays.copyOf(bytes, bytes.length + 1)));
        byte[] bad = bytes.clone();
        ByteBuffer.wrap(bad).putInt(4, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> CanonicalCodec.readGeometry(bad));
        assertThrows(IOException.class, () -> CanonicalCodec.readGeometry(new byte[2 * 1024 * 1024 + 1]));
    }

    @Test void invalidUtf8BooleanAndSchemaFailClosed() {
        byte[] bytes = CanonicalCodec.prefix(new PrefixSummary(1, 64, Optional.empty(), H));
        byte[] bool = bytes.clone(); bool[20] = 2;
        assertThrows(IOException.class, () -> CanonicalCodec.readPrefix(bool));
        byte[] utf8 = bytes.clone(); utf8[25] = (byte) 0xff;
        assertThrows(IOException.class, () -> CanonicalCodec.readPrefix(utf8));
        bytes[3] = 2;
        assertThrows(IOException.class, () -> CanonicalCodec.readPrefix(bytes));
    }

    @Test void writePlanMapsAreSortedAndTallYIsNotPacked() throws Exception {
        var a = new LinkedHashMap<String, String>(); a.put("z", "last"); a.put("a", "first");
        var b = new LinkedHashMap<String, String>(); b.put("a", "first"); b.put("z", "last");
        var x = writePlan(a, 6000);
        var y = writePlan(b, 6000);
        assertArrayEquals(CanonicalCodec.writePlan(x), CanonicalCodec.writePlan(y));
        var result = CanonicalCodec.readWritePlan(CanonicalCodec.writePlan(x), new DimensionBounds(-64, 7000, 10000));
        assertEquals(6000, result.voxels().getFirst().pos().y());
        assertEquals(x.voxels(), result.voxels());
        assertThrows(IOException.class, () -> CanonicalCodec.readWritePlan(CanonicalCodec.writePlan(x),
                new DimensionBounds(-64, 320, 10000)));
    }

    @Test void recipeOmitsSessionAndDerivedIdentities() {
        var first = recipe(new UUID(0, 1), "wall-a", H);
        var second = recipe(new UUID(0, 2), "wall-b", "b".repeat(64));
        assertArrayEquals(CanonicalCodec.recipe(first), CanonicalCodec.recipe(second));
    }

    private static WorldRecipe recipe(UUID instance, String wallId, String hash) {
        return new WorldRecipe(instance, wallId, 1, "minecraft:overworld", new Point3i(0, 64, 0),
                new Axis(new Point3i(1000, 64, 0), 1 << 30, 0), new DimensionBounds(-64, 320, 29_000_000),
                new GenerationConfig(1, 1, 2048, 4, 150000, 384, 512, 4, 67108864, 67108864, 33554432),
                H, H, H, hash);
    }

    private static ChunkWritePlan writePlan(Map<String, String> properties, int y) {
        var id = new SiteId(H, SiteKind.ENEMY_TOWER, 1);
        var pos = new Point3i(-1, y, -1);
        return new ChunkWritePlan(new ChunkKey("minecraft:overworld", -1, -1), H, H,
                List.of(new VoxelIntent(pos, IntentKind.SOLID, Part.SKIN, new BlockSpec("minecraft:stone", properties),
                        id, 1, true, false)),
                List.of(new BlockProof(pos, "minecraft:air", "minecraft:stone", false, id)),
                List.of(new BlockEntityIntent(pos, "minecraft:chest", id, 0, properties)),
                List.of(pos), List.of(), List.of(), H);
    }
}
