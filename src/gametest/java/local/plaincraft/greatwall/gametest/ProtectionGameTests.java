package local.plaincraft.greatwall.gametest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import local.plaincraft.greatwall.generation.ProtectionSnapshot;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;

public final class ProtectionGameTests implements FabricGameTest {
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=100)
    public void knownReferenceUsesOnlyThePreparedStartAndHalfOpenPiece(GameTestHelper helper) {
        var owner=chunk(helper,new ChunkPos(0,0));
        var neighbor=chunk(helper,new ChunkPos(1,0));
        var structure=helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE)
                .get(ResourceLocation.parse("minecraft:buried_treasure"));
        helper.assertTrue(structure!=null,"registered native structure is required");
        var start=new StructureStart(structure,neighbor.getPos(),0,new PiecesContainer(List.of(
                new BuriedTreasurePieces.BuriedTreasurePiece(new BlockPos(15,70,1)))));
        neighbor.setStartForStructure(structure,start);
        owner.addReferenceForStructure(structure,neighbor.getPos().toLong());
        var prepared=Map.<ChunkPos,net.minecraft.world.level.chunk.ChunkAccess>of(owner.getPos(),owner,neighbor.getPos(),neighbor);
        var read=new Bounds(0,49,0,16,92,16);
        var boxes=ProtectionSnapshot.structureBounds(owner,List.of(),prepared,read);
        var snapshot=ProtectionSnapshot.freeze(boxes,Set.of(),Set.of());
        helper.assertTrue(snapshot.protectedAt(new Point3i(15,70,1)),"referenced native piece must be protected");
        helper.assertTrue(!snapshot.protectedAt(new Point3i(0,70,0)),"resolved reference must not blanket-protect the unrelated owner");
        helper.assertTrue(!snapshot.protectedAt(new Point3i(16,70,1)),"piece maximum is exclusive");
        helper.assertTrue(ProtectionSnapshot.structureBoxUpperBound(owner,0,prepared)>=boxes.size(),
                "counting pass must cover all retained piece/start boxes");
        helper.succeed();
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=100)
    public void missingReferenceProtectsReadBoundsWithoutLoadingAnything(GameTestHelper helper) {
        var owner=chunk(helper,new ChunkPos(-1,-1));
        var structure=helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE)
                .get(ResourceLocation.parse("minecraft:buried_treasure"));
        owner.addReferenceForStructure(structure,new ChunkPos(10000,-10000).toLong());
        var read=new Bounds(-16,49,-16,0,92,0);
        var predicted=new Bounds(20,50,20,24,90,24);
        var prepared=Map.<ChunkPos,net.minecraft.world.level.chunk.ChunkAccess>of(owner.getPos(),owner);
        var boxes=ProtectionSnapshot.structureBounds(owner,List.of(predicted),prepared,read);
        helper.assertTrue(boxes.contains(read)&&boxes.contains(predicted),"unknown references and frozen predictions both protect");
        helper.assertTrue(prepared.size()==1,"only the supplied finite owner map is consulted");
        helper.assertTrue(ProtectionSnapshot.structureBoxUpperBound(owner,1,prepared)==2,"unknown reference needs one forecast box");
        helper.succeed();
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=500)
    public void captureRequiresFeaturesAndPreservesEntitiesAndUnknownBlocks(GameTestHelper helper) {
        var owner=chunk(helper,new ChunkPos(0,0));
        var catalog=new BlockCatalog(helper.getLevel().registryAccess());
        owner.setPersistedStatus(ChunkStatus.SURFACE);
        boolean rejected=false;
        try { ProtectionSnapshot.capture(owner,List.of(),catalog); } catch(WallFailure expected) {
            rejected=expected.code()==FailureCode.MISSING_FEATURES_PLAN;
        }
        helper.assertTrue(rejected,"capture before completed FEATURES is forbidden");
        owner.setPersistedStatus(ChunkStatus.FEATURES);
        owner.setBlockState(new BlockPos(1,70,1),Blocks.DIAMOND_BLOCK.defaultBlockState(),false);
        owner.setBlockState(new BlockPos(2,70,1),Blocks.CHEST.defaultBlockState(),false);
        var tag=new CompoundTag();tag.putString("id","minecraft:chest");tag.putInt("x",2);tag.putInt("y",70);tag.putInt("z",1);
        owner.setBlockEntityNbt(tag);
        var predicted=new Bounds(4,60,4,8,80,8);
        var snapshot=ProtectionSnapshot.capture(owner,List.of(predicted),catalog);
        helper.assertTrue(snapshot.blockEntities().contains(new Point3i(2,70,1)),"pending native BE positions are retained");
        helper.assertTrue(snapshot.protectedAt(new Point3i(1,70,1)),"unknown non-natural blocks fail closed");
        helper.assertTrue(snapshot.protectedAt(new Point3i(4,60,4)),"frozen predicted structure is retained");
        helper.assertTrue(!snapshot.protectedAt(new Point3i(0,70,0)),"ordinary air is not protected");
        helper.assertTrue(owner.getBlockState(new BlockPos(1,70,1)).is(Blocks.DIAMOND_BLOCK),"capture never writes");
        helper.succeed();
    }

    private static ProtoChunk chunk(GameTestHelper helper,ChunkPos pos) {
        var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME),null);
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        return chunk;
    }
}
