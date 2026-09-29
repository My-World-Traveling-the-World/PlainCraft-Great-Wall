package local.plaincraft.greatwall;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import local.plaincraft.greatwall.lifecycle.WorldRecipeGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorldRecipeGuardTest {
    @TempDir Path temporary;
    @Test void jsonKeyOrderAndFormattingDoNotChangeFingerprint() {
        var a = Map.of("terrain", JsonParser.parseString("{\"a\":1,\"b\":2}"));
        var b = Map.of("terrain", JsonParser.parseString("{\n\"b\":2,\"a\":1}"));
        assertEquals(WorldRecipeGuard.fingerprint(a), WorldRecipeGuard.fingerprint(b));
    }
    @Test void persistedRecipeRejectsGenerationChanges() throws Exception {
        Path path = temporary.resolve("recipe.json");
        var inputs = Map.of("seed", JsonParser.parseString("42"));
        var guard = new WorldRecipeGuard(path, true, inputs);
        guard.validate(inputs);
        assertEquals(guard.hash(), new WorldRecipeGuard(path, false, inputs).hash());
        assertThrows(IllegalStateException.class, () -> guard.validate(Map.of("seed", JsonParser.parseString("43"))));
        assertThrows(IOException.class, () -> new WorldRecipeGuard(path, false, Map.of("seed", JsonParser.parseString("43"))));
    }
    @Test void oldWorldAndCorruptManifestAreNotSilentlyReset() throws Exception {
        Path path = temporary.resolve("recipe.json");
        assertThrows(IOException.class, () -> new WorldRecipeGuard(path, false, Map.of()));
        Files.writeString(path, "corrupt");
        assertThrows(IOException.class, () -> new WorldRecipeGuard(path, true, Map.of()));
        assertEquals("corrupt", Files.readString(path));
    }

    @Test void validJsonWithTamperedInputsIsRejectedWithoutOverwritingEvidence() throws Exception {
        Path path = temporary.resolve("recipe.json");
        var inputs = Map.of("seed", JsonParser.parseString("42"));
        new WorldRecipeGuard(path, true, inputs);
        var tampered = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        tampered.getAsJsonObject("inputs").addProperty("seed", 43);
        Files.writeString(path, tampered.toString());
        assertThrows(IOException.class, () -> new WorldRecipeGuard(path, true, inputs));
        assertEquals(tampered.toString(), Files.readString(path));
        try (var files = Files.list(temporary)) { assertEquals(1, files.count()); }
    }
}
