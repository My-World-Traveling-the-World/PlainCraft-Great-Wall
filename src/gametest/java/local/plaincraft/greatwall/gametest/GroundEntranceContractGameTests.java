package local.plaincraft.greatwall.gametest;

import java.util.*;
import local.plaincraft.greatwall.component.GroundEntranceContract;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import local.plaincraft.greatwall.terrain.BlockCatalog;

public final class GroundEntranceContractGameTests implements FabricGameTest {
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000)
    public void declaredWidthHasNativeHeadroomAndOutsideStepWithoutWholePlaneFlatness(GameTestHelper helper){
        var frame=new Frame(new Point2q(0,0),1<<30,0,84,false);
        var corridor=GroundEntranceContract.Corridor.fort();
        var supports=new HashMap<Point3i,ProbeResult>();var views=new HashMap<Point3i,SurfaceProbe.ColumnView>();
        var natural=new HashMap<Point3i,net.minecraft.world.level.block.state.BlockState>();
        for(var p:GroundEntranceContract.columns(frame,corridor)){
            int h=p.z()>=23?66:p.x()==0&&p.z()==0?67:64;
            var probe=new ProbeResult(ProbeKind.SUPPORTED,h,OptionalInt.empty(),OptionalInt.empty(),0,ComponentFixtures.H);
            supports.put(p,probe);
            var captured=Map.of(h-1,BlockCatalog.token(Blocks.STONE.defaultBlockState(),false));
            views.put(new Point3i(p.x(),0,p.z()),new SurfaceProbe.ColumnView(){
                public String state(int y){return captured.getOrDefault(y,BlockCatalog.token(Blocks.AIR.defaultBlockState(),false));}
                public boolean solidSupport(int y){return y<h;}
                public boolean protectedAt(int y){return false;}
                public boolean water(int y){return false;}
                public boolean airOrReplaceablePlant(int y){return y>=h;}
                public int highestNonPlantFeet(){return h;}
                public String hash(){return ComponentFixtures.H;}
            });
            natural.put(new Point3i(p.x(),h-1,p.z()),Blocks.STONE.defaultBlockState());
        }
        var cert=GroundEntranceContract.inspect(frame,corridor,supports,new GroundEntranceContract.Evidence(
                GroundEntranceContract.Source.ANALYTIC_FIXTURE,new DimensionBounds(-64,320,30_000_000),views)).orElseThrow();
        helper.assertTrue(cert.lowerFeetY()==67,"a high interior rock is not carved to fit a lower platform");
        var states=new HashMap<>(natural);
        for(var p:supports.keySet())if(corridor.contains(local.plaincraft.greatwall.geometry.Rasterizer.localCell(frame,p)))
            states.put(new Point3i(p.x(),66,p.z()),Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE,
                    net.minecraft.world.level.block.state.properties.SlabType.TOP));
        var collision=new ComponentFixtures.CollisionView(states);
        for(var lane:cert.approaches()){
            var outside=lane.getFirst();var inside=lane.getLast();
            var b=new Bounds(-4,62,-27,4,71,27);
            var reachable=collision.reachable(outside,b);
            helper.assertTrue(reachable.contains(new ComponentFixtures.Foot(inside.x()*2,inside.y()*2,inside.z()*2)),
                    "native collision outside-to-inside step must remain connected: "+lane);
        }
        helper.assertTrue(cert.approaches().size()==4,"all actual columns of the four-wide portal are certified");
        helper.succeed();
    }
}
