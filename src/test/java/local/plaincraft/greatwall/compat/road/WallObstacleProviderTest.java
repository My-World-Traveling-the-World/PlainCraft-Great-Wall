package local.plaincraft.greatwall.compat.road;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.*;
import org.junit.jupiter.api.Test;

class WallObstacleProviderTest {
    private static final String H="a".repeat(64);
    private static Request request(Rect bounds) {
        return new Request(new UUID(1,2),"minecraft:overworld","road",1,bounds,2,List.of("road"));
    }
    private static WallObstacleProvider.WallSnapshotSource source(List<Strip> strips,
            List<PlanLease<PlanWindow>> leases) {
        return new WallObstacleProvider.WallSnapshotSource() {
            public String revision(){return H;}
            public List<Strip> strips(){return strips;}
            public List<Passage> gates(){return List.of();}
            public Map<Long,String> gateByArcBucket(){return Map.of();}
            public List<PlanLease<PlanWindow>> leases(){return leases;}
        };
    }
    private static PlanLease<PlanWindow> lease(ConcurrentHashMap<String,Integer> releases) {
        return new PlanLease<>(new UUID(1,3),new CompletableFuture<>(),
                ()->releases.merge("closed",1,Integer::sum));
    }

    @Test void providerRetainsSparseCrossingAndReleasesItsActualPinsOnlyOnClose() {
        var releases=new ConcurrentHashMap<String,Integer>();
        var pin=lease(releases);
        var strip=new Strip("wall",List.of(new Pos(-128,84,0),new Pos(128,84,0)),0,256*256L,5);
        var provider=new WallObstacleProvider(Runnable::run,(r,c)->CompletableFuture.completedFuture(source(List.of(strip),List.of(pin))));
        var future=provider.prepare(request(new Rect(-16,-16,16,16)),()->false).toCompletableFuture();
        assertTrue(future.isDone()&&!future.isCompletedExceptionally());
        future.thenAccept(snapshot->{
            assertEquals(List.of(strip),snapshot.queryXZ(new Rect(-2,-2,2,2)));
            assertTrue(releases.isEmpty());
            snapshot.close();snapshot.close();
        });
        assertEquals(1,releases.get("closed"));
    }

    @Test void failedPreparedSnapshotClosesPinsAndDoesNotPretendThereIsNoWall() {
        var releases=new ConcurrentHashMap<String,Integer>();
        var strip=new Strip("wall",List.of(new Pos(0,84,0),new Pos(8,84,0)),0,8*256L,5);
        var provider=new WallObstacleProvider(action->{throw new IllegalStateException("executor rejected");},
                (r,c)->CompletableFuture.completedFuture(source(List.of(strip),List.of(lease(releases)))));
        var future=provider.prepare(request(new Rect(-16,-16,16,16)),()->false).toCompletableFuture();
        assertTrue(future.isCompletedExceptionally());
        assertEquals(1,releases.get("closed"));
    }

    @Test void cancellationWhileSourceIsPendingClosesLeasesOnHandoff() {
        var releases=new ConcurrentHashMap<String,Integer>();
        var input=new CompletableFuture<WallObstacleProvider.WallSnapshotSource>();
        var provider=new WallObstacleProvider(Runnable::run,(r,c)->input);
        var future=provider.prepare(request(new Rect(-16,-16,16,16)),()->false).toCompletableFuture();
        assertTrue(future.cancel(false));
        input.complete(source(List.of(),List.of(lease(releases))));
        assertEquals(1,releases.get("closed"));
    }

    @Test void oversizedOrInitiallyCancelledRequestNeverAsksForPlans() {
        var provider=new WallObstacleProvider(Runnable::run,(r,c)->{throw new AssertionError("unexpected planning");});
        assertTrue(provider.prepare(request(new Rect(Integer.MIN_VALUE,0,Integer.MAX_VALUE,1)),()->false)
                .toCompletableFuture().isCompletedExceptionally());
        assertTrue(provider.prepare(request(new Rect(-16,-16,16,16)),()->true)
                .toCompletableFuture().isCompletedExceptionally());
    }

    @Test void everyStripIsAtMost512ArcBlocksAndStopsAtThePermanentTerminal() {
        var stations=List.of(new Station(0,new Point2q(0,0),84,0),
                new Station(600*256L,new Point2q(600*256L,0),84,0),
                new Station(1200*256L,new Point2q(1200*256L,0),84,0));
        var start=new Boundary(0,stations.getFirst().point(),84,H);
        var end=new Boundary(1,stations.getLast().point(),84,H);
        var geometry=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",0),
                List.of(start.point(),end.point()),List.of(0L,1200*256L),List.of(),
                Optional.of(new Terminal(0,900*256L,TerminalKind.OCEAN)),start,end,0,0,H);
        var plan=new SegmentPlan(geometry,new PrefixSummary(0,0,Optional.empty(),H),
                new StationTable(stations,List.of(),H),List.of(),H);
        var source=WallSnapshotBuilder.build(H,"wall",request(new Rect(-16,-16,1216,16)),
                List.of(new PlanWindow(List.of(plan)),new PlanWindow(List.of(plan))),Map.of(),List.of());
        assertEquals(2,source.strips().size(),"duplicate pinned windows do not duplicate obstacles");
        for(var strip:source.strips())assertTrue(strip.toSQ8()-strip.fromSQ8()<=512*256L);
        assertEquals(900*256L,source.strips().getLast().toSQ8());
        assertEquals(900,source.strips().getLast().centerline().getLast().x());
    }

    @Test void journalAckAloneCannotPublishAPhysicallyReadyGate() {
        var id=new GateId("wall","planned",1);
        var gate=new RoadTypes.GateContract(id,800*256L,new Frame(new Point2q(0,0),1<<30,0,84,false),
                64,4,5,List.of(new ChunkKey("minecraft:overworld",0,0)),H);
        var record=new RoadTypes.GateRecord(gate,3,RoadTypes.GateState.APPLIED,List.of(),List.of("road"),Map.of(),3);
        var source=WallSnapshotBuilder.build(H,"wall",request(new Rect(-16,-16,16,16)),List.of(),Map.of(id,record),List.of());
        assertEquals(1,source.gates().size());
        assertEquals(64,source.gates().getFirst().center().y());
        assertFalse(source.gates().getFirst().physicallyReady(),"loaded target revalidation is a separate unfinished integration");
        assertEquals(id,RoadAdapterState.decode(source.gates().getFirst().gateId()));
    }
}
