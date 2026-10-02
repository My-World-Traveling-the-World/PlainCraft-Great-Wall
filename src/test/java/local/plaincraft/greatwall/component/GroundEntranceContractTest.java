package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class GroundEntranceContractTest {
    private static final Frame FRAME=new Frame(new Point2q(0,0),1<<30,0,84,false);
    private static final GroundEntranceContract.Corridor CORRIDOR=GroundEntranceContract.Corridor.fort();
    private static Map<Point3i,ProbeResult> flat(){
        var map=new HashMap<Point3i,ProbeResult>();
        for(var p:GroundEntranceContract.columns(FRAME,CORRIDOR))map.put(p,probe(ProbeKind.SUPPORTED,64,0));
        return map;
    }
    private static ProbeResult probe(ProbeKind kind,int h,int drop){
        return new ProbeResult(kind,h,OptionalInt.empty(),OptionalInt.empty(),drop,TemplateFixtures.HASH);
    }
    private static Optional<GroundEntranceContract.Certificate> inspect(Map<Point3i,ProbeResult> supports){
        return GroundEntranceContract.inspect(FRAME,CORRIDOR,supports,GroundEntranceContract.Evidence.analytic(supports));
    }
    @Test void naturalHeightSpreadIsNotAProxyForACompleteThreeWideEntrance(){
        var map=flat();
        map.put(new Point3i(0,84,0),probe(ProbeKind.SUPPORTED,67,0));
        map.replaceAll((p,v)->p.z()>=23?probe(ProbeKind.SUPPORTED,66,0):v);
        var cert=inspect(map).orElseThrow();
        assertEquals(67,cert.lowerFeetY(),"retain the level template floor without carving the peak");
        assertEquals(CORRIDOR.maxX()-CORRIDOR.minX(),cert.approaches().size(),
                "all four actual columns of the declared at-least-three-wide portal must be certified");
        for(var lane:cert.approaches()){
            assertTrue(lane.getFirst().z()>=24,"outside natural ground, not a central high island");
            assertTrue(Math.abs(lane.getFirst().y()-lane.getLast().y())<=1);
        }
        assertTrue(67-64>1,"retained counterexample to the old full-plane proxy");
    }
    @Test void anIsolatedLevelIslandCannotSupplyExteriorAccess(){
        var map=flat();map.put(new Point3i(0,84,0),probe(ProbeKind.SUPPORTED,67,0));
        assertTrue(inspect(map).isEmpty(),"all outside ground is three blocks below the entrance");
    }
    @Test void protectedWaterMissingAndUpperCensoredColumnsRejectInsteadOfGuessingAir(){
        for(var kind:new ProbeKind[]{ProbeKind.PROTECTED,ProbeKind.WATER,ProbeKind.NO_SUPPORT,ProbeKind.UPPER_CENSORED}){
            var map=flat();map.put(new Point3i(0,84,0),probe(kind,64,0));
            assertTrue(inspect(map).isEmpty(),kind.toString());
        }
        var map=flat();map.remove(new Point3i(0,84,0));
        assertTrue(inspect(map).isEmpty(),"missing required column");
        assertTrue(GroundEntranceContract.inspect(FRAME,CORRIDOR,flat(),new GroundEntranceContract.Evidence(
                GroundEntranceContract.Source.DURABLE_T0,new DimensionBounds(-64,320,30_000_000),Map.of())).isEmpty(),
                "missing T0 headroom cannot use the analytic fixture constructor");
    }
    @Test void dropThirteenAndBothFootHeightBoundsStayClosed(){
        for(var bad:new ProbeResult[]{probe(ProbeKind.SUPPORTED,51,13),probe(ProbeKind.SUPPORTED,84,0)}){
            var map=flat();map.put(new Point3i(0,84,0),bad);assertTrue(inspect(map).isEmpty());
        }
        var map=flat();map.replaceAll((p,v)->probe(ProbeKind.SUPPORTED,51,0));
        assertTrue(inspect(map).isEmpty(),"lying drop zero cannot hide h<g-12");
    }
    @Test void oneLaneAndAllBlockedExteriorFacesAreNotThreeWideEntrances(){
        var map=flat();
        map.replaceAll((p,v)->p.z()<-24||p.z()>=24?probe(ProbeKind.PROTECTED,64,0):v);
        assertTrue(inspect(map).isEmpty());
        map.put(new Point3i(0,84,-25),probe(ProbeKind.SUPPORTED,64,0));
        assertTrue(inspect(map).isEmpty(),"one legal outside column is not three-wide access");
    }
    @Test void twoOutsideColumnsCannotBeReusedAsThreeIndependentRotatedApproaches(){
        var frame=new Frame(new Point2q(52736,243968),247565951,1044812234,87,false);
        var map=new HashMap<Point3i,ProbeResult>();
        for(var p:GroundEntranceContract.columns(frame,CORRIDOR)){
            boolean inside=CORRIDOR.contains(local.plaincraft.greatwall.geometry.Rasterizer.localCell(frame,p));
            boolean outside=p.x()==182&&(p.z()==958||p.z()==959);
            map.put(p,probe(inside||outside?ProbeKind.SUPPORTED:ProbeKind.PROTECTED,inside?70:69,0));
        }
        assertTrue(GroundEntranceContract.inspect(frame,CORRIDOR,map,
                GroundEntranceContract.Evidence.analytic(map)).isEmpty(),
                "the same outside voxel cannot certify several independent entrance columns");
    }
    @Test void realHeadroomAndUnapprovedVoidMaterialsAreCheckedBeforeCertificate(){
        var supports=flat();
        var analytic=GroundEntranceContract.Evidence.analytic(supports);
        for(String obstruction:List.of("minecraft:stone{}:fluid=minecraft:empty:be=false",
                "minecraft:sweet_berry_bush{age=3}:fluid=minecraft:empty:be=false")){
            var columns=new HashMap<>(analytic.columns());
            columns.put(new Point3i(0,0,0),new SurfaceProbe.ColumnView(){
                public String state(int y){return y==64?obstruction:"minecraft:air{}:fluid=minecraft:empty:be=false";}
                public boolean solidSupport(int y){return y<64;}
                public boolean protectedAt(int y){return false;}
                public boolean water(int y){return false;}
                public boolean airOrReplaceablePlant(int y){return !obstruction.startsWith("minecraft:stone")||y!=64;}
                public int highestNonPlantFeet(){return 65;}
                public String hash(){return TemplateFixtures.HASH;}
            });
            assertTrue(GroundEntranceContract.inspect(FRAME,CORRIDOR,supports,new GroundEntranceContract.Evidence(
                    GroundEntranceContract.Source.DURABLE_T0,analytic.bounds(),columns)).isEmpty(),obstruction);
        }
    }
}
