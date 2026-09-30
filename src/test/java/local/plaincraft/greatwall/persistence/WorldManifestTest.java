package local.plaincraft.greatwall.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldManifestTest {
    @TempDir Path temp;
    private static final GenerationConfig CONFIG = PlanningFixtures.recipe(1).config();
    private static final String CONFIG_HASH = WorldManifest.configHash(CONFIG);

    @Test void existingUnmarkedWorldIsRejected() throws Exception {
        var manifest = new WorldManifest(temp);
        var error = assertThrows(WallFailure.class, () -> manifest.authorize(true, 1, CONFIG_HASH));
        assertEquals(FailureCode.EXISTING_WORLD, error.code());
        assertFalse(Files.exists(temp.resolve("creation.json")));
        assertTrue(manifest.read().isEmpty());
    }

    @Test void creationCrashResumesOwnToken() throws Exception {
        var first = new WorldManifest(temp).authorize(false, 1, CONFIG_HASH);
        byte[] evidence = Files.readAllBytes(temp.resolve("creation.json"));
        var resumed = new WorldManifest(temp).authorize(true, 1, CONFIG_HASH);
        assertEquals(first, resumed);
        assertFalse(first.vanillaWasInitialized());
        assertArrayEquals(evidence, Files.readAllBytes(temp.resolve("creation.json")));
        assertTrue(new WorldManifest(temp).read().isEmpty());
    }

    @Test void worldRecipeRoundTripsThreeIntCoordinatesIncluding6000() throws Exception {
        var manifest = new WorldManifest(temp);
        var creation = manifest.authorize(false, 1, CONFIG_HASH);
        var recipe = recipe(creation.instance(), new Point3i(-1000000, 6000, 1000000));
        manifest.freeze(recipe);
        assertEquals(recipe, new WorldManifest(temp).read().orElseThrow());
        assertEquals(6000, manifest.read().orElseThrow().frozenSpawn().y());
        assertTrue(Files.exists(temp.resolve("creation.json")));
    }

    @Test void frozenRecipeCannotChangeIdentityOrGeometry() throws Exception {
        var manifest = new WorldManifest(temp);
        var creation = manifest.authorize(false, 1, CONFIG_HASH);
        var value = recipe(creation.instance(), new Point3i(0, 64, 0));
        manifest.freeze(value);
        byte[] evidence = Files.readAllBytes(temp.resolve("world.json"));
        manifest.freeze(value);
        assertArrayEquals(evidence, Files.readAllBytes(temp.resolve("world.json")));
        assertThrows(WallFailure.class, () -> manifest.freeze(recipe(UUID.randomUUID(), value.frozenSpawn())));
        assertThrows(WallFailure.class, () -> manifest.freeze(recipe(creation.instance(), new Point3i(0, 65, 0))));
        assertArrayEquals(evidence, Files.readAllBytes(temp.resolve("world.json")));
    }

    @Test void wrongSeedOrConfigRejectedOnReopen() throws Exception {
        var manifest = new WorldManifest(temp);
        var creation = manifest.authorize(false, 1, CONFIG_HASH);
        manifest.freeze(recipe(creation.instance(), new Point3i(0, 64, 0)));
        assertEquals(FailureCode.RECIPE_MISMATCH, assertThrows(WallFailure.class,
                () -> manifest.authorize(true, 2, CONFIG_HASH)).code());
        assertEquals(FailureCode.RECIPE_MISMATCH, assertThrows(WallFailure.class,
                () -> manifest.authorize(true, 1, "b".repeat(64))).code());
    }

    @Test void corruptCreationNeverBecomesNewWorld() throws Exception {
        var manifest = new WorldManifest(temp);
        manifest.authorize(false, 1, CONFIG_HASH);
        Path path = temp.resolve("creation.json");
        Files.writeString(path, "{");
        byte[] corrupt = Files.readAllBytes(path);
        assertThrows(IOException.class, () -> manifest.authorize(false, 1, CONFIG_HASH));
        assertThrows(IOException.class, manifest::read);
        assertArrayEquals(corrupt, Files.readAllBytes(path));
        assertFalse(Files.exists(temp.resolve("world.json")));
    }

    @Test void corruptWorldNeverFallsBackToEmptyRecipe() throws Exception {
        var manifest = new WorldManifest(temp);
        var creation = manifest.authorize(false, 1, CONFIG_HASH);
        manifest.freeze(recipe(creation.instance(), new Point3i(0, 64, 0)));
        Path path = temp.resolve("world.json");
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        json.getAsJsonObject("payload").addProperty("seed", 2);
        Files.writeString(path, json.toString());
        byte[] corrupt = Files.readAllBytes(path);
        assertThrows(IOException.class, manifest::read);
        assertThrows(IOException.class, () -> manifest.authorize(true, 1, CONFIG_HASH));
        assertArrayEquals(corrupt, Files.readAllBytes(path));
    }

    @Test void missingCreationForFrozenWorldFailsClosed() throws Exception {
        var manifest = new WorldManifest(temp);
        var creation = manifest.authorize(false, 1, CONFIG_HASH);
        manifest.freeze(recipe(creation.instance(), new Point3i(0, 64, 0)));
        Files.delete(temp.resolve("creation.json"));
        assertThrows(IOException.class, manifest::read);
        assertThrows(IOException.class, () -> manifest.authorize(false, 1, CONFIG_HASH));
        assertFalse(Files.exists(temp.resolve("creation.json")));
    }

    @Test void schemaAndCrcDamageAreExplicitErrors() throws Exception {
        for (String field : new String[] {"schemaVersion", "crc32c"}) {
            Path root = temp.resolve(field);
            var manifest = new WorldManifest(root);
            manifest.authorize(false, 1, CONFIG_HASH);
            Path path = root.resolve("creation.json");
            var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            json.addProperty(field, field.equals("schemaVersion") ? 99 : -1);
            Files.writeString(path, json.toString());
            assertThrows(IOException.class, manifest::read);
            assertThrows(IOException.class, () -> manifest.authorize(false, 1, CONFIG_HASH));
        }
    }

    @Test void oversizedManifestIsRejectedBeforeParsing() throws Exception {
        var manifest = new WorldManifest(temp);
        manifest.authorize(false, 1, CONFIG_HASH);
        Files.write(temp.resolve("creation.json"), new byte[65537]);
        assertThrows(IOException.class, manifest::read);
    }

    @Test void concurrentFileLeaseDeniesSecondWriter() throws Exception {
        try (var channel = FileChannel.open(temp.resolve(".manifest.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lease = channel.lock()) {
            assertThrows(IOException.class, () -> new WorldManifest(temp).authorize(false, 1, CONFIG_HASH));
            assertFalse(Files.exists(temp.resolve("creation.json")));
        }
        assertNotNull(new WorldManifest(temp).authorize(false, 1, CONFIG_HASH));
    }

    @Test void s0RootNeverAuthorizesProduction() {
        assertThrows(IllegalArgumentException.class, () -> new WorldManifest(temp.resolve("greatwall-s0")));
        assertThrows(IllegalArgumentException.class, () -> new WorldManifest(temp.resolve("GREATWALL-S0/manifest")));
    }

    private static WorldRecipe recipe(UUID instance, Point3i spawn) {
        var r = PlanningFixtures.recipe(1);
        var provisional = new WorldRecipe(instance, r.wallId(), r.seed(), r.dimension(), spawn, r.axis(), r.bounds(),
                r.config(), r.terrainHash(), r.paletteHash(), r.templatesHash(), r.recipeHash());
        String hash = DeterministicHash.sha256(CanonicalCodec.recipe(provisional));
        return new WorldRecipe(instance, hash, r.seed(), r.dimension(), spawn, r.axis(), r.bounds(),
                r.config(), r.terrainHash(), r.paletteHash(), r.templatesHash(), hash);
    }
}
