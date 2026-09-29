package local.plaincraft.greatwall.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import local.plaincraft.greatwall.diagnostics.AllocationBudget;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.TerrainTile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Production-scope admission assertions. The three capacity tests intentionally expose missing
 * accounting; a file-size ceiling is not admission into the active working-set budget. */
class EvidenceAllocationBoundaryAuditTest {
    private static final String H = "a".repeat(64);
    private static final ChunkKey OWNER = new ChunkKey("minecraft:overworld", -1, 0);
    @TempDir Path directory;

    @Test void persistPlanMustReserveScratchBeforePublishingIntoAFullWorkingSet() throws Exception {
        var store = ChunkEvidenceStore.production(directory);
        var plan = plan();
        assertAll(
                () -> assertRejectedBeforeReturn(() -> { store.persistPlan(plan); }),
                () -> {
                    try (var files = Files.walk(directory)) {
                        assertTrue(files.noneMatch(p -> p.getFileName().toString().equals("build-proof.bin")),
                                "capacity denial must precede publication of authoritative evidence");
                    }
                });
    }

    @Test void readPlanMustReserveScratchBeforeDecodingIntoAFullWorkingSet() throws Exception {
        var store = ChunkEvidenceStore.production(directory);
        var expected = plan();
        store.persistPlan(expected); // Fixture setup, outside the production scope under test.
        assertRejectedBeforeReturn(() -> {
            assertEquals(expected, store.readPlan(OWNER).orElseThrow());
        });
    }

    @Test void canonicalPlanEncodingMustReserveItsBufferInsideAProductionScope() {
        var plan = plan();
        assertRejectedBeforeReturn(() -> {
            assertTrue(CanonicalCodec.writePlan(plan).length > 0);
        });
    }

    @Test void allocationHintAccountsForPaletteTextAndPreservesOriginalT0Hash() throws Exception {
        var store = ChunkEvidenceStore.production(directory);
        var key = new TerrainTile.Key(OWNER.dimension(), OWNER.x(), OWNER.z(), 0, 127);
        var palette = List.of("fixture:plain", "fixture:long_" + "x".repeat(4096), "utf_\u0000_\u4e2d");
        int[] states = new int[key.volume()];
        for (int i = 0; i < states.length; i++) states[i] = i % palette.size();
        var tile = new TerrainTile(key, H, palette, states);
        store.persistBeforeApply(tile);
        long hint = store.tileAllocationHint(key).orElseThrow();
        assertTrue(hint > 4L * key.volume() + 4L * 4096,
                "a palette-sensitive forecast must exceed the raw int[] alone");
        var restored = store.read(key, H).orElseThrow();
        assertEquals(tile.hash(), restored.hash());
        assertEquals(tile.palette(), restored.palette());
        assertArrayEquals(tile.copyStates(), restored.copyStates());
    }

    @Test void allocationHintIsNotAuthorityToReadWithAnotherRecipe() throws Exception {
        var store = ChunkEvidenceStore.production(directory);
        var key = new TerrainTile.Key(OWNER.dimension(), OWNER.x(), OWNER.z(), 0, 127);
        store.persistBeforeApply(new TerrainTile(key, H, List.of("stone"), new int[key.volume()]));
        assertTrue(store.tileAllocationHint(key).isPresent());
        assertThrows(IOException.class, () -> store.read(key, "b".repeat(64)));
    }

    private static void assertRejectedBeforeReturn(IoAction action) {
        try (var budget = new AllocationBudget(1)) {
            budget.consume(1);
            var returned = new AtomicBoolean();
            assertAll(
                    () -> {
                        var failure = assertThrows(WallFailure.class, () -> CompiledAllocation.run(budget, () -> {
                            try {
                                action.run();
                            } catch (IOException error) {
                                throw new UncheckedIOException(error);
                            }
                            returned.set(true);
                            return 0L;
                        }, Long::longValue));
                        assertEquals(FailureCode.CAPACITY, failure.code());
                    },
                    () -> assertFalse(returned.get(), "operation must reject before allocating/returning its payload"),
                    () -> assertEquals(1, budget.stats().used(), "denial cannot release the existing consumer"));
        }
    }

    private static ChunkWritePlan plan() {
        var site = new SiteId("allocation-audit", SiteKind.WALL, 0);
        var point = new Point3i(-1, 80, 0);
        var voxel = new VoxelIntent(point, IntentKind.SOLID, Part.SKIN,
                new BlockSpec("minecraft:stone_bricks", Map.of()), site, 400, true, false);
        var raw = new ChunkWritePlan(OWNER, H, H, List.of(voxel), List.of(), List.of(), List.of(),
                List.of(), List.of(), H);
        return new ChunkWritePlan(raw.owner(), raw.recipeHash(), raw.inputHash(), raw.voxels(), raw.proofs(),
                raw.blockEntities(), raw.postprocess(), raw.fluidTicks(), raw.walkwayFeet(),
                DeterministicHash.sha256(CanonicalCodec.writePlan(raw)));
    }

    @FunctionalInterface private interface IoAction { void run() throws IOException; }
}
