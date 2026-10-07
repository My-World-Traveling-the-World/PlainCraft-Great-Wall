package local.plaincraft.greatwall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BuildContractTest {
    @Test
    void javaAndMinecraftTargetAreFrozen() {
        assertEquals(21, Runtime.version().feature());
        assertEquals(21, BuildContract.JAVA_VERSION);
        assertEquals("1.21.1", BuildContract.MINECRAFT_VERSION);
    }

    @Test
    void commonMetadataSeparatesClientEntrypoint() throws IOException {
        JsonObject metadata = resource("fabric.mod.json");
        assertEquals(BuildContract.MOD_ID, metadata.get("id").getAsString());
        JsonObject entrypoints = metadata.getAsJsonObject("entrypoints");
        assertEquals("local.plaincraft.greatwall.GreatWallMod",
                entrypoints.getAsJsonArray("main").get(0).getAsString());
        assertEquals("local.plaincraft.greatwall.client.GreatWallClient",
                entrypoints.getAsJsonArray("client").get(0).getAsString());
        assertFalse(entrypoints.has("fabric-gametest"));
        JsonObject dependencies = metadata.getAsJsonObject("depends");
        assertEquals("=0.19.3", dependencies.get("fabricloader").getAsString());
        assertEquals("=0.116.14+1.21.1", dependencies.get("fabric-api").getAsString());
        assertFalse(dependencies.has("ftbquests"));
        assertFalse(dependencies.has("roadweaver"));
        assertFalse(dependencies.has("c2me"));
    }

    @Test
    void combinedModPackSupportsBothMinecraftPackFormats() throws IOException {
        JsonObject pack = resource("pack.mcmeta").getAsJsonObject("pack");
        assertEquals(BuildContract.RESOURCE_PACK_VERSION, pack.get("pack_format").getAsInt());
        JsonObject formats = pack.getAsJsonObject("supported_formats");
        assertTrue(formats.get("min_inclusive").getAsInt() <= BuildContract.RESOURCE_PACK_VERSION);
        assertTrue(formats.get("max_inclusive").getAsInt() >= BuildContract.DATA_PACK_VERSION);
    }

    /**
     * Every mixin class must be reachable, either from the config's {@code mixins} list or from the
     * plugin that loads the C2ME ones conditionally.
     *
     * <h2>Why this exists</h2>
     * A mixin class that no configuration names is never applied and never reported: the build stays
     * green, the class ships inside the jar, and the behaviour it was written to add simply does not
     * happen. That is the same silent-gap shape as an unregistered GameTest entrypoint, and the count
     * makes it concrete - at the time of writing there are 20 mixin classes, 18 named in the config and
     * 2 returned by {@code ProbeMixinPlugin.getMixins()} only when the certified C2ME version is
     * present.
     *
     * <p>The reverse direction is checked too: a name in the config with no class behind it would fail
     * at load time, which is loud, but a test that only checks one direction would not notice the pair
     * drifting apart.
     */
    @Test
    void everyMixinClassIsNamedByAConfiguration() throws IOException {
        JsonObject config = resource("plaincraft_great_wall.mixins.json");
        assertEquals("local.plaincraft.greatwall.mixin", config.get("package").getAsString(),
                "the mixin package must match the source tree the names are relative to");
        var declared = new java.util.HashSet<String>();
        for (var element : config.getAsJsonArray("mixins")) declared.add(element.getAsString());

        // The two C2ME mixins are applied by the plugin rather than listed, because they target classes
        // that only exist when that mod is installed.
        var pluginLoaded = java.util.Set.of("C2meGateMixin", "C2meFeatureSkipMixin");
        for (String name : pluginLoaded) {
            assertFalse(declared.contains(name),
                    name + " must be plugin-loaded, not listed: listing it would apply it unconditionally");
            declared.add(name);
        }

        // The plugin is what makes those two reachable, so it must remain wired in.
        assertEquals("local.plaincraft.greatwall.compat.c2me.ProbeMixinPlugin",
                config.get("plugin").getAsString(),
                "the conditional mixins are unreachable without their plugin");
        assertEquals(1, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt(),
                "injectors must default to required, so a missing target fails loudly");

        var onDisk = new java.util.HashSet<String>();
        var directory = java.nio.file.Path.of("src/main/java/local/plaincraft/greatwall/mixin");
        assertTrue(java.nio.file.Files.isDirectory(directory),
                "the mixin source directory must exist; run this from the project root");
        try (var files = java.nio.file.Files.list(directory)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> onDisk.add(path.getFileName().toString().replace(".java", "")));
        }

        var unregistered = new java.util.TreeSet<>(onDisk);
        unregistered.removeAll(declared);
        assertTrue(unregistered.isEmpty(),
                "these mixin classes would never be applied: " + unregistered);

        var dangling = new java.util.TreeSet<>(declared);
        dangling.removeAll(onDisk);
        assertTrue(dangling.isEmpty(),
                "these names are configured but have no class behind them: " + dangling);
    }

    private static JsonObject resource(String name) throws IOException {
        var stream = BuildContractTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(stream, name);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
