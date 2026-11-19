package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

/** 对象身份是窗口版本，不以同一个配方字符串误复用另一次冻结站表。 */
class ImportedProjectionMemoTest {
    private static final String H="a".repeat(64);
    private static final List<Station> STATIONS=List.of(new Station(0,new Point2q(0,0),64,0),
            new Station(1024,new Point2q(1024,0),64,0));
    @Test void changedPlanIdentityRebuildsAndEvictsThePreviousWindow() {
        var memo=new ImportedProjectionMemo();var a=window(64);var b=window(80);
        var first=memo.get(a,STATIONS,List.of());
        assertSame(first,memo.get(new PlanWindow(a.plans()),STATIONS,List.of()));
        var changed=List.of(new Station(0,new Point2q(0,0),80,0),new Station(1024,new Point2q(1024,0),80,0));
        var second=memo.get(b,changed,List.of());
        assertNotSame(first,second);assertEquals(80,second.contract(0,0).walkY());
        assertEquals(2,memo.stats().builds());assertEquals(1,memo.stats().hits());assertEquals(1,memo.stats().entries());
        memo.clear();assertEquals(0,memo.stats().bytes());assertEquals(0,memo.stats().entries());
    }
    @Test void oversizedWindowUsesExactProjectionWithoutRetainingIt() {
        var memo=new ImportedProjectionMemo();var stations=new ArrayList<Station>();
        for(int i=0;i<17000;i++)stations.add(new Station(i*1024L,new Point2q(i*1024L,0),64,0));
        var projection=memo.get(window(64),stations,List.of());
        assertTrue(projection.estimatedBytes()>ImportedProjectionMemo.MAX_BYTES);
        assertNotNull(projection.contract(100,0));
        assertEquals(0,memo.stats().bytes());assertEquals(0,memo.stats().entries());
    }
    private static PlanWindow window(int y) {
        var points=List.of(new Point2q(0,0),new Point2q(1024,0));
        var geometry=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",0),points,List.of(0L,1024L),
                List.of(),Optional.empty(),new Boundary(0,points.getFirst(),y,H),
                new Boundary(1,points.getLast(),y,H),0,0,H);
        var stations=List.of(new Station(0,points.getFirst(),y,0),new Station(1024,points.getLast(),y,0));
        return new PlanWindow(List.of(new SegmentPlan(geometry,new PrefixSummary(0,0,Optional.empty(),H),
                new StationTable(stations,List.of(),H),List.of(),H)));
    }
}
