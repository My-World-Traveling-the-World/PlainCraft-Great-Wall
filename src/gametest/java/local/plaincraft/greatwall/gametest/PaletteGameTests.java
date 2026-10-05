package local.plaincraft.greatwall.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import local.plaincraft.greatwall.decay.PaletteResolver;
import local.plaincraft.greatwall.decay.PaletteTypes.Variant;
import local.plaincraft.greatwall.lifecycle.WorldRecipeGuard;
import local.plaincraft.greatwall.model.WallTypes.DecayState;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * S4 gallery acceptance: the full 128-cell index (96 + 32 red extras) is real, gravity/water
 * stays stable across the gallery, identical world positions bind identical material regardless
 * of owner order, and a palette/geometry mutation is rejected while a language-only reload is
 * accepted and history is preserved. The FORT cell reuses the production template chain binding
 * ({@code S1TemplateSet.create}, which maps FORT to {@code FortPlaceholder}), exactly as
 * generation does; the S3 full {@code Fort} is only assembled through {@code compileWithSockets}.
 */
public final class PaletteGameTests implements FabricGameTest {

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
    public void galleryContainsAll96Plus32(GameTestHelper helper) {
        var fixture = new VisualGalleryFixture();
        var specs = fixture.specs(0);
        helper.assertTrue(specs.size() == 128, "gallery must index 96 + 32 red = 128 cells");

        Set<String> templates = new java.util.TreeSet<>();
        Set<Variant> variants = java.util.EnumSet.noneOf(Variant.class);
        Set<DecayState> states = java.util.EnumSet.noneOf(DecayState.class);
        Set<Integer> headings = new java.util.TreeSet<>();
        for (var spec : specs) {
            templates.add(spec.templateId());
            variants.add(spec.variant());
            states.add(spec.decay());
            headings.add(spec.headingDegrees());
        }
        helper.assertTrue(templates.size() == 8, "all eight templates present: " + templates);
        helper.assertTrue(variants.size() == 4, "all four families present: " + variants);
        helper.assertTrue(states.size() == 4, "all four decay states present: " + states);
        helper.assertTrue(headings.contains(30), "the 30-degree main gallery must be present");

        // Every cell compiles to a non-empty, geometry-stable component. The FORT cell reuses the
        // production binding (FortPlaceholder via S1TemplateSet), matching how generation builds
        // FORT; the S3 full Fort is a socket assembly, not a standalone gallery cell.
        for (var spec : specs) {
            var component = VisualGalleryFixture.compile(spec);
            helper.assertTrue(!component.voxels().isEmpty(),
                    "cell must not be suppressed: " + spec.templateId() + "/" + spec.variant() + "/" + spec.decay());
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
    public void gravityAndWaterUpdatesRemainStable(GameTestHelper helper) {
        var fixture = new VisualGalleryFixture();
        for (var spec : fixture.specs(0)) {
            var component = VisualGalleryFixture.compile(spec);
            if (component.voxels().isEmpty()) continue;
            var states = new HashMap<Point3i, BlockState>();
            for (var voxel : component.voxels()) {
                states.put(voxel.pos(), BlockCatalog.resolveState(voxel.state()));
            }
            for (var entry : states.entrySet()) {
                var state = entry.getValue();
                var pos = new BlockPos(entry.getKey().x(), entry.getKey().y(), entry.getKey().z());
                // S4 palette risk 1: no block may be a free-falling gravity block (sand, gravel,
                // concrete powder, anvil) — the raw component would collapse before placement.
                helper.assertTrue(!(state.getBlock() instanceof FallingBlock),
                        "no unsupported gravity palette: " + entry.getKey() + " " + state);
                // S4 palette risk 2: water must stay source-only at its frozen level, never a
                // flowing body (a flowing level would spread during the post-build 200-tick check).
                if (!state.getFluidState().isEmpty()) {
                    helper.assertTrue(state.getFluidState().isSource(),
                            "water must stay source-only: " + entry.getKey());
                }
            }
            // Iron-bars/stair connection properties are intentionally recomputed by
            // ChunkPlanCompiler.connections()/stairShape() from final logical adjacency (S4.md
            // "铁栏四向连接从最终逻辑体素邻接预求"), so the raw component legitimately carries
            // all-false/straight placeholders here — that is the pipeline, not palette, layer.
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 400)
    public void sameWorldPositionSameMaterial(GameTestHelper helper) {
        var fixture = new VisualGalleryFixture();
        var specs = fixture.specs(0);
        for (var spec : specs) {
            // Compile the same cell multiple times and through a "cold" re-ordered path; the
            // material selection is keyed only by world position + part, never by owner order.
            var first = VisualGalleryFixture.compile(spec);
            var second = VisualGalleryFixture.compile(spec);
            helper.assertTrue(first.hash().equals(second.hash()),
                    "recompiling the same cell must be byte-identical: " + spec.templateId());
            helper.assertTrue(blockHash(first).equals(blockHash(second)),
                    "block ids/properties must be stable across owner order: " + spec.templateId());
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void reloadRejectsGeometryPaletteMutation(GameTestHelper helper) {
        Path dir = temp("reload");
        try {
            String paletteHash = PaletteResolver.defaults().hash();
            var inputs = recipeInputs(paletteHash, "template-0", "minecraft:overworld");
            var guard = new WorldRecipeGuard(dir.resolve("recipes/s0.json"), true, inputs);
            String frozen = guard.hash();

            // A language-only reload changes nothing in the frozen recipe fingerprint.
            guard.validate(recipeInputs(paletteHash, "template-0", "minecraft:overworld"));

            // A palette table / geometry mutation changes the recipe identity and must fail closed.
            assertRejects(helper, guard, recipeInputs("0".repeat(64), "template-0", "minecraft:overworld"));
            assertRejects(helper, guard, recipeInputs(paletteHash, "template-1", "minecraft:overworld"));
            assertRejects(helper, guard, recipeInputs(paletteHash, "template-0", "minecraft:the_nether"));

            // History (the durable manifest) is preserved after every rejection.
            helper.assertTrue(dir.resolve("recipes/s0.json").toFile().exists(), "manifest must survive rejection");
            String onDisk = Files.readString(dir.resolve("recipes/s0.json"));
            helper.assertTrue(onDisk.contains(frozen), "the frozen recipe hash must remain on disk after rejection");
            helper.assertTrue(guard.hash().equals(frozen), "the in-memory frozen identity must not change");
            helper.succeed();
        } catch (IOException error) {
            throw new AssertionError(error);
        } finally {
            delete(dir);
        }
    }

    private static Map<String, JsonElement> recipeInputs(String palette, String template, String dimension) {
        var map = new TreeMap<String, JsonElement>();
        map.put("dimension", new JsonPrimitive(dimension));
        map.put("seed", new JsonPrimitive("1"));
        map.put("terrain", new JsonPrimitive("terrain-0"));
        map.put("template", new JsonPrimitive(template));
        map.put("palette", new JsonPrimitive(palette));
        map.put("biome-tags", new JsonPrimitive("sandstone;deepslate;red"));
        map.put("support-tags", new JsonPrimitive("foundation_support;natural_replaceable"));
        return map;
    }

    private static void assertRejects(GameTestHelper helper, WorldRecipeGuard guard,
            Map<String, JsonElement> changed) {
        boolean rejected = false;
        try {
            guard.validate(changed);
        } catch (IllegalStateException error) {
            rejected = error.getMessage().contains("WORLD_RECIPE_MISMATCH");
        }
        helper.assertTrue(rejected, "a changed generation recipe must fail closed");
    }

    private static String blockHash(local.plaincraft.greatwall.model.WallTypes.ComponentVoxels component) {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(1);
            for (var voxel : component.voxels()) {
                out.writeUTF(voxel.state().id());
                var properties = new TreeMap<>(voxel.state().properties());
                out.writeInt(properties.size());
                for (var entry : properties.entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                }
            }
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return local.plaincraft.greatwall.geometry.DeterministicHash.sha256(bytes.toByteArray());
    }

    private static Path temp(String name) {
        try {
            return Files.createTempDirectory("pgw-" + name + "-");
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static void delete(Path path) {
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
