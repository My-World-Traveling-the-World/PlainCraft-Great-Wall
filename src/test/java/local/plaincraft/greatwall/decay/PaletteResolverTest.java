package local.plaincraft.greatwall.decay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import local.plaincraft.greatwall.component.BeaconTower;
import local.plaincraft.greatwall.component.ComponentTemplate;
import local.plaincraft.greatwall.component.EnemyTower;
import local.plaincraft.greatwall.decay.PaletteTypes.*;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

/** Pure material and resource-adapter tests; these do not stand in for registry GameTests. */
class PaletteResolverTest {
    private static final String HASH = "a".repeat(64);
    private static final long SEED = 0x123456789abcdefL;

    @Test void all320TablesHaveTheExactOrderedWeightsAndEveryRoll() {
        var catalog = PaletteResolver.defaults();
        var resolver = new PaletteResolver(SEED, catalog);
        int count = 0;
        for (var variant : Variant.values()) for (var decay : DecayState.values()) for (var part : Part.values()) {
            var table = catalog.tables().get(variant).get(decay).get(part);
            assertEquals(variant, table.variant());
            assertEquals(decay, table.decay());
            assertEquals(part, table.part());
            assertEquals(100, table.entries().stream().mapToInt(WeightedState::weight).sum());
            assertTrue(table.entries().stream().allMatch(entry -> entry.weight() > 0));
            List<String> expected = expected(variant, decay, part);
            assertEquals(expected, table.entries().stream().map(entry ->
                    entry.block().id().substring("minecraft:".length()) + ":" + entry.weight()).toList(),
                    variant + "/" + decay + "/" + part);
            var frequencies = new HashMap<String, Integer>();
            int roll = 0;
            for (var entry : table.entries()) {
                for (int n = 0; n < entry.weight(); n++) {
                    var state = resolver.resolveVariant(variant, decay, part, roll++, Map.of());
                    assertEquals(entry.block().id(), state.id(), "half-open roll boundary");
                    frequencies.merge(state.id(), 1, Integer::sum);
                }
            }
            for (var entry : table.entries()) assertEquals(entry.weight(), frequencies.get(entry.block().id()).intValue());
            count++;
        }
        assertEquals(320, count);
        assertEquals(catalog.hash(), resolver.hash());
        assertSame(catalog, PaletteResolver.defaults());
    }

    @Test void unsignedRollsAndBoundariesDoNotRoundOrUseAbsoluteValue() {
        var resolver = new PaletteResolver(SEED, PaletteResolver.defaults());
        for (int roll : new int[] {0, 84, 100}) assertEquals("minecraft:stone_bricks",
                resolver.resolveVariant(Variant.STONE, DecayState.INTACT, Part.SKIN, roll, Map.of()).id());
        for (int roll : new int[] {85, 94}) assertEquals("minecraft:mossy_stone_bricks",
                resolver.resolveVariant(Variant.STONE, DecayState.INTACT, Part.SKIN, roll, Map.of()).id());
        for (int roll : new int[] {95, 99}) assertEquals("minecraft:cracked_stone_bricks",
                resolver.resolveVariant(Variant.STONE, DecayState.INTACT, Part.SKIN, roll, Map.of()).id());
        assertEquals("minecraft:stone_bricks",
                resolver.resolveVariant(Variant.STONE, DecayState.INTACT, Part.SKIN, -1, Map.of()).id());
        assertEquals("minecraft:stone_bricks",
                resolver.resolveVariant(Variant.STONE, DecayState.INTACT, Part.SKIN, Long.MIN_VALUE, Map.of()).id());
    }

    @Test void allFrozenPropertiesAndGeometryOverridesAreImmutable() {
        var resolver = new PaletteResolver(SEED, PaletteResolver.defaults());
        for (var variant : Variant.values()) for (var decay : DecayState.values()) {
            for (var part : List.of(Part.FLOOR, Part.SLAB, Part.ROOF)) {
                var slab = resolver.resolveVariant(variant, decay, part, 0, Map.of());
                assertEquals(Map.of("type", "top", "waterlogged", "false"), slab.properties());
                assertThrows(UnsupportedOperationException.class, () -> slab.properties().put("type", "bottom"));
            }
            assertEquals(Map.of("facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false"),
                    resolver.resolveVariant(variant, decay, Part.STAIR, 0, Map.of()).properties());
            assertEquals(Map.of("facing", "north", "lit", "false", "waterlogged", "false", "signal_fire", "true"),
                    resolver.resolveVariant(variant, decay, Part.CAMPFIRE, 0, Map.of()).properties());
            assertEquals(Map.of("axis", "y"), resolver.resolveVariant(variant, decay, Part.HAY, 0, Map.of()).properties());
            assertEquals(Map.of("axis", "y", "waterlogged", "false"),
                    resolver.resolveVariant(variant, decay, Part.CHAIN, 0, Map.of()).properties());
            assertEquals(Map.of("facing", "north", "type", "single", "waterlogged", "false"),
                    resolver.resolveVariant(variant, decay, Part.CHEST, 0, Map.of()).properties());
            var lamp = resolver.resolveVariant(variant, decay, Part.LAMP, 0, Map.of("hanging", "true"));
            assertEquals("true", lamp.properties().get("hanging"));
            assertFalse(lamp.properties().containsKey("lit"));
            assertEquals(Map.of("level", "0"), resolver.resolveVariant(variant, decay, Part.WATER, 0, Map.of()).properties());
            assertEquals(Map.of(), resolver.resolveVariant(variant, decay, Part.AIR, 0, Map.of()).properties());
        }
        var shape = new HashMap<>(Map.of("facing", "west", "shape", "inner_left", "waterlogged", "true"));
        var stair = resolver.resolveVariant(Variant.RED_SANDSTONE, DecayState.RUIN, Part.STAIR, 0, shape);
        shape.put("facing", "east");
        assertEquals(Map.of("facing", "west", "shape", "inner_left", "half", "bottom", "waterlogged", "true"),
                stair.properties());
    }

    @Test void catalogCannotSupplyFakeHashesMissingTablesOrGeometryProperties() {
        var defaults = PaletteResolver.defaults();
        invalid(() -> new PaletteResolver(0, new Catalog(defaults.tables(), "0".repeat(64))));
        var missing = copyTables(defaults);
        missing.get(Variant.STONE).get(DecayState.INTACT).remove(Part.AIR);
        invalid(() -> new PaletteResolver(0, new Catalog(missing, defaults.hash())));
        var mismatch = copyTables(defaults);
        mismatch.get(Variant.STONE).get(DecayState.INTACT).put(Part.AIR,
                defaults.tables().get(Variant.DEEPSLATE).get(DecayState.INTACT).get(Part.AIR));
        invalid(() -> new PaletteResolver(0, new Catalog(mismatch, defaults.hash())));
        for (String property : List.of("facing", "half", "type", "shape", "waterlogged", "hanging",
                "north", "east", "south", "west", "up", "down")) {
            var changed = copyTables(defaults);
            changed.get(Variant.STONE).get(DecayState.INTACT).put(Part.SKIN,
                    new Table(Variant.STONE, DecayState.INTACT, Part.SKIN,
                            List.of(new WeightedState(new BlockSpec("minecraft:stone_bricks", Map.of(property, "false")), 100))));
            invalid(() -> new PaletteResolver(0, new Catalog(changed, defaults.hash())));
        }
        assertThrows(UnsupportedOperationException.class,
                () -> defaults.tables().get(Variant.STONE).get(DecayState.INTACT).remove(Part.SKIN));
    }

    @Test void sameWorldPartUsesIndependentNamedEntropyNotCallerHashOrOwnerIdentity() {
        var resolver = new PaletteResolver(SEED, PaletteResolver.defaults());
        var input = input(SiteKind.ENEMY_TOWER, DecayState.PARTIAL, Variant.STONE, 0);
        var mixed = new ComponentInput(input.site(), input.stations(), input.supports(), input.decay(),
                PaletteFamily.SANDSTONE, true, input.waters(), List.of(
                new BiomeStation(0, "minecraft:plains", Variant.STONE),
                new BiomeStation(2048L * 256, "minecraft:dark_forest", Variant.DEEPSLATE)));
        long s = 1024L * 256;
        for (var world : List.of(new Point3i(-17, 84, 16), new Point3i(Integer.MIN_VALUE, -64, Integer.MAX_VALUE))) {
            var part = Part.SKIN;
            long familyHash = materialHash(SEED, "material-family", world, part);
            long skinHash = materialHash(SEED, "material-skin", world, part);
            assertNotEquals(familyHash, skinHash);
            var variant = PaletteBlend.choose(PaletteBlend.at(s, mixed.materials()), familyHash);
            var expected = resolver.resolveVariant(variant, input.decay().state(), part, skinHash, Map.of());
            for (long hash : new long[] {0, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
                assertEquals(expected, resolver.resolve(mixed, part, new Point3i(-50, 1, 9), world, s, hash));
                assertEquals(expected, resolver.resolve(mixed, part, new Point3i(17, -12, -9), world, s, ~hash));
            }
        }
    }

    @Test void frameDirectionsAgreeWithTheTemplateConvention() {
        var resolver = new PaletteResolver(SEED, PaletteResolver.defaults());
        String[] stairs = {"east", "south", "west", "north"};
        String[] chests = {"south", "west", "north", "east"};
        for (int i = 0; i < 4; i++) {
            var input = input(SiteKind.ENEMY_TOWER, DecayState.INTACT, Variant.STONE, i * 90);
            var world = new Point3i(16, 84, -17);
            assertEquals(stairs[i], resolver.resolve(input, Part.STAIR, new Point3i(0, -1, 0), world, 1024L * 256, 0)
                    .properties().get("facing"));
            assertEquals(chests[i], resolver.resolve(input, Part.CHEST, new Point3i(-1, -9, 0), world, 1024L * 256, 0)
                    .properties().get("facing"));
        }
    }

    @Test void emptyAndUnorderedRibbonsFailClosed() {
        var resolver = new PaletteResolver(SEED, PaletteResolver.defaults());
        var base = input(SiteKind.ENEMY_TOWER, DecayState.INTACT, Variant.STONE, 0);
        for (var ribbon : List.<List<BiomeStation>>of(List.of(),
                List.of(new BiomeStation(2048L * 256, "minecraft:plains", Variant.STONE),
                        new BiomeStation(0, "minecraft:plains", Variant.STONE)))) {
            var input = new ComponentInput(base.site(), base.stations(), base.supports(), base.decay(),
                    base.family(), false, List.of(), ribbon);
            invalid(() -> resolver.resolve(input, Part.SKIN, new Point3i(0, 0, 0), new Point3i(0, 84, 0), 1024L * 256, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(base, Part.SKIN,
                new Point3i(0, 0, 0), new Point3i(0, 84, 0), -1, 0));
    }

    /**
     * A ribbon that does not reach eight blocks past the component clamps to its nearest station.
     *
     * <p>It used to be a hard failure, and that is how a measured session lost 2176 of 2188
     * components: the stations beyond a window edge belong to the next owner, so no ribbon built for
     * one window could ever carry the halo. The blend itself has no such requirement - it extends the
     * first and last station to plus and minus infinity - so the component is built with the nearest
     * biome's material, which is what a stone wall looks like anyway.
     */
    @Test void aRibbonWithoutTheHaloClampsToItsNearestStation() {
        var resolver = new PaletteResolver(SEED, PaletteResolver.defaults());
        var base = input(SiteKind.ENEMY_TOWER, DecayState.INTACT, Variant.STONE, 0);
        for (var ribbon : List.<List<BiomeStation>>of(
                List.of(new BiomeStation(1024L * 256, "minecraft:plains", Variant.STONE)),
                List.of(new BiomeStation(1023L * 256, "minecraft:plains", Variant.STONE),
                        new BiomeStation(1025L * 256, "minecraft:plains", Variant.STONE)))) {
            var input = new ComponentInput(base.site(), base.stations(), base.supports(), base.decay(),
                    base.family(), false, List.of(), ribbon);
            for (long s : new long[] {1024L * 256, 4096L * 256}) {
                var spec = resolver.resolve(input, Part.SKIN, new Point3i(0, 0, 0), new Point3i(0, 84, 0), s, 0);
                assertNotNull(spec, "arc " + s);
            }
        }
    }

    @Test void fixedDecayTowerAndBeaconOccupancyRoutesAndFunctionalIdentitiesMatchAllFamilies() {
        var resolver = new PaletteResolver(SEED, PaletteResolver.defaults());
        for (var kind : List.of(SiteKind.ENEMY_TOWER, SiteKind.BEACON)) for (var state : DecayState.values()) {
            ComponentTemplate template = kind == SiteKind.ENEMY_TOWER
                    ? new EnemyTower(new DecayField(0), resolver) : new BeaconTower(new DecayField(0), resolver);
            ComponentVoxels baseline = null;
            for (var variant : Variant.values()) {
                var component = template.compile(input(kind, state, variant, 0));
                assertFalse(component.voxels().isEmpty(), kind + "/" + state + "/" + variant);
                if (baseline == null) baseline = component;
                else {
                    assertEquals(occupancy(baseline), occupancy(component), kind + "/" + state + "/" + variant);
                    assertEquals(baseline.requiredRoutes(), component.requiredRoutes());
                    assertEquals(baseline.blockEntities(), component.blockEntities());
                    assertEquals(baseline.spawnSlots(), component.spawnSlots());
                    assertEquals(baseline.realizedState(), component.realizedState());
                }
            }
        }
    }

    @Test void absentResourceUsesDefaultsAndCompleteResourcesAreOrderIndependent() throws Exception {
        assertSame(PaletteResolver.defaults(), PaletteResolver.load(resources(() -> null)));
        var json = json();
        var loaded = PaletteResolver.load(resources(() -> bytes(json.toString())));
        assertEquals(PaletteResolver.defaults(), loaded);
        var reversed = new JsonArray();
        var tables = json.getAsJsonArray("tables");
        for (int i = tables.size() - 1; i >= 0; i--) reversed.add(tables.get(i));
        var root = new JsonObject();
        root.add("tables", reversed);
        root.addProperty("schema", 1);
        assertEquals(loaded, PaletteResolver.load(resources(() -> bytes(root.toString()))));
    }

    @Test void contentChangesAlterShaButPropertyOrderDoesNotAndConflictingShapeFails() throws Exception {
        var root = json();
        var fire = root.getAsJsonArray("tables").get(Part.CAMPFIRE.ordinal()).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject();
        var properties = new JsonObject();
        properties.addProperty("lit", "false");
        properties.addProperty("signal_fire", "true");
        fire.add("properties", properties);
        var authored = PaletteResolver.load(resources(() -> bytes(root.toString())));
        assertNotEquals(PaletteResolver.defaults().hash(), authored.hash());
        var reversed = new JsonObject();
        reversed.addProperty("signal_fire", "true");
        reversed.addProperty("lit", "false");
        fire.add("properties", reversed);
        assertEquals(authored, PaletteResolver.load(resources(() -> bytes(root.toString()))));
        var resolver = new PaletteResolver(SEED, authored);
        invalid(() -> resolver.resolveVariant(Variant.STONE, DecayState.INTACT, Part.CAMPFIRE,
                0, Map.of("lit", "true")));
        var skin = root.getAsJsonArray("tables").get(Part.SKIN.ordinal()).getAsJsonObject().getAsJsonArray("entries");
        skin.get(0).getAsJsonObject().addProperty("weight", 86);
        skin.get(2).getAsJsonObject().addProperty("weight", 4);
        var reweighted = PaletteResolver.load(resources(() -> bytes(root.toString())));
        assertNotEquals(authored.hash(), reweighted.hash());
        assertEquals(86, reweighted.tables().get(Variant.STONE).get(DecayState.INTACT).get(Part.SKIN).entries().getFirst().weight());
    }

    @Test void malformedExistingResourcesNeverFallBackToDefaults() {
        for (String payload : List.of(
                "", "{}", "{\"schema\":1,\"schema\":1,\"tables\":[]}",
                "{\"schema\":1,\"tables\":[],\"extra\":0}",
                "{\"schema\":\"1\",\"tables\":[]}",
                "{\"schema\":1.0,\"tables\":[]}", "{\"schema\":2,\"tables\":[]}",
                "{\"schema\":1,\"tables\":[]} true",
                "{\"schema\":1,\"tables\":[{\"variant\":\"UNKNOWN\",\"decay\":\"INTACT\",\"part\":\"AIR\",\"entries\":[]}]}",
                "{\"schema\":1,\"tables\":[{\"variant\":\"STONE\",\"decay\":\"INTACT\",\"part\":\"AIR\",\"entries\":"
                        + "[{\"block\":\"minecraft:air\",\"properties\":{\"x\":\"a\",\"x\":\"b\"},\"weight\":100}]}]}")) {
            invalid(() -> PaletteResolver.load(resources(() -> bytes(payload))));
        }
        var duplicate = json();
        duplicate.getAsJsonArray("tables").set(1, duplicate.getAsJsonArray("tables").get(0).deepCopy());
        invalid(() -> PaletteResolver.load(resources(() -> bytes(duplicate.toString()))));
        var missing = json();
        missing.getAsJsonArray("tables").remove(0);
        invalid(() -> PaletteResolver.load(resources(() -> bytes(missing.toString()))));
        for (String field : List.of("weight", "block", "properties")) {
            var incomplete = json();
            incomplete.getAsJsonArray("tables").get(0).getAsJsonObject().getAsJsonArray("entries")
                    .get(0).getAsJsonObject().remove(field);
            invalid(() -> PaletteResolver.load(resources(() -> bytes(incomplete.toString()))));
        }
        for (String number : List.of("0", "-1", "101", "1.5", "2147483648")) {
            String payload = "{\"schema\":1,\"tables\":[{\"variant\":\"STONE\",\"decay\":\"INTACT\",\"part\":\"AIR\","
                    + "\"entries\":[{\"block\":\"minecraft:air\",\"properties\":{},\"weight\":" + number + "}]}]}";
            invalid(() -> PaletteResolver.load(resources(() -> bytes(payload))));
        }
        var conflict = json();
        conflict.getAsJsonArray("tables").get(0).getAsJsonObject().getAsJsonArray("entries")
                .get(0).getAsJsonObject().getAsJsonObject("properties").addProperty("waterlogged", "false");
        invalid(() -> PaletteResolver.load(resources(() -> bytes(conflict.toString()))));
    }

    @Test void resourceByteLimitUtf8AndStreamClosureAreEnforced() throws Exception {
        var oversized = new TrackedStream(new byte[1024 * 1024 + 1]);
        invalid(() -> PaletteResolver.load(resources(() -> oversized)));
        assertTrue(oversized.closed);
        var malformed = new TrackedStream(new byte[] {(byte) 0xc3, (byte) 0x28});
        invalid(() -> PaletteResolver.load(resources(() -> malformed)));
        assertTrue(malformed.closed);
        var valid = new TrackedStream(json().toString().getBytes(StandardCharsets.UTF_8));
        PaletteResolver.load(resources(() -> valid));
        assertTrue(valid.closed);
        String payload = json().toString();
        String exactLimit = payload + " ".repeat(1024 * 1024 - payload.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(PaletteResolver.defaults(), PaletteResolver.load(resources(() -> bytes(exactLimit))));
        var failed = new TrackedStream(new byte[0]) {
            @Override public byte[] readNBytes(int length) throws IOException { throw new IOException("read failed"); }
        };
        assertThrows(IOException.class, () -> PaletteResolver.load(resources(() -> failed)));
        assertTrue(failed.closed);
    }

    private static List<String> expected(Variant variant, DecayState decay, Part part) {
        if (part == Part.SKIN) {
            if (variant == Variant.STONE) return switch (decay) {
                case INTACT -> List.of("stone_bricks:85", "mossy_stone_bricks:10", "cracked_stone_bricks:5");
                case PARTIAL -> List.of("stone_bricks:40", "mossy_stone_bricks:30", "cracked_stone_bricks:20", "cobblestone:10");
                case RUIN -> List.of("stone_bricks:10", "mossy_stone_bricks:30", "cracked_stone_bricks:30", "cobblestone:30");
                case BURIED -> List.of("mossy_stone_bricks:40", "cobblestone:60");
            };
            if (variant == Variant.DEEPSLATE) return switch (decay) {
                case INTACT -> List.of("deepslate_bricks:88", "deepslate_tiles:12");
                case PARTIAL -> List.of("deepslate_bricks:45", "cracked_deepslate_bricks:25", "deepslate_tiles:20", "cobbled_deepslate:10");
                case RUIN -> List.of("deepslate_bricks:15", "cracked_deepslate_bricks:35", "cobbled_deepslate:50");
                case BURIED -> List.of("cracked_deepslate_bricks:30", "cobbled_deepslate:70");
            };
            var sand = switch (decay) {
                case INTACT -> List.of("cut_sandstone:70", "chiseled_sandstone:10", "smooth_sandstone:20");
                case PARTIAL -> List.of("cut_sandstone:35", "chiseled_sandstone:5", "smooth_sandstone:20", "sandstone:40");
                case RUIN -> List.of("cut_sandstone:20", "smooth_sandstone:10", "sandstone:70");
                case BURIED -> List.of("sandstone:100");
            };
            return variant == Variant.RED_SANDSTONE ? sand.stream().map(value -> value.replace("sandstone", "red_sandstone")).toList() : sand;
        }
        int family = variant.ordinal();
        if (part == Part.CORE) return List.of(
                List.of("cobblestone:70", "coarse_dirt:30"), List.of("cobbled_deepslate:85", "coarse_dirt:15"),
                List.of("sandstone:80", "coarse_dirt:20"), List.of("red_sandstone:80", "coarse_dirt:20")).get(family);
        if (part == Part.RUBBLE) return List.of(
                List.of("cobblestone:70", "mossy_cobblestone:30"), List.of("cobbled_deepslate:100"),
                List.of("sandstone:100"), List.of("red_sandstone:100")).get(family);
        if (part == Part.COVER) return List.of(
                List.of("coarse_dirt:70", "dirt:30"), List.of("coarse_dirt:70", "cobbled_deepslate:30"),
                List.of("sandstone:80", "coarse_dirt:20"), List.of("red_sandstone:80", "coarse_dirt:20")).get(family);
        String id = switch (part) {
            case FOUNDATION -> List.of("andesite", "polished_deepslate", "smooth_sandstone", "smooth_red_sandstone").get(family);
            case FLOOR, SLAB -> List.of("stone_brick_slab", "deepslate_brick_slab", "sandstone_slab", "red_sandstone_slab").get(family);
            case STAIR -> List.of("stone_brick_stairs", "deepslate_brick_stairs", "sandstone_stairs", "red_sandstone_stairs").get(family);
            case PARAPET -> List.of("stone_bricks", "polished_blackstone_bricks", "cut_sandstone", "cut_red_sandstone").get(family);
            case ARCH -> List.of("stone_bricks", "deepslate_bricks", "smooth_sandstone", "smooth_red_sandstone").get(family);
            case ROOF -> List.of("stone_brick_slab", "deepslate_tile_slab", "smooth_sandstone_slab", "smooth_red_sandstone_slab").get(family);
            case LAMP -> "lantern"; case CHAIN -> "chain"; case FENCE -> "iron_bars"; case CAMPFIRE -> "campfire";
            case HAY -> "hay_block"; case CHEST -> "chest"; case SPAWNER -> "spawner"; case WATER -> "water"; case AIR -> "air";
            default -> throw new AssertionError(part);
        };
        return List.of(id + ":100");
    }

    private static ComponentInput input(SiteKind kind, DecayState state, Variant variant, int heading) {
        double radians = heading * StrictMath.PI / 180;
        var frame = new Frame(new Point2q(16L * 256, -17L * 256),
                (int) StrictMath.round(StrictMath.cos(radians) * (1L << 30)),
                (int) StrictMath.round(StrictMath.sin(radians) * (1L << 30)), 84, false);
        var local = kind == SiteKind.BEACON ? new Bounds(-5, -32, -5, 5, 7, 5) : new Bounds(-5, -32, -5, 6, 8, 6);
        var bounds = Rasterizer.worldBounds(frame, local);
        var site = new SiteRecord(new SiteId("palette-fixture", kind, 1), 1024L * 256, frame,
                bounds, SiteState.PLANNED, Optional.empty(), Optional.empty());
        var supports = new HashMap<Point3i, ProbeResult>();
        for (int z = bounds.minZ(); z < bounds.maxZExclusive(); z++) for (int x = bounds.minX(); x < bounds.maxXExclusive(); x++) {
            supports.put(new Point3i(x, 84, z), new ProbeResult(ProbeKind.SUPPORTED, 64,
                    OptionalInt.empty(), OptionalInt.empty(), 0, HASH));
        }
        var amount = switch (state) {
            case INTACT -> 6554; case PARTIAL -> 27853; case RUIN -> 43581; case BURIED -> 58982;
        };
        var height = switch (state) {
            case INTACT -> 20; case PARTIAL -> 12; case RUIN -> 4; case BURIED -> 2;
        };
        return new ComponentInput(site, List.of(
                new Station(0, Projection.local(frame, -1024L * 256, 0), 84, 0),
                new Station(2048L * 256, Projection.local(frame, 1024L * 256, 0), 84, 0)),
                supports, new DecaySample(state, amount, height), PaletteFamily.STONE, false, List.of(),
                List.of(new BiomeStation(0, "minecraft:plains", variant),
                        new BiomeStation(2048L * 256, "minecraft:plains", variant)));
    }

    private static List<List<Object>> occupancy(ComponentVoxels component) {
        return component.voxels().stream().map(voxel -> List.<Object>of(voxel.pos(), voxel.kind(),
                voxel.part(), voxel.required(), voxel.walkable())).toList();
    }

    private static long materialHash(long seed, String domain, Point3i world, Part part) {
        byte[] name = part.name().getBytes(StandardCharsets.US_ASCII);
        long[] values = new long[4 + name.length];
        values[0] = world.x(); values[1] = world.y(); values[2] = world.z(); values[3] = name.length;
        for (int i = 0; i < name.length; i++) values[4 + i] = name[i] & 255;
        return DeterministicHash.fields(seed, domain, values);
    }

    private static Map<Variant, Map<DecayState, Map<Part, Table>>> copyTables(Catalog catalog) {
        var copy = new EnumMap<Variant, Map<DecayState, Map<Part, Table>>>(Variant.class);
        catalog.tables().forEach((variant, decays) -> {
            var d = new EnumMap<DecayState, Map<Part, Table>>(DecayState.class);
            decays.forEach((decay, parts) -> d.put(decay, new EnumMap<>(parts)));
            copy.put(variant, d);
        });
        return copy;
    }

    private static JsonObject json() {
        var root = new JsonObject();
        root.addProperty("schema", 1);
        var tables = new JsonArray();
        for (var variant : Variant.values()) for (var decay : DecayState.values()) for (var part : Part.values()) {
            var table = PaletteResolver.defaults().tables().get(variant).get(decay).get(part);
            var item = new JsonObject();
            item.addProperty("variant", variant.name()); item.addProperty("decay", decay.name()); item.addProperty("part", part.name());
            var entries = new JsonArray();
            for (var entry : table.entries()) {
                var value = new JsonObject();
                value.addProperty("block", entry.block().id());
                var properties = new JsonObject();
                entry.block().properties().forEach(properties::addProperty);
                value.add("properties", properties); value.addProperty("weight", entry.weight());
                entries.add(value);
            }
            item.add("entries", entries); tables.add(item);
        }
        root.add("tables", tables);
        return root;
    }

    private static ResourceManager resources(Supplier<InputStream> supplier) {
        return (ResourceManager) Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                new Class<?>[] {ResourceManager.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getResource")) throw new UnsupportedOperationException(method.getName());
                    assertEquals("plaincraft_great_wall:greatwall/palette_v1.json", args[0].toString());
                    var stream = supplier.get();
                    return stream == null ? Optional.empty() : Optional.of(new Resource(null, () -> stream));
                });
    }

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void invalid(org.junit.jupiter.api.function.Executable action) {
        assertEquals(FailureCode.INVALID_RESOURCE, assertThrows(WallFailure.class, action).code());
    }

    private static class TrackedStream extends ByteArrayInputStream {
        boolean closed;
        TrackedStream(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
