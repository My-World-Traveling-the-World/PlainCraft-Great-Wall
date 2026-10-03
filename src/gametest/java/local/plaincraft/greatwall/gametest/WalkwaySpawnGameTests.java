package local.plaincraft.greatwall.gametest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import local.plaincraft.greatwall.diagnostics.WalkwayCollisionCheck;
import local.plaincraft.greatwall.gameplay.WalkwaySpawnGuard;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.Shapes;

public final class WalkwaySpawnGameTests implements FabricGameTest {
    private static final String HASH = "a".repeat(64);
    private static final long REVISION = 7;

    @GameTest(template = EMPTY_STRUCTURE)
    public void ownedBottomWestStairIsVetoedDespiteNonSturdyUpFace(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var stair = Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                    .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
            f.chunk.setBlockState(f.feet.below(), stair, false);
            helper.assertFalse(stair.isFaceSturdy(f.chunk, f.feet.below(), Direction.UP),
                    "preserve the old full-UP-face false-negative witness");
            var shape = stair.getCollisionShape(f.chunk, f.feet.below());
            helper.assertTrue(WalkwayCollisionCheck.inspect(Shapes.empty(), Shapes.empty(), shape).supported(),
                    "the shared walkway support band accepts this bottom west stair");
            helper.assertFalse(blocked(helper, f), "stair support alone cannot authorize a walkway veto");
            f.certify(true);
            helper.assertTrue(blocked(helper, f), "certified owned bottom west stair must be vetoed");
            helper.assertFalse(f.chunk.getBlockState(f.feet.below())
                    .isFaceSturdy(f.chunk, f.feet.below(), Direction.UP),
                    "veto must not depend on turning the stair into a full UP face");
            f.chunk.setAttached(TerrainMarkers.TOMBSTONES, Set.of(f.floor()));
            helper.assertFalse(blocked(helper, f), "stair support must not bypass a floor tombstone");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void ownedBottomSlabWithoutIntegerFootSupportIsNotVetoed(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            f.chunk.setBlockState(f.feet.below(), Blocks.STONE_BRICK_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM), false);
            f.certify(true);
            helper.assertFalse(blocked(helper, f),
                    "certified ownership cannot fake support below the integer foot plane");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void topSlabCounterexampleAndMask(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            helper.assertTrue(f.chunk.getBlockState(f.feet.below())
                    .isValidSpawn(f.chunk, f.feet.below(), EntityType.ZOMBIE),
                    "an upper slab is not vanilla spawn-proof");
            helper.assertFalse(blocked(helper, f), "an unmarked slab is not a walkway");
            f.certify(true);
            helper.assertTrue(blocked(helper, f), "certified upper-slab walkway is vetoed");
            helper.assertFalse(WalkwaySpawnGuard.blocksNaturalSpawn(helper.getLevel(), f.feet.above()),
                    "matching X/Z at another Y is not a matching three-int foot position");
            helper.assertFalse(WalkwaySpawnGuard.blocksNaturalSpawn(helper.getLevel(), f.feet.east()),
                    "adjacent unmarked ground is not vetoed");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void incompleteOrUnownedMarkerDoesNotVeto(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            f.certify(false);
            helper.assertFalse(blocked(helper, f), "partial generation is not certified");
            f.certify(true);
            f.chunk.removeAttached(TerrainMarkers.OWNERSHIP);
            helper.assertFalse(blocked(helper, f), "marker alone does not own the floor");
            f.chunk.setAttached(TerrainMarkers.OWNERSHIP,
                    new TerrainMarkers.Ownership(REVISION + 1, Set.of(f.floor()), Set.of()));
            helper.assertFalse(blocked(helper, f), "ownership revision must match the marker");
            f.chunk.setAttached(TerrainMarkers.OWNERSHIP,
                    new TerrainMarkers.Ownership(REVISION, Set.of(f.point()), Set.of()));
            helper.assertFalse(blocked(helper, f), "owning only air at the feet is insufficient");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void replacedSameFloorNeverRegainsVeto(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            f.certify(true);
            var slab = f.chunk.getBlockState(f.feet.below());
            f.chunk.setBlockState(f.feet.below(), Blocks.AIR.defaultBlockState(), false);
            helper.assertFalse(blocked(helper, f), "missing support is not an owned floor");
            f.chunk.setBlockState(f.feet.below(), slab, false);
            helper.assertTrue(f.chunk.getAttached(TerrainMarkers.TOMBSTONES).contains(f.floor()),
                    "existing mutation observer must persist the replacement tombstone");
            helper.assertFalse(blocked(helper, f), "replacing the same slab never reclaims ownership");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void persistentMasksRoundTripAndKeepTombstones(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            f.certify(true);
            var marker = f.chunk.getAttached(TerrainMarkers.PRODUCTION);
            var encoded = TerrainMarkers.CODEC.encodeStart(NbtOps.INSTANCE, marker).result().orElseThrow();
            f.chunk.setAttached(TerrainMarkers.PRODUCTION,
                    TerrainMarkers.CODEC.parse(NbtOps.INSTANCE, encoded).result().orElseThrow());
            var ownershipNbt = TerrainMarkers.OWNERSHIP_CODEC.encodeStart(NbtOps.INSTANCE,
                    f.chunk.getAttached(TerrainMarkers.OWNERSHIP)).result().orElseThrow();
            f.chunk.setAttached(TerrainMarkers.OWNERSHIP,
                    TerrainMarkers.OWNERSHIP_CODEC.parse(NbtOps.INSTANCE, ownershipNbt).result().orElseThrow());
            helper.assertTrue(blocked(helper, f), "persisted walkway feet retain their exact identity");
            var tombstones = Set.of(f.floor());
            var codec = TerrainMarkers.TOMBSTONES.persistenceCodec();
            var tombstoneNbt = codec.encodeStart(NbtOps.INSTANCE, tombstones).result().orElseThrow();
            f.chunk.setAttached(TerrainMarkers.TOMBSTONES,
                    codec.parse(NbtOps.INSTANCE, tombstoneNbt).result().orElseThrow());
            helper.assertFalse(blocked(helper, f), "persisted tombstone overrides persisted walkway");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void unloadedChunkQueryDoesNotLoadAnything(GameTestHelper helper) {
        var level = helper.getLevel();
        var source = level.getChunkSource();
        var remote = new BlockPos(28_000_000, helper.absolutePos(new BlockPos(1, 2, 1)).getY(),
                -28_000_000);
        int x = Math.floorDiv(remote.getX(), 16), z = Math.floorDiv(remote.getZ(), 16);
        helper.assertTrue(source.getChunkNow(x, z) == null, "remote fixture must be unloaded");
        int before = source.getLoadedChunksCount();
        helper.assertFalse(WalkwaySpawnGuard.blocksNaturalSpawn(level, remote),
                "unloaded chunk is not queried through a loading API");
        helper.assertTrue(source.getChunkNow(x, z) == null && source.getLoadedChunksCount() == before,
                "read-only guard must not load the remote chunk");
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void naturalMixinVetoesBeforeVanillaCandidateChecks(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            f.certify(true);
            var method = NaturalSpawner.class.getDeclaredMethod("isValidSpawnPostitionForType",
                    ServerLevel.class, MobCategory.class, StructureManager.class, ChunkGenerator.class,
                    MobSpawnSettings.SpawnerData.class, BlockPos.MutableBlockPos.class, double.class);
            method.setAccessible(true);
            // Null candidate data deliberately fails unless the registered HEAD injection vetoes first.
            boolean result = (boolean) method.invoke(null, helper.getLevel(), MobCategory.MONSTER,
                    null, null, null, f.feet.mutable(), 1024.0);
            helper.assertFalse(result, "actual transformed natural-spawn method must veto the walkway");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE)
    public void nonNaturalEntityAddsAreNotIntercepted(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            f.certify(true);
            helper.assertTrue(blocked(helper, f), "fixture is blocked for natural candidates");
            for (var reason : List.of(MobSpawnType.COMMAND, MobSpawnType.SPAWNER, MobSpawnType.STRUCTURE)) {
                var mob = EntityType.PIG.create(helper.getLevel());
                helper.assertTrue(mob != null, "registered test entity must be creatable");
                try {
                    mob.moveTo(f.feet.getX() + 0.5, f.feet.getY(), f.feet.getZ() + 0.5, 0, 0);
                    mob.finalizeSpawn(helper.getLevel(), helper.getLevel().getCurrentDifficultyAt(f.feet),
                            reason, null);
                    helper.assertTrue(helper.getLevel().addFreshEntity(mob),
                            "non-natural add must not be globally vetoed: " + reason);
                } finally {
                    mob.discard();
                }
            }
        }
        helper.succeed();
    }

    private static boolean blocked(GameTestHelper helper, Fixture fixture) {
        return WalkwaySpawnGuard.blocksNaturalSpawn(helper.getLevel(), fixture.feet);
    }

    private static final class Fixture implements AutoCloseable {
        private final LevelChunk chunk;
        private final BlockPos feet;
        private final TerrainMarkers.Marker previousMarker;
        private final TerrainMarkers.Ownership previousOwnership;
        private final Set<Point3i> previousTombstones;
        private final List<BlockState> previousBlocks;

        private Fixture(GameTestHelper helper) {
            feet = helper.absolutePos(new BlockPos(1, 2, 1));
            chunk = helper.getLevel().getChunkSource().getChunkNow(
                    Math.floorDiv(feet.getX(), 16), Math.floorDiv(feet.getZ(), 16));
            if (chunk == null) throw new AssertionError("test chunk must already be loaded");
            previousMarker = chunk.getAttached(TerrainMarkers.PRODUCTION);
            previousOwnership = chunk.getAttached(TerrainMarkers.OWNERSHIP);
            previousTombstones = chunk.getAttached(TerrainMarkers.TOMBSTONES);
            previousBlocks = List.of(chunk.getBlockState(feet.below()),
                    chunk.getBlockState(feet), chunk.getBlockState(feet.above()));
            chunk.removeAttached(TerrainMarkers.PRODUCTION);
            chunk.removeAttached(TerrainMarkers.OWNERSHIP);
            chunk.removeAttached(TerrainMarkers.TOMBSTONES);
            chunk.setBlockState(feet.below(), Blocks.STONE_BRICK_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP), false);
            chunk.setBlockState(feet, Blocks.AIR.defaultBlockState(), false);
            chunk.setBlockState(feet.above(), Blocks.AIR.defaultBlockState(), false);
        }

        private Point3i point() { return new Point3i(feet.getX(), feet.getY(), feet.getZ()); }
        private Point3i floor() { return new Point3i(feet.getX(), feet.getY() - 1, feet.getZ()); }

        private void certify(boolean complete) {
            chunk.setAttached(TerrainMarkers.PRODUCTION, new TerrainMarkers.Marker(
                    HASH, HASH, HASH, REVISION, complete, List.of(point()), List.of(), List.of(), Map.of()));
            chunk.setAttached(TerrainMarkers.OWNERSHIP,
                    new TerrainMarkers.Ownership(REVISION, Set.of(floor()), Set.of()));
        }

        @Override public void close() {
            chunk.removeAttached(TerrainMarkers.OWNERSHIP);
            chunk.setBlockState(feet.below(), previousBlocks.get(0), false);
            chunk.setBlockState(feet, previousBlocks.get(1), false);
            chunk.setBlockState(feet.above(), previousBlocks.get(2), false);
            restore(TerrainMarkers.PRODUCTION, previousMarker);
            restore(TerrainMarkers.OWNERSHIP, previousOwnership);
            restore(TerrainMarkers.TOMBSTONES, previousTombstones);
        }

        private <T> void restore(AttachmentType<T> type, T value) {
            if (value == null) chunk.removeAttached(type);
            else chunk.setAttached(type, value);
        }
    }
}
