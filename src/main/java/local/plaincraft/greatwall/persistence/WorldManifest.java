package local.plaincraft.greatwall.persistence;

import static local.plaincraft.greatwall.model.WallTypes.requireHash;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32C;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Permanent new-world identity, not a reconstructible cache. All calls belong on the bounded IO lane. */
public final class WorldManifest {
    /**
     * Permanent identity of a world.
     *
     * <p>{@code configHash} covers the structural generation config; {@code parametersHash} covers the
     * tuned generation parameters. They are stored separately rather than merged into one hash so the
     * refusal can name which of the two moved — a player who edited the ridge weights needs a different
     * sentence from one whose block budgets changed.
     */
    public record Creation(UUID instance, long seed, String configHash, String parametersHash,
            boolean vanillaWasInitialized) {
        public Creation {
            Objects.requireNonNull(instance);
            requireHash(configHash);
            requireHash(parametersHash);
        }
    }
    private static final Gson JSON = new Gson();
    private static final int SCHEMA = 1, MAX_FILE_BYTES = 65536;
    private final Path root;

    public WorldManifest(Path root) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        for (var part : this.root) if ("greatwall-s0".equalsIgnoreCase(part.toString())) {
            throw new IllegalArgumentException("S0 evidence must not authorize production generation");
        }
    }

    public Creation authorize(boolean vanillaInitialized, long seed, String configHash, String parametersHash) throws IOException {
        return authorize(vanillaInitialized, seed, configHash, Set.of(), parametersHash);
    }

    public Creation authorize(boolean vanillaInitialized, long seed, String newConfigHash,
            Set<String> readableConfigHashes, String parametersHash) throws IOException {
        requireHash(newConfigHash);
        var readable = Set.copyOf(readableConfigHashes);
        readable.forEach(local.plaincraft.greatwall.model.WallTypes::requireHash);
        requireHash(parametersHash);
        return locked(() -> {
            var creation = readCreation();
            var world = readWorld();
            if (world.isPresent()) {
                if (creation.isEmpty()) throw corrupt("frozen world is missing its permanent creation token", null);
                validate(world.get(), creation.get());
            }
            if (creation.isPresent()) {
                Creation value = creation.get();
                // Parameters are compared before the creation token so this reports the cause a player can
                // act on. The frozen recipe is immutable, so a world whose parameters drifted cannot be
                // migrated in place: its planned segments came from the old ridge choice.
                if (!value.parametersHash().equals(parametersHash)) {
                    throw new WallFailure(FailureCode.RECIPE_MISMATCH,
                            "generation parameters changed; create a new world");
                }
                // 旧创建凭证即使还没有 world.json 也已选定配方，重开不能补写成新版。
                if (value.seed() != seed || (!value.configHash().equals(newConfigHash)
                        && !readable.contains(value.configHash()))) {
                    throw new WallFailure(FailureCode.RECIPE_MISMATCH, "new-world creation seed/config changed");
                }
                return value;
            }
            if (vanillaInitialized) {
                throw new WallFailure(FailureCode.EXISTING_WORLD, "initialized world has no Great Wall creation token");
            }
            var value = new Creation(UUID.randomUUID(), seed, newConfigHash, parametersHash, false);
            atomicCreate(root.resolve("creation.json"), envelope("creation", JSON.toJsonTree(value).getAsJsonObject()));
            return value;
        });
    }

    public Optional<WorldRecipe> read() throws IOException {
        return locked(() -> {
            var creation = readCreation(); // Corrupt tokens also fail when world.json has not been written yet.
            var world = readWorld();
            if (world.isPresent()) {
                if (creation.isEmpty()) throw corrupt("frozen world is missing its permanent creation token", null);
                validate(world.get(), creation.get());
            }
            return world;
        });
    }

    public void freeze(WorldRecipe recipe) throws IOException {
        Objects.requireNonNull(recipe);
        locked(() -> {
            var creation = readCreation();
            if (creation.isEmpty()) {
                throw corrupt("creation token required before freezing recipe", null);
            }
            validate(recipe, creation.get());
            var saved = readWorld();
            if (saved.isPresent()) {
                validate(saved.get(), creation.get());
                if (!saved.get().equals(recipe)) throw new WallFailure(FailureCode.RECIPE_MISMATCH, "frozen recipe is immutable");
                return null;
            }
            atomicCreate(root.resolve("world.json"), envelope("world", JSON.toJsonTree(recipe).getAsJsonObject()));
            return null;
        });
    }

    /** Versioned semantic GenerationConfig hash; creation and final recipe use this same definition. */
    public static String configHash(GenerationConfig config) {
        Objects.requireNonNull(config);
        try {
            var bytes = new ByteArrayOutputStream(128);
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(SCHEMA);
                out.writeInt(config.algorithmVersion()); out.writeInt(config.templateVersion());
                out.writeInt(config.segmentBlocks()); out.writeInt(config.stationBlocks()); out.writeInt(config.columnBudget());
                out.writeInt(config.carrierAmplitude()); out.writeInt(config.corridorHalfWidth()); out.writeInt(config.maxGeometryInflight());
                out.writeLong(config.segmentBytes()); out.writeLong(config.terrainBytes()); out.writeLong(config.compiledBytes());
            }
            return DeterministicHash.sha256(bytes.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private Optional<Creation> readCreation() throws IOException {
        var payload = readEnvelope(root.resolve("creation.json"), "creation");
        if (payload.isEmpty()) return Optional.empty();
        try {
            var json = payload.get();
            // Refused before the generic key check so the message names the cause. A token without a
            // parameter identity cannot be compared, and guessing "same parameters" would silently keep
            // generating a world whose frozen plans came from a different tuning.
            if (!json.has("parametersHash")) throw new WallFailure(FailureCode.RECIPE_MISMATCH,
                    "saved world predates parameter identity; create a new world");
            keys(json, "instance", "seed", "configHash", "parametersHash", "vanillaWasInitialized");
            json.get("seed").getAsBigDecimal().longValueExact();
            if (!json.get("vanillaWasInitialized").isJsonPrimitive()
                    || !json.getAsJsonPrimitive("vanillaWasInitialized").isBoolean()) throw new IllegalArgumentException("boolean required");
            var creation = JSON.fromJson(json, Creation.class);
            if (creation.vanillaWasInitialized()) throw new IllegalArgumentException("creation may not authorize an already initialized world");
            return Optional.of(creation);
        } catch (WallFailure refusal) { throw refusal; }
        catch (RuntimeException invalid) { throw corrupt("invalid creation payload", invalid); }
    }

    private Optional<WorldRecipe> readWorld() throws IOException {
        var payload = readEnvelope(root.resolve("world.json"), "world");
        if (payload.isEmpty()) return Optional.empty();
        try {
            var json = payload.get();
            keys(json, "instance", "wallId", "seed", "dimension", "frozenSpawn", "axis", "bounds", "config",
                    "terrainHash", "paletteHash", "templatesHash", "recipeHash");
            json.get("seed").getAsBigDecimal().longValueExact();
            point(json.getAsJsonObject("frozenSpawn"));
            var axis = json.getAsJsonObject("axis"); keys(axis, "origin", "exQ30", "ezQ30"); point(axis.getAsJsonObject("origin"));
            ints(axis, "exQ30", "ezQ30");
            var bounds = json.getAsJsonObject("bounds"); keys(bounds, "minY", "maxYExclusive", "horizontalLimit");
            ints(bounds, "minY", "maxYExclusive", "horizontalLimit");
            var config = json.getAsJsonObject("config");
            if (!config.has("parametersHash")) throw new WallFailure(FailureCode.RECIPE_MISMATCH,
                    "saved world predates parameter identity; create a new world");
            keys(config, "algorithmVersion", "templateVersion", "segmentBlocks", "stationBlocks", "columnBudget",
                    "carrierAmplitude", "corridorHalfWidth", "maxGeometryInflight", "segmentBytes", "terrainBytes", "compiledBytes",
                    "parametersHash");
            ints(config, "algorithmVersion", "templateVersion", "segmentBlocks", "stationBlocks", "columnBudget",
                    "carrierAmplitude", "corridorHalfWidth", "maxGeometryInflight");
            for (String name : new String[] {"segmentBytes", "terrainBytes", "compiledBytes"}) {
                config.get(name).getAsBigDecimal().longValueExact();
            }
            return Optional.of(JSON.fromJson(json, WorldRecipe.class));
        } catch (WallFailure refusal) { throw refusal; }
        catch (RuntimeException invalid) { throw corrupt("invalid frozen recipe payload", invalid); }
    }

    private void validate(WorldRecipe recipe, Creation creation) throws IOException {
        String semantic = DeterministicHash.sha256(CanonicalCodec.recipe(recipe));
        if (!recipe.recipeHash().equals(semantic) || !recipe.wallId().equals(semantic)
                || !"minecraft:overworld".equals(recipe.dimension())
                || !recipe.instance().equals(creation.instance()) || recipe.seed() != creation.seed()
                || !configHash(recipe.config()).equals(creation.configHash())) {
            throw new WallFailure(FailureCode.RECIPE_MISMATCH, "creation/recipe identity or semantic hash mismatch");
        }
    }

    private Optional<JsonObject> readEnvelope(Path file, String type) throws IOException {
        checked(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw corrupt("manifest is not a regular file", null);
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw corrupt("manifest exceeds bounded file size", null);
            var wrapper = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            keys(wrapper, "schemaVersion", "type", "payload", "sha256", "crc32c");
            if (wrapper.get("schemaVersion").getAsBigDecimal().intValueExact() != SCHEMA
                    || !wrapper.get("type").getAsString().equals(type)) throw corrupt("unsupported manifest schema/type", null);
            var payload = wrapper.getAsJsonObject("payload");
            byte[] encoded = JSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
            var crc = new CRC32C(); crc.update(encoded);
            if (!DeterministicHash.sha256(encoded).equals(wrapper.get("sha256").getAsString())
                    || crc.getValue() != wrapper.get("crc32c").getAsBigDecimal().longValueExact()) {
                throw corrupt("manifest SHA-256/CRC32C mismatch", null);
            }
            return Optional.of(payload);
        } catch (RuntimeException invalid) { throw corrupt("invalid manifest envelope", invalid); }
    }

    private static byte[] envelope(String type, JsonObject payload) {
        byte[] encoded = JSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        var crc = new CRC32C(); crc.update(encoded);
        var wrapper = new JsonObject();
        wrapper.addProperty("schemaVersion", SCHEMA); wrapper.addProperty("type", type);
        wrapper.add("payload", payload); wrapper.addProperty("sha256", DeterministicHash.sha256(encoded));
        wrapper.addProperty("crc32c", crc.getValue());
        byte[] bytes = JSON.toJson(wrapper).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("manifest exceeds bounded file size");
        return bytes;
    }

    private <T> T locked(IoAction<T> action) throws IOException {
        checked(root);
        Files.createDirectories(root); checked(root);
        Path lock = checked(root.resolve(".manifest.lock"));
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            try (var lease = channel.tryLock()) {
                if (lease == null) throw new IOException("manifest is busy; no second creation writer admitted");
                return action.run();
            } catch (OverlappingFileLockException busy) { throw new IOException("manifest is already in use in this process", busy); }
        }
    }

    private void atomicCreate(Path target, byte[] bytes) throws IOException {
        checked(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("permanent manifest must not be overwritten");
        Path temp = checked(target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp"));
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }

    private Path checked(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IOException("manifest path escapes root");
        for (Path part = normalized; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("manifest path contains a symbolic link");
        }
        if (Files.exists(root) && Files.exists(normalized) && !normalized.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("manifest path escapes real root");
        }
        return normalized;
    }

    private static void keys(JsonObject object, String... names) {
        if (object == null || !object.keySet().equals(Set.of(names))) throw new IllegalArgumentException("manifest fields differ from schema");
    }
    private static void ints(JsonObject object, String... names) {
        for (String name : names) object.get(name).getAsBigDecimal().intValueExact();
    }
    private static void point(JsonObject object) { keys(object, "x", "y", "z"); ints(object, "x", "y", "z"); }
    private static IOException corrupt(String message, Throwable cause) {
        return new IOException("WORLD_MANIFEST_CORRUPT: " + message + "; permanent identity is not a cache", cause);
    }
    @FunctionalInterface private interface IoAction<T> { T run() throws IOException; }
}
