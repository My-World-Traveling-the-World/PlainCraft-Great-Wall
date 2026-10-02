package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.geometry.Rasterizer;
import org.junit.jupiter.api.Test;

class CoveragePlannerTest {
    private static final String H="a".repeat(64);
    @Test void rotatedFortReadDomainIncludesTheActualRasterGuardAndExternalApron(){
        var frame=new Frame(new Point2q(52736,243968),247565951,1044812234,87,false);
        var site=new SiteRecord(new SiteId("fixture",SiteKind.FORT,0),0,frame,
                new Bounds(177,54,924,235,95,982),SiteState.PLANNED,Optional.empty(),Optional.empty());
        var start=new Boundary(0,frame.center(),87,H);
        var end=new Boundary(1,new Point2q(269*256L,1202*256L),87,H);
        var geometry=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",0),
                List.of(start.point(),end.point()),List.of(0L,256L*256),List.of(),Optional.empty(),start,end,0,0,H);
        var stations=new StationTable(List.of(new Station(0,start.point(),87,0),new Station(256L*256,end.point(),87,0)),List.of(),H);
        var plan=new SegmentPlan(geometry,new PrefixSummary(0,0,Optional.empty(),H),stations,List.of(site),H);
        var owners=new HashSet<>(new CoveragePlanner().realizationOwners(new CellKey(H,"minecraft:overworld",0),
                new PlanWindow(List.of(plan))));
        var actual=Rasterizer.worldBounds(frame,new Bounds(-24,-33,-24,24,8,24));
        var apron=new Bounds(actual.minX()-1,actual.minY(),actual.minZ()-1,
                actual.maxXExclusive()+1,actual.maxYExclusive(),actual.maxZExclusive()+1);
        assertTrue(owners.containsAll(Rasterizer.owners("minecraft:overworld",apron)),
                "all potential exit proof columns must be captured before realization");
        assertTrue(owners.contains(new ChunkKey("minecraft:overworld",10,59)),
                "retained seed17 witness: x=175 falls in owner10, not the old owner11 floor");
        assertTrue(owners.size()<=256,"the existing finite capture owner budget is not increased");
    }
    @Test void canonicalCoverageIncludesLowerGuardUpperSentinelAndNegativePages() {
        var stations=new StationTable(List.of(new Station(0,new Point2q(-256,0),-20,0),
                new Station(64L*256,new Point2q(63L*256,0),-20,0)),List.of(),H);
        var boundary=new Boundary(0,new Point2q(-256,0),-20,H);
        var geometry=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",0),
                stations.stations().stream().map(Station::point).toList(),List.of(0L,64L*256),List.of(),Optional.empty(),boundary,boundary,0,0,H);
        var plan=new SegmentPlan(geometry,new PrefixSummary(0,0,Optional.empty(),H),stations,List.of(),H);
        var window=new PlanWindow(List.of(plan));var compiler=new CoveragePlanner();var key=new ChunkKey("minecraft:overworld",-1,0);
        var c=compiler.forChunk(key,window,new DimensionBounds(-64,8192,29_999_000));
        assertEquals(-55,c.readBounds().minY());assertEquals(-12,c.readBounds().maxYExclusive());
        assertEquals(List.of(-128),c.tileBases());
        assertEquals(c,compiler.forChunk(key,new PlanWindow(List.of(plan,plan)),new DimensionBounds(-64,8192,29_999_000)));
    }

    @Test void triggerDomainUsesTheSameOwnerFunctionAsDurableCapture(){
        var window=window(128);
        var planner=new CoveragePlanner();
        var potentialOwners=new TreeSet<>(Comparator.comparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z));
        for(long cell=0;cell<=2;cell++) {
            var key=new CellKey(H,"minecraft:overworld",cell);
            for(var owner:planner.realizationOwners(key,window)) {
                potentialOwners.add(owner);
                assertTrue(planner.cellsForChunk(owner,window).contains(key),
                        "every captured station/site owner remains dispatchable: "+owner+" cell="+cell);
            }
        }
        for(var owner:potentialOwners) {
            for(var cell:planner.cellsForChunk(owner,window))
                assertTrue(planner.realizationOwners(cell,window).contains(owner),
                        "dispatch must be a subset of durable capture owners");
        }
        var fringe=new ChunkKey("minecraft:overworld",2,6);
        assertTrue(planner.cellsForChunk(fringe,window).isEmpty(),
                "the old lateral-halo fringe is explicitly a non-owner after A+C");
    }

    private static PlanWindow window(int length) {
        var stations=new ArrayList<Station>();
        for(int x=0;x<=length;x+=4) stations.add(new Station(x*256L,new Point2q(x*256L,0),84,0));
        var start=new Boundary(0,new Point2q(0,0),84,H);
        var end=new Boundary(1,new Point2q(length*256L,0),84,H);
        var geometry=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",0),
                List.of(start.point(),end.point()),List.of(0L,length*256L),List.of(),
                Optional.of(new Terminal(0,length*256L,TerminalKind.WORLD_LIMIT)),start,end,0,0,H);
        var fort=new SiteRecord(new SiteId("fixture",SiteKind.FORT,0),0,
                new Frame(new Point2q(0,0),1<<30,0,84,false),
                new Bounds(-24,49,-24,24,92,24),SiteState.PLANNED,Optional.empty(),Optional.empty());
        return new PlanWindow(List.of(new SegmentPlan(geometry,
                new PrefixSummary(0,0,Optional.empty(),H),
                new StationTable(stations,List.of(),H),List.of(fort),H)));
    }
}
