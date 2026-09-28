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

    private static JsonObject resource(String name) throws IOException {
        var stream = BuildContractTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(stream, name);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
