package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.decay.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class RealizationPlannerTest {
    private static final String H="a".repeat(64);
    private static final PaletteBinding PALETTE=(i,p,l,w,s,h)->new BlockSpec(switch(p){
        case FLOOR,SLAB->"minecraft:stone_brick_slab";case STAIR->"minecraft:stone_brick_stairs";
        case AIR->"minecraft:air";case WATER->"minecraft:water";default->"minecraft:stone_bricks";},Map.of());
    private static RealizationPlanner planner() {
        var d=new DecayField(0);
        return new RealizationPlanner(new SurfaceProbe(),Map.of(SiteKind.WALL,new WallSection(d,PALETTE),
                SiteKind.END_CAP,new EndCap(d,PALETTE),SiteKind.GATE,new Gate(d,PALETTE)));
    }
    private static RealizationPlanner.Input input(boolean reverse,long revision) {
        var stations=new ArrayList<Station>();for(int x=0;x<=1024;x+=4)stations.add(new Station(x*256L,new Point2q(x*256L,0),84,0));
        var key=new SegmentKey(H,"minecraft:overworld",0);var start=new Boundary(0,new Point2q(0,0),84,H);
        var end=new Boundary(1,new Point2q(1024L*256,0),84,H);
        var g=new SegmentGeometry(key,List.of(start.point(),end.point()),List.of(0L,1024L*256),List.of(),Optional.empty(),start,end,0,0,H);
        var plan=new SegmentPlan(g,new PrefixSummary(0,0,Optional.empty(),H),new StationTable(stations,List.of(),H),List.of(),H);
        var tiles=Map.of(new TerrainTile.Key("minecraft:overworld",20,0,0,127),new TerrainTile(
                new TerrainTile.Key("minecraft:overworld",20,0,0,127),H,List.of("minecraft:air{}:fluid=minecraft:empty:be=false"),new int[32768]));
        var entries=new ArrayList<Point3i>();for(int z=-16;z<16;z++)for(int x=144;x<576;x++)entries.add(new Point3i(x,0,z));
        if(reverse)Collections.reverse(entries);
        var columns=new LinkedHashMap<Point3i,SurfaceProbe.ColumnView>();
        for(var p:entries)columns.put(p,new Flat());
        return new RealizationPlanner.Input(new CellKey(H,"minecraft:overworld",5),new PlanWindow(List.of(plan)),tiles,columns,List.of(),Map.of(),revision,H);
    }
    @Test void orderIndependentCellKeepsStationContractImmutable() {
        var a=input(false,0);var b=input(true,0);
        var first=planner().resolve(a);var second=planner().resolve(b);
        assertEquals(first,second);assertFalse(first.components().isEmpty());
        assertEquals(1,first.components().size());
        assertEquals(SiteKind.WALL,first.components().getFirst().id().kind());
        assertTrue(a.plans().plans().getFirst().stations().stations().stream().allMatch(s->s.walkY()==84));
    }
    @Test void frozenGateRevisionChangesCellHashWithoutChangingGeometry() {
        var a=planner().resolve(input(false,0));var b=planner().resolve(input(false,1));
        assertEquals(a.components(),b.components());assertNotEquals(a.hash(),b.hash());
    }
    private static final class Flat implements SurfaceProbe.ColumnView {
        public String state(int y){return y<64?"minecraft:stone{}:fluid=minecraft:empty:be=false":"minecraft:air{}:fluid=minecraft:empty:be=false";}
        public boolean solidSupport(int y){return y<64;}public boolean protectedAt(int y){return false;}
        public boolean water(int y){return false;}public boolean airOrReplaceablePlant(int y){return y>=64;}
        public int highestNonPlantFeet(){return 64;}public String hash(){return H;}
    }
}
