package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.GroundExitCodec;
import local.plaincraft.greatwall.terrain.GroundExitDecision.*;
import org.junit.jupiter.api.Test;

class GroundExitResolverTest {
    private static final String H="a".repeat(64),T="b".repeat(64);
    private static CellKey cell(long n){return new CellKey(H,"minecraft:overworld",n);}
    private static final Exit ROOT=new Exit(new SiteId("fixture",SiteKind.FORT,0),0,new ArcInterval(0,24*256L),H,T);
    private static class Source implements GroundExitResolver.Source {
        final ConcurrentHashMap<Key,GroundExitDecision> saved=new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long,Integer> reads=new ConcurrentHashMap<>();
        public CompletableFuture<Slice> inspect(CellKey key){
            reads.merge(key.arcCell(),1,Integer::sum);
            return CompletableFuture.completedFuture(new Slice(key,key.arcCell()==0?List.of(ROOT):List.of(),List.of(),
                    Optional.of(new BreakInterval(new ArcInterval(480*256L,Long.MAX_VALUE),BreakReason.WORLD_LIMIT,BreakScope.PLANNED)),T));
        }
        public CompletableFuture<CapVote> cap(Key key,Edge edge){return CompletableFuture.completedFuture(new CapVote(Optional.empty(),T));}
        public CompletableFuture<Void> persist(GroundExitDecision value){
            saved.compute(value.key(),(k,old)->{if(old!=null&&!old.equals(value))throw new AssertionError("opposite decision published");return value;});
            return CompletableFuture.completedFuture(null);
        }
    }
    @Test void earlyAndTerminalConsumersShareCanonicalFallbackBeforePublication() throws Exception {
        try(var worker=Executors.newFixedThreadPool(3);var resolver=new GroundExitResolver(new Source(),worker,1<<20)){
            var early=resolver.require(cell(2));var late=resolver.require(cell(7));
            var a=early.get(10,TimeUnit.SECONDS).getFirst();var b=late.get(10,TimeUnit.SECONDS).getFirst();
            assertEquals(a,b);assertEquals(ROOT,a.left());assertEquals(480*256L,a.terminalSQ8());
            assertEquals(new ArcInterval(24*256L,Long.MAX_VALUE),a.breaks().getFirst().range());
            assertEquals(BreakReason.WORLD_LIMIT,a.breaks().getFirst().reason());assertTrue(a.caps().isEmpty());
            assertEquals(a,GroundExitCodec.decode(GroundExitCodec.encode(a)));
        }
    }
    @Test void forwardReverseRandomColdAndWarmRequestsProduceIdenticalBytes() throws Exception {
        byte[] expected=null;
        for(var order:List.of(List.of(0,1,2,3,4,5,6,7),List.of(7,6,5,4,3,2,1,0),List.of(5,2,7,0,3,6,1,4))){
            var source=new Source();
            try(var worker=Executors.newSingleThreadExecutor();var resolver=new GroundExitResolver(source,worker,1<<20)){
                for(int n:order){var bytes=GroundExitCodec.encode(resolver.require(cell(n)).get(10,TimeUnit.SECONDS).getFirst());
                    if(expected==null)expected=bytes;else assertArrayEquals(expected,bytes);}
                assertEquals(1,source.saved.size());
            }
        }
    }
    @Test void pausedRemoteEvidenceBlocksEverySubscriberAndCancellationIsLocal() throws Exception {
        var gate=new CompletableFuture<Slice>();var waiting=new CompletableFuture<Void>();
        var source=new Source(){@Override public CompletableFuture<Slice> inspect(CellKey key){
            if(key.arcCell()==7){waiting.complete(null);return gate;}
            return super.inspect(key);
        }};
        try(var worker=Executors.newSingleThreadExecutor();var resolver=new GroundExitResolver(source,worker,1<<20)){
            var early=resolver.require(cell(2));var second=resolver.require(cell(2));var late=resolver.require(cell(7));
            waiting.get(10,TimeUnit.SECONDS);assertFalse(early.isDone());assertFalse(late.isDone());assertTrue(source.saved.isEmpty());
            second.cancel(false);gate.complete(new Source().inspect(cell(7)).get());
            assertEquals(early.get(10,TimeUnit.SECONDS),late.get(10,TimeUnit.SECONDS));assertTrue(second.isCancelled());
        }
    }
    @Test void acceptedIntermediateGroundExitInsulatesEarlierPublishedInterval() throws Exception {
        var middle=new Exit(new SiteId("fixture",SiteKind.GATE,1),192*256L,new ArcInterval(162*256L,222*256L),H,T);
        var source=new Source(){@Override public CompletableFuture<Slice> inspect(CellKey k){
            return super.inspect(k).thenApply(s->k.arcCell()==3?new Slice(k,List.of(middle),s.breaks(),s.terminal(),s.t0Hash()):s);
        }};
        try(var worker=Executors.newSingleThreadExecutor();var resolver=new GroundExitResolver(source,worker,1<<20)){
            var first=resolver.require(cell(1)).get(10,TimeUnit.SECONDS).getFirst();
            var last=resolver.require(cell(7)).get(10,TimeUnit.SECONDS).getFirst();
            assertTrue(first.breaks().isEmpty());assertEquals(middle,first.right().orElseThrow());
            assertEquals(middle,last.left());assertEquals(222*256L,last.breaks().getFirst().range().fromQ8());
            assertEquals(first,resolver.require(cell(1)).get(10,TimeUnit.SECONDS).getFirst());
        }
    }
    @Test void missingEvidenceDoesNotBecomeARejectedRampOrPublishACut() throws Exception {
        var source=new Source(){@Override public CompletableFuture<CapVote> cap(Key k,Edge e){
            return CompletableFuture.failedFuture(new WallFailure(FailureCode.T0_MISSING,"missing earlier page"));
        }};
        try(var worker=Executors.newSingleThreadExecutor();var resolver=new GroundExitResolver(source,worker,1<<20)){
            var error=assertThrows(ExecutionException.class,()->resolver.require(cell(7)).get(10,TimeUnit.SECONDS));
            assertInstanceOf(WallFailure.class,error.getCause());assertTrue(source.saved.isEmpty());
            assertEquals(0,resolver.diagnostics().get("requests"));assertEquals(0,resolver.diagnostics().get("admitted"));
        }
    }
    @Test void closedAndRejectedExecutorReleaseAllFlights() throws Exception {
        var source=new Source(){@Override public CompletableFuture<Slice> inspect(CellKey k){return new CompletableFuture<>();}};
        try(var worker=Executors.newSingleThreadExecutor()){
            var resolver=new GroundExitResolver(source,worker,1<<20);var pending=resolver.require(cell(2));resolver.close();
            assertThrows(ExecutionException.class,()->pending.get(10,TimeUnit.SECONDS));assertEquals(0,resolver.diagnostics().get("admitted"));
        }
        try(var resolver=new GroundExitResolver(new Source(),r->{throw new RejectedExecutionException("fixture");},1<<20)){
            for(int n=0;n<100;n++)assertThrows(ExecutionException.class,()->resolver.require(cell(2)).get(10,TimeUnit.SECONDS));
            assertEquals(0,resolver.diagnostics().get("admitted"));assertEquals(0,resolver.diagnostics().get("requests"));
        }
    }
    @Test void codecRejectsTruncationTrailingBytesAndUnknownVersion() throws Exception {
        try(var worker=Executors.newSingleThreadExecutor();var resolver=new GroundExitResolver(new Source(),worker,1<<20)){
            var bytes=GroundExitCodec.encode(resolver.require(cell(7)).get(10,TimeUnit.SECONDS).getFirst());
            assertThrows(IOException.class,()->GroundExitCodec.decode(Arrays.copyOf(bytes,bytes.length-1)));
            assertThrows(IOException.class,()->GroundExitCodec.decode(Arrays.copyOf(bytes,bytes.length+1)));
            bytes[7]=3;assertThrows(IOException.class,()->GroundExitCodec.decode(bytes));
        }
    }
    @Test void distantSparseRequestStreamsSlicesWithoutUnboundedPinnedSummaries() throws Exception {
        long terminalCell=8192;
        var source=new Source(){@Override public CompletableFuture<Slice> inspect(CellKey k){
            return CompletableFuture.completedFuture(new Slice(k,k.arcCell()==0?List.of(ROOT):List.of(),List.of(),
                    Optional.of(new BreakInterval(new ArcInterval(terminalCell*GroundExitDecision.CELL_Q8+32*256,Long.MAX_VALUE),
                            BreakReason.WORLD_LIMIT,BreakScope.PLANNED)),T));
        }};
        try(var worker=Executors.newSingleThreadExecutor();var resolver=new GroundExitResolver(source,worker,64*1024)){
            var d=resolver.require(cell(8000)).get(30,TimeUnit.SECONDS).getFirst();
            assertEquals(24*256L,d.breaks().getFirst().range().fromQ8());
            assertTrue(resolver.diagnostics().get("summary-bytes")<=32*1024);
            assertTrue(resolver.diagnostics().get("summaries")<=256);
            resolver.close();assertEquals(0,resolver.diagnostics().get("summary-bytes"));
        }
    }
    @Test void identityIncludesRemoteT0AndAcceptedCapSiteAndFinalCut() throws Exception {
        GroundExitDecision first;
        try(var worker=Executors.newSingleThreadExecutor();var resolver=new GroundExitResolver(new Source(),worker,1<<20)){
            first=resolver.require(cell(2)).get(10,TimeUnit.SECONDS).getFirst();
        }
        var changed=new Source(){@Override public CompletableFuture<Slice> inspect(CellKey k){
            return super.inspect(k).thenApply(s->new Slice(s.cell(),s.exits(),s.breaks(),s.terminal(),H));
        }};
        try(var worker=Executors.newSingleThreadExecutor();var resolver=new GroundExitResolver(changed,worker,1<<20)){
            var second=resolver.require(cell(2)).get(10,TimeUnit.SECONDS).getFirst();
            assertEquals(first.breaks(),second.breaks());assertNotEquals(first.t0Hash(),second.t0Hash());assertNotEquals(first.hash(),second.hash());
        }
        var withCap=new Source(){@Override public CompletableFuture<CapVote> cap(Key k,Edge e){
            var id=new SiteId("fixture",SiteKind.END_CAP,e.sQ8()*2);
            return CompletableFuture.completedFuture(new CapVote(Optional.of(new Cap(id,e,e.sQ8()-4*256,H)),T));
        }};
        try(var worker=Executors.newSingleThreadExecutor();var resolver=new GroundExitResolver(withCap,worker,1<<20)){
            var d=resolver.require(cell(2)).get(10,TimeUnit.SECONDS).getFirst();
            assertEquals(SiteKind.END_CAP,d.caps().getFirst().site().kind());assertEquals(476*256L,d.caps().getFirst().actualSQ8());
            assertEquals(480*256L,d.breaks().getFirst().range().fromQ8());assertNotEquals(first.hash(),d.hash());
        }
    }
}
