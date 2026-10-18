package local.plaincraft.greatwall.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import local.plaincraft.greatwall.model.WallTypes.Axis;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The root-search cache has to be safe to ignore and safe to lose.
 *
 * <p>It exists to skip roughly thirteen minutes of work, which makes it worth having and
 * also makes a wrong hit expensive: an axis reused against terrain that has changed would
 * place the wall against ground that no longer exists. These tests pin the two properties
 * that keep that from happening - a mismatched identity is never accepted, and no failure
 * mode throws.
 */
class RootSearchCacheTest {
    private static final String TERRAIN = "a".repeat(64);
    private static final String PALETTE = "b".repeat(64);
    private static final String TEMPLATES = "c".repeat(64);
    private static final String PARAMETERS = "0".repeat(64);

    private static RootSearchCache.Identity identity(long seed, int spawnX) {
        return RootSearchCache.identityOf(seed, "minecraft:overworld", new Point3i(spawnX, 116, 866),
                TERRAIN, PALETTE, TEMPLATES, PARAMETERS);
    }

    private static Axis axis() {
        return new Axis(new Point3i(500, 116, 866), 822542724, 690177492);
    }

    @Test
    void roundTripsTheAxisItStored(@TempDir Path world) {
        var identity = identity(17L, 0);
        RootSearchCache.write(world, RootSearchCache.of(identity, axis(), 1, 6, 2));

        var read = RootSearchCache.read(world, identity);
        assertTrue(read.isPresent(), "a written entry must read back");
        var restored = RootSearchCache.axisOf(read.get());
        assertEquals(axis(), restored, "the axis must survive exactly; a drift here places the wall elsewhere");
        assertEquals(1, read.get().radiusIndex());
        assertEquals(6, read.get().angleIndex());
        assertEquals(2, read.get().axisIndex());
    }

    @Test
    void anEntryForDifferentTerrainIsRefused(@TempDir Path world) {
        RootSearchCache.write(world, RootSearchCache.of(identity(17L, 0), axis(), 1, 6, 2));

        // Same everything except the terrain hash: the ground the axis was chosen against
        // has changed, so the axis no longer describes this world.
        var changedTerrain = RootSearchCache.identityOf(17L, "minecraft:overworld", new Point3i(0, 116, 866),
                "d".repeat(64), PALETTE, TEMPLATES, PARAMETERS);
        assertTrue(RootSearchCache.read(world, changedTerrain).isEmpty(),
                "a changed terrain hash must invalidate the entry");
    }

    @Test
    void everyIdentityFieldIsPartOfTheMatch(@TempDir Path world) {
        var identity = identity(17L, 0);
        RootSearchCache.write(world, RootSearchCache.of(identity, axis(), 1, 6, 2));

        assertTrue(RootSearchCache.read(world, identity(18L, 0)).isEmpty(), "a different seed must not match");
        assertTrue(RootSearchCache.read(world, identity(17L, 32)).isEmpty(), "a different spawn must not match");
        assertTrue(RootSearchCache.read(world, RootSearchCache.identityOf(17L, "minecraft:the_nether",
                new Point3i(0, 116, 866), TERRAIN, PALETTE, TEMPLATES, PARAMETERS)).isEmpty(),
                "a different dimension must not match");
        assertTrue(RootSearchCache.read(world, RootSearchCache.identityOf(17L, "minecraft:overworld",
                new Point3i(0, 116, 866), TERRAIN, PALETTE, "e".repeat(64), PARAMETERS)).isEmpty(),
                "a different palette hash must not match");
        assertTrue(RootSearchCache.read(world, RootSearchCache.identityOf(17L, "minecraft:overworld",
                new Point3i(0, 116, 866), TERRAIN, "f".repeat(64), TEMPLATES, PARAMETERS)).isEmpty(),
                "a different templates hash must not match");
        // Wall length and the sampling grid both move where the search lands, so an entry chosen under
        // other parameters is not this world selection even though the terrain is unchanged.
        assertTrue(RootSearchCache.read(world, RootSearchCache.identityOf(17L, "minecraft:overworld",
                new Point3i(0, 116, 866), TERRAIN, PALETTE, TEMPLATES, "1".repeat(64))).isEmpty(),
                "a different parameters hash must not match");
    }

    @Test
    void aMissingFileIsNotAHitAndNotAFailure(@TempDir Path world) {
        assertTrue(RootSearchCache.read(world, identity(17L, 0)).isEmpty());
        assertFalse(Files.exists(RootSearchCache.file(world)));
    }

    @Test
    void malformedFilesAreIgnoredRatherThanThrown(@TempDir Path world) throws Exception {
        Path file = RootSearchCache.file(world);
        Files.createDirectories(file.getParent());
        for (String malformed : new String[] {"", "{", "null", "[]", "{\"version\":1}",
                "{\"version\":99}", "not json at all"}) {
            Files.writeString(file, malformed, StandardCharsets.UTF_8);
            assertTrue(RootSearchCache.read(world, identity(17L, 0)).isEmpty(),
                    "malformed content must read as a miss, not throw: " + malformed);
        }
    }

    @Test
    void anEditedPayloadIsDetectedByTheChecksum(@TempDir Path world) throws Exception {
        var identity = identity(17L, 0);
        RootSearchCache.write(world, RootSearchCache.of(identity, axis(), 1, 6, 2));
        Path file = RootSearchCache.file(world);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        // Move the axis without recomputing the checksum, as a hand edit or a bad merge would.
        String edited = text.replace("\"axisOriginX\": 500", "\"axisOriginX\": 501");
        assertNotEquals(text, edited, "the fixture must actually change the payload");
        Files.writeString(file, edited, StandardCharsets.UTF_8);

        assertTrue(RootSearchCache.read(world, identity).isEmpty(),
                "an edited payload must be rejected rather than silently used");
    }

    @Test
    void anUnwritableLocationDoesNotThrow(@TempDir Path world) throws Exception {
        // A regular file where the directory needs to be: creating the cache directory
        // must fail, and the failure must stay inside write().
        Path blocker = world.resolve("greatwall");
        Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
        RootSearchCache.write(world, RootSearchCache.of(identity(17L, 0), axis(), 1, 6, 2));
        assertTrue(RootSearchCache.read(world, identity(17L, 0)).isEmpty());
    }

    @Test
    void describeReportsTheCachedAxisWithoutThrowing(@TempDir Path world) {
        assertEquals(1, RootSearchCache.describe(world).size(), "a missing cache still describes something");
        RootSearchCache.write(world, RootSearchCache.of(identity(17L, 0), axis(), 1, 6, 2));
        assertTrue(RootSearchCache.describe(world).getFirst().contains("radiusIndex=1"));
    }
}
