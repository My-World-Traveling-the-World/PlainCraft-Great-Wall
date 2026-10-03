package local.plaincraft.greatwall.lifecycle;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import local.plaincraft.greatwall.decay.PaletteResolver;
import local.plaincraft.greatwall.decay.PaletteTypes.*;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Startup/reload registry-owner validation, without reading a world or requesting chunks. */
public final class PaletteResourceValidator {
    private static final String ROOT = "greatwall/palette_v1.json";
    private static final Set<String> GEOMETRY_PROPERTIES = Set.of(
            "facing", "half", "type", "shape", "waterlogged", "hanging",
            "north", "east", "south", "west", "up", "down");
    private static final List<String> FACINGS = List.of("north", "east", "south", "west");
    private static final List<String> BOOLEANS = List.of("false", "true");

    private PaletteResourceValidator() {}

    public static Validation validate(Catalog catalog, RegistryAccess registries, BlockCatalog blocks) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(registries, "registries");
        Objects.requireNonNull(blocks, "blocks");
        var errors = new TreeSet<String>();
        var resolved = new TreeMap<String, String>();
        resolved.put("block-catalog", blocks.hash());
        try {
            new PaletteResolver(0, catalog);
        } catch (WallFailure failure) {
            errors.add(ROOT + "/catalog: " + failure.getMessage());
        }
        var baseline = new PaletteResolver(0, PaletteResolver.defaults());
        for (var variant : Variant.values()) for (var decay : DecayState.values()) for (var part : Part.values()) {
            String path = ROOT + "/tables/" + variant + "/" + decay + "/" + part;
            var decays = catalog.tables().get(variant);
            var parts = decays == null ? null : decays.get(decay);
            var table = parts == null ? null : parts.get(part);
            if (table == null) {
                errors.add(path + ": missing table");
                continue;
            }
            if (table.variant() != variant || table.decay() != decay || table.part() != part) {
                errors.add(path + ": table key mismatch");
            }
            long sum = 0;
            if (table.entries().isEmpty()) errors.add(path + ": empty entries");
            for (int i = 0; i < table.entries().size(); i++) {
                var entry = table.entries().get(i);
                sum += entry.weight();
                String entryPath = path + "/" + entry.block().id() + "/entry[" + i + "]";
                if (entry.weight() <= 0) errors.add(entryPath + ": weight must be positive");
                for (String property : new TreeSet<>(entry.block().properties().keySet())) {
                    if (GEOMETRY_PROPERTIES.contains(property)) {
                        errors.add(entryPath + ": table overrides geometry property " + property);
                    }
                }
                // Parse authored properties before applying geometry; invalid values cannot be masked.
                try {
                    blocks.resolve(entry.block());
                } catch (RuntimeException failure) {
                    errors.add(entryPath + ": " + failure.getMessage());
                    continue;
                }
                for (var geometry : shapes(part)) {
                    String shapePath = entryPath + "/shape" + new TreeMap<>(geometry);
                    try {
                        var expectedSpec = baseline.resolveVariant(variant, DecayState.INTACT, part, 0, geometry);
                        var expected = blocks.resolve(expectedSpec);
                        for (String property : List.of("axis", "lit", "signal_fire", "level")) {
                            String authored = entry.block().properties().get(property);
                            String frozen = expectedSpec.properties().get(property);
                            if (authored != null && frozen != null && !authored.equals(frozen)) {
                                throw invalid("table conflicts with frozen property " + property);
                            }
                        }
                        var properties = new TreeMap<>(expectedSpec.properties());
                        properties.putAll(entry.block().properties());
                        properties.putAll(geometry);
                        var state = blocks.resolve(new BlockSpec(entry.block().id(), properties));
                        check(state, expected, part, blocks);
                        resolved.put(shapePath, entry.weight() + ":" + BlockCatalog.token(state, state.hasBlockEntity()));
                    } catch (RuntimeException failure) {
                        errors.add(shapePath + ": " + failure.getMessage());
                    }
                }
            }
            if (sum != 100) errors.add(path + ": weights sum to " + sum + ", expected 100");
        }
        var tags = expandedTags(registries, errors);
        return new Validation(List.copyOf(errors), hash(resolved), hash(tags));
    }

    public static void requireValid(Validation validation) {
        Objects.requireNonNull(validation, "validation");
        if (!validation.errors().isEmpty()) {
            throw new WallFailure(FailureCode.INVALID_RESOURCE,
                    "invalid palette resources:\n" + String.join("\n", validation.errors()));
        }
    }

    private static void check(BlockState state, BlockState expected, Part part, BlockCatalog blocks) {
        var flags = blocks.flags(BlockCatalog.token(state, state.hasBlockEntity()));
        var expectedFlags = blocks.flags(BlockCatalog.token(expected, expected.hasBlockEntity()));
        if (state.getBlock() instanceof FallingBlock || flags.gravity()) throw invalid("gravity-bound palette block");
        if (flags.blockEntity() != expectedFlags.blockEntity() || flags.water() != expectedFlags.water()
                || state.isAir() != expected.isAir()
                || state.getFluidState().getType() != expected.getFluidState().getType()
                || state.getFluidState().isSource() != expected.getFluidState().isSource()) {
            throw invalid("air/fluid/block-entity category differs from the frozen part");
        }
        boolean typeMatches = switch (part) {
            case FLOOR, SLAB, ROOF -> state.getBlock() instanceof SlabBlock;
            case STAIR -> state.getBlock() instanceof StairBlock;
            case LAMP -> state.getBlock() instanceof LanternBlock;
            case CHAIN -> state.getBlock() instanceof ChainBlock;
            case FENCE -> state.getBlock() instanceof IronBarsBlock;
            case CAMPFIRE -> state.is(Blocks.CAMPFIRE);
            case HAY -> state.is(Blocks.HAY_BLOCK) && !state.hasBlockEntity();
            case CHEST -> state.is(Blocks.CHEST);
            case SPAWNER -> state.is(Blocks.SPAWNER);
            case WATER -> state.is(Blocks.WATER);
            case AIR -> state.is(Blocks.AIR);
            default -> true;
        };
        if (!typeMatches) throw invalid("block type differs from the frozen part");
        var view = EmptyBlockGetter.INSTANCE;
        if (state.hasOffsetFunction() || Shapes.joinIsNotEmpty(
                state.getCollisionShape(view, BlockPos.ZERO),
                expected.getCollisionShape(view, BlockPos.ZERO), BooleanOp.NOT_SAME)) {
            throw invalid("collision shape differs from the frozen part");
        }
        if (Shapes.joinIsNotEmpty(state.getBlockSupportShape(view, BlockPos.ZERO),
                expected.getBlockSupportShape(view, BlockPos.ZERO), BooleanOp.NOT_SAME)) {
            throw invalid("support shape differs from the frozen part");
        }
        // Natural foundation tags authorize terrain, not the structural skin of a built wall.
        for (var direction : Direction.values()) {
            if (state.isFaceSturdy(view, BlockPos.ZERO, direction)
                    != expected.isFaceSturdy(view, BlockPos.ZERO, direction)) {
                throw invalid("sturdy face differs from the frozen part: " + direction.getName());
            }
        }
        var actualProperties = BlockCatalog.specification(state).properties();
        var expectedProperties = BlockCatalog.specification(expected).properties();
        for (String property : List.of("axis", "lit", "signal_fire", "level")) {
            if (expectedProperties.containsKey(property)
                    && !expectedProperties.get(property).equals(actualProperties.get(property))) {
                throw invalid("frozen property differs: " + property);
            }
        }
        BlockEntityType<?> entityType = switch (part) {
            case CAMPFIRE -> BlockEntityType.CAMPFIRE;
            case CHEST -> BlockEntityType.CHEST;
            case SPAWNER -> BlockEntityType.MOB_SPAWNER;
            default -> null;
        };
        if (entityType != null) {
            if (!state.hasBlockEntity() || !entityType.isValid(state)
                    || !(state.getBlock() instanceof EntityBlock factory)) {
                throw invalid("functional block entity type mismatch");
            }
            var entity = factory.newBlockEntity(BlockPos.ZERO, state);
            if (entity == null || entity.getType() != entityType) throw invalid("functional block entity factory mismatch");
        }
    }

    private static List<Map<String, String>> shapes(Part part) {
        List<Map<String, String>> result = List.of(Map.of());
        return switch (part) {
            case STAIR -> expand(expand(expand(expand(result, "facing", FACINGS),
                    "half", List.of("bottom")), "shape",
                    List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right")), "waterlogged", BOOLEANS);
            case FLOOR, SLAB, ROOF -> expand(expand(result, "type", List.of("top")), "waterlogged", BOOLEANS);
            case CHEST -> expand(expand(expand(result, "facing", FACINGS),
                    "type", List.of("single")), "waterlogged", BOOLEANS);
            case CAMPFIRE -> expand(expand(expand(expand(result, "facing", FACINGS),
                    "lit", List.of("false")), "signal_fire", List.of("true")), "waterlogged", List.of("false"));
            case LAMP -> expand(expand(result, "hanging", BOOLEANS), "waterlogged", BOOLEANS);
            case CHAIN -> expand(expand(result, "axis", List.of("y")), "waterlogged", BOOLEANS);
            case FENCE -> expand(expand(expand(expand(expand(result, "north", BOOLEANS),
                    "east", BOOLEANS), "south", BOOLEANS), "west", BOOLEANS), "waterlogged", BOOLEANS);
            case HAY -> expand(result, "axis", List.of("y"));
            case WATER -> expand(result, "level", List.of("0"));
            default -> result;
        };
    }

    private static List<Map<String, String>> expand(List<Map<String, String>> inputs, String key, List<String> values) {
        var result = new ArrayList<Map<String, String>>(inputs.size() * values.size());
        for (var input : inputs) for (String value : values) {
            var properties = new TreeMap<>(input);
            properties.put(key, value);
            result.add(Map.copyOf(properties));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> expandedTags(RegistryAccess registries, Set<String> errors) {
        var tags = new TreeMap<String, String>();
        BuiltInRegistries.BLOCK.getTags().forEach(pair -> {
            String path = "block/" + pair.getFirst().location();
            tags.put(path + "/count", Integer.toString(pair.getSecond().size()));
            pair.getSecond().stream().map(holder -> BuiltInRegistries.BLOCK.getKey(holder.value()).toString())
                    .sorted().forEach(key -> tags.put(path + "/" + key, key));
        });
        try {
            var biomes = registries.registryOrThrow(Registries.BIOME);
            var memberships = new TreeMap<String, Set<String>>();
            biomes.getTags().forEach(pair -> {
                String name = pair.getFirst().location().toString();
                var keys = new TreeSet<String>();
                pair.getSecond().stream().forEach(holder -> keys.add(holder.unwrapKey().orElseThrow().location().toString()));
                memberships.put(name, keys);
                String path = "biome/" + name;
                tags.put(path + "/count", Integer.toString(keys.size()));
                keys.forEach(key -> tags.put(path + "/" + key, key));
            });
            // Include both the frozen resource names and the pre-S4 adapter's existing names.
            for (var names : List.of(
                    List.of("palette_sandstone", "palette_red_sandstone"),
                    List.of("sandstone_biomes", "red_sandstone_biomes"))) {
                String sand = ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", names.get(0)).toString();
                String red = ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", names.get(1)).toString();
                var sandstone = memberships.getOrDefault(sand, Set.of());
                for (String biome : memberships.getOrDefault(red, Set.of())) {
                    if (!sandstone.contains(biome)) errors.add("tags/biome/" + red + "/" + biome + ": red is not a sandstone subset");
                }
            }
        } catch (RuntimeException failure) {
            errors.add("tags/biome: " + failure.getMessage());
        }
        return tags;
    }

    private static String hash(Map<String, String> values) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeInt(values.size());
            for (var entry : new TreeMap<>(values).entrySet()) {
                string(out, entry.getKey());
                string(out, entry.getValue());
            }
        } catch (IOException impossible) {
            throw new AssertionError("memory encoding failed", impossible);
        }
        return DeterministicHash.sha256(bytes.toByteArray());
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static WallFailure invalid(String message) {
        return new WallFailure(FailureCode.INVALID_RESOURCE, message);
    }
}
