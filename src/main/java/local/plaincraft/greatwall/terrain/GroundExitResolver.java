package local.plaincraft.greatwall.terrain;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.GroundExitCodec;
import local.plaincraft.greatwall.terrain.GroundExitDecision.*;

/** Lock-external asynchronous resolution. Accepted exits delimit stable, closed dependency intervals. */
public final class GroundExitResolver implements AutoCloseable {
    public interface Source {
        CompletableFuture<Slice> inspect(CellKey cell);
        CompletableFuture<CapVote> cap(Key key, Edge edge);
        CompletableFuture<Void> persist(GroundExitDecision decision);
    }
    private record Right(Optional<Exit> exit, Optional<BreakInterval> terminal) {
        long s() { return exit.map(Exit::sQ8).orElseGet(()->terminal.orElseThrow().range().fromQ8()); }
    }
    private record Evidence(List<BreakInterval> gaps, String hash) {
        Evidence { gaps=List.copyOf(gaps); }
    }
    private record Resolved(List<BreakInterval> gaps,List<ArcInterval> exclusions,List<Cap> caps,String hash) {
        Resolved { gaps=List.copyOf(gaps);exclusions=List.copyOf(exclusions);caps=List.copyOf(caps); }
    }
    private final Source source;
    private final Executor worker;
    private final long summaryBytes;
    private final ConcurrentHashMap<CellKey,CompletableFuture<List<GroundExitDecision>>> requests=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key,CompletableFuture<GroundExitDecision>> sections=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CellKey,Slice> summaries=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Integer> state=new ConcurrentHashMap<>();

    public GroundExitResolver(Source source,Executor worker,long summaryBytes) {
        this.source=Objects.requireNonNull(source);this.worker=Objects.requireNonNull(worker);
        if(summaryBytes<1024)throw new IllegalArgumentException("summary budget must be at least 1024 bytes");
        this.summaryBytes=summaryBytes;
    }
    /** The returned view is independently cancellable; no caller owns the shared computation. */
    public CompletableFuture<List<GroundExitDecision>> require(CellKey cell) {
        CompletableFuture<List<GroundExitDecision>> promise=null;
        try {
            active();if(cell.arcCell()<0)throw new IllegalArgumentException("negative cell");
            var prior=requests.get(cell);if(prior!=null)return prior.copy();
            state.compute("admitted",(k,n)->{int old=n==null?0:n;if(old>=32)throw fail(FailureCode.CAPACITY,"exit waiter budget");return old+1;});
            promise=new CompletableFuture<>();prior=requests.putIfAbsent(cell,promise);
            if(prior!=null){release();return prior.copy();}
            var owned=promise;
            promise.whenComplete((v,e)->{requests.remove(cell,owned);release();});
            long from=Math.multiplyExact(cell.arcCell(),GroundExitDecision.CELL_Q8),to=Math.addExact(from,GroundExitDecision.CELL_Q8);
            left(cell,from).thenComposeAsync(start->cover(cell,start,to,List.of()),worker)
                    .whenComplete((v,e)->complete(owned,v,e));
            return promise.copy();
        } catch(Throwable error) {
            if(promise!=null)promise.completeExceptionally(error);
            return CompletableFuture.failedFuture(error);
        }
    }
    private CompletableFuture<Exit> left(CellKey cell,long before) {
        var result=new CompletableFuture<Exit>();scanLeft(cell,before,result);return result;
    }
    private void scanLeft(CellKey cell,long before,CompletableFuture<Exit> result) {
        if(result.isDone())return;
        try {inspect(cell).thenAcceptAsync(slice->{
            var exit=slice.exits().stream().filter(e->e.sQ8()<=before).max(Comparator.comparingLong(Exit::sQ8));
            if(exit.isPresent()){result.complete(exit.get());return;}
            if(cell.arcCell()==0)throw fail(FailureCode.ROOT_NOT_FOUND,"no T0-accepted ground exit at or before requested cell");
            long previous=cell.arcCell()-1;
            if(slice.terminal().isPresent())previous=Math.min(previous,Math.floorDiv(slice.terminal().get().range().fromQ8(),GroundExitDecision.CELL_Q8));
            scanLeft(at(cell,previous),before,result);
        },worker).whenComplete((v,e)->{if(e!=null)result.completeExceptionally(e);});
        }catch(Throwable e){result.completeExceptionally(e);}
    }
    private CompletableFuture<Right> right(CellKey cell,long after) {
        var result=new CompletableFuture<Right>();scanRight(cell,after,result);return result;
    }
    private void scanRight(CellKey cell,long after,CompletableFuture<Right> result) {
        if(result.isDone())return;
        try {inspect(cell).thenAcceptAsync(slice->{
            var exit=slice.exits().stream().filter(e->e.sQ8()>after).min(Comparator.comparingLong(Exit::sQ8));
            if(exit.isPresent() && (slice.terminal().isEmpty()||exit.get().sQ8()<slice.terminal().get().range().fromQ8())) {
                result.complete(new Right(exit,Optional.empty()));return;
            }
            long next=Math.multiplyExact(Math.addExact(cell.arcCell(),1),GroundExitDecision.CELL_Q8);
            if(slice.terminal().isPresent()&&slice.terminal().get().range().fromQ8()<=next) {
                result.complete(new Right(Optional.empty(),slice.terminal()));return;
            }
            scanRight(at(cell,cell.arcCell()+1),after,result);
        },worker).whenComplete((v,e)->{if(e!=null)result.completeExceptionally(e);});
        }catch(Throwable e){result.completeExceptionally(e);}
    }
    private CompletableFuture<List<GroundExitDecision>> cover(CellKey request,Exit left,long to,List<GroundExitDecision> prior) {
        checkBytes(1024L+prior.size()*2048L);
        return right(at(request,Math.floorDiv(left.sQ8(),GroundExitDecision.CELL_Q8)),left.sQ8()).thenComposeAsync(right->{
            var key=new Key(request.recipeHash(),request.dimension(),new ArcInterval(left.sQ8(),right.exit().isPresent()?right.s():Long.MAX_VALUE));
            return section(key,left,right).thenComposeAsync(decision->{
                var result=new ArrayList<>(prior);result.add(decision);
                if(right.exit().isEmpty()||right.s()>=to)return CompletableFuture.completedFuture(List.copyOf(result));
                return cover(request,right.exit().orElseThrow(),to,List.copyOf(result));
            },worker);
        },worker);
    }
    private CompletableFuture<GroundExitDecision> section(Key key,Exit left,Right right) {
        active();var promise=new CompletableFuture<GroundExitDecision>();var old=sections.putIfAbsent(key,promise);
        if(old!=null)return old.copy();
        promise.whenComplete((v,e)->sections.remove(key,promise));
        long first=Math.floorDiv(left.sQ8(),GroundExitDecision.CELL_Q8),last=Math.floorDiv(right.s(),GroundExitDecision.CELL_Q8);
        try {
            gather(key,first,last,new Evidence(List.of(),digest("ground-exit-v1")))
                    .thenComposeAsync(e->decide(key,left,right,e),worker)
                    .thenComposeAsync(d->{active();return source.persist(d).thenApply(v->d);},worker)
                    .whenComplete((v,e)->complete(promise,v,e));
        }catch(Throwable error){promise.completeExceptionally(error);}
        return promise.copy();
    }
    private CompletableFuture<Evidence> gather(Key key,long cell,long last,Evidence prior) {
        var result=new CompletableFuture<Evidence>();gatherNext(key,cell,last,prior,result);return result;
    }
    private void gatherNext(Key key,long cell,long last,Evidence prior,CompletableFuture<Evidence> result) {
        if(result.isDone())return;
        try {inspect(new CellKey(key.recipeHash(),key.dimension(),cell)).thenAcceptAsync(slice->{
            var gaps=new ArrayList<>(prior.gaps());gaps.addAll(slice.breaks());
            var merged=merge(gaps);checkBytes(1024L+merged.size()*128L);
            var next=new Evidence(merged,digest(prior.hash()+"\n"+cell+"\n"+slice.t0Hash()));
            if(cell==last)result.complete(next);else gatherNext(key,cell+1,last,next,result);
        },worker).whenComplete((v,e)->{if(e!=null)result.completeExceptionally(e);});
        }catch(Throwable e){result.completeExceptionally(e);}
    }
    private CompletableFuture<GroundExitDecision> decide(Key key,Exit left,Right right,Evidence evidence) {
        long lo=left.footprint().toQ8(),hi=right.exit().map(e->e.footprint().fromQ8()).orElse(right.s());
        if(hi<lo)throw fail(FailureCode.OWNERSHIP_CONFLICT,"accepted ground exits overlap");
        var gaps=new ArrayList<BreakInterval>();
        for(var b:evidence.gaps()) {
            long a=Math.max(lo,b.range().fromQ8()),z=Math.min(hi,b.range().toQ8());
            if(a<z)gaps.add(new BreakInterval(new ArcInterval(a,z),b.reason(),b.scope()));
        }
        gaps=new ArrayList<>(merge(gaps));
        var runs=new ArrayList<ArcInterval>();long cursor=lo;
        for(var b:gaps){if(cursor<b.range().fromQ8())runs.add(new ArcInterval(cursor,b.range().fromQ8()));cursor=b.range().toQ8();}
        if(cursor<hi)runs.add(new ArcInterval(cursor,hi));
        String evidenceHash=digest(evidence.hash()+"\n"+left+"\n"+right);
        CompletableFuture<Resolved> chain=CompletableFuture.completedFuture(new Resolved(gaps,List.of(),List.of(),evidenceHash));
        for(var run:runs)chain=chain.thenComposeAsync(prior->run(key,run,run.fromQ8()==lo,
                run.toQ8()==hi&&right.exit().isPresent(),prior),worker);
        return chain.thenApplyAsync(result->{
            var all=new ArrayList<>(result.gaps());right.terminal().ifPresent(all::add);
            var value=new GroundExitDecision(key,left,right.exit(),right.exit().isPresent()?Long.MAX_VALUE:right.s(),
                    merge(all),result.exclusions(),result.caps(),result.hash(),"0".repeat(64));
            checkBytes(1024L+value.breaks().size()*128L+value.exclusions().size()*64L+value.caps().size()*512L);
            return GroundExitCodec.hashed(value);
        },worker);
    }
    private CompletableFuture<Resolved> run(Key key,ArcInterval run,boolean groundedLeft,boolean groundedRight,Resolved prior) {
        var empty=new CapVote(Optional.empty(),digest("accepted-endpoint"));
        var first=groundedLeft?CompletableFuture.completedFuture(empty):vote(key,new Edge(run.fromQ8(),true,run));
        return first.thenComposeAsync(left->(groundedRight?CompletableFuture.completedFuture(empty):vote(key,new Edge(run.toQ8(),false,run)))
                .thenApplyAsync(right->{
                    var gaps=new ArrayList<>(prior.gaps());var exclusions=new ArrayList<>(prior.exclusions());var caps=new ArrayList<>(prior.caps());
                    boolean l=groundedLeft||left.cap().isPresent(),r=groundedRight||right.cap().isPresent();
                    long keepFrom=left.cap().map(c->c.footprint().toQ8()).orElse(run.fromQ8());
                    long keepTo=right.cap().map(c->c.footprint().fromQ8()).orElse(run.toQ8());
                    if(keepFrom>keepTo) { l=false;r=false; }
                    if(l&&r) {
                        left.cap().ifPresent(c->{caps.add(c);exclusions.add(new ArcInterval(run.fromQ8(),c.footprint().toQ8()));});
                        right.cap().ifPresent(c->{caps.add(c);exclusions.add(new ArcInterval(c.footprint().fromQ8(),run.toQ8()));});
                    } else {
                        // Withdraw before APPLY, retaining only an already-proven ground-exit footprint.
                        long cutFrom=run.fromQ8(),cutTo=run.toQ8();
                        if(l&&left.cap().isPresent()) {var c=left.cap().get();caps.add(c);cutFrom=c.footprint().toQ8();}
                        if(r&&right.cap().isPresent()) {var c=right.cap().get();caps.add(c);cutTo=c.footprint().fromQ8();}
                        exclusions.add(run);
                        if(cutFrom<cutTo)gaps.add(new BreakInterval(new ArcInterval(cutFrom,cutTo),BreakReason.NO_SUPPORT,BreakScope.REALIZED));
                    }
                    return new Resolved(merge(gaps),exclusions,caps,digest(prior.hash()+"\n"+run+"\n"+left.t0Hash()+"\n"+right.t0Hash()));
                },worker),worker);
    }
    private CompletableFuture<CapVote> vote(Key key,Edge edge) {
        active();return source.cap(key,edge).thenApplyAsync(v->{
            active();v.cap().ifPresent(c->{if(!c.edge().equals(edge))throw fail(FailureCode.T0_CORRUPT,"cap voted for another edge");});return v;
        },worker);
    }
    private CompletableFuture<Slice> inspect(CellKey cell) {
        active();var known=summaries.get(cell);if(known!=null)return CompletableFuture.completedFuture(known);
        return source.inspect(cell).thenApplyAsync(s->{
            active();if(!s.cell().equals(cell))throw fail(FailureCode.RECIPE_MISMATCH,"foreign exit slice");
            int bytes=Math.toIntExact(1024L+s.exits().size()*1024L+s.breaks().size()*128L);
            state.compute("summary-bytes",(k,n)->{
                int used=n==null?0:n;
                if(!state.containsKey("closed")&&(long)used+bytes<=Math.min(summaryBytes/2,4L<<20)&&summaries.size()<256){
                    var prior=summaries.putIfAbsent(cell,s);
                    if(prior==null)return used+bytes;
                    if(!prior.equals(s))throw fail(FailureCode.T0_CORRUPT,"same cell has two exit surveys");
                }
                return used;
            });
            return s;
        },worker);
    }
    public static List<BreakInterval> merge(List<BreakInterval> values) {
        var sorted=values.stream().sorted(Comparator.comparingLong((BreakInterval b)->b.range().fromQ8()).thenComparing(b->b.reason().name())).toList();
        var result=new ArrayList<BreakInterval>();
        for(var b:sorted) {
            if(result.isEmpty()||result.getLast().range().toQ8()<b.range().fromQ8())result.add(b);
            else {var old=result.removeLast();var reason=b.range().toQ8()==Long.MAX_VALUE?b:old;
                result.add(new BreakInterval(new ArcInterval(old.range().fromQ8(),Math.max(old.range().toQ8(),b.range().toQ8())),reason.reason(),reason.scope()));}
        }
        return List.copyOf(result);
    }
    private void checkBytes(long bytes){if(bytes>summaryBytes)throw fail(FailureCode.CAPACITY,"exit summary byte budget");}
    private void active(){if(state.containsKey("closed"))throw fail(FailureCode.CLOSED,"exit resolver closed");}
    private void release(){state.compute("admitted",(k,n)->n-1);}
    private <T> void complete(CompletableFuture<T> promise,T value,Throwable error){
        if(error!=null)promise.completeExceptionally(error);
        else try{active();promise.complete(value);}catch(Throwable stopped){promise.completeExceptionally(stopped);}
    }
    private static CellKey at(CellKey key,long cell){return new CellKey(key.recipeHash(),key.dimension(),cell);}
    private static String digest(String s){return DeterministicHash.sha256(s.getBytes(StandardCharsets.UTF_8));}
    private static WallFailure fail(FailureCode code,String message){return new WallFailure(code,message);}
    public Map<String,Integer> diagnostics(){return Map.of("requests",requests.size(),"sections",sections.size(),"admitted",state.getOrDefault("admitted",0),
            "summary-bytes",state.getOrDefault("summary-bytes",0),"summaries",summaries.size());}
    @Override public void close(){
        if(state.putIfAbsent("closed",1)!=null)return;
        var failure=fail(FailureCode.CLOSED,"exit resolver closed");
        requests.values().forEach(f->f.completeExceptionally(failure));sections.values().forEach(f->f.completeExceptionally(failure));
        state.compute("summary-bytes",(k,n)->{summaries.clear();return 0;});
    }
}
