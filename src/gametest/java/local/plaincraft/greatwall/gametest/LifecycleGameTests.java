package local.plaincraft.greatwall.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import local.plaincraft.greatwall.lifecycle.WorldRecipeGuard;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * S5 lifecycle fail-closed tests driven against the real, pure {@link WorldRecipeGuard}. The full
 * server-side {@code WorldServices}/{@code ProductionSession} session objects are not instantiable
 * from a GameTest (they require the S1C fixture system properties and the {@code MinecraftServer}
 * lifecycle), so the durable recipe-identity invariants they rely on are verified here.
 */
public final class LifecycleGameTests implements FabricGameTest {

    private static JsonElement str(String value) {
        return new JsonPrimitive(value);
    }

    private static Map<String, JsonElement> inputs(long seed, String terrain, String template, String dimension) {
        var map = new TreeMap<String, JsonElement>();
        map.put("dimension", str(dimension));
        map.put("seed", str(Long.toString(seed)));
        map.put("terrain", str(terrain));
        map.put("template", str(template));
        return map;
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

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void lateWorldACallbackCannotWriteB(GameTestHelper helper) {
        Path dir = temp("late-write");
        try {
            var a = new WorldRecipeGuard(dir.resolve("a/recipes/s0.json"), true,
                    inputs(1, "terrain-a", "template-a", "minecraft:overworld"));
            var b = new WorldRecipeGuard(dir.resolve("b/recipes/s0.json"), true,
                    inputs(2, "terrain-b", "template-b", "minecraft:overworld"));
            String bHash = b.hash();

            helper.assertTrue(!a.hash().equals(b.hash()),
                    "world A and world B must be distinct frozen identities");

            // A late callback from world A attempts to write through world B's guard: refused.
            assertRejects(helper, b, inputs(1, "terrain-a", "template-a", "minecraft:overworld"));
            helper.assertTrue(b.hash().equals(bHash),
                    "a foreign write attempt must not change world B's frozen ledger identity");
            helper.assertTrue(b.hash().equals(new WorldRecipeGuard(dir.resolve("b/recipes/s0.json"),
                    false, inputs(2, "terrain-b", "template-b", "minecraft:overworld")).hash()),
                    "world B must reopen with the identical durable identity after the rejected write");
            helper.succeed();
        } catch (IOException error) {
            throw new AssertionError(error);
        } finally {
            delete(dir);
        }
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void onlyNewProductionWorldAuthorized(GameTestHelper helper) {
        Path dir = temp("authorize");
        try {
            var recipe = inputs(1, "terrain-0", "template-0", "minecraft:overworld");

            // An already-initialized world without a manifest is refused, never adopted.
            boolean refused = false;
            try {
                new WorldRecipeGuard(dir.resolve("recipes/s0.json"), false, recipe);
            } catch (IOException error) {
                refused = error.getMessage().contains("EXISTING_WORLD_WITHOUT_MANIFEST");
            }
            helper.assertTrue(refused,
                    "an initialized world without a creation manifest must be refused");

            // New-world authorization durably writes the manifest, then a reopen resumes it.
            var first = new WorldRecipeGuard(dir.resolve("recipes/s0.json"), true, recipe);
            var resumed = new WorldRecipeGuard(dir.resolve("recipes/s0.json"), false, recipe);
            helper.assertTrue(first.hash().equals(resumed.hash()),
                    "reopening must resume the same frozen identity rather than re-pick a new one");
            helper.assertTrue(dir.resolve("recipes/s0.json").toFile().exists(),
                    "the authorized manifest must be durably persisted");
            helper.succeed();
        } catch (IOException error) {
            throw new AssertionError(error);
        } finally {
            delete(dir);
        }
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void dimensionAndRecipeChangesFailClosed(GameTestHelper helper) {
        Path dir = temp("fail-closed");
        try {
            var frozen = new WorldRecipeGuard(dir.resolve("recipes/s0.json"), true,
                    inputs(1, "terrain-0", "template-0", "minecraft:overworld"));

            // Seed, terrain and template changes are each independently rejected.
            assertRejects(helper, frozen, inputs(2, "terrain-0", "template-0", "minecraft:overworld"));
            assertRejects(helper, frozen, inputs(1, "terrain-1", "template-0", "minecraft:overworld"));
            assertRejects(helper, frozen, inputs(1, "terrain-0", "template-1", "minecraft:overworld"));

            // Nether/End never own a wall: a dimension change fails closed (NO_WALL).
            assertRejects(helper, frozen, inputs(1, "terrain-0", "template-0", "minecraft:the_nether"));
            assertRejects(helper, frozen, inputs(1, "terrain-0", "template-0", "minecraft:the_end"));

            // A mismatched manifest is refused on reopen, not silently reused from a wrong cache.
            boolean refused = false;
            try {
                new WorldRecipeGuard(dir.resolve("recipes/s0.json"), true,
                        inputs(2, "terrain-0", "template-0", "minecraft:overworld"));
            } catch (IOException error) {
                refused = true;
            }
            helper.assertTrue(refused,
                    "a changed recipe must not silently reuse the frozen manifest");
            helper.succeed();
        } catch (IOException error) {
            throw new AssertionError(error);
        } finally {
            delete(dir);
        }
    }
}
