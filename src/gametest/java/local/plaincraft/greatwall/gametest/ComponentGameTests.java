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
        h.assertTrue(!c.voxels().isEmpty(),"component must not be suppressed by fixture");
        var collision=new ComponentFixtures.CollisionView(ComponentFixtures.states(c));
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
    public void capHasGroundEgress(GameTestHelper helper){
        routes(helper,new EndCap(DECAY,ComponentFixtures.PALETTE).compile(ComponentFixtures.input(SiteKind.END_CAP,
                new Bounds(-30,-33,-5,1,3,15),0,0,0,DecayState.INTACT)));
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
}
