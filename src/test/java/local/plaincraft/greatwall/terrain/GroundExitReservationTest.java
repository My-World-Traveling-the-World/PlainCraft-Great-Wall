package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import local.plaincraft.greatwall.diagnostics.AllocationBudget;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.GroundExitCodec;
import local.plaincraft.greatwall.terrain.GroundExitDecision.*;
import org.junit.jupiter.api.Test;

class GroundExitReservationTest {
    private static final String H="a".repeat(64),T="b".repeat(64);
    private static final Exit ROOT=new Exit(new SiteId("reservation",SiteKind.FORT,0),0,new ArcInterval(0,24L*256),H,T);
    private static CellKey cell(long n){return new CellKey(H,"minecraft:overworld",n);}
    private static BreakInterval terminal(long end){return new BreakInterval(new ArcInterval(end,Long.MAX_VALUE),BreakReason.WORLD_LIMIT,BreakScope.PLANNED);}

    private static class Source implements GroundExitResolver.Source {
        final AtomicInteger persists=new AtomicInteger();
        public CompletableFuture<Slice> inspect(CellKey key){
            return CompletableFuture.completedFuture(new Slice(key,key.arcCell()==0?List.of(ROOT):List.of(),List.of(),
                    Optional.of(terminal(480L*256)),T));
        }
        public CompletableFuture<CapVote> cap(Key key,Edge edge){return CompletableFuture.completedFuture(new CapVote(Optional.empty(),T));}
        public CompletableFuture<Void> persist(GroundExitDecision decision){persists.incrementAndGet();return CompletableFuture.completedFuture(null);}
    }

    /** The list defines 65,536 disjoint ranges, but reading/copying any element is observable. */
    private static final class DisjointGaps extends AbstractList<BreakInterval> {
        final AtomicInteger reads=new AtomicInteger();
        @Override public int size(){return 65536;}
        @Override public BreakInterval get(int index){
            Objects.checkIndex(index,size());reads.incrementAndGet();
            return new BreakInterval(new ArcInterval(index*2L,index*2L+1),BreakReason.SLOPE,BreakScope.REALIZED);
        }
    }

    @Test void sixtyFiveThousandDisjointGapsRejectBeforeAnyCopyOrSortRead(){
        var gaps=new DisjointGaps();
        try(var allocations=new AllocationBudget(8L<<20)){
            var failure=assertThrows(WallFailure.class,()->GroundExitResolver.merge(gaps,allocations));
            assertEquals(FailureCode.CAPACITY,failure.code());
            assertEquals(0,gaps.reads.get(),"a post-copy size check is too late");
            assertEquals(0,allocations.stats().used());assertEquals(0,allocations.stats().peak());
        }
        var failure=assertThrows(WallFailure.class,()->GroundExitResolver.merge(gaps));
        assertEquals(FailureCode.CAPACITY,failure.code());assertEquals(0,gaps.reads.get(),"old public signature must also preflight");
    }

    @Test void actualGatherCallbacksRejectTheDenseEightCellDomainWithoutPublishing() throws Exception {
        var pages=new ArrayList<List<BreakInterval>>();
        for(int c=0;c<8;c++){
            var gaps=new ArrayList<BreakInterval>();
            for(int n=0;n<8192;n++){
                long from=c*GroundExitDecision.CELL_Q8+2L*n;
                gaps.add(new BreakInterval(new ArcInterval(from,from+1),BreakReason.SLOPE,BreakScope.REALIZED));
            }
            pages.add(List.copyOf(gaps));
        }
        var source=new Source(){@Override public CompletableFuture<Slice> inspect(CellKey key){
            return CompletableFuture.completedFuture(new Slice(key,key.arcCell()==0?List.of(ROOT):List.of(),
                    pages.get(Math.toIntExact(key.arcCell())),Optional.of(terminal(8*GroundExitDecision.CELL_Q8-1)),T));
        }};
        try(var parent=new AllocationBudget(32L<<20);var worker=Executors.newSingleThreadExecutor();
                var resolver=new GroundExitResolver(source,worker,8L<<20,()->parent)){
            assertFailure(FailureCode.CAPACITY,resolver.require(cell(7)));
            assertEquals(0,source.persists.get());assertEquals(0,resolver.diagnostics().get("temporary-bytes"));
            assertTrue(resolver.diagnostics().get("temporary-peak")<=resolver.diagnostics().get("temporary-limit"));
            assertTrue(resolver.diagnostics().get("summary-bytes")<=resolver.diagnostics().get("cache-limit"));
            assertEquals(resolver.diagnostics().get("summary-bytes").longValue(),parent.stats().used());
            resolver.close();assertReleased(resolver,parent);
        }
    }

    @Test void asynchronousCallbackCannotEscapeAnExhaustedCompiledArena() throws Exception {
        var delayed=new CompletableFuture<Slice>();
        var source=new Source(){@Override public CompletableFuture<Slice> inspect(CellKey key){return delayed;}};
        try(var parent=new AllocationBudget(4096);var worker=Executors.newSingleThreadExecutor();
                var resolver=new GroundExitResolver(source,worker,64L<<10,()->parent)){
            var pending=resolver.require(cell(0));
            try(var occupied=parent.reserve(parent.stats().limit()-parent.stats().used())){
                delayed.complete(new Source().inspect(cell(0)).getNow(null));
                assertFailure(FailureCode.CAPACITY,pending);
                assertEquals(occupied.bytes(),parent.stats().used(),"callback charges must not use an unrelated local-only arena");
                assertEquals(0,resolver.diagnostics().get("temporary-bytes"));assertEquals(0,source.persists.get());
            }
            resolver.close();assertReleased(resolver,parent);
        }
    }

    @Test void pausedVoteKeepsTemporaryAndCacheChargesUntilClose() throws Exception {
        var entered=new CompletableFuture<Void>();var vote=new CompletableFuture<CapVote>();
        var source=new Source(){@Override public CompletableFuture<CapVote> cap(Key key,Edge edge){entered.complete(null);return vote;}};
        try(var parent=new AllocationBudget(1L<<20);var worker=Executors.newSingleThreadExecutor();
                var resolver=new GroundExitResolver(source,worker,1L<<20,()->parent)){
            var pending=resolver.require(cell(7));entered.get(10,TimeUnit.SECONDS);
            var stats=resolver.diagnostics();
            assertTrue(stats.get("temporary-bytes")>0);assertTrue(stats.get("summary-bytes")>0);
            assertEquals(stats.get("temporary-bytes").longValue()+stats.get("summary-bytes"),parent.stats().used());
            resolver.close();assertFailure(FailureCode.CLOSED,pending);
            assertFalse(resolver.drained().isDone(),"a pending vote still owns the schedule");
            assertTrue(parent.stats().used()>0);
            vote.complete(new CapVote(Optional.empty(),T));
            resolver.drained().get(10,TimeUnit.SECONDS);
            assertEquals(0,source.persists.get());assertReleased(resolver,parent);
        }
    }

    @Test void completedOutputsStayChargedAndBothConstructorFormsKeepCanonicalOrder() throws Exception {
        for(boolean accepted:List.of(false,true)){
            byte[] expected=null;
            for(boolean accounted:List.of(false,true))for(var order:List.of(
                    List.of(0,1,2,3,4,5,6,7),List.of(7,6,5,4,3,2,1,0),List.of(5,2,7,0,3,6,1,4))){
                var source=new Source(){@Override public CompletableFuture<CapVote> cap(Key key,Edge edge){
                    if(!accepted)return super.cap(key,edge);
                    var site=new SiteId("reservation",SiteKind.END_CAP,edge.sQ8()*2);
                    return CompletableFuture.completedFuture(new CapVote(Optional.of(new Cap(site,edge,edge.sQ8()-4*256,H)),T));
                }};
                try(var parent=new AllocationBudget(1L<<20);var worker=Executors.newSingleThreadExecutor();
                        var resolver=accounted?new GroundExitResolver(source,worker,1L<<20,()->parent)
                                :new GroundExitResolver(source,worker,1L<<20)){
                    for(int n:order){
                        var bytes=GroundExitCodec.encode(resolver.require(cell(n)).get(10,TimeUnit.SECONDS).getFirst());
                        if(expected==null)expected=bytes;else assertArrayEquals(expected,bytes);
                    }
                    var stats=resolver.diagnostics();
                    assertTrue(stats.get("temporary-bytes")>0,"returned output lists remain owned until resolver/owner close");
                    assertTrue(stats.get("summary-bytes")<=stats.get("cache-limit"));
                    assertTrue(stats.get("summaries")<=256);assertTrue(stats.get("sealed")<=256);
                    assertEquals(accounted?stats.get("temporary-bytes").longValue()+stats.get("summary-bytes"):0,parent.stats().used());
                    resolver.close();assertReleased(resolver,parent);
                }
            }
        }
    }

    @Test void aCachedResolverCannotMigrateToTheNextOwnerArena() throws Exception {
        try(var first=new AllocationBudget(1L<<20);var next=new AllocationBudget(1L<<20);var worker=Executors.newSingleThreadExecutor()){
            var arena=new AtomicReference<>(first);var source=new Source();
            try(var resolver=new GroundExitResolver(source,worker,1L<<20,arena::get)){
                resolver.require(cell(0)).get(10,TimeUnit.SECONDS);
                long held=first.stats().used();assertTrue(held>0);
                arena.set(next);
                assertFailure(FailureCode.CLOSED,resolver.require(cell(1)));
                assertEquals(held,first.stats().used(),"old cache must not become an uncharged cross-owner object graph");
                assertEquals(0,next.stats().used());
                resolver.close();assertReleased(resolver,first);assertEquals(0,next.stats().used());
            }
        }
    }

    @Test void rejectedExecutorDoesNotLeakPreallocatedWork() throws Exception {
        try(var parent=new AllocationBudget(1L<<20);
                var resolver=new GroundExitResolver(new Source(),r->{throw new RejectedExecutionException("fixture");},1L<<20,()->parent)){
            for(int n=0;n<20;n++){
                assertThrows(ExecutionException.class,()->resolver.require(cell(0)).get(10,TimeUnit.SECONDS));
                assertEquals(0,parent.stats().used());assertEquals(0,resolver.diagnostics().get("temporary-bytes"));
                assertEquals(0,resolver.diagnostics().get("requests"));assertEquals(0,resolver.diagnostics().get("admitted"));
            }
            resolver.close();assertReleased(resolver,parent);
        }
    }

    @Test void failedPersistenceReleasesUnpublishedDecisionAndCopyReservations() throws Exception {
        var source=new Source(){@Override public CompletableFuture<Void> persist(GroundExitDecision d){return CompletableFuture.failedFuture(new IOException("fixture"));}};
        try(var parent=new AllocationBudget(1L<<20);var worker=Executors.newSingleThreadExecutor();
                var resolver=new GroundExitResolver(source,worker,1L<<20,()->parent)){
            var failure=assertThrows(ExecutionException.class,()->resolver.require(cell(7)).get(10,TimeUnit.SECONDS));
            assertInstanceOf(IOException.class,root(failure));
            assertEquals(0,resolver.diagnostics().get("temporary-bytes"));
            assertEquals(resolver.diagnostics().get("summary-bytes").longValue(),parent.stats().used());
            resolver.close();
            // 结果的副本可以先唤醒等待者；同一执行器上的哨兵等发布线程跑完移表和归还准入回调。
            worker.submit(() -> {}).get(10,TimeUnit.SECONDS);
            resolver.drained().get(10,TimeUnit.SECONDS);
            assertReleased(resolver,parent);
        }
    }

    @Test void localSubdomainsNeverIncreaseTheEightMiBSummaryAllowance(){
        try(var resolver=new GroundExitResolver(new Source(),Runnable::run,Long.MAX_VALUE)){
            var stats=resolver.diagnostics();
            assertEquals(4<<20,stats.get("cache-limit"));assertEquals(4<<20,stats.get("temporary-limit"));
            assertEquals(8<<20,stats.get("cache-limit")+stats.get("temporary-limit"));
        }
    }

    @Test void closeKeepsPinsWhileEnteredPersistenceStillOwnsTheDecision() throws Exception {
        var entered=new CompletableFuture<Void>();var resume=new CountDownLatch(1);
        var source=new Source(){@Override public CompletableFuture<Void> persist(GroundExitDecision decision){
            entered.complete(null);
            try {if(!resume.await(10,TimeUnit.SECONDS))throw new AssertionError("fixture did not resume");}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
            assertNotNull(decision.hash());
            return CompletableFuture.completedFuture(null);
        }};
        try(var parent=new AllocationBudget(1L<<20);var worker=Executors.newSingleThreadExecutor();
                var resolver=new GroundExitResolver(source,worker,1L<<20,()->parent)){
            var pending=resolver.require(cell(7));entered.get(10,TimeUnit.SECONDS);
            long held=parent.stats().used();assertTrue(held>0);
            try {
                resolver.close();assertFailure(FailureCode.CLOSED,pending);
                // Only the now-finished request's 512-byte control may be released; the section is still entered.
                assertEquals(held-512,parent.stats().used(),"public failure must retain every entered section allocation");
                assertTrue(resolver.diagnostics().get("temporary-bytes")>0);
                assertFalse(resolver.drained().isDone());
            } finally {resume.countDown();}
            resolver.drained().get(10,TimeUnit.SECONDS);assertReleased(resolver,parent);
        }
    }

    @Test void cancellationOfDrainViewCannotReleaseOrCancelTheProducer() throws Exception {
        var entered=new CompletableFuture<Void>();var vote=new CompletableFuture<CapVote>();
        var source=new Source(){@Override public CompletableFuture<CapVote> cap(Key key,Edge edge){entered.complete(null);return vote;}};
        try(var parent=new AllocationBudget(1L<<20);var worker=Executors.newSingleThreadExecutor();
                var resolver=new GroundExitResolver(source,worker,1L<<20,()->parent)){
            var pending=resolver.require(cell(7));entered.get(10,TimeUnit.SECONDS);
            var abandoned=resolver.drained();assertTrue(abandoned.cancel(false));
            resolver.close();assertFailure(FailureCode.CLOSED,pending);
            assertFalse(resolver.drained().isDone());assertTrue(parent.stats().used()>0);
            vote.completeExceptionally(new IOException("delayed failure"));
            resolver.drained().get(10,TimeUnit.SECONDS);assertReleased(resolver,parent);
        }
    }

    private static Throwable root(Throwable error){
        while((error instanceof ExecutionException||error instanceof CompletionException)&&error.getCause()!=null)error=error.getCause();
        return error;
    }
    private static void assertFailure(FailureCode code,CompletableFuture<?> future){
        var failure=assertThrows(ExecutionException.class,()->future.get(30,TimeUnit.SECONDS));
        assertEquals(code,assertInstanceOf(WallFailure.class,root(failure)).code());
    }
    private static void assertReleased(GroundExitResolver resolver,AllocationBudget parent){
        var stats=resolver.diagnostics();
        assertEquals(0,stats.get("summary-bytes"));assertEquals(0,stats.get("temporary-bytes"));
        assertEquals(0,stats.get("summaries"));assertEquals(0,stats.get("sealed"));
        assertEquals(0,stats.get("requests"));assertEquals(0,stats.get("sections"));assertEquals(0,stats.get("admitted"));
        assertEquals(0,parent.stats().used());
    }
}
