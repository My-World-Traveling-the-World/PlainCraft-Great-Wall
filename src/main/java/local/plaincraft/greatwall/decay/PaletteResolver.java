package local.plaincraft.greatwall.decay;

import local.plaincraft.greatwall.GreatWallMod;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import local.plaincraft.greatwall.decay.PaletteTypes.*;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/** Immutable material selection. Registry binding belongs to PaletteResourceValidator. */
public final class PaletteResolver implements PaletteBinding {
    /** Components whose arc fell outside the sampled ribbon and clamped to its nearest station. */
    private static final java.util.concurrent.atomic.AtomicInteger RIBBON_CLAMPS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final int RESOURCE_LIMIT = 1024 * 1024;
    private static final int TABLE_COUNT = Variant.values().length * DecayState.values().length * Part.values().length;
    private static final Set<String> GEOMETRY_PROPERTIES = Set.of(
            "facing", "half", "type", "shape", "waterlogged", "hanging",
            "north", "east", "south", "west", "up", "down");
    private static final Catalog DEFAULTS = buildDefaults();
    private final long seed;
    private final Catalog catalog;

    public PaletteResolver(long seed, Catalog catalog) {
        this.seed = seed;
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        validateTables(catalog.tables());
        if (!catalog.hash().equals(catalogHash(catalog.tables()))) {
            throw invalid("catalog hash does not match its content");
        }
    }

    public static Catalog defaults() {
        return DEFAULTS;
    }

    public static Catalog load(ResourceManager resources) throws IOException {
        Objects.requireNonNull(resources, "resources");
        var resource = resources.getResource(ResourceLocation.fromNamespaceAndPath(
                "plaincraft_great_wall", "greatwall/palette_v1.json"));
        if (resource.isEmpty()) return defaults();
        byte[] bytes;
        try (var stream = resource.orElseThrow().open()) {
            bytes = stream.readNBytes(RESOURCE_LIMIT + 1);
        }
        if (bytes.length > RESOURCE_LIMIT) throw invalid("palette_v1.json exceeds 1 MiB");
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            try (var reader = new JsonReader(new StringReader(json))) {
                reader.setLenient(false);
                reader.beginObject();
                var names = new HashSet<String>();
                Integer schema = null;
                Map<Variant, Map<DecayState, Map<Part, Table>>> tables = null;
                while (reader.hasNext()) {
                    String name = member(reader, names);
                    switch (name) {
                        case "schema" -> schema = integer(reader);
                        case "tables" -> tables = readTables(reader);
                        default -> throw invalid("unknown root field " + name);
                    }
                }
                reader.endObject();
                if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("trailing palette content");
                if (schema == null || schema != 1 || tables == null) {
                    throw invalid("palette requires schema=1 and tables");
                }
                return createCatalog(tables);
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            throw new WallFailure(FailureCode.INVALID_RESOURCE, "invalid palette_v1.json: " + failure.getMessage(), failure);
        }
    }

    @Override
    public BlockSpec resolve(ComponentInput input, Part part, Point3i local,
            Point3i world, long globalSQ8, long voxelHash) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(world, "world");
        if (globalSQ8 < 0 || globalSQ8 > Long.MAX_VALUE - 8L * 256) {
            throw new IllegalArgumentException("global S out of range");
        }
        var ribbon = input.materials();
        if (ribbon.isEmpty()) {
            throw invalid("material ribbon is empty");
        }
        // The ribbon used to have to reach eight blocks past this component on both sides. That is
        // not a requirement of the blend: PaletteBlend.at extends the first and last station to plus
        // and minus infinity, so an arc outside the sampled range clamps to the nearest biome instead
        // of failing. It was also unsatisfiable at a window edge, where the stations beyond the edge
        // belong to the next owner: a measured session rejected 2176 of 2188 components this way. A
        // wall is one stone for hundreds of blocks, so clamping is also what the real thing looks like.
        if (ribbon.size() < 2
                || ribbon.getFirst().globalSQ8() > Math.max(0, globalSQ8 - 8L * 256)
                || ribbon.getLast().globalSQ8() < globalSQ8 + 8L * 256) {
            int clamped = RIBBON_CLAMPS.incrementAndGet();
            if (clamped <= 4 || clamped % 4096 == 0) {
                GreatWallMod.LOGGER.warn(
                        "[GreatWall:S1C] PALETTE_CLAMPED s={} ribbon=[{},{}] stations={} clamped={}",
                        globalSQ8, ribbon.getFirst().globalSQ8(), ribbon.getLast().globalSQ8(),
                        ribbon.size(), clamped);
            }
        }
        var mix = PaletteBlend.at(globalSQ8, ribbon);
        var variant = PaletteBlend.choose(mix, materialHash("material-family", world, part));
        Map<String, String> shape = switch (part) {
            case STAIR -> Map.of("facing", facing(input.site().frame(), 1, 0));
            case CHEST -> Map.of("facing", facing(input.site().frame(), 0, 1));
            default -> Map.of();
        };
        // The supplied geometry hash may include component identity; it is not material entropy.
        return resolveVariant(variant, input.decay().state(), part,
                materialHash("material-skin", world, part), shape);
    }

    public BlockSpec resolveVariant(Variant variant, DecayState decay, Part part,
            long voxelHash, Map<String, String> shapeProperties) {
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(decay, "decay");
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(shapeProperties, "shapeProperties");
        var table = catalog.tables().get(variant).get(decay).get(part);
        int roll = DeterministicHash.bounded(voxelHash, 100);
        int cumulative = 0;
        for (var entry : table.entries()) {
            cumulative += entry.weight();
            if (roll >= cumulative) continue;
            var properties = new TreeMap<>(defaultProperties(part));
            properties.putAll(entry.block().properties());
            for (var value : shapeProperties.entrySet()) {
                String authored = entry.block().properties().get(value.getKey());
                if (authored != null && !authored.equals(value.getValue())) {
                    throw invalid("table conflicts with shape property " + value.getKey());
                }
                properties.put(value.getKey(), value.getValue());
            }
            return new BlockSpec(entry.block().id(), properties);
        }
        throw invalid("table has no entry for roll " + roll);
    }

    public String hash() {
        return catalog.hash();
    }

    private long materialHash(String domain, Point3i world, Part part) {
        byte[] name = part.name().getBytes(StandardCharsets.US_ASCII);
        long[] fields = new long[4 + name.length];
        fields[0] = world.x();
        fields[1] = world.y();
        fields[2] = world.z();
        fields[3] = name.length;
        for (int i = 0; i < name.length; i++) fields[4 + i] = name[i] & 255;
        return DeterministicHash.fields(seed, domain, fields);
    }

    private static String facing(Frame frame, int dx, int dz) {
        long normal = frame.mirror() ? -(long) dz : dz;
        long x = (long) dx * frame.txQ30() - normal * frame.tzQ30();
        long z = (long) dx * frame.tzQ30() + normal * frame.txQ30();
        long best = -z;
        String result = "north";
        if (x > best) { best = x; result = "east"; }
        if (z > best) { best = z; result = "south"; }
        if (-x > best) result = "west";
        return result;
    }

    private static Map<String, String> defaultProperties(Part part) {
        return switch (part) {
            case FLOOR, SLAB, ROOF -> Map.of("type", "top", "waterlogged", "false");
            case STAIR -> Map.of("facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false");
            case CAMPFIRE -> Map.of("facing", "north", "lit", "false", "waterlogged", "false", "signal_fire", "true");
            case HAY -> Map.of("axis", "y");
            case CHEST -> Map.of("facing", "north", "type", "single", "waterlogged", "false");
            case CHAIN -> Map.of("axis", "y", "waterlogged", "false");
            case LAMP -> Map.of("hanging", "false", "waterlogged", "false");
            case FENCE -> Map.of("north", "false", "east", "false", "south", "false", "west", "false", "waterlogged", "false");
            case WATER -> Map.of("level", "0");
            default -> Map.of();
        };
    }

    private static Catalog buildDefaults() {
        var tables = new EnumMap<Variant, Map<DecayState, Map<Part, Table>>>(Variant.class);
        for (var variant : Variant.values()) {
            var decays = new EnumMap<DecayState, Map<Part, Table>>(DecayState.class);
            for (var decay : DecayState.values()) {
                var parts = new EnumMap<Part, Table>(Part.class);
                for (var part : Part.values()) {
                    parts.put(part, new Table(variant, decay, part,
                            part == Part.SKIN ? skin(variant, decay) : parts(variant, part)));
                }
                decays.put(decay, parts);
            }
            tables.put(variant, decays);
        }
        return createCatalog(tables);
    }

    private static List<WeightedState> skin(Variant variant, DecayState decay) {
        return switch (variant) {
            case STONE -> switch (decay) {
                case INTACT -> List.of(weight("stone_bricks", 85), weight("mossy_stone_bricks", 10), weight("cracked_stone_bricks", 5));
                case PARTIAL -> List.of(weight("stone_bricks", 40), weight("mossy_stone_bricks", 30),
                        weight("cracked_stone_bricks", 20), weight("cobblestone", 10));
                case RUIN -> List.of(weight("stone_bricks", 10), weight("mossy_stone_bricks", 30),
                        weight("cracked_stone_bricks", 30), weight("cobblestone", 30));
                case BURIED -> List.of(weight("mossy_stone_bricks", 40), weight("cobblestone", 60));
            };
            case DEEPSLATE -> switch (decay) {
                case INTACT -> List.of(weight("deepslate_bricks", 88), weight("deepslate_tiles", 12));
                case PARTIAL -> List.of(weight("deepslate_bricks", 45), weight("cracked_deepslate_bricks", 25),
                        weight("deepslate_tiles", 20), weight("cobbled_deepslate", 10));
                case RUIN -> List.of(weight("deepslate_bricks", 15), weight("cracked_deepslate_bricks", 35), weight("cobbled_deepslate", 50));
                case BURIED -> List.of(weight("cracked_deepslate_bricks", 30), weight("cobbled_deepslate", 70));
            };
            case SANDSTONE -> sandstoneSkin(false, decay);
            case RED_SANDSTONE -> sandstoneSkin(true, decay);
        };
    }

    private static List<WeightedState> sandstoneSkin(boolean red, DecayState decay) {
        String cut = red ? "cut_red_sandstone" : "cut_sandstone";
        String chiseled = red ? "chiseled_red_sandstone" : "chiseled_sandstone";
        String smooth = red ? "smooth_red_sandstone" : "smooth_sandstone";
        String plain = red ? "red_sandstone" : "sandstone";
        return switch (decay) {
            case INTACT -> List.of(weight(cut, 70), weight(chiseled, 10), weight(smooth, 20));
            case PARTIAL -> List.of(weight(cut, 35), weight(chiseled, 5), weight(smooth, 20), weight(plain, 40));
            case RUIN -> List.of(weight(cut, 20), weight(smooth, 10), weight(plain, 70));
            case BURIED -> List.of(weight(plain, 100));
        };
    }

    private static List<WeightedState> parts(Variant variant, Part part) {
        if (part == Part.CORE) {
            return switch (variant) {
                case STONE -> List.of(weight("cobblestone", 70), weight("coarse_dirt", 30));
                case DEEPSLATE -> List.of(weight("cobbled_deepslate", 85), weight("coarse_dirt", 15));
                case SANDSTONE -> List.of(weight("sandstone", 80), weight("coarse_dirt", 20));
                case RED_SANDSTONE -> List.of(weight("red_sandstone", 80), weight("coarse_dirt", 20));
            };
        }
        if (part == Part.RUBBLE) {
            return variant == Variant.STONE
                    ? List.of(weight("cobblestone", 70), weight("mossy_cobblestone", 30))
                    : List.of(weight(family(variant, "cobblestone", "cobbled_deepslate", "sandstone", "red_sandstone"), 100));
        }
        if (part == Part.COVER) {
            return switch (variant) {
                case STONE -> List.of(weight("coarse_dirt", 70), weight("dirt", 30));
                case DEEPSLATE -> List.of(weight("coarse_dirt", 70), weight("cobbled_deepslate", 30));
                case SANDSTONE -> List.of(weight("sandstone", 80), weight("coarse_dirt", 20));
                case RED_SANDSTONE -> List.of(weight("red_sandstone", 80), weight("coarse_dirt", 20));
            };
        }
        String id = switch (part) {
            case FOUNDATION -> family(variant, "andesite", "polished_deepslate", "smooth_sandstone", "smooth_red_sandstone");
            case FLOOR, SLAB -> family(variant, "stone_brick_slab", "deepslate_brick_slab", "sandstone_slab", "red_sandstone_slab");
            case STAIR -> family(variant, "stone_brick_stairs", "deepslate_brick_stairs", "sandstone_stairs", "red_sandstone_stairs");
            case PARAPET -> family(variant, "stone_bricks", "polished_blackstone_bricks", "cut_sandstone", "cut_red_sandstone");
            case ARCH -> family(variant, "stone_bricks", "deepslate_bricks", "smooth_sandstone", "smooth_red_sandstone");
            case ROOF -> family(variant, "stone_brick_slab", "deepslate_tile_slab", "smooth_sandstone_slab", "smooth_red_sandstone_slab");
            case LAMP -> "lantern";
            case CHAIN -> "chain";
            case FENCE -> "iron_bars";
            case CAMPFIRE -> "campfire";
            case HAY -> "hay_block";
            case CHEST -> "chest";
            case SPAWNER -> "spawner";
            case WATER -> "water";
            case AIR -> "air";
            default -> throw new AssertionError("part already handled: " + part);
        };
        return List.of(weight(id, 100));
    }

    private static String family(Variant variant, String stone, String deep, String sand, String red) {
        return switch (variant) {
            case STONE -> stone;
            case DEEPSLATE -> deep;
            case SANDSTONE -> sand;
            case RED_SANDSTONE -> red;
        };
    }

    private static WeightedState weight(String path, int amount) {
        return new WeightedState(new BlockSpec("minecraft:" + path, Map.of()), amount);
    }

    private static Catalog createCatalog(Map<Variant, Map<DecayState, Map<Part, Table>>> tables) {
        validateTables(tables);
        return new Catalog(tables, catalogHash(tables));
    }

    private static void validateTables(Map<Variant, Map<DecayState, Map<Part, Table>>> tables) {
        for (var variant : Variant.values()) {
            var decays = tables.get(variant);
            if (decays == null) throw invalid("missing variant " + variant);
            for (var decay : DecayState.values()) {
                var parts = decays.get(decay);
                if (parts == null) throw invalid("missing decay " + variant + "/" + decay);
                for (var part : Part.values()) {
                    String path = variant + "/" + decay + "/" + part;
                    var table = parts.get(part);
                    if (table == null) throw invalid("missing table " + path);
                    if (table.variant() != variant || table.decay() != decay || table.part() != part) {
                        throw invalid("table key mismatch " + path);
                    }
                    long sum = 0;
                    if (table.entries().isEmpty() || table.entries().size() > 100) throw invalid("invalid entries " + path);
                    for (var entry : table.entries()) {
                        if (entry.weight() <= 0) throw invalid("nonpositive weight " + path);
                        sum += entry.weight();
                        for (String key : entry.block().properties().keySet()) {
                            if (GEOMETRY_PROPERTIES.contains(key)) {
                                throw invalid("table overrides geometry property " + path + "/" + entry.block().id() + "/" + key);
                            }
                        }
                    }
                    if (sum != 100) throw invalid("table weight must sum to 100: " + path);
                }
            }
        }
    }

    private static String catalogHash(Map<Variant, Map<DecayState, Map<Part, Table>>> tables) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeInt(TABLE_COUNT);
            for (var variant : Variant.values()) for (var decay : DecayState.values()) for (var part : Part.values()) {
                string(out, variant.name());
                string(out, decay.name());
                string(out, part.name());
                var entries = tables.get(variant).get(decay).get(part).entries();
                out.writeInt(entries.size());
                for (var entry : entries) {
                    string(out, entry.block().id());
                    var properties = new TreeMap<>(entry.block().properties());
                    out.writeInt(properties.size());
                    for (var property : properties.entrySet()) {
                        string(out, property.getKey());
                        string(out, property.getValue());
                    }
                    out.writeInt(entry.weight());
                }
            }
        } catch (IOException impossible) {
            throw new AssertionError("memory encoding failed", impossible);
        }
        return DeterministicHash.sha256(bytes.toByteArray());
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) throw invalid("canonical string exceeds 4096 bytes");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static Map<Variant, Map<DecayState, Map<Part, Table>>> readTables(JsonReader reader) throws IOException {
        var tables = new EnumMap<Variant, Map<DecayState, Map<Part, Table>>>(Variant.class);
        reader.beginArray();
        int count = 0;
        while (reader.hasNext()) {
            if (++count > TABLE_COUNT) throw invalid("too many tables");
            Table table = readTable(reader);
            var parts = tables.computeIfAbsent(table.variant(), key -> new EnumMap<>(DecayState.class))
                    .computeIfAbsent(table.decay(), key -> new EnumMap<>(Part.class));
            if (parts.putIfAbsent(table.part(), table) != null) {
                throw invalid("duplicate table " + table.variant() + "/" + table.decay() + "/" + table.part());
            }
        }
        reader.endArray();
        return tables;
    }

    private static Table readTable(JsonReader reader) throws IOException {
        reader.beginObject();
        var names = new HashSet<String>();
        Variant variant = null;
        DecayState decay = null;
        Part part = null;
        List<WeightedState> entries = null;
        while (reader.hasNext()) {
            String name = member(reader, names);
            switch (name) {
                case "variant" -> variant = Variant.valueOf(text(reader));
                case "decay" -> decay = DecayState.valueOf(text(reader));
                case "part" -> part = Part.valueOf(text(reader));
                case "entries" -> entries = readEntries(reader);
                default -> throw invalid("unknown table field " + name);
            }
        }
        reader.endObject();
        if (variant == null || decay == null || part == null || entries == null) throw invalid("incomplete table");
        return new Table(variant, decay, part, entries);
    }

    private static List<WeightedState> readEntries(JsonReader reader) throws IOException {
        reader.beginArray();
        var entries = new ArrayList<WeightedState>();
        while (reader.hasNext()) {
            if (entries.size() >= 100) throw invalid("too many weighted states");
            reader.beginObject();
            var names = new HashSet<String>();
            String block = null;
            Map<String, String> properties = null;
            Integer weight = null;
            while (reader.hasNext()) {
                String name = member(reader, names);
                switch (name) {
                    case "block" -> block = text(reader);
                    case "properties" -> properties = readProperties(reader);
                    case "weight" -> weight = integer(reader);
                    default -> throw invalid("unknown entry field " + name);
                }
            }
            reader.endObject();
            if (block == null || properties == null || weight == null) throw invalid("incomplete weighted state");
            entries.add(new WeightedState(new BlockSpec(block, properties), weight));
        }
        reader.endArray();
        return entries;
    }

    private static Map<String, String> readProperties(JsonReader reader) throws IOException {
        reader.beginObject();
        var properties = new TreeMap<String, String>();
        var names = new HashSet<String>();
        while (reader.hasNext()) {
            if (properties.size() >= 64) throw invalid("too many block properties");
            String name = member(reader, names);
            properties.put(name, text(reader));
        }
        reader.endObject();
        return properties;
    }

    private static String member(JsonReader reader, Set<String> names) throws IOException {
        String name = reader.nextName();
        if (!names.add(name)) throw invalid("duplicate field " + name);
        return name;
    }

    private static String text(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw invalid("string required at " + reader.getPath());
        String value = reader.nextString();
        if (value.getBytes(StandardCharsets.UTF_8).length > 4096) throw invalid("string exceeds 4096 bytes");
        return value;
    }

    private static int integer(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw invalid("integer required at " + reader.getPath());
        String value = reader.nextString();
        if (!value.matches("-?(0|[1-9][0-9]*)")) throw invalid("integer required at " + reader.getPath());
        return Integer.parseInt(value);
    }

    private static WallFailure invalid(String message) {
        return new WallFailure(FailureCode.INVALID_RESOURCE, message);
    }
}
