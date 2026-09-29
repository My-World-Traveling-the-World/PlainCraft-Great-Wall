package local.plaincraft.greatwall.gametest;

import java.util.*;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.decay.*;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.generation.*;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class ComponentGameTests implements FabricGameTest {
    private static final DecayField DECAY=new DecayField(0);
    private static void routes(GameTestHelper h,ComponentVoxels c){
        routes(h,c,64);
    }
    private static void routes(GameTestHelper h,ComponentVoxels c,int ground){
        h.assertTrue(!c.voxels().isEmpty(),"component must not be suppressed by fixture");
        var collision=new ComponentFixtures.CollisionView(ComponentFixtures.states(c,ground));
        for(var route:c.requiredRoutes()){
            var visited=collision.reachable(route.getFirst(),c.bounds());
            for(var p:route)h.assertTrue(visited.contains(new ComponentFixtures.Foot(p.x()*2,p.y()*2,p.z()*2)),
                    "native player collision disconnected at "+p+" site="+c.id());
        }
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void towerAcrossOneTwoFourChunks(GameTestHelper helper){
        var template=new EnemyTower(DECAY,ComponentFixtures.PALETTE);
        int n=0;
        for(int[] center:new int[][]{{8,8},{16,8},{16,16}}){
            var input=ComponentFixtures.input(SiteKind.ENEMY_TOWER,new Bounds(-5,-32,-5,6,8,6),center[0],center[1],0,DecayState.INTACT);
            var c=template.compile(input);var plans=ComponentFixtures.plans(c);
            helper.assertTrue(plans.size()==new int[]{1,2,4}[n++],"actual occupied owner count");
            var keys=new ArrayList<>(plans.keySet());keys.sort(Comparator.comparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z));
            var first=write(helper,plans,keys);Collections.reverse(keys);var second=write(helper,plans,keys);
            helper.assertTrue(first.equals(second),"reversed owner replay must have identical blocks and marker");
            routes(helper,c);
        }
        helper.succeed();
    }
    private static Map<Point3i,String> write(GameTestHelper helper,Map<ChunkKey,ChunkWritePlan> plans,List<ChunkKey> order){
        var result=new HashMap<Point3i,String>();var writer=new ChunkWallWriter();
        for(var key:order){
            var chunk=new ProtoChunk(new ChunkPos(key.x(),key.z()),UpgradeData.EMPTY,helper.getLevel(),
                    helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME),null);
            chunk.setPersistedStatus(ChunkStatus.FEATURES);
            var plan=plans.get(key);
            for(var v:plan.voxels())if(v.pos().y()<64)chunk.setBlockState(new BlockPos(v.pos().x(),v.pos().y(),v.pos().z()),Blocks.STONE.defaultBlockState(),false);
            StageTrace.enterLockAction();
            try{helper.assertTrue(writer.apply(chunk,plan,WriteMode.INITIAL).complete(),"owner applies");}
            finally{StageTrace.leaveLockAction();}
            helper.assertTrue(chunk.getAttached(TerrainMarkers.PRODUCTION).planHash().equals(plan.hash()),"matching complete marker");
            for(var v:plan.voxels()){
                var pos=new BlockPos(v.pos().x(),v.pos().y(),v.pos().z());
                result.put(v.pos(),WallStageCoordinator.token(chunk,pos));
            }
        }
        return Map.copyOf(result);
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void rotatedWallWalksAtAllHeadings(GameTestHelper helper){
        for(int angle:new int[]{0,15,30,45,60,90}){
            routes(helper,new WallSection(DECAY,ComponentFixtures.PALETTE).compile(ComponentFixtures.input(SiteKind.WALL,
                    new Bounds(-32,-32,-5,32,3,5),0,0,angle,DecayState.INTACT)));
            routes(helper,new EnemyTower(DECAY,ComponentFixtures.PALETTE).compile(ComponentFixtures.input(SiteKind.ENEMY_TOWER,
                    new Bounds(-5,-32,-5,6,8,6),0,0,angle,DecayState.INTACT)));
        }
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void rampsNeverBlockGateApproach(GameTestHelper helper){
        routes(helper,new Gate(DECAY,ComponentFixtures.PALETTE).compile(ComponentFixtures.input(SiteKind.GATE,
                new Bounds(-30,-33,-15,30,4,15),0,0,0,DecayState.INTACT)));
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void allRampHeightsHaveNativeCollisionClearanceWithRails(GameTestHelper helper){
        for(int height=1;height<=32;height++) {
            var base=ComponentFixtures.input(SiteKind.RAMP,new Bounds(-30,-33,4,-6,3,15),0,0,0,DecayState.INTACT);
            var supports=new HashMap<>(base.supports());int ground=Math.min(64,84-height);
            supports.replaceAll((p,v)->new ProbeResult(ProbeKind.SUPPORTED,ground,OptionalInt.empty(),OptionalInt.empty(),64-ground,ComponentFixtures.H));
            var input=new ComponentInput(base.site(),base.stations(),supports,base.decay(),base.family(),false,List.of(),List.of());
            routes(helper,new Ramp(DECAY,ComponentFixtures.PALETTE).compileBetween(input,84-height,84),ground);
        }
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void capturedThinIceThawsOnlyInMainChannel(GameTestHelper helper){
        var catalog=new BlockCatalog(helper.getLevel().registryAccess());
        var key=new ChunkKey("minecraft:overworld",0,0);var pageKey=new local.plaincraft.greatwall.terrain.TerrainTile.Key(key.dimension(),0,0,0,127);
        var palette=List.of(Blocks.AIR,Blocks.STONE,Blocks.WATER,Blocks.ICE).stream().map(b->BlockCatalog.token(b.defaultBlockState(),false)).toList();
        int[] data=new int[32768];var summaries=new ArrayList<ColumnEvidence>();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            summaries.add(new ColumnEvidence(x,z,66,false));
            for(int y=0;y<128;y++)data[(y*16+z)*16+x]=y<60?1:y<66?(y==65&&(x==0||x==5)?3:2):0;
        }
        var tile=new local.plaincraft.greatwall.terrain.TerrainTile(pageKey,ComponentFixtures.H,palette,data);
        var coverage=new Coverage(key,List.of(0),new Bounds(0,49,0,16,92,16),ComponentFixtures.H);
        var metadata=local.plaincraft.greatwall.persistence.EvidenceCodec.hashed(new CaptureMetadata(coverage,summaries,List.of(),Set.of(),Set.of(),ComponentFixtures.H));
        var column=new local.plaincraft.greatwall.terrain.SnapshotColumnView(0,0,metadata,Map.of(pageKey,tile),catalog);
        var probe=new SurfaceProbe().probe(column,84,new DimensionBounds(0,128,29_999_000));
        helper.assertTrue(probe.kind()==ProbeKind.WATER&&probe.thinIceY().orElseThrow()==65,"thin ice proven from frozen native states");
        var initial=water(49);var supports=new HashMap<>(initial.supports());
        supports.replaceAll((p,v)->p.x()>=-24&&p.x()<25?new ProbeResult(ProbeKind.WATER,60,OptionalInt.of(66),OptionalInt.of(60),4,ComponentFixtures.H,
                (p.x()==0||p.x()==5)?OptionalInt.of(65):OptionalInt.empty()):v);
        var old=initial.waters().getFirst();var water=new WaterEvidence(old.crossing(),old.classification(),old.channelWidthQ8(),old.crossingSpanQ8(),66,
                old.banks(),List.of(new Point3i(0,60,0)),old.inputHash());
        var component=new WaterGate(DECAY,ComponentFixtures.PALETTE).compile(new ComponentInput(initial.site(),initial.stations(),supports,
                initial.decay(),initial.family(),false,List.of(water),List.of()));
        helper.assertTrue(!component.voxels().isEmpty(),"ice fixture builds");
        var before=new HashMap<Point3i,String>();
        for(var v:component.voxels())before.put(v.pos(),BlockCatalog.token(v.pos().y()<60?Blocks.STONE.defaultBlockState():
                v.pos().y()<66?(v.pos().y()==65&&(v.pos().x()==0||v.pos().x()==5)?Blocks.ICE.defaultBlockState():Blocks.WATER.defaultBlockState()):
                Blocks.AIR.defaultBlockState(),false));
        var cell=new RealizationCell(new CellKey(ComponentFixtures.H,key.dimension(),16),List.of(),List.of(component),ComponentFixtures.H,0,ComponentFixtures.H,ComponentFixtures.H);
        var compiler=new ChunkPlanCompiler();var plan=compiler.bindCoverage(compiler.compile(key,ComponentFixtures.H,List.of(cell),before),ComponentFixtures.H);
        var chunk=new ProtoChunk(new ChunkPos(0,0),UpgradeData.EMPTY,helper.getLevel(),helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME),null);
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        for(var v:plan.voxels())chunk.setBlockState(new BlockPos(v.pos().x(),v.pos().y(),v.pos().z()),v.pos().y()<60?Blocks.STONE.defaultBlockState():
                v.pos().y()<66?(v.pos().y()==65&&(v.pos().x()==0||v.pos().x()==5)?Blocks.ICE.defaultBlockState():Blocks.WATER.defaultBlockState()):
                Blocks.AIR.defaultBlockState(),false);
        chunk.setBlockState(new BlockPos(5,65,0),Blocks.ICE.defaultBlockState(),false);
        StageTrace.enterLockAction();
        try{helper.assertTrue(new ChunkWallWriter().apply(chunk,plan,WriteMode.INITIAL).complete(),"ice owner preflight and APPLY");}
        finally{StageTrace.leaveLockAction();}
        helper.assertTrue(chunk.getBlockState(new BlockPos(0,65,0)).is(Blocks.WATER),"main ice returns to original-level source water");
        helper.assertTrue(chunk.getBlockState(new BlockPos(5,65,0)).is(Blocks.ICE),"side ice remains byte-for-byte unchanged");
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void capHasGroundEgress(GameTestHelper helper){
        routes(helper,new EndCap(DECAY,ComponentFixtures.PALETTE).compile(ComponentFixtures.input(SiteKind.END_CAP,
                new Bounds(-30,-33,-5,1,3,15),0,0,0,DecayState.INTACT)));
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void alternativeCapRampsStayConnectedThroughNativeCollisionShapes(GameTestHelper helper){
        var base=ComponentFixtures.input(SiteKind.END_CAP,new Bounds(-30,-33,-15,30,3,15),0,0,0,DecayState.INTACT);
        for(int first=1;first<4;first++){
            int target=first;var supports=new HashMap<>(base.supports());
            supports.replaceAll((p,v)->{
                int slot=(p.x()>=0?2:0)+(p.z()<0?1:0);
                boolean entrance=(p.x()>=-29&&p.x()<-26||p.x()>=26&&p.x()<29)&&(p.z()==6||p.z()==-7);
                return entrance&&slot<target?new ProbeResult(ProbeKind.NO_SUPPORT,0,OptionalInt.empty(),OptionalInt.empty(),64,ComponentFixtures.H):v;
            });
            var input=new ComponentInput(base.site(),base.stations(),supports,base.decay(),base.family(),false,List.of(),List.of());
            var c=new EndCap(DECAY,ComponentFixtures.PALETTE).compileAt(input);routes(helper,c);
            var ground=c.requiredRoutes().getLast().getFirst();
            var reachable=new ComponentFixtures.CollisionView(ComponentFixtures.states(c)).reachable(c.requiredRoutes().getFirst().getFirst(),c.bounds());
            helper.assertTrue(reachable.contains(new ComponentFixtures.Foot(ground.x()*2,ground.y()*2,ground.z()*2)),"alternate cap has no disconnected ramp");
        }
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void ruinsHaveNoFloatingIslands(GameTestHelper helper){
        for(var state:List.of(DecayState.RUIN,DecayState.BURIED)){
            var c=new EnemyTower(DECAY,ComponentFixtures.PALETTE).compile(ComponentFixtures.input(SiteKind.ENEMY_TOWER,
                    new Bounds(-5,-32,-5,6,8,6),0,0,0,state));
            var blocks=ComponentFixtures.states(c);var roots=new HashSet<Point3i>();var queue=new ArrayDeque<Point3i>();
            blocks.forEach((p,s)->{if(!s.isAir()&&p.y()<=64){roots.add(p);queue.add(p);}});
            while(!queue.isEmpty()){var p=queue.removeFirst();for(var d:Direction.values()){
                var q=new Point3i(p.x()+d.getStepX(),p.y()+d.getStepY(),p.z()+d.getStepZ());
                if(blocks.containsKey(q)&&!blocks.get(q).isAir()&&roots.add(q))queue.add(q);
            }}
            helper.assertTrue(blocks.entrySet().stream().filter(e->!e.getValue().isAir()).allMatch(e->roots.contains(e.getKey())),"every ruin block rooted");
            helper.assertTrue(c.spawnSlots().isEmpty()&&c.blockEntities().isEmpty(),"no ruined gameplay");
        }
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void gravityAndNeighborUpdatesRemainStable(GameTestHelper helper){
        for(int angle:new int[]{0,15,30,45,60,90}){
            var c=new BeaconTower(DECAY,ComponentFixtures.PALETTE).compile(ComponentFixtures.input(SiteKind.BEACON,
                    new Bounds(-5,-32,-5,5,7,5),0,0,angle,DecayState.INTACT));
            var states=ComponentFixtures.states(c);var view=ComponentFixtures.view(states);
            states.forEach((p,state)->{
                var pos=new BlockPos(p.x(),p.y(),p.z());
                helper.assertTrue(!(state.getBlock() instanceof FallingBlock),"no unsupported gravity palette");
                if(state.getBlock() instanceof StairBlock||state.getBlock() instanceof IronBarsBlock){
                    for(var d:Direction.values()){
                        var next=state.updateShape(d,view.getBlockState(pos.relative(d)),view,pos,pos.relative(d));
                        helper.assertTrue(next.equals(state),"native neighbor update changed frozen state at "+p+": "+state+" -> "+next);
                    }
                }
            });
        }
        helper.succeed();
    }
    private static ComponentInput water(int width){
        var base=ComponentFixtures.input(SiteKind.WATER_GATE,new Bounds(-40,-32,-6,40,3,6),0,0,0,DecayState.INTACT);
        var supports=new HashMap<Point3i,ProbeResult>();
        base.supports().forEach((p,v)->supports.put(p,p.x()>=-24&&p.x()<25
                ?new ProbeResult(ProbeKind.WATER,56,OptionalInt.of(65),OptionalInt.of(56),8,ComponentFixtures.H):v));
        var evidence=new WaterEvidence(new ArcInterval((1024-24)*256L,(1024+25)*256L),
                width<50?WaterClass.NARROW:WaterClass.WIDE,width*256L,49*256L,65,
                List.of(new Point3i(-25,64,0),new Point3i(25,64,0)),List.of(new Point3i(0,56,0)),ComponentFixtures.H);
        return new ComponentInput(base.site(),base.stations(),supports,base.decay(),base.family(),false,List.of(evidence),List.of());
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void sourceWaterNeverFillsBoatClearance(GameTestHelper helper){
        var c=new WaterGate(DECAY,ComponentFixtures.PALETTE).compile(water(49));
        helper.assertTrue(!c.voxels().isEmpty(),"49-wide crossing accepted with supported bed");
        for(var v:c.voxels())if(v.kind()==IntentKind.FLUID)
            helper.assertTrue(v.pos().y()<65&&BlockCatalog.resolveState(v.state()).getFluidState().isSource(),"water source below old surface only");
        for(int z=-5;z<5;z++)for(int x=-2;x<2;x++)for(int y=65;y<68;y++){
            var pos=new Point3i(x,y,z);
            helper.assertTrue(c.voxels().stream().anyMatch(v->v.pos().equals(pos)&&v.kind()==IntentKind.OWNED_VOID),"three-block main boat clearance");
        }
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void water49BoatPasses(GameTestHelper helper){
        var c=new WaterGate(DECAY,ComponentFixtures.PALETTE).compile(water(49));
        helper.assertTrue(!c.voxels().isEmpty(),"49-wide crossing built");
        var origin=helper.absolutePos(new BlockPos(0,150,200));
        var level=helper.getLevel();
        // A small physical slice preserves the full central arch and water on both sides.
        for(int z=-9;z<=9;z++)for(int x=-4;x<=4;x++)for(int y=55;y<72;y++)
            level.setBlock(origin.offset(x,y-56,z),y<56?Blocks.STONE.defaultBlockState():
                    y<65?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
        for(var v:c.voxels())if(v.pos().x()>=-4&&v.pos().x()<=4)
            level.setBlock(origin.offset(v.pos().x(),v.pos().y()-56,v.pos().z()),BlockCatalog.resolveState(v.state()),2);
        var boat=new net.minecraft.world.entity.vehicle.Boat(level,origin.getX(),origin.getY()+65-56-.1,origin.getZ()-7);
        try{
            for(int step=0;step<56;step++)boat.move(net.minecraft.world.entity.MoverType.SELF,new net.minecraft.world.phys.Vec3(0,0,.25));
            helper.assertTrue(boat.getZ()>=origin.getZ()+6.99,"native boat collision moves through central arch");
        }finally{boat.discard();}
        helper.succeed();
    }
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void water50And51HaveOneTerminalCap(GameTestHelper helper){
        for(int width:new int[]{50,51}){
            helper.assertTrue(new WaterGate(DECAY,ComponentFixtures.PALETTE).compile(water(width)).voxels().isEmpty(),"wide water never gets a crossing");
            var input=ComponentFixtures.input(SiteKind.END_CAP,new Bounds(-30,-33,-5,1,3,15),0,0,0,DecayState.INTACT);
            var cap=new EndCap(DECAY,ComponentFixtures.PALETTE).compile(input);
            helper.assertTrue(!cap.voxels().isEmpty()&&cap.voxels().stream().allMatch(v->v.pos().x()<0),"single near-side cap, no far-side body");
            routes(helper,cap);
        }
        helper.succeed();
    }
}
