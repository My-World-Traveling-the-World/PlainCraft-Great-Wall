package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;

/**
 * Pins the null-tolerant shape of the world-service lookup.
 *
 * <p>Mixin entry points that also run in the client process call
 * {@code WorldServices.find(level.getServer())}. On a client, {@code getServer()} is null until a
 * world is hosted, and {@code ConcurrentHashMap.get(null)} throws. Handling the null inside the
 * lookup keeps every call site on the existing "no services, do nothing" path, so a client can never
 * crash merely because a shared mixin target executed there.
 */
class WorldServicesLookupTest {
    @Test void nullServerYieldsNoServicesInsteadOfThrowing() {
        // The cast is required because find(UUID) shares the name; the call shape under test is the
        // server overload that every mixin uses.
        assertNull(WorldServices.find((MinecraftServer) null),
                "a null server is the normal client case and must resolve to 'no services'");
    }

    @Test void nullServerIsHandledWithoutThrowing() {
        assertDoesNotThrow(() -> WorldServices.find((MinecraftServer) null));
    }
}
