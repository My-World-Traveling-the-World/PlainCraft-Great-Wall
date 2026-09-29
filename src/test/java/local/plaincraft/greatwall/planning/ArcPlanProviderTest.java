package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class ArcPlanProviderTest {
    private static final String H="a".repeat(64);
    private static SegmentPlan plan(long index,boolean terminal) {
        long before=index*2304*256L,after=before+2304*256L;
        var start=new Boundary(index,new Point2q(index*2048*256L,0),84,H);
        var end=new Boundary(index+1,new Point2q((index+1)*2048*256L,0),84,H);
        var g=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",index),List.of(start.point(),end.point()),List.of(0L,2304*256L),
                List.of(),terminal?Optional.of(new Terminal(index,2304*256L,TerminalKind.WORLD_LIMIT)):Optional.empty(),start,end,0,0,H);
        return new SegmentPlan(g,new PrefixSummary(index,before,Optional.empty(),H),
                new StationTable(List.of(new Station(before,start.point(),84,0),new Station(after,end.point(),84,0)),List.of(),H),List.of(),H);
    }
    @Test void resolvesArcInsteadOfAxialSegmentAndReleasesEveryLease()throws Exception {
        var reads=new ConcurrentHashMap<Long,Integer>();var closed=new ConcurrentHashMap<Long,Integer>();
        try(var worker=Executors.newSingleThreadExecutor();var source=new ArcPlanProvider(n->{
            reads.merge(n,1,Integer::sum);return new PlanLease<>(UUID.randomUUID(),CompletableFuture.completedFuture(plan(n,n==3)),
                    ()->closed.merge(n,1,Integer::sum));
        },worker);var lease=source.require(new CellKey(H,"minecraft:overworld",65))) {
            var window=lease.future().get(5,TimeUnit.SECONDS);
            assertEquals(List.of(1L),window.plans().stream().map(p->p.geometry().key().index()).toList());
            assertEquals(reads,closed);assertEquals(0,source.diagnostics().get("leases"));
        }
    }
    @Test void stopsAtPermanentTerminalEvenForDistantRequest()throws Exception {
        var reads=new ConcurrentHashMap<Long,Integer>();
        try(var worker=Executors.newSingleThreadExecutor();var source=new ArcPlanProvider(n->{
            reads.put(n,1);return new PlanLease<>(UUID.randomUUID(),CompletableFuture.completedFuture(plan(n,n==1)),()->{});
        },worker);var lease=source.require(new CellKey(H,"minecraft:overworld",1000000))) {
            assertEquals(1,lease.future().get(5,TimeUnit.SECONDS).plans().getLast().geometry().terminal().orElseThrow().segment());
            assertEquals(Set.of(0L,1L),reads.keySet());
        }
    }
    @Test void closeAndExecutorRejectionCompleteRequests()throws Exception {
        try(var worker=Executors.newSingleThreadExecutor()) {
            var source=new ArcPlanProvider(n->new PlanLease<>(UUID.randomUUID(),new CompletableFuture<>(),()->{}),worker);
            var request=source.require(new CellKey(H,"minecraft:overworld",4));source.close();
            assertThrows(ExecutionException.class,()->request.future().get(5,TimeUnit.SECONDS));
            assertEquals(0,source.diagnostics().get("leases"));request.close();
        }
        try(var source=new ArcPlanProvider(n->new PlanLease<>(UUID.randomUUID(),CompletableFuture.completedFuture(plan(n,true)),()->{}),
                r->{throw new RejectedExecutionException("fixture");});var request=source.require(new CellKey(H,"minecraft:overworld",4))) {
            assertThrows(ExecutionException.class,()->request.future().get(5,TimeUnit.SECONDS));
            assertEquals(0,source.diagnostics().get("leases"));
        }
    }
}
