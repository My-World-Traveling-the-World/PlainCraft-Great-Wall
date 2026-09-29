package local.plaincraft.greatwall.gametest;

import java.util.HashMap;
import java.util.List;
import java.util.OptionalInt;
import local.plaincraft.greatwall.component.Ramp;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public final class RampGroundPortalTests implements FabricGameTest {
    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000,batch="pgw-ground-portals")
    public void everyHeightAndRotationReachesExteriorCapturedGround(GameTestHelper helper) {
        runCase(helper,0);
    }

    private static void runCase(GameTestHelper helper,int index) {
        if(index==192) {
            System.out.println("S1B_RAMP_GROUND_PORTAL attempted=192 passed=192 failed=0");
            helper.succeed();return;
        }
        int height=index/6+1,angle=new int[]{0,15,30,45,60,90}[index%6],ground=84-height;
        var base=ComponentFixtures.input(SiteKind.RAMP,new Bounds(-30,-33,4,-6,3,15),0,0,angle,DecayState.INTACT);
        var supports=new HashMap<>(base.supports());
        supports.replaceAll((p,v)->new ProbeResult(ProbeKind.SUPPORTED,ground,OptionalInt.empty(),OptionalInt.empty(),
                Math.max(0,height-20),ComponentFixtures.H));
        var input=new ComponentInput(base.site(),base.stations(),supports,base.decay(),base.family(),false,List.of(),List.of());
        var component=new Ramp(new DecayField(0),ComponentFixtures.PALETTE).compileBetween(input,ground,84);
        helper.assertTrue(!component.voxels().isEmpty(),"nonempty ramp H="+height+" angle="+angle);
        var states=new HashMap<>(ComponentFixtures.states(component,ground));var b=component.bounds();
        var bounds=new Bounds(b.minX()-2,b.minY(),b.minZ()-2,b.maxXExclusive()+2,b.maxYExclusive(),b.maxZExclusive()+2);
        for(int z=bounds.minZ();z<bounds.maxZExclusive();z++)for(int x=bounds.minX();x<bounds.maxXExclusive();x++)
            states.putIfAbsent(new Point3i(x,ground-1,z),Blocks.STONE.defaultBlockState());
        var route=component.requiredRoutes().getFirst();
        var reached=new ComponentFixtures.CollisionView(states).reachable(route.getLast(),bounds);
        for(var p:route)helper.assertTrue(reached.contains(new ComponentFixtures.Foot(p.x()*2,p.y()*2,p.z()*2)),
                "required route H="+height+" angle="+angle+" point="+p);
        boolean exterior=false;
        for(int z=bounds.minZ();z<bounds.maxZExclusive();z++)for(int x=bounds.minX();x<bounds.maxXExclusive();x++)
            if(x<b.minX()||x>=b.maxXExclusive()||z<b.minZ()||z>=b.maxZExclusive())
                exterior|=reached.contains(new ComponentFixtures.Foot(x*2,ground*2,z*2));
        helper.assertTrue(exterior,"upper route cannot exit complete ramp footprint H="+height+" angle="+angle);
        System.out.println("S1B_RAMP_GROUND_PORTAL_CASE index="+index+" height="+height+" angle="+angle+" status=PASS");
        helper.runAfterDelay(1,()->runCase(helper,index+1));
    }
}
