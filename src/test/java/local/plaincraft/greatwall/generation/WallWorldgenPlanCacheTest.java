package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

/**
 * The cache's hot-path {@code get}/{@code put} are keyed by {@code ServerLevel}, which cannot be
 * constructed in a bare JUnit JVM. These tests cover the offline-verifiable contract: the write
 * plan shape the cache stores, and the cache's null-safety (every public entry point is a no-op on
 * null input so a half-initialized level can never crash the generation path).
 */
class WallWorldgenPlanCacheTest {
    private static final String HASH = "a".repeat(64);

    private static ChunkWritePlan plan(ChunkKey key, boolean empty) {
        var voxels = empty ? List.<VoxelIntent>of() : List.of(
                new VoxelIntent(new Point3i(key.x() * 16, 64, key.z() * 16), IntentKind.SOLID, Part.CORE,
                        new local.plaincraft.greatwall.model.WallTypes.BlockSpec("minecraft:stone", java.util.Map.of()), new SiteId("w", SiteKind.WALL, 0),
                        0, false, true));
        return new ChunkWritePlan(key, HASH, HASH, voxels, List.of(), List.of(), List.of(), List.of(),
                List.of(), WriteMetadata.EMPTY, HASH);
    }

    @Test void diskReadStaysDeduplicatedUntilServerReceivesIt() {
        var key = new ChunkKey("minecraft:overworld", 1, 2);
        var session = new WallWorldgenPlanCache.Session();
        var ticket = session.beginRead(key);
        assertNotNull(ticket);
        assertNull(session.beginRead(key));
        var diskPlan = plan(key, false);
        assertTrue(session.finishRead(key, ticket, diskPlan));
        assertSame(diskPlan, session.get(key));
        assertNull(session.beginRead(key));
    }

    @Test void publicationWinsEvenAfterCacheEvictsNewPlan() {
        var key = new ChunkKey("minecraft:overworld", 0, 0);
        var session = new WallWorldgenPlanCache.Session();
        var oldRead = session.beginRead(key);
        assertNotNull(oldRead);
        var published = plan(key, false);
        session.put(key, published);
        assertSame(published, session.get(key));
        // 填满非空缓存，证明拒绝旧 IO 不依赖当下还能命中刚发布的计划。
        for (int x = 1; x <= 4096; x++) {
            var other = new ChunkKey(key.dimension(), x, 0);
            session.put(other, plan(other, false));
        }
        assertNull(session.get(key));
        assertFalse(session.finishRead(key, oldRead, plan(key, true)));
        assertNull(session.get(key));
        assertNotNull(session.beginRead(key));
    }

    @Test void oldTicketCannotBeReusedInAnotherSessionOrForAnotherOwner() {
        var key = new ChunkKey("minecraft:overworld", 1, 2);
        var other = new ChunkKey("minecraft:overworld", 2, 2);
        var oldSession = new WallWorldgenPlanCache.Session();
        var newSession = new WallWorldgenPlanCache.Session();
        var old = oldSession.beginRead(key);
        var current = newSession.beginRead(key);
        assertFalse(newSession.finishRead(key, old, plan(key, false)));
        assertFalse(newSession.finishRead(key, current, plan(other, false)));
        assertNull(newSession.get(key));
        var fresh = newSession.beginRead(key);
        assertNotNull(fresh);
        assertTrue(newSession.finishRead(key, fresh, plan(key, false)));
    }

    @Test void nullInputsAreNoOps() {
        WallWorldgenPlanCache.put(null, null);
        WallWorldgenPlanCache.invalidate(null);
        WallWorldgenPlanCache.clearAll();
        assertNull(WallWorldgenPlanCache.get(null, null));
    }

    @Test void emptyPlanCarriesOwnerAndNoVoxels() {
        var key = new ChunkKey("minecraft:overworld", 1, 2);
        var p = plan(key, true);
        assertEquals(key, p.owner());
        assertTrue(p.voxels().isEmpty());
    }

    @Test void nonEmptyPlanCarriesOwnerRecipeAndVoxels() {
        var key = new ChunkKey("minecraft:overworld", 3, 4);
        var p = plan(key, false);
        assertEquals(key, p.owner());
        assertEquals(HASH, p.recipeHash());
        assertEquals(1, p.voxels().size());
        var v = p.voxels().get(0);
        assertEquals(IntentKind.SOLID, v.kind());
        assertEquals(Part.CORE, v.part());
    }
}
