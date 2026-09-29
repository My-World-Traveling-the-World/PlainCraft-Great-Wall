package local.plaincraft.greatwall.lifecycle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;
import local.plaincraft.greatwall.planning.ProbePlanner;

/** Authoritative S0 recipe evidence, deliberately separate from disposable caches. */
public final class WorldRecipeGuard {
    private final String frozen;

    public WorldRecipeGuard(Path manifest, boolean authorizedNewWorld, Map<String, JsonElement> inputs) throws IOException {
        String hash = fingerprint(inputs);
        if (Files.exists(manifest)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
                if (json.get("schema").getAsInt() != 1 || !json.get("hash").getAsString().equals(hash)
                        || !fingerprint(asMap(json.getAsJsonObject("inputs"))).equals(hash)) {
                    throw new IOException("WORLD_RECIPE_MISMATCH: refusing changed or corrupt manifest");
                }
            } catch (RuntimeException invalid) {
                throw new IOException("WORLD_RECIPE_CORRUPT: permanent identity is not a cache", invalid);
            }
        } else {
            if (!authorizedNewWorld) throw new IOException("EXISTING_WORLD_WITHOUT_MANIFEST");
            JsonObject json = new JsonObject();
            json.addProperty("schema", 1);
            json.addProperty("purpose", "s0-probe-only-not-a-wall-recipe");
            json.addProperty("hash", hash);
            JsonObject savedInputs = new JsonObject();
            new TreeMap<>(inputs).forEach((key, value) -> savedInputs.add(key, canonical(value)));
            json.add("inputs", savedInputs);
            Files.createDirectories(manifest.getParent());
            Path temporary = Files.createTempFile(manifest.getParent(), "recipe-", ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = StandardCharsets.UTF_8.encode(json.toString());
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        frozen = hash;
    }

    public void validate(Map<String, JsonElement> inputs) {
        if (!frozen.equals(fingerprint(inputs))) {
            throw new IllegalStateException("WORLD_RECIPE_MISMATCH: generation resources changed; create a new world");
        }
    }
    public String hash() { return frozen; }

    public static String fingerprint(Map<String, JsonElement> inputs) {
        JsonObject root = new JsonObject();
        new TreeMap<>(inputs).forEach((key, value) -> root.add(key, canonical(value)));
        return ProbePlanner.hash(root.toString());
    }

    private static Map<String, JsonElement> asMap(JsonObject object) {
        var map = new TreeMap<String, JsonElement>();
        object.entrySet().forEach(entry -> map.put(entry.getKey(), entry.getValue()));
        return map;
    }

    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            asMap(value.getAsJsonObject()).forEach((key, child) -> result.add(key, canonical(child)));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(child -> result.add(canonical(child)));
            return result;
        }
        return value.deepCopy();
    }
}
