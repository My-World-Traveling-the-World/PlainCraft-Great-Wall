package local.plaincraft.greatwall.generation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;

/** Pure whole-component resolution followed by owner clipping; no live-world reads. */
public final class ChunkPlanCompiler {
    private static final Comparator<Point3i> POS=Comparator.comparingInt(Point3i::y).thenComparingInt(Point3i::z).thenComparingInt(Point3i::x);
    private static final Comparator<SiteId> SITE=Comparator.comparing(SiteId::wallId).thenComparing(s->s.kind().name()).thenComparingLong(SiteId::ordinal);
    public ChunkPlanCompiler() {}
    public ChunkWritePlan compile(ChunkKey owner,String recipeHash,List<RealizationCell> cells,Map<Point3i,String> beforeStates) {
        Objects.requireNonNull(owner);Objects.requireNonNull(beforeStates);
        long candidates=0,owned=0;
        for(var cell:cells)for(var c:cell.components())for(var voxel:c.voxels()) {
            candidates++;
            if(owns(owner,voxel.pos())&&voxel.kind()!=IntentKind.KEEP)owned++;
        }
        CompiledAllocation.charge(Math.addExact(4096L,Math.addExact(Math.multiplyExact(candidates,128L),Math.multiplyExact(owned,512L))));
        var components=new TreeMap<SiteId,ComponentVoxels>(SITE);
        var inputs=new TreeSet<String>();var t0=new TreeSet<String>();long revision=0;
        for(var cell:cells) {
            if(!cell.key().dimension().equals(owner.dimension())||!cell.key().recipeHash().equals(recipeHash))
                throw failure("foreign realization cell");
            inputs.add(cell.hash()+":"+cell.gateRevision()+":"+cell.protectionHash());
            t0.add(cell.t0Hash()); revision=Math.max(revision,cell.gateRevision());
            for(var c:cell.components()) {
                if(!c.hash().equals(CanonicalCodec.componentHash(c))) throw failure("component hash mismatch");
                var old=components.putIfAbsent(c.id(),c);
                if(old!=null&&!old.hash().equals(c.hash())) throw failure("same component has two realizations");
            }
        }
        var logical=new TreeMap<Point3i,VoxelIntent>(POS);
        for(var c:components.values()) for(var v:c.voxels()) logical.merge(v.pos(),v,ChunkPlanCompiler::winner);
        var resolved=new TreeMap<Point3i,VoxelIntent>(POS);
        for(var v:logical.values()) resolved.put(v.pos(),connections(v,logical));
        var voxels=new ArrayList<VoxelIntent>();var proofs=new ArrayList<BlockProof>();
        var entityPositions=new java.util.HashSet<Point3i>();
        for(var c:components.values()) for(var e:c.blockEntities()) {
            var v=resolved.get(e.pos());
            if(v!=null&&v.component().equals(e.site())&&v.state().id().equals(e.type()))entityPositions.add(e.pos());
        }
        var post=new TreeSet<Point3i>(POS);var fluid=new TreeSet<Point3i>(POS);var feet=new TreeSet<Point3i>(POS);
        for(var v:resolved.values()) {
            if(!owns(owner,v.pos())||v.kind()==IntentKind.KEEP) continue;
            String before=beforeStates.get(v.pos());
            if(before==null) throw new WallFailure(FailureCode.T0_MISSING,"missing before-state "+v.pos());
            boolean be=before.endsWith(":be=true");
            if(be) throw failure("foreign block entity in write set");
            boolean thaw=v.component().kind()==SiteKind.WATER_GATE&&v.part()==Part.WATER&&v.kind()==IntentKind.FLUID
                    &&before.startsWith("minecraft:ice{")&&!be;
            if((v.kind()==IntentKind.OWNED_VOID||v.kind()==IntentKind.FLUID)&&!replaceableVoid(before)&&!thaw)
                throw failure("negative space cannot carve natural/foreign solid: "+v.pos());
            String after=token(v.state(),entityPositions.contains(v.pos()));
            voxels.add(v);proofs.add(new BlockProof(v.pos(),before,after,be,v.component()));
            if(v.state().id().endsWith("_stairs")||v.state().id().endsWith("_slab")||v.part()==Part.FENCE) post.add(v.pos());
            if(v.kind()==IntentKind.FLUID||"true".equals(v.state().properties().get("waterlogged"))) fluid.add(v.pos());
            Point3i f=new Point3i(v.pos().x(),Math.addExact(v.pos().y(),1),v.pos().z());
            if(v.walkable()&&!solid(resolved.get(f))&&!solid(resolved.get(new Point3i(f.x(),f.y()+1,f.z())))) feet.add(f);
        }
        var entities=new TreeMap<Point3i,BlockEntityIntent>(POS);var slots=new ArrayList<SpawnSlot>();var states=new TreeMap<SiteId,DecayState>(SITE);
        for(var c:components.values()) {
            boolean intersects=c.voxels().stream().anyMatch(v->owns(owner,v.pos()));
            if(intersects) states.put(c.id(),c.realizedState());
            for(var e:c.blockEntities()) if(owns(owner,e.pos())) {
                var winner=resolved.get(e.pos());
                if(winner!=null && winner.component().equals(e.site())&&winner.state().id().equals(e.type())) {
                    var old=entities.putIfAbsent(e.pos(),e);if(old!=null&&!old.equals(e))throw failure("conflicting block entity intent");
                }
            }
            for(var s:c.spawnSlots()) if(owns(owner,s.feet())&&feet.contains(s.feet())) slots.add(s);
        }
        slots.sort(Comparator.comparing(SpawnSlot::site,SITE).thenComparingInt(SpawnSlot::slot));
        String inputHash=hash(recipeHash+"\n"+owner+"\n"+String.join("\n",inputs));
        var metadata=new WriteMetadata(hash(String.join("\n",t0)),revision,slots,states);
        var plan=new ChunkWritePlan(owner,recipeHash,inputHash,voxels,proofs,List.copyOf(entities.values()),
                List.copyOf(post),List.copyOf(fluid),List.copyOf(feet),metadata,"0".repeat(64));
        return rehash(plan);
    }
    /** Coordinator replaces the cell-input digest with this owner's durable coverage digest. */
    public ChunkWritePlan bindCoverage(ChunkWritePlan plan,String coverageHash) {
        var m=plan.metadata();
        return rehash(new ChunkWritePlan(plan.owner(),plan.recipeHash(),plan.inputHash(),plan.voxels(),plan.proofs(),plan.blockEntities(),
                plan.postprocess(),plan.fluidTicks(),plan.walkwayFeet(),new WriteMetadata(coverageHash,m.gateRevision(),m.spawnSlots(),m.realizedStates()),"0".repeat(64)));
    }
    public static String token(BlockSpec state) {
        return token(state,false);
    }
    public static String token(BlockSpec state,boolean entity) {
        String fluid=state.id().equals("minecraft:water")||"true".equals(state.properties().get("waterlogged"))?"minecraft:water":"minecraft:empty";
        return state.id()+new TreeMap<>(state.properties()).toString()+":fluid="+fluid+":be="+entity;
    }
    private static ChunkWritePlan rehash(ChunkWritePlan p) {
        return new ChunkWritePlan(p.owner(),p.recipeHash(),p.inputHash(),p.voxels(),p.proofs(),p.blockEntities(),p.postprocess(),
                p.fluidTicks(),p.walkwayFeet(),p.metadata(),CanonicalCodec.writePlanHash(p));
    }
    private static VoxelIntent winner(VoxelIntent a,VoxelIntent b) {
        if(a.equals(b))return a;
        if(a.kind()==IntentKind.OWNED_VOID&&b.kind()==IntentKind.OWNED_VOID)
            return a.priority()!=b.priority()?(a.priority()>b.priority()?a:b):(SITE.compare(a.component(),b.component())<=0?a:b);
        if(a.kind()==IntentKind.OWNED_VOID||b.kind()==IntentKind.OWNED_VOID) {
            var empty=a.kind()==IntentKind.OWNED_VOID?a:b;var other=empty==a?b:a;
            if(!empty.component().equals(other.component()) && !(empty.component().wallId().equals(other.component().wallId())
                    && other.component().kind()==SiteKind.WALL)) {
                if(empty.required())throw failure("void is not authorized to erase this component");
                return other;
            }
        }
        if(a.priority()!=b.priority())return a.priority()>b.priority()?a:b;
        int id=SITE.compare(a.component(),b.component());
        if(id!=0)return id<0?a:b;
        if(!a.equals(b))throw failure("ambiguous equal-priority same-site voxel");
        return a;
    }
    private static VoxelIntent connections(VoxelIntent v,Map<Point3i,VoxelIntent> logical) {
        if(v.state().id().endsWith("_stairs"))return stairShape(v,logical);
        if(v.part()!=Part.FENCE)return v;
        var properties=new TreeMap<>(v.state().properties());properties.putIfAbsent("waterlogged","false");
        String[] names={"north","east","south","west"};int[][] sides={{0,-1},{1,0},{0,1},{-1,0}};
        for(int i=0;i<4;i++) {
            var n=logical.get(new Point3i(v.pos().x()+sides[i][0],v.pos().y(),v.pos().z()+sides[i][1]));
            boolean connected=solid(n)&&(n.part()==Part.FENCE||switch(n.part()){case FOUNDATION,CORE,SKIN,ARCH,ROOF,HAY->true;default->false;});
            properties.put(names[i],Boolean.toString(connected));
        }
        return new VoxelIntent(v.pos(),v.kind(),v.part(),new BlockSpec(v.state().id(),properties),v.component(),v.priority(),v.required(),v.walkable());
    }
    private static VoxelIntent stairShape(VoxelIntent v,Map<Point3i,VoxelIntent> logical) {
        String[] directions={"north","east","south","west"};int[][] offsets={{0,-1},{1,0},{0,1},{-1,0}};
        String facing=v.state().properties().get("facing"),half=v.state().properties().get("half");
        int index=List.of(directions).indexOf(facing);
        if(index<0||half==null)throw failure("stairs lack frozen facing/half");
        String shape="straight";
        for(int pass=0;pass<2;pass++){
            int side=pass==0?index:(index+2)%4;
            var neighbor=logical.get(new Point3i(v.pos().x()+offsets[side][0],v.pos().y(),v.pos().z()+offsets[side][1]));
            if(neighbor==null||!neighbor.state().id().endsWith("_stairs")||!half.equals(neighbor.state().properties().get("half")))continue;
            int next=List.of(directions).indexOf(neighbor.state().properties().get("facing"));
            if(next<0||next%2==index%2)continue;
            int check=pass==0?(next+2)%4:next;
            var corner=logical.get(new Point3i(v.pos().x()+offsets[check][0],v.pos().y(),v.pos().z()+offsets[check][1]));
            if(corner!=null&&corner.state().id().endsWith("_stairs")&&facing.equals(corner.state().properties().get("facing"))
                    &&half.equals(corner.state().properties().get("half")))continue;
            shape=(pass==0?"outer_":"inner_")+(next==(index+3)%4?"left":"right");break;
        }
        var properties=new TreeMap<>(v.state().properties());properties.put("shape",shape);
        return new VoxelIntent(v.pos(),v.kind(),v.part(),new BlockSpec(v.state().id(),properties),v.component(),v.priority(),v.required(),v.walkable());
    }
    private static boolean solid(VoxelIntent v){return v!=null&&v.kind()==IntentKind.SOLID;}
    private static boolean owns(ChunkKey key,Point3i p){return Math.floorDiv(p.x(),16)==key.x()&&Math.floorDiv(p.z(),16)==key.z();}
    private static boolean replaceableVoid(String token) {
        int end=token.indexOf('{');String id=end<0?token:token.substring(0,end);
        return switch(id){case "minecraft:air","minecraft:cave_air","minecraft:void_air","minecraft:water","minecraft:short_grass",
                "minecraft:tall_grass","minecraft:fern","minecraft:large_fern","minecraft:dead_bush","minecraft:snow"->true;default->false;};
    }
    private static String hash(String value){return DeterministicHash.sha256(value.getBytes(StandardCharsets.UTF_8));}
    private static WallFailure failure(String message){return new WallFailure(FailureCode.OWNERSHIP_CONFLICT,message);}
}
