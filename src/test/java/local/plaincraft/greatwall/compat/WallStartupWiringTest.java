package local.plaincraft.greatwall.compat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 静态接线只约束长城自己的生命周期入口，不宣称运行了Mixin或真实存档。 */
class WallStartupWiringTest {
    private static final Path MAIN = Path.of("src/main/java/local/plaincraft/greatwall");

    @Test
    void worldLifecycleAttachesAfterRegistrationAndDetachesBeforeClose() throws Exception {
        String source = Files.readString(MAIN.resolve("lifecycle/WorldServices.java"));
        assertTrue(source.contains("RIVERS_READY.attach(server, services.production::riversPrewarmed);"));
        assertTrue(source.indexOf("SESSIONS.put(server, services);")
                < source.indexOf("RIVERS_READY.attach(server, services.production::riversPrewarmed);"));
        assertTrue(source.contains("RIVERS_READY.publish();"));
        assertEquals(2, source.split(java.util.regex.Pattern.quote("RIVERS_READY.detach(server);"), -1).length - 1);
        assertTrue(source.contains("RIVERS_READY.clear();"));
        assertFalse(source.contains("if (RIVERS_READY.get())"));
    }

    @Test
    void publicBridgeAndProviderActuallyUseProductionReaders() throws Exception {
        String bridge = Files.readString(MAIN.resolve("compat/RoadWeaverLifecycleBridge.java"));
        String provider = Files.readString(MAIN.resolve("compat/GreatWallReadinessProvider.java"));
        assertTrue(bridge.contains("register(WorldServices::riversPrewarmed);"));
        assertTrue(provider.contains("this(WorldServices::awaitPrewarm, WorldServices::prewarmSnapshot);"));
        String mod = Files.readString(MAIN.resolve("GreatWallMod.java"));
        assertTrue(mod.contains("RoadWeaverLifecycleBridge.register();"));
        assertTrue(mod.contains("GreatWallReadinessProvider.register();"));
    }
}
