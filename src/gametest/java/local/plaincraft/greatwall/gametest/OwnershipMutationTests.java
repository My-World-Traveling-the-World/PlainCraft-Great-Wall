package local.plaincraft.greatwall.gametest;

import java.util.Set;
import local.plaincraft.greatwall.generation.OwnershipMutations;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.chunk.LevelChunk;

public final class OwnershipMutationTests implements FabricGameTest {
    private record Fixture(LevelChunk chunk,BlockPos pos,Point3i point,ChunkKey owner) {}
    private static Fixture owned(GameTestHelper helper,boolean firepit) {
        var p=helper.absolutePos(new BlockPos(1,2,1));
        var c=helper.getLevel().getChunkSource().getChunkNow(Math.floorDiv(p.getX(),16),Math.floorDiv(p.getZ(),16));
        if(c==null)throw new AssertionError("test chunk must already be loaded");
        c.setBlockState(p,firepit?Blocks.CAMPFIRE.defaultBlockState():Blocks.STONE_BRICKS.defaultBlockState(),false);
        var point=new Point3i(p.getX(),p.getY(),p.getZ());
        c.setAttached(TerrainMarkers.OWNERSHIP,new TerrainMarkers.Ownership(7,Set.of(point),firepit?Set.of(point):Set.of()));
        return new Fixture(c,p,point,new ChunkKey(helper.getLevel().dimension().location().toString(),c.getPos().x,c.getPos().z));
    }
    @GameTest(template=EMPTY_STRUCTURE)
    public void playerReplacedSameBrickIsProtected(GameTestHelper helper) {
        var f=owned(helper,false);
        f.chunk().setBlockState(f.pos(),Blocks.AIR.defaultBlockState(),false);
        f.chunk().setBlockState(f.pos(),Blocks.STONE_BRICKS.defaultBlockState(),false);
        helper.assertTrue(OwnershipMutations.wasChanged(f.chunk(),f.point()),"same target brick cannot reclaim lost ownership");
        var encoded=TerrainMarkers.TOMBSTONES.persistenceCodec().encodeStart(NbtOps.INSTANCE,
                f.chunk().getAttached(TerrainMarkers.TOMBSTONES)).result().orElseThrow();
        var decoded=TerrainMarkers.TOMBSTONES.persistenceCodec().parse(NbtOps.INSTANCE,encoded).result().orElseThrow();
        helper.assertTrue(decoded.contains(f.point()),"three-int tombstone survives persistent codec");
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE)
    public void ownedWriteNeedsExactRevisionAndStackOrder(GameTestHelper helper) throws Exception {
        var f=owned(helper,false);
        try(var outer=OwnershipMutations.beginOwnedWrite(f.owner(),7)) {
            f.chunk().setBlockState(f.pos(),Blocks.STONE.defaultBlockState(),false);
            helper.assertTrue(!OwnershipMutations.wasChanged(f.chunk(),f.point()),"matching owner/revision is authorized");
            try(var inner=OwnershipMutations.beginOwnedWrite(f.owner(),8)) {
                f.chunk().setBlockState(f.pos(),Blocks.DIRT.defaultBlockState(),false);
            }
        }
        helper.assertTrue(OwnershipMutations.wasChanged(f.chunk(),f.point()),"wrong revision cannot authorize edits");
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE)
    public void unchangedStateAndFirepitTogglePreserveIdentity(GameTestHelper helper) {
        var f=owned(helper,true);
        var initial=f.chunk().getBlockState(f.pos());
        f.chunk().setBlockState(f.pos(),initial,false);
        f.chunk().setBlockState(f.pos(),initial.setValue(CampfireBlock.LIT,!initial.getValue(CampfireBlock.LIT)),false);
        helper.assertTrue(!OwnershipMutations.wasChanged(f.chunk(),f.point()),"only legitimate firepit toggles preserve identity");
        f.chunk().setBlockState(f.pos(),Blocks.SOUL_CAMPFIRE.defaultBlockState(),false);
        helper.assertTrue(OwnershipMutations.wasChanged(f.chunk(),f.point()),"another campfire block is replacement, not ignition");
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE)
    public void headerMustMatchRecoveredJournal(GameTestHelper helper) {
        var state=new local.plaincraft.greatwall.persistence.HistoryTypes.State(
                new java.util.UUID(1,2),"a".repeat(64),12,6,3,
                local.plaincraft.greatwall.persistence.HistoryTypes.Health.READY,"b".repeat(64));
        var saved=new local.plaincraft.greatwall.persistence.WallWorldState(state);
        var loaded=local.plaincraft.greatwall.persistence.WallWorldState.load(
                saved.save(new CompoundTag(),helper.getLevel().registryAccess()),helper.getLevel().registryAccess());
        helper.assertTrue(loaded.snapshot().equals(state),"header matches independently supplied verified journal state");
        helper.succeed();
    }
}
