package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.ScheduledTick;

/** Owner-only ProtoChunk write. Coordinator must durably persist proof before entering the native action. */
public final class ChunkWallWriter {
    public ChunkWallWriter() {}
    public WriteReport apply(ChunkAccess owner,ChunkWritePlan plan,WriteMode mode) {
        StageTrace.assertInsideLock();
        if(mode!=WriteMode.INITIAL)throw new IllegalArgumentException("loaded backfill requires the S2 loaded-owner writer");
        if(owner.getPos().x!=plan.owner().x()||owner.getPos().z!=plan.owner().z())throw failure("foreign owner");
        if(!plan.hash().equals(CanonicalCodec.writePlanHash(plan)))throw failure("tampered write plan");
        if(plan.metadata().coverageHash().equals("0".repeat(64)))throw failure("missing durable coverage identity");
        var marker=owner.getAttached(TerrainMarkers.PRODUCTION);
        if(marker!=null && (!marker.recipe().equals(plan.recipeHash())||!marker.planHash().equals(plan.hash())
                ||!marker.coverageHash().equals(plan.metadata().coverageHash())))throw failure("marker/proof identity mismatch");
        if(marker!=null&&!marker.equals(marker(plan,marker.complete())))throw failure("marker/proof payload mismatch");
        if(marker!=null&&marker.complete())return new WriteReport(plan.owner(),0,plan.voxels().size(),List.of(),true,plan.hash());
        if(owner.getPersistedStatus().isOrAfter(ChunkStatus.FULL))throw failure("initial write cannot mutate a FULL chunk");
        CompiledAllocation.charge(Math.addExact(4096L,Math.addExact(256L*plan.voxels().size(),4096L*plan.blockEntities().size())));
        var proofs=new HashMap<Point3i,BlockProof>();var entities=new HashMap<Point3i,BlockEntityIntent>();
        for(var p:plan.proofs())if(proofs.put(p.pos(),p)!=null)throw failure("duplicate proof");
        for(var e:plan.blockEntities())if(entities.put(e.pos(),e)!=null)throw failure("duplicate entity intent");
        if(proofs.size()!=plan.voxels().size())throw failure("proof count does not match write set");
        var states=new HashMap<Point3i,BlockState>();var decisions=new HashMap<Point3i,WriteDecision>();var conflicts=new ArrayList<Point3i>();
        var blockEntities=owner.getBlockEntitiesPos();
        var bounds=new DimensionBounds(owner.getMinBuildHeight(),owner.getMaxBuildHeight(),29_999_000);
        var context=new OwnershipGuard.Context(bounds,marker!=null,true,false,true);
        for(var v:plan.voxels()) {
            var proof=proofs.get(v.pos());
            if(proof==null||!proof.owner().equals(v.component())||states.containsKey(v.pos()))throw failure("write/proof mismatch");
            BlockState target=BlockCatalog.resolveState(v.state());
            if(target.hasBlockEntity()!=entities.containsKey(v.pos()))throw failure("missing or spurious block entity intent");
            if(!proof.afterState().equals(BlockCatalog.token(target,target.hasBlockEntity())))throw failure("noncanonical target proof "+v.state());
            BlockPos pos=pos(v.pos());boolean hasEntity=blockEntities.contains(pos);
            BlockState currentState=owner.getBlockState(pos);
            String current=BlockCatalog.token(currentState,hasEntity);
            WriteDecision decision=OwnershipGuard.decide(mode,plan.owner(),v.pos(),current,
                    hasEntity||currentState.hasBlockEntity(),proof,false,context);
            if(marker!=null && currentState.equals(target) && target.hasBlockEntity() && !hasEntity)
                decision=WriteDecision.WRITE;
            if(decision==WriteDecision.CONFLICT||decision==WriteDecision.PROTECTED)conflicts.add(v.pos());
            states.put(v.pos(),target);decisions.put(v.pos(),decision);
        }
        for(var p:plan.postprocess())checkOwner(plan,p,bounds);
        for(var p:plan.fluidTicks())checkOwner(plan,p,bounds);
        for(var p:plan.walkwayFeet())checkOwner(plan,p,bounds);
        for(var p:plan.metadata().spawnSlots())checkOwner(plan,p.feet(),bounds);
        if(!conflicts.isEmpty())return new WriteReport(plan.owner(),0,0,conflicts,false,plan.hash());
        var owned=new HashSet<Point3i>();var firepits=new HashSet<Point3i>();
        for(var v:plan.voxels())owned.add(v.pos());
        for(var e:plan.blockEntities())if(e.type().equals("minecraft:campfire"))firepits.add(e.pos());
        owner.setAttached(TerrainMarkers.OWNERSHIP,new TerrainMarkers.Ownership(plan.metadata().gateRevision(),owned,firepits));
        owner.setAttached(TerrainMarkers.PRODUCTION,marker(plan,false));
        owner.setUnsaved(true);
        int written=0,unchanged=0;
        for(var v:plan.voxels()) {
            if(decisions.get(v.pos())==WriteDecision.ALREADY_TARGET) {unchanged++;continue;}
            owner.setBlockState(pos(v.pos()),states.get(v.pos()),false);written++;
            var e=entities.get(v.pos());
            if(e!=null) {
                var tag=new CompoundTag();tag.putString("id",e.type());
                tag.putInt("x",e.pos().x());tag.putInt("y",e.pos().y());tag.putInt("z",e.pos().z());
                var pgw=new CompoundTag();pgw.putString("wall",e.site().wallId());pgw.putString("kind",e.site().kind().name());
                pgw.putLong("ordinal",e.site().ordinal());pgw.putInt("localId",e.localId());
                e.fields().forEach(pgw::putString);tag.put("plaincraft_great_wall",pgw);
                owner.setBlockEntityNbt(tag);
            }
        }
        Heightmap.primeHeightmaps(owner,EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG,Heightmap.Types.OCEAN_FLOOR_WG,Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
        plan.postprocess().forEach(p->owner.markPosForPostprocessing(pos(p)));
        long index=0;
        for(var p:plan.fluidTicks()) {
            var fluid=owner.getFluidState(pos(p));
            if(!fluid.isEmpty())owner.getFluidTicks().schedule(new ScheduledTick<>(fluid.getType(),pos(p),0,index++));
        }
        owner.setAttached(TerrainMarkers.PRODUCTION,marker(plan,true));owner.setUnsaved(true);
        return new WriteReport(plan.owner(),written,unchanged,List.of(),true,plan.hash());
    }
    private static TerrainMarkers.Marker marker(ChunkWritePlan p,boolean complete) {
        return new TerrainMarkers.Marker(p.recipeHash(),p.metadata().coverageHash(),p.hash(),p.metadata().gateRevision(),complete,
                p.walkwayFeet(),p.blockEntities(),p.metadata().spawnSlots(),p.metadata().realizedStates());
    }
    private static void checkOwner(ChunkWritePlan plan,Point3i p,DimensionBounds b) {
        if(Math.floorDiv(p.x(),16)!=plan.owner().x()||Math.floorDiv(p.z(),16)!=plan.owner().z()||p.y()<b.minY()||p.y()>=b.maxYExclusive())
            throw failure("metadata outside owner");
    }
    private static BlockPos pos(Point3i p){return new BlockPos(p.x(),p.y(),p.z());}
    private static WallFailure failure(String s){return new WallFailure(FailureCode.OWNERSHIP_CONFLICT,s);}
}
