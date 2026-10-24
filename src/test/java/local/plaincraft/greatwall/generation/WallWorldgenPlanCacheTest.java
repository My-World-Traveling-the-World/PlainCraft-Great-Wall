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
