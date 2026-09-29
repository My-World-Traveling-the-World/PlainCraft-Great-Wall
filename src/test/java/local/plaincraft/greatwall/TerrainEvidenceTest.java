package local.plaincraft.greatwall;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import local.plaincraft.greatwall.generation.OwnershipGuard;
import local.plaincraft.greatwall.persistence.ChunkEvidenceStore;
import local.plaincraft.greatwall.planning.ProbePlanner;
import local.plaincraft.greatwall.terrain.TerrainTile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TerrainEvidenceTest {
    @TempDir Path root;
    private static final String RECIPE = ProbePlanner.hash("fixture");
    private static final TerrainTile.Key KEY = new TerrainTile.Key("minecraft:overworld", -1, 0, 80, 118);
    private static TerrainTile tile() { return new TerrainTile(KEY, RECIPE, List.of("stone"), new int[KEY.volume()]); }

    @Test void windowIsInclusiveAndDataIsDefensivelyCopied() {
        int[] states = new int[KEY.volume()];
        var snapshot = new TerrainTile(KEY, RECIPE, List.of("stone", "gold"), states);
        states[0] = 1;
        snapshot.copyStates()[0] = 1;
        assertEquals(9984, KEY.volume());
        assertEquals("stone", snapshot.state(0, 80, 0));
        assertThrows(IllegalArgumentException.class, () -> snapshot.state(0, 79, 0));
    }
    @Test void durableRoundTripIsIdempotentAndNotACache() throws Exception {
        var store = new ChunkEvidenceStore(root);
        assertEquals(tile().hash(), store.persistBeforeApply(tile()).hash());
        assertEquals(tile().hash(), new ChunkEvidenceStore(root).read(KEY, RECIPE).orElseThrow().hash());
        assertEquals(tile().hash(), store.persistBeforeApply(tile()).hash());
        byte[] bytes = Files.readAllBytes(store.path(KEY));
        bytes[20] ^= 1;
        Files.write(store.path(KEY), bytes);
        assertThrows(java.io.IOException.class, () -> store.read(KEY, RECIPE));
        assertThrows(java.io.IOException.class, () -> store.persistBeforeApply(tile()));
        assertArrayEquals(bytes, Files.readAllBytes(store.path(KEY)));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }
    @Test void evidenceRejectsDifferentRecipeAndOverwrite() throws Exception {
        var store = new ChunkEvidenceStore(root);
        store.persistBeforeApply(tile());
        assertThrows(java.io.IOException.class, () -> store.read(KEY, ProbePlanner.hash("changed")));
        var changed = new TerrainTile(KEY, RECIPE, List.of("gold"), new int[KEY.volume()]);
        assertThrows(java.io.IOException.class, () -> store.persistBeforeApply(changed));
    }
    @Test void paletteNumberingDoesNotChangeCanonicalHash() {
        int[] stoneAtOne = new int[KEY.volume()];
        java.util.Arrays.fill(stoneAtOne, 1);
        var reordered = new TerrainTile(KEY, RECIPE, List.of("gold", "stone"), stoneAtOne);
        assertEquals(tile().hash(), reordered.hash());
        assertThrows(UnsupportedOperationException.class, () -> reordered.palette().set(0, "player"));
    }
    @Test void highYAndDimensionIdentitySurviveDiskRoundTrip() throws Exception {
        var high = new TerrainTile.Key("minecraft:overworld", -62500, 62500, 5968, 6006);
        var other = new TerrainTile.Key("test:other", -62500, 62500, 5968, 6006);
        var first = new TerrainTile(high, RECIPE, List.of("stone"), new int[high.volume()]);
        var second = new TerrainTile(other, RECIPE, List.of("stone"), new int[other.volume()]);
        var store = new ChunkEvidenceStore(root);
        store.persistBeforeApply(first);
        store.persistBeforeApply(second);
        assertNotEquals(first.hash(), second.hash());
        assertNotEquals(store.path(high), store.path(other));
        assertEquals(high, store.read(high, RECIPE).orElseThrow().key());
        assertEquals("stone", store.read(high, RECIPE).orElseThrow().state(15, 6006, 15));
        assertEquals(other, store.read(other, RECIPE).orElseThrow().key());
    }
    @Test void truncatedAndOversizedEvidenceFailsWithoutReconstruction() throws Exception {
        var store = new ChunkEvidenceStore(root);
        store.persistBeforeApply(tile());
        byte[] original = Files.readAllBytes(store.path(KEY));
        for (int length : new int[] {0, 4, 19, original.length / 2}) {
            byte[] damaged = java.util.Arrays.copyOf(original, length);
            Files.write(store.path(KEY), damaged);
            assertThrows(java.io.IOException.class, () -> store.read(KEY, RECIPE));
            assertThrows(java.io.IOException.class, () -> store.persistBeforeApply(tile()));
            assertArrayEquals(damaged, Files.readAllBytes(store.path(KEY)));
        }
        byte[] oversized = new byte[2 * 1024 * 1024 + 1];
        Files.write(store.path(KEY), oversized);
        assertThrows(java.io.IOException.class, () -> store.read(KEY, RECIPE));
        assertArrayEquals(oversized, Files.readAllBytes(store.path(KEY)));
    }
    @Test void windowBoundsRejectOverflowAndInvalidPaletteIndices() {
        assertThrows(IllegalArgumentException.class, () -> new TerrainTile.Key("minecraft:overworld", 0, 0,
                Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new TerrainTile.Key("minecraft:overworld", 0, 0, 0, 128));
        assertThrows(IllegalArgumentException.class, () -> new TerrainTile.Key("../world", 0, 0, 0, 1));
        int[] invalid = new int[KEY.volume()];
        invalid[0] = 1;
        assertThrows(IllegalArgumentException.class, () -> new TerrainTile(KEY, RECIPE, List.of("stone"), invalid));
        assertThrows(IllegalArgumentException.class, () -> new TerrainTile(KEY, RECIPE, List.of("stone"), new int[1]));
    }
    @Test void ownerGuardUsesFloorDivisionAndProtectsPlayerChanges() {
        assertDoesNotThrow(() -> OwnershipGuard.check(-1, 0, -1, 80, 0, 80, 118, false, "stone", "stone", "gold"));
        assertDoesNotThrow(() -> OwnershipGuard.check(-1, 0, -1, 80, 0, 80, 118, false, "gold", "stone", "gold"));
        assertThrows(IllegalStateException.class, () -> OwnershipGuard.check(-1, 0, 0, 80, 0, 80, 118, false, "stone", "stone", "gold"));
        assertThrows(IllegalStateException.class, () -> OwnershipGuard.check(-1, 0, -1, 80, 0, 80, 118, true, "stone", "stone", "gold"));
        assertThrows(IllegalStateException.class, () -> OwnershipGuard.check(-1, 0, -1, 80, 0, 80, 118, false, "player", "stone", "gold"));
        assertThrows(IllegalStateException.class, () -> OwnershipGuard.check(-1, 0, -1, 79, 0, 80, 118, false, "stone", "stone", "gold"));
        assertThrows(IllegalStateException.class, () -> OwnershipGuard.check(-1, 0, -1, 119, 0, 80, 118, false, "stone", "stone", "gold"));
    }
}
