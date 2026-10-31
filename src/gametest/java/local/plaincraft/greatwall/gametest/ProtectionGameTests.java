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
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

/**
 * Protection evidence is owner-only: the owner's own starts plus the planning-resolved boxes.
 *
 * <p>{@code b907cd12} ("owner-only capture, planning-resolved structure protection") removed the
 * cross-chunk {@code getAllReferences} walk from {@link ProtectionSnapshot#structureBounds}: resolving a
 * neighbour's start required the 81-chunk FEATURES halo and was the source of the Chunky deadlock. The
 * corridor scan already resolves every reachable structure into {@code predicted} boxes, so a prepared
 * neighbour chunk is no longer a protection source and an unresolved reference no longer sweeps the whole
 * read window. These tests pin both halves of that contract: the surviving positive sources, and the
 * deleted ones staying deleted.
 */
public final class ProtectionGameTests implements FabricGameTest {
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=100)
    public void ownerStartKeepsItsRealBoxWhileAReferencedNeighbourStartIsNotResolved(GameTestHelper helper) {
        var owner=chunk(helper,new ChunkPos(-14,72));
        var referenced=chunk(helper,new ChunkPos(-14,76));
        var structure=helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE)
                .get(ResourceLocation.parse("minecraft:village_plains"));
        helper.assertTrue(structure instanceof JigsawStructure,
                "this fixture must use an actual registered surface jigsaw type");
        // Detached native pieces on the far chunk; the owner only holds a reference to that chunk.
        var neighbour=new StructureStart(structure,referenced.getPos(),0,new PiecesContainer(List.of(
                new BuriedTreasurePieces.BuriedTreasurePiece(new BlockPos(-289,51,1141)),
                new BuriedTreasurePieces.BuriedTreasurePiece(new BlockPos(-158,100,1270)))));
        referenced.setStartForStructure(structure,neighbour);
        owner.addReferenceForStructure(structure,referenced.getPos().toLong());
        var own=new StructureStart(structure,owner.getPos(),0,new PiecesContainer(List.of(
                new BuriedTreasurePieces.BuriedTreasurePiece(new BlockPos(-224,60,1152)))));
        owner.setStartForStructure(structure,own);
        var read=new Bounds(-224,43,1152,-208,113,1168);
        var prepared=Map.<ChunkPos,ChunkAccess>of(
                owner.getPos(),owner,referenced.getPos(),referenced);
        var boxes=ProtectionSnapshot.structureBounds(owner,List.of(),prepared,read);
        helper.assertTrue(boxes.contains(box(own)),"the owner's own start retains its actual aggregate box: "+boxes);
        helper.assertTrue(!boxes.contains(box(neighbour)),
                "a prepared neighbour start is not resolved: the cross-chunk reference walk is gone");
        helper.assertTrue(boxes.size()<=2,"only the owner's start and its single piece are evidence: "+boxes);
        helper.assertTrue(boxes.stream().allMatch(candidate -> candidate.equals(box(own))
                        || candidate.equals(box(own.getPieces().get(0)))),
                "every returned box belongs to the owner's own start or its piece: "+boxes);
        helper.assertTrue(!boxes.contains(read),"the read window is not inserted as a fallback");
        helper.assertTrue(ProtectionSnapshot.structureBoxUpperBound(owner,0,prepared)==2,
                "the counting pass reserves exactly the owner's start and its single piece");
        helper.assertTrue(ProtectionSnapshot.freeze(boxes,Set.of(),Set.of()).protectedAt(new Point3i(-224,60,1152)),
                "the owner's measured piece stays protected by real structure evidence");
        helper.succeed();
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=100)
    public void preparedNeighbourChunksAreNotAProtectionSourceAndPieceBoundsStayHalfOpen(GameTestHelper helper) {
        var owner=chunk(helper,new ChunkPos(0,0));
        var neighbour=chunk(helper,new ChunkPos(1,0));
        var structure=helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE)
                .get(ResourceLocation.parse("minecraft:buried_treasure"));
        helper.assertTrue(structure!=null,"registered native structure is required");
        var piece=new BuriedTreasurePieces.BuriedTreasurePiece(new BlockPos(15,70,1));
        neighbour.setStartForStructure(structure,new StructureStart(structure,neighbour.getPos(),0,
                new PiecesContainer(List.of(piece))));
        owner.addReferenceForStructure(structure,neighbour.getPos().toLong());
        var prepared=Map.<ChunkPos,ChunkAccess>of(owner.getPos(),owner,neighbour.getPos(),neighbour);
        var read=new Bounds(0,49,0,16,92,16);
        var boxes=ProtectionSnapshot.structureBounds(owner,List.of(),prepared,read);
        helper.assertTrue(boxes.isEmpty(),
                "a prepared neighbour is no longer a source: the owner has no start and no predicted box");
        helper.assertTrue(!ProtectionSnapshot.freeze(boxes,Set.of(),Set.of()).protectedAt(new Point3i(15,70,1)),
                "the referenced native piece is no longer protected without a resolved owner start");
        helper.assertTrue(ProtectionSnapshot.structureBoxUpperBound(owner,0,prepared)==0,
                "no box is reserved for a reference that is never walked");
        // The same piece is still evidence once it belongs to the owner, and its box stays half-open.
        owner.setStartForStructure(structure,new StructureStart(structure,owner.getPos(),0,
                new PiecesContainer(List.of(piece))));
        var owned=ProtectionSnapshot.structureBounds(owner,List.of(),prepared,read);
        var snapshot=ProtectionSnapshot.freeze(owned,Set.of(),Set.of());
        var native1=piece.getBoundingBox();
        var expectedPiece=new Bounds(native1.minX(),native1.minY(),native1.minZ(),
                native1.maxX()+1,native1.maxY()+1,native1.maxZ()+1);
        helper.assertTrue(owned.contains(expectedPiece),"a piece box is stored half-open (maximum exclusive)");
        helper.assertTrue(snapshot.protectedAt(new Point3i(15,70,1)),"the owner's own piece is protected");
        helper.assertTrue(ProtectionSnapshot.structureBoxUpperBound(owner,0,prepared)>=owned.size(),
                "counting pass must cover all retained start/piece boxes");
        helper.succeed();
    }

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=100)
    public void predictedPlanningBoxesAreTheOnlyProtectionAnUnresolvedReferenceGets(GameTestHelper helper) {
        var owner=chunk(helper,new ChunkPos(-1,-1));
        var structure=helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE)
                .get(ResourceLocation.parse("minecraft:buried_treasure"));
        owner.addReferenceForStructure(structure,new ChunkPos(10000,-10000).toLong());
        var read=new Bounds(-16,49,-16,0,92,0);
        var predicted=new Bounds(20,50,20,24,90,24);
        var prepared=Map.<ChunkPos,ChunkAccess>of(owner.getPos(),owner);
        var boxes=ProtectionSnapshot.structureBounds(owner,List.of(predicted),prepared,read);
        helper.assertTrue(boxes.contains(predicted),"the corridor scan's planning box is the protection source");
        helper.assertTrue(!boxes.contains(read),
                "an unresolved reference no longer protects the read window (that walk caused the deadlock)");
        var snapshot=ProtectionSnapshot.freeze(boxes,Set.of(),Set.of());
        helper.assertTrue(snapshot.protectedAt(new Point3i(20,50,20)),"a predicted structure keeps its corner protected");
        helper.assertTrue(!snapshot.protectedAt(new Point3i(-16,49,-16)),
                "the read-window corner is not protected by an unresolved reference any more");
        helper.assertTrue(prepared.size()==1,"only the supplied owner map is consulted");
        helper.assertTrue(ProtectionSnapshot.structureBoxUpperBound(owner,1,prepared)==1,
                "an unresolved reference reserves no forecast box");
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

    private static Bounds box(StructureStart start) {
        return box(start.getBoundingBox());
    }

    private static Bounds box(net.minecraft.world.level.levelgen.structure.StructurePiece piece) {
        return box(piece.getBoundingBox());
    }

    private static Bounds box(net.minecraft.world.level.levelgen.structure.BoundingBox nativeBox) {
        return new Bounds(nativeBox.minX(),nativeBox.minY(),nativeBox.minZ(),
                nativeBox.maxX()+1,nativeBox.maxY()+1,nativeBox.maxZ()+1);
    }

    private static ProtoChunk chunk(GameTestHelper helper,ChunkPos pos) {
        var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME),null);
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        return chunk;
    }
}
