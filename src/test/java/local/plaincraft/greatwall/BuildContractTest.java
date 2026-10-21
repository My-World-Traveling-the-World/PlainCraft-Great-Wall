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
        // A floor, not an exact pin: an exact pin made the wall refuse to load on any pack whose
        // loader or API differed by one patch release, which has nothing to do with this code.
        assertEquals(">=0.19.3", dependencies.get("fabricloader").getAsString());
        assertEquals(">=0.116.14+1.21.1", dependencies.get("fabric-api").getAsString());
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

    /**
     * A mixin may not declare a non-private static method.
     *
     * <h2>The failure this prevents</h2>
     * Mixin copies a mixin class's members into its target and requires static
     * members to be private — a static method with any other visibility is applied to
     * the target class and changes its public surface. The check runs at APPLY time,
     * so the build is green and the class loads fine until the transform happens;
     * then it throws {@code InvalidMixinException} and takes the server down.
     *
     * <p>A real session crashed this way: a helper method kept "so the signature
     * cannot drift" was the only thing in the file that was not private, and the
     * server died in its tick loop the moment the target class was first loaded.
     *
     * <p>Only the shape is checked, not the intent — a declared helper is far more
     * likely to be an oversight than a deliberate widening of a target's API, and if
     * it is deliberate the annotation to say so is the fix.
     */
    @Test
    void mixinsDeclareNoNonPrivateStaticMethods() throws IOException {
        var violations = new java.util.TreeSet<String>();
        var staticMethod = java.util.regex.Pattern.compile(
                "(?m)^\\s*(?!private\\b)(?:public\\s+|protected\\s+)?static\\s+[\\w<>\\[\\].,?\\s]+\\s+(\\w+)\\s*\\(");
        for (var root : java.util.List.of(
                java.nio.file.Path.of("src/main/java/local/plaincraft/greatwall/mixin"),
                java.nio.file.Path.of("src/client/java/local/plaincraft/greatwall/client/mixin"))) {
            if (!java.nio.file.Files.isDirectory(root)) continue;
            try (var files = java.nio.file.Files.list(root)) {
                for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    var text = java.nio.file.Files.readString(file, StandardCharsets.UTF_8);
                    // Strip block comments so a doc example is not mistaken for code.
                    var code = text.replaceAll("(?s)/\\*.*?\\*/", "");
                    var matcher = staticMethod.matcher(code);
                    while (matcher.find()) {
                        violations.add(file.getFileName() + "#" + matcher.group(1));
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "these mixin methods are static but not private; Mixin applies them to the target "
                        + "class and throws InvalidMixinException at load time, which crashes the "
                        + "server: " + violations);
    }

    /**
     * Every class in a mixin config's package must itself be named by that config.
     *
     * <h2>The failure this prevents</h2>
     * Mixin treats a config's {@code package} as owned by that config: any class
     * inside it becomes a mixin candidate, and Mixin then refuses to let ordinary
     * code reference it. Putting an ordinary class there does not fail the build —
     * it fails at the moment something first loads that class, with
     * {@code IllegalClassLoadError: ... is in a defined mixin package ... and
     * cannot be referenced directly}. A real session hit exactly this: the client
     * config screen sat in the same package as the world-creation mixin, so
     * pressing the button crashed the game.
     *
     * <p>The existing test above checks this in one direction only — that every
     * declared mixin has a class. This one checks the other: that the package
     * holds nothing <em>but</em> declared mixins. Together they pin the invariant
     * that makes the package safe to reference from outside.
     *
     * <p>Client and server configs are both checked, because the crash came from
     * the client one and there is no reason the server one could not drift next.
     */
    @Test
    void mixinPackagesContainOnlyNamedMixins() throws IOException {
        assertMixinPackageIsClosed("plaincraft_great_wall.mixins.json", "src/main/java");
        assertMixinPackageIsClosed("plaincraft_great_wall.client.mixins.json", "src/client/java");
    }

    private void assertMixinPackageIsClosed(String configName, String sourceRoot) throws IOException {
        JsonObject config = resource(configName);
        String packageName = config.get("package").getAsString();
        var declared = new java.util.HashSet<String>();
        for (String key : java.util.List.of("mixins", "client", "server")) {
            if (!config.has(key)) continue;
            for (var element : config.getAsJsonArray(key)) declared.add(element.getAsString());
        }
        // The C2ME mixins live in the server package but are applied by a plugin,
        // which the server config names through ProbeMixinPlugin.getMixins().
        if ("local.plaincraft.greatwall.mixin".equals(packageName)) {
            declared.add("C2meGateMixin");
            declared.add("C2meFeatureSkipMixin");
        }

        var directory = java.nio.file.Path.of(sourceRoot,
                packageName.replace('.', java.io.File.separatorChar));
        assertTrue(java.nio.file.Files.isDirectory(directory),
                configName + " declares " + packageName + " but " + directory + " does not exist");

        var foreign = new java.util.TreeSet<String>();
        try (var files = java.nio.file.Files.list(directory)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String simple = path.getFileName().toString().replace(".java", "");
                if (!declared.contains(simple)) foreign.add(simple);
            });
        }
        assertTrue(foreign.isEmpty(),
                "these classes sit in the mixin package " + packageName + " but are not mixins; Mixin will "
                        + "refuse to let ordinary code reference them, which crashes the moment they load. "
                        + "Move them to a non-mixin package: " + foreign);
    }

    /**
     * Every translation key the code asks for must exist in every shipped language.
     *
     * <h2>The failure this prevents</h2>
     * A missing key does not throw. Minecraft renders the key itself, so the button
     * or label simply shows {@code text.plaincraft_great_wall.configButton} on
     * screen, and nothing in the build or the log says a word about it. A one-letter
     * typo is enough, and one was made here: the button asked for
     * {@code config_button} while the language files carried {@code configButton}.
     *
     * <h2>Keys the code builds at runtime</h2>
     * Not every key appears whole in the source. The loading card resolves its stage and detail
     * lines by concatenation ({@code …loading_progress.stage." + id}, and the same family with
     * {@code "_unknown"} appended), so only the family stem is a literal. A stem cannot be required
     * to exist as a key; instead every language must carry the <em>same</em> set of members for it,
     * which catches a member added to one language only - the way those keys are actually lost.
     *
     * <p>Only keys this mod owns are checked, so vanilla and other mods' keys
     * referenced in passing are not reported.
     */
    @Test
    void everyOwnedTranslationKeyExistsInEveryLanguage() throws IOException {
        var sourceRoots = java.util.List.of(
                java.nio.file.Path.of("src/main/java"),
                java.nio.file.Path.of("src/client/java"));
        var referenced = new java.util.TreeSet<String>();
        var stems = new java.util.TreeSet<String>();
        var pattern = java.util.regex.Pattern.compile("translatable\\(\\\"([^\\\"]+)\\\"\\)");
        for (var root : sourceRoots) {
            if (!java.nio.file.Files.isDirectory(root)) continue;
            try (var files = java.nio.file.Files.walk(root)) {
                for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    var matcher = pattern.matcher(java.nio.file.Files.readString(file, StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        String key = matcher.group(1);
                        if (!owned(key)) continue;
                        if (key.endsWith(".")) {
                            stems.add(key);
                        } else {
                            referenced.add(key);
                        }
                    }
                }
            }
        }
        assertFalse(referenced.isEmpty() && stems.isEmpty(),
                "no owned translation keys were found; the source layout moved");

        var languageDirectory = java.nio.file.Path.of(
                "src/client/resources/assets/plaincraft_great_wall/lang");
        assertTrue(java.nio.file.Files.isDirectory(languageDirectory),
                "the language directory must exist; run this from the project root");

        var shipped = new java.util.TreeMap<String, java.util.Set<String>>();
        try (var files = java.nio.file.Files.list(languageDirectory)) {
            for (var path : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                var json = JsonParser.parseString(java.nio.file.Files.readString(path, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                shipped.put(path.getFileName().toString(), json.keySet());
            }
        }
        assertFalse(shipped.isEmpty(), "no language files were found");

        for (var language : shipped.entrySet()) {
            var missing = new java.util.TreeSet<String>();
            for (String key : referenced) {
                if (!language.getValue().contains(key)) missing.add(key);
            }
            assertTrue(missing.isEmpty(),
                    language.getKey() + " is missing keys the code asks for, which would render as the raw key on "
                            + "screen with nothing logged: " + missing);
        }

        for (String stem : stems) {
            var expected = family(shipped.values().iterator().next(), stem);
            assertFalse(expected.isEmpty(),
                    "no shipped key belongs to " + stem + ", the family the code builds its keys from");
            for (var language : shipped.entrySet()) {
                assertEquals(expected, family(language.getValue(), stem),
                        language.getKey() + " carries a different set of keys for the family " + stem);
            }
        }
    }

    /** {@return the shipped keys belonging to a family stem, such as {@code …loading_progress.stage.}} */
    private static java.util.TreeSet<String> family(java.util.Set<String> keys, String stem) {
        var members = new java.util.TreeSet<String>();
        for (String key : keys) {
            if (key.startsWith(stem)) members.add(key);
        }
        return members;
    }

    /**
     * Whether a key belongs to this mod.
     *
     * <p>The five prefixes are the ones the shipped language files actually use. The earlier list
     * stopped at {@code screen}/{@code option}/{@code text} and so quietly skipped the 47
     * {@code plaincraft.great_wall.*} loading-card keys and the 42 {@code config.*} ones: the guard
     * passed while covering a tenth of the surface it claimed to check.
     */
    private static boolean owned(String key) {
        return key.startsWith("plaincraft.great_wall.")
                || key.startsWith("config.plaincraft_great_wall")
                || key.startsWith("screen.plaincraft_great_wall")
                || key.startsWith("option.plaincraft_great_wall")
                || key.startsWith("text.plaincraft_great_wall");
    }

    /**
     * The startup backfill may not call {@code ServerChunkCache.getChunkFuture}.
     *
     * <h2>The failure this prevents</h2>
     * On the server thread that method does not return a pending future: it calls
     * {@code mainThreadProcessor.managedBlock(future::isDone)} and parks the thread until the chunk is
     * generated. Called from inside the startup pump - itself a {@code managedBlock} that drains the
     * world-local chunk queue only between calls - the promotion callbacks the wait needs never run.
     * A real session froze for twenty-five minutes this way: the server thread sat in
     * {@code getChunkFuture}, all fourteen c2me workers were idle, and no log line was written after
     * the eleventh batch. The ticket + {@code getChunkFutureMainThread} pair is the fix, and this guard
     * keeps the blocking call from coming back.
     */
    @Test
    void startupBackfillNeverUsesTheBlockingChunkRequest() throws IOException {
        var file = java.nio.file.Path.of(
                "src/main/java/local/plaincraft/greatwall/generation/WallBodyBackfill.java");
        assertTrue(java.nio.file.Files.isRegularFile(file),
                file + " is missing; this guard would pass while checking nothing");
        var code = java.nio.file.Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        assertFalse(code.contains(".getChunkFuture("),
                "ServerChunkCache.getChunkFuture blocks the server thread inside its own startup pump and "
                        + "deadlocks the world; use the ticket + getChunkFutureMainThread pair in requestChunk()");
        assertTrue(code.contains("greatwall$request("),
                "the non-blocking chunk request disappeared; the backfill cannot force a chunk without it");
        assertTrue(code.contains("drainWorldLocalQueues("),
                "the world-local queue drain disappeared; a request whose callbacks never run never completes");
    }

    private static JsonObject resource(String name) throws IOException {
        var stream = BuildContractTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(stream, name);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
