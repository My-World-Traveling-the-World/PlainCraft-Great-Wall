package local.plaincraft.greatwall.gametest;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import local.plaincraft.greatwall.decay.PaletteResolver;
import local.plaincraft.greatwall.decay.PaletteTypes.*;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.lifecycle.PaletteResourceValidator;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;

/** Uses the actual GameTest registries, not a synthetic registry or a world block lookup. */
public final class PaletteResourceValidatorGameTests implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void all320DefaultTablesBindActualBlocksAndEveryTemplateShape(GameTestHelper helper) {
        var blocks = new BlockCatalog(helper.getLevel().registryAccess());
        var catalog = PaletteResolver.defaults();
        var validation = PaletteResourceValidator.validate(catalog, helper.getLevel().registryAccess(), blocks);
        helper.assertTrue(validation.errors().isEmpty(), "native defaults rejected: " + validation.errors());
        PaletteResourceValidator.requireValid(validation);
        var repeated = PaletteResourceValidator.validate(catalog, helper.getLevel().registryAccess(), blocks);
        helper.assertTrue(validation.equals(repeated), "resolved state and expanded tag hashes must repeat");
        int count = 0;
        for (var variant : Variant.values()) for (var decay : DecayState.values()) for (var part : Part.values()) {
            var table = catalog.tables().get(variant).get(decay).get(part);
            for (var entry : table.entries()) {
                helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(entry.block().id())),
                        "actual block missing: " + entry.block().id());
            }
            count++;
        }
        helper.assertTrue(count == 320, "all four families, four states and twenty parts must be bound");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void nonexistentNamesCannotBecomeRegistryDefaultAir(GameTestHelper helper) {
        var blocks = new BlockCatalog(helper.getLevel().registryAccess());
        for (var example : List.of(
                new Example(Part.HAY, "minecraft:hay_bale", Map.of()),
                new Example(Part.SKIN, "minecraft:mossy_deepslate", Map.of()),
                new Example(Part.AIR, "plaincraft_great_wall:no_such_palette_block", Map.of()))) {
            helper.assertTrue(!BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(example.id())), "counterexample must be absent");
            var validation = validate(helper, blocks, changed(List.of(example)));
            rejected(helper, validation, "unknown block " + example.id());
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void authoredPropertyNamesAndValuesAreParsedBeforeGeometryCanMaskThem(GameTestHelper helper) {
        var blocks = new BlockCatalog(helper.getLevel().registryAccess());
        for (var example : List.of(
                new Example(Part.LAMP, "minecraft:lantern", Map.of("lit", "false")),
                new Example(Part.HAY, "minecraft:hay_block", Map.of("axis", "diagonal")),
                new Example(Part.SKIN, "minecraft:stone_bricks", Map.of("unknown", "false")),
                new Example(Part.STAIR, "minecraft:stone_brick_stairs", Map.of("shape", "not_a_shape")))) {
            var validation = validate(helper, blocks, changed(List.of(example)));
            String expected = example.part() == Part.LAMP ? "unknown property lit"
                    : example.part() == Part.SKIN ? "unknown property unknown" : "invalid block property value";
            rejected(helper, validation, expected);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void registeredGravityAndCollisionSubstitutesStillFailClosed(GameTestHelper helper) {
        var blocks = new BlockCatalog(helper.getLevel().registryAccess());
        for (var example : List.of(
                new Example(Part.FOUNDATION, "minecraft:anvil", Map.of()),
                new Example(Part.CORE, "minecraft:sand", Map.of()),
                new Example(Part.COVER, "minecraft:gravel", Map.of()),
                new Example(Part.SKIN, "minecraft:stone_brick_slab", Map.of()),
                new Example(Part.SKIN, "minecraft:mud", Map.of()),
                new Example(Part.SKIN, "minecraft:air", Map.of()),
                new Example(Part.SKIN, "minecraft:water", Map.of()))) {
            helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(example.id())), "registered counterexample required");
            var validation = validate(helper, blocks, changed(List.of(example)));
            String expected = switch (example.part()) {
                case FOUNDATION, CORE, COVER -> "gravity-bound";
                default -> example.id().endsWith(":air") || example.id().endsWith(":water") ? "category differs" : "collision shape differs";
            };
            rejected(helper, validation, expected);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void compatibleRegisteredSlabsAndStairsValidateAllDirectionsShapesAndWaterStates(GameTestHelper helper) {
        var blocks = new BlockCatalog(helper.getLevel().registryAccess());
        var catalog = changed(List.of(
                new Example(Part.FLOOR, "minecraft:spruce_slab", Map.of()),
                new Example(Part.STAIR, "minecraft:spruce_stairs", Map.of())));
        var validation = validate(helper, blocks, catalog);
        helper.assertTrue(validation.errors().isEmpty(), "compatible native shape substitutions rejected: " + validation.errors());
        var defaults = validate(helper, blocks, PaletteResolver.defaults());
        helper.assertTrue(!validation.blocksHash().equals(defaults.blocksHash()), "actual resolved state substitution must change block SHA");
        helper.assertTrue(validation.tagsHash().equals(defaults.tagsHash()), "changing a table cannot invent registry tag changes");
        helper.assertTrue(!catalog.hash().equals(PaletteResolver.defaults().hash()), "authored content changes catalog SHA");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void functionalTypesAndFrozenAxisSignalSourcePropertiesCannotBeSubstituted(GameTestHelper helper) {
        var blocks = new BlockCatalog(helper.getLevel().registryAccess());
        for (var example : List.of(
                new Example(Part.CAMPFIRE, "minecraft:soul_campfire", Map.of()),
                new Example(Part.HAY, "minecraft:stripped_oak_log", Map.of()),
                new Example(Part.CHEST, "minecraft:trapped_chest", Map.of()),
                new Example(Part.SPAWNER, "minecraft:trial_spawner", Map.of()))) {
            rejected(helper, validate(helper, blocks, changed(List.of(example))), "differs");
        }
        for (var example : List.of(
                new Example(Part.CAMPFIRE, "minecraft:campfire", Map.of("lit", "true")),
                new Example(Part.CAMPFIRE, "minecraft:campfire", Map.of("signal_fire", "false")),
                new Example(Part.HAY, "minecraft:hay_block", Map.of("axis", "x")),
                new Example(Part.CHAIN, "minecraft:chain", Map.of("axis", "x")),
                new Example(Part.WATER, "minecraft:water", Map.of("level", "1")))) {
            rejected(helper, validate(helper, blocks, changed(List.of(example))), "table conflicts with frozen property");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void geometryPropertiesAreOwnedByTemplatesEvenWhenTheirValuesParse(GameTestHelper helper) {
        var blocks = new BlockCatalog(helper.getLevel().registryAccess());
        for (var example : List.of(
                new Example(Part.STAIR, "minecraft:stone_brick_stairs", Map.of("facing", "north")),
                new Example(Part.STAIR, "minecraft:stone_brick_stairs", Map.of("half", "bottom")),
                new Example(Part.STAIR, "minecraft:stone_brick_stairs", Map.of("shape", "straight")),
                new Example(Part.SLAB, "minecraft:stone_brick_slab", Map.of("type", "top")),
                new Example(Part.STAIR, "minecraft:stone_brick_stairs", Map.of("waterlogged", "false")),
                new Example(Part.LAMP, "minecraft:lantern", Map.of("hanging", "false")),
                new Example(Part.FENCE, "minecraft:iron_bars", Map.of("north", "false")))) {
            rejected(helper, validate(helper, blocks, changed(List.of(example))), "table overrides geometry property");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void validationCollectsSortedErrorsAcrossPartsAndRequireValidRetainsThemAll(GameTestHelper helper) {
        var blocks = new BlockCatalog(helper.getLevel().registryAccess());
        var validation = validate(helper, blocks, changed(List.of(
                new Example(Part.HAY, "minecraft:hay_bale", Map.of()),
                new Example(Part.LAMP, "minecraft:lantern", Map.of("lit", "false")),
                new Example(Part.CORE, "minecraft:sand", Map.of()))));
        helper.assertTrue(validation.errors().stream().anyMatch(error -> error.contains("unknown block minecraft:hay_bale")), "missing HAY error");
        helper.assertTrue(validation.errors().stream().anyMatch(error -> error.contains("unknown property lit")), "missing LAMP error");
        helper.assertTrue(validation.errors().stream().anyMatch(error -> error.contains("gravity-bound")), "missing CORE error");
        var sorted = new ArrayList<>(validation.errors());
        sorted.sort(String::compareTo);
        helper.assertTrue(sorted.equals(validation.errors()), "errors must be sorted by path/part/resource");
        rejected(helper, validation, "unknown block minecraft:hay_bale");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void tagsShaEncodesActualExpandedBlockAndBiomeMemberships(GameTestHelper helper) {
        var blocks = new BlockCatalog(helper.getLevel().registryAccess());
        var validation = validate(helper, blocks, PaletteResolver.defaults());
        var values = new TreeMap<String, String>();
        BuiltInRegistries.BLOCK.getTags().forEach(pair -> {
            String path = "block/" + pair.getFirst().location();
            values.put(path + "/count", Integer.toString(pair.getSecond().size()));
            pair.getSecond().stream().forEach(holder -> {
                String key = BuiltInRegistries.BLOCK.getKey(holder.value()).toString();
                values.put(path + "/" + key, key);
            });
        });
        helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getTags().forEach(pair -> {
            String path = "biome/" + pair.getFirst().location();
            values.put(path + "/count", Integer.toString(pair.getSecond().size()));
            pair.getSecond().stream().forEach(holder -> {
                String key = holder.unwrapKey().orElseThrow().location().toString();
                values.put(path + "/" + key, key);
            });
        });
        helper.assertTrue(!values.isEmpty(), "actual expanded tags must not be an empty fixture");
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeInt(values.size());
            for (var entry : values.entrySet()) {
                string(out, entry.getKey());
                string(out, entry.getValue());
            }
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        helper.assertTrue(validation.tagsHash().equals(DeterministicHash.sha256(bytes.toByteArray())),
                "tag SHA must come from actual registries, independent of table contents");
        helper.succeed();
    }

    private record Example(Part part, String id, Map<String, String> properties) {}

    private static Validation validate(GameTestHelper helper, BlockCatalog blocks, Catalog catalog) {
        return PaletteResourceValidator.validate(catalog, helper.getLevel().registryAccess(), blocks);
    }

    private static void rejected(GameTestHelper helper, Validation validation, String expected) {
        helper.assertTrue(validation.errors().stream().anyMatch(error -> error.contains(expected)),
                "actual registry error missing: " + expected + " in " + validation.errors());
        boolean rejected = false;
        try {
            PaletteResourceValidator.requireValid(validation);
        } catch (WallFailure failure) {
            rejected = failure.code() == FailureCode.INVALID_RESOURCE
                    && validation.errors().stream().allMatch(failure.getMessage()::contains);
        }
        helper.assertTrue(rejected, "one INVALID_RESOURCE must preserve the complete error list");
    }

    private static Catalog changed(List<Example> examples) {
        var tables = new EnumMap<Variant, Map<DecayState, Map<Part, Table>>>(Variant.class);
        PaletteResolver.defaults().tables().forEach((variant, decays) -> {
            var copy = new EnumMap<DecayState, Map<Part, Table>>(DecayState.class);
            decays.forEach((decay, parts) -> copy.put(decay, new EnumMap<>(parts)));
            tables.put(variant, copy);
        });
        for (var example : examples) {
            tables.get(Variant.STONE).get(DecayState.INTACT).put(example.part(),
                    new Table(Variant.STONE, DecayState.INTACT, example.part(),
                            List.of(new WeightedState(new BlockSpec(example.id(), example.properties()), 100))));
        }
        // Independently encode the authored catalog so a stale hash cannot explain a negative test.
        return new Catalog(tables, catalogHash(tables));
    }

    private static String catalogHash(Map<Variant, Map<DecayState, Map<Part, Table>>> tables) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeInt(Variant.values().length * DecayState.values().length * Part.values().length);
            for (var variant : Variant.values()) for (var decay : DecayState.values()) for (var part : Part.values()) {
                string(out, variant.name()); string(out, decay.name()); string(out, part.name());
                var entries = tables.get(variant).get(decay).get(part).entries();
                out.writeInt(entries.size());
                for (var entry : entries) {
                    string(out, entry.block().id());
                    var properties = new TreeMap<>(entry.block().properties());
                    out.writeInt(properties.size());
                    for (var property : properties.entrySet()) {
                        string(out, property.getKey()); string(out, property.getValue());
                    }
                    out.writeInt(entry.weight());
                }
            }
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return DeterministicHash.sha256(bytes.toByteArray());
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
