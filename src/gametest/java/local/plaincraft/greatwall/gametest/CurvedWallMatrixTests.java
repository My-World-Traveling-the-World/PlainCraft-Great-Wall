package local.plaincraft.greatwall.gametest;

import java.util.*;
import local.plaincraft.greatwall.component.WallSection;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.*;

public final class CurvedWallMatrixTests implements FabricGameTest {
    private record Case(int heading, int bend, int slope, int drop, DecayState state) {}

    @GameTest(template=EMPTY_STRUCTURE,timeoutTicks=1000000,batch="pgw-curve-matrix")
    public void mixedCurveSlopeCollisionProduct(GameTestHelper helper) {
        var cases = new ArrayList<Case>();
        for (int heading : new int[]{0,15,30,45,60,90}) for (int bend : new int[]{-1,1})
            for (int slope : new int[]{-1,0,1}) for (int drop : new int[]{0,4,5,12})
                for (var state : DecayState.values()) cases.add(new Case(heading,bend,slope,drop,state));
        helper.assertTrue(cases.size()==576,"complete curve fixture Cartesian product");
        run(helper,List.copyOf(cases),0,new ArrayList<>());
    }

    private static void run(GameTestHelper helper,List<Case> cases,int index,List<String> errors) {
        if (index==cases.size()) {
            System.out.println("S1B_CURVE_MATRIX attempted="+index+" failed="+errors.size());
            errors.forEach(e->System.out.println("S1B_CURVE_FAILURE "+e));
            helper.assertTrue(errors.isEmpty(),"curve failures="+errors.size()+": "+errors.stream().limit(2).toList());
            helper.succeed();return;
        }
        var c=cases.get(index);
        try {
            var component=new WallSection(new DecayField(0),ComponentFixtures.PALETTE).compile(input(c));
            if(component.voxels().isEmpty())throw new AssertionError("supported curve unexpectedly empty");
            var blocks=new HashMap<Point3i,net.minecraft.world.level.block.state.BlockState>();
            for(var v:component.voxels())blocks.put(v.pos(),BlockCatalog.resolveState(v.state()));
            var collision=new ComponentFixtures.CollisionView(Collections.unmodifiableMap(blocks));
            for(var route:component.requiredRoutes()) {
                var reached=collision.reachable(route.getFirst(),component.bounds());
                for(var p:route)if(!reached.contains(new ComponentFixtures.Foot(p.x()*2,p.y()*2,p.z()*2)))
                    throw new AssertionError("native route disconnected at "+p);
            }
        } catch(Exception|AssertionError failure) {errors.add(c+" "+failure);}
        helper.runAfterDelay(1,()->run(helper,cases,index+1,errors));
    }

    private static ComponentInput input(Case c) {
        var base=ComponentFixtures.input(SiteKind.WALL,new Bounds(-32,-32,-5,32,3,5),0,0,c.heading(),c.state());
        var stations=new ArrayList<Station>();
        double angle=c.heading()*StrictMath.PI/180,cos=StrictMath.cos(angle),sin=StrictMath.sin(angle);
        var points=new ArrayList<Point2q>();var lengths=new ArrayList<Long>();
        for(int x=-192;x<=192;x+=4) {
            double z=c.bend()*8*StrictMath.sin(x*StrictMath.PI/64);
            var point=new Point2q(StrictMath.round((x*cos-z*sin)*256),StrictMath.round((x*sin+z*cos)*256));
            lengths.add(points.isEmpty()?0:Math.addExact(lengths.getLast(),FixedMath.hypotQ8(
                    point.xQ8()-points.getLast().xQ8(),point.zQ8()-points.getLast().zQ8())));
            points.add(point);
        }
        for(int n=0;n<points.size();n++) {
            long relative=lengths.get(n)-lengths.get(48);
            int rise=(int)Math.floorDiv(relative,8*256L),wave=Math.floorMod(rise,16);
            int w=84+(c.slope()==0?(wave<=8?wave:16-wave)-4:c.slope()*rise);
            stations.add(new Station(1024*256L+relative,points.get(n),w,0));
        }
        long dx=points.get(49).xQ8()-points.get(47).xQ8(),dz=points.get(49).zQ8()-points.get(47).zQ8();
        double norm=StrictMath.hypot(dx,dz);
        var frame=new Frame(points.get(48),(int)StrictMath.round(dx*(1L<<30)/norm),
                (int)StrictMath.round(dz*(1L<<30)/norm),stations.get(48).walkY(),false);
        var old=base.site();var site=new SiteRecord(old.id(),old.globalSQ8(),frame,old.bounds(),old.state(),old.absorbedBy(),old.reason());
        var supports=new HashMap<Point3i,ProbeResult>();
        for(int z=-64;z<=64;z++)for(int x=-64;x<=64;x++) {
            var contract=WallSection.contract(stations,x,z);
            int mixedDrop=c.drop()==0?0:c.drop()-Math.floorMod(x+z,2);
            supports.put(new Point3i(x,contract.walkY(),z),new ProbeResult(ProbeKind.SUPPORTED,
                    contract.walkY()-20-mixedDrop,OptionalInt.empty(),OptionalInt.empty(),mixedDrop,ComponentFixtures.H));
        }
        return new ComponentInput(site,stations,supports,base.decay(),base.family(),false,List.of(),List.of());
    }
}
