package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class CoveragePlannerTest {
    private static final String H="a".repeat(64);
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
}
