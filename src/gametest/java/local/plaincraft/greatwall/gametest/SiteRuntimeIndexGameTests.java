package local.plaincraft.greatwall.gametest;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.plaincraft.greatwall.gameplay.SiteRuntimeIndex;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Pins {@link SiteRuntimeIndex}, the per-chunk runtime index the garrison and spawn paths read.
 *
 * <h2>Why this exists</h2>
 * The class had no test anywhere, and it sits between durable markers and live gameplay: every "is
 * there a firepit / spawner / spawn slot here" question the spawn guard asks goes through it. Its
 * failure mode is a wrong answer rather than an exception - a slot reported at the wrong distance, a
 * marker from another wall accepted, or a half-written chunk indexed would each send a mob or a beacon
 * somewhere the plan never authorised, and nothing downstream re-derives the index.
 *
 * <p>These drive real objects rather than mocks: {@code loaded()} takes a {@link LevelChunk} and a
 * {@link TerrainMarkers.Marker}, so a GameTest supplies both. Assertions use
 * {@link GameTestHelper} rather than JUnit, because the gametest source set has no JUnit classpath.
 */
public final class SiteRuntimeIndexGameTests implements FabricGameTest {
    private static final String H = "a".repeat(64);

    private static SiteId site(SiteKind kind, long ordinal) {
        return new SiteId("fixture", kind, ordinal);
    }

    private static SpawnSlot slot(SiteId site, int slotIndex, int x, int y, int z) {
        return new SpawnSlot(site, slotIndex, new Point3i(x, y, z), "minecraft:pillager");
    }

    private static TerrainMarkers.Marker marker(String recipe, boolean complete, List<SpawnSlot> slots,
            Map<SiteId, DecayState> realized) {
        return new TerrainMarkers.Marker(recipe, H, H, 0L, complete, List.of(), List.of(), slots, realized);
    }

    /**
     * A recipe whose hash the markers below carry.
     *
     * <p>Built inline rather than taken from {@code PlanningFixtures}: that helper lives in the
     * {@code test} source set, which the {@code gametest} source set cannot see. This mirrors the
     * recipe already constructed by {@code ProductionPipelineTests}.
     */
    private static WorldRecipe recipe() {
        return new WorldRecipe(new UUID(0, 1), "fixture", 0L, "minecraft:overworld", new Point3i(0, 64, 0),
                new Axis(new Point3i(0, 64, 0), 1 << 30, 0), new DimensionBounds(-64, 320, 29_999_000),
                new GenerationConfig(1, 1, 2048, 4, 150000, 256, 512, 8, 64L << 20, 64L << 20, 32L << 20),
                H, H, H, H);
    }

    private static String recipeHash() {
        return recipe().recipeHash();
    }

    /** The level's chunk position, so every case works at whatever coordinates this run landed on. */
    private static ChunkPos pos(GameTestHelper helper) {
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        return new ChunkPos(origin.getX() >> 4, origin.getZ() >> 4);
    }

    private static LevelChunk chunk(GameTestHelper helper, ChunkPos pos) {
        var access = helper.getLevel().getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
        helper.assertTrue(access instanceof LevelChunk,
                "the fixture needs a fully loaded LevelChunk, got " + access.getClass().getName());
        return (LevelChunk) access;
    }

    private static SiteRuntimeIndex index() {
        return new SiteRuntimeIndex(recipe());
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void aMatchingCompleteMarkerIsIndexedAndItsSlotsBecomeQueryable(GameTestHelper helper) {
        var p = pos(helper);
        var index = index();
        var spawnSite = site(SiteKind.ENEMY_TOWER, 1);
        var feet = new Point3i(p.getMinBlockX() + 8, 70, p.getMinBlockZ() + 8);

        index.loaded(chunk(helper, p), marker(recipeHash(), true,
                List.of(slot(spawnSite, 0, feet.x(), feet.y(), feet.z())),
                Map.of(spawnSite, DecayState.INTACT)));

        helper.assertTrue(index.loadedChunks() == 1, "the chunk must be counted once, got " + index.loadedChunks());
        var near = index.nearbySlots(feet, 8);
        helper.assertTrue(near.size() == 1, "a slot at the query point is within radius 8, got " + near.size());
        helper.assertTrue(near.getFirst().feet().equals(feet), "the slot keeps its recorded position");
        var far = index.nearbySlots(new Point3i(feet.x() + 1000, feet.y(), feet.z()), 8);
        helper.assertTrue(far.isEmpty(), "the same slot must not be reported a thousand blocks away");
        index.close();
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void aMarkerForAnotherRecipeIsIgnoredRatherThanIndexed(GameTestHelper helper) {
        // A foreign recipe means the chunk belongs to a different wall; indexing it would let this
        // session's spawn logic act on another world's site.
        var p = pos(helper);
        var index = index();
        index.loaded(chunk(helper, p), marker("b".repeat(64), true,
                List.of(slot(site(SiteKind.ENEMY_TOWER, 1), 0, 0, 70, 0)), Map.of()));

        helper.assertTrue(index.loadedChunks() == 0,
                "a marker for another recipe must not be indexed, got " + index.loadedChunks());
        index.close();
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void anIncompleteMarkerIsIgnoredRatherThanIndexed(GameTestHelper helper) {
        // "complete" gates half-written chunks: without it a partially generated chunk would publish
        // slots for terrain that does not exist yet.
        var p = pos(helper);
        var index = index();
        index.loaded(chunk(helper, p), marker(recipeHash(), false,
                List.of(slot(site(SiteKind.ENEMY_TOWER, 1), 0, 0, 70, 0)), Map.of()));

        helper.assertTrue(index.loadedChunks() == 0,
                "an incomplete marker must not be indexed, got " + index.loadedChunks());
        index.close();
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void unloadingRemovesTheChunkAndItsSlotsTogether(GameTestHelper helper) {
        // Leaving slots behind would let the index answer with sites whose terrain is no longer loaded,
        // which is exactly what the guard exists to prevent.
        var p = pos(helper);
        var index = index();
        var feet = new Point3i(p.getMinBlockX() + 4, 70, p.getMinBlockZ() + 4);
        index.loaded(chunk(helper, p), marker(recipeHash(), true,
                List.of(slot(site(SiteKind.ENEMY_TOWER, 1), 0, feet.x(), feet.y(), feet.z())), Map.of()));
        helper.assertTrue(index.nearbySlots(feet, 4).size() == 1, "the slot must be visible before unload");

        index.unloaded(new ChunkKey(recipe().dimension(), p.x, p.z));

        helper.assertTrue(index.loadedChunks() == 0, "the chunk must be forgotten");
        helper.assertTrue(index.nearbySlots(feet, 4).isEmpty(), "and its slots with it");
        index.close();
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void theSameMarkerIsIdempotentSoAReloadDoesNotDuplicateTheBucket(GameTestHelper helper) {
        var p = pos(helper);
        var index = index();
        var feet = new Point3i(p.getMinBlockX() + 4, 70, p.getMinBlockZ() + 4);
        var m = marker(recipeHash(), true,
                List.of(slot(site(SiteKind.ENEMY_TOWER, 1), 0, feet.x(), feet.y(), feet.z())), Map.of());

        index.loaded(chunk(helper, p), m);
        index.loaded(chunk(helper, p), m);

        helper.assertTrue(index.loadedChunks() == 1, "a repeated load of one chunk is still one chunk");
        helper.assertTrue(index.nearbySlots(feet, 4).size() == 1,
                "a reload must not duplicate the slot bucket, got " + index.nearbySlots(feet, 4).size());
        index.close();
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void anOutOfRangeRadiusIsRejectedRatherThanSilentlyUnbounded(GameTestHelper helper) {
        // An unbounded radius would turn the guard's proximity question into "any slot anywhere",
        // letting a garrison activate from across the world.
        var index = index();
        var query = new Point3i(0, 70, 0);
        boolean negativeRejected = false;
        try {
            index.nearbySlots(query, -1);
        } catch (IllegalArgumentException expected) {
            negativeRejected = true;
        }
        helper.assertTrue(negativeRejected, "a negative radius must be rejected");

        boolean tooLargeRejected = false;
        try {
            index.nearbySlots(query, 65);
        } catch (IllegalArgumentException expected) {
            tooLargeRejected = true;
        }
        helper.assertTrue(tooLargeRejected, "a radius above 64 must be rejected");

        helper.assertTrue(index.nearbySlots(query, 0).isEmpty(), "radius 0 is legal and matches nothing here");
        index.close();
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void aClosedIndexRefusesFurtherUseRatherThanAnsweringStaleData(GameTestHelper helper) {
        var index = index();
        index.close();
        // The index fails closed with IllegalStateException("STALE_SESSION: ..."), not WallFailure.
        // This assertion originally expected WallFailure and failed, which was the test being wrong
        // about the contract rather than the code being wrong; the message is asserted too so a
        // different refusal cannot be mistaken for this one.
        boolean refused = false;
        String message = null;
        try {
            index.nearbySlots(new Point3i(0, 70, 0), 4);
        } catch (IllegalStateException expected) {
            refused = true;
            message = expected.getMessage();
        }
        helper.assertTrue(refused, "a closed index must fail closed instead of answering from a cleared table");
        helper.assertTrue(message != null && message.startsWith("STALE_SESSION"),
                "the refusal must name the stale session, got: " + message);
        helper.succeed();
    }
}
