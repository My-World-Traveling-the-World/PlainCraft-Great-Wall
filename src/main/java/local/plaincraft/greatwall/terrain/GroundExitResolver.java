package local.plaincraft.greatwall.terrain;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import local.plaincraft.greatwall.diagnostics.AllocationBudget;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.GroundExitCodec;
import local.plaincraft.greatwall.terrain.GroundExitDecision.*;

/** Lock-external asynchronous resolution. Accepted exits delimit stable, closed dependency intervals. */
public final class GroundExitResolver implements AutoCloseable {
    private static final long MAX_SUMMARY_BYTES=8L<<20;
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
    private record Schedule(List<ArcInterval> runs,Resolved initial) {}
    private record Charge(AllocationBudget.Pin local,AllocationBudget.Pin parent) implements AutoCloseable {
        @Override public void close(){try{if(parent!=null)parent.close();}finally{if(local!=null)local.close();}}
    }
    private record Cached<T>(T value,AllocationBudget.Pin parent) {
        void release(){if(parent!=null)parent.close();}
    }
    private final Source source;
    private final Executor worker;
    private final long summaryBytes;
    private final long cacheLimit;
    private final AllocationBudget temporary;
    private final Supplier<AllocationBudget> compiledArena;
    private final ConcurrentHashMap<String,AllocationBudget> parentArena=new ConcurrentHashMap<>();
    private final Set<Work> workSets=ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<CellKey,CompletableFuture<List<GroundExitDecision>>> requests=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key,CompletableFuture<GroundExitDecision>> sections=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CellKey,Cached<Slice>> summaries=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key,Cached<GroundExitDecision>> sealed=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Integer> state=new ConcurrentHashMap<>();
    private final CompletableFuture<Void> drained=new CompletableFuture<>();

    public GroundExitResolver(Source source,Executor worker,long summaryBytes) {
        this(source,worker,summaryBytes,null);
    }
    /** Production must close this resolver before its one compiled arena; caches cannot migrate between arenas. */
    public GroundExitResolver(Source source,Executor worker,long summaryBytes,Supplier<AllocationBudget> compiledArena) {
        this.source=Objects.requireNonNull(source);this.worker=Objects.requireNonNull(worker);
        if(summaryBytes<1024)throw new IllegalArgumentException("summary budget must be at least 1024 bytes");
        this.summaryBytes=Math.min(summaryBytes,MAX_SUMMARY_BYTES);
        this.cacheLimit=Math.min(this.summaryBytes/2,4L<<20);
        this.temporary=new AllocationBudget(this.summaryBytes-cacheLimit);
        this.compiledArena=compiledArena;
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
            execute(()->{
                var work=new Work();
                try {return left(cell,from).thenComposeAsync(start->cover(work,cell,start,to,List.of()),worker)
                        .whenComplete((v,e)->{if(e==null)work.finish();else work.close();});}
                catch(Throwable error){work.close();throw error;}
            }).whenComplete((v,e)->complete(owned,v,e));
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
            if(cell.arcCell()==0) {
                // The wall's anchor fort (FORT0) at s=0 is allowed to degrade when the terrain has
                // no buildable ground entrance (RealizationPlanner records FORT0_DEGRADED), which
                // leaves cell 0 with no real natural exit. s=0 is still the authoritative origin of
                // the wall, so surface a synthetic root-anchor exit: it lets cover() seal the interval
                // [0, nextExit) and, when a break exists, apply an EndCap. Without it, scanLeft throws
                // ROOT_NOT_FOUND and the whole production session fails on a normal terrain boundary.
                long extent = 24L * 256;
                String sentinelHash = digest("root-sentinel-" + cell.recipeHash() + "-" + slice.t0Hash());
                result.complete(new Exit(new SiteId(cell.recipeHash(), SiteKind.FORT, 0), 0L,
                        new ArcInterval(0L, extent), sentinelHash, slice.t0Hash()));
                return;
            }
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
    private CompletableFuture<List<GroundExitDecision>> cover(Work work,CellKey request,Exit left,long to,List<GroundExitDecision> prior) {
        return right(at(request,Math.floorDiv(left.sQ8(),GroundExitDecision.CELL_Q8)),left.sQ8()).thenComposeAsync(right->{
            var key=new Key(request.recipeHash(),request.dimension(),new ArcInterval(left.sQ8(),right.exit().isPresent()?right.s():Long.MAX_VALUE));
            return section(key,left,right).thenComposeAsync(decision->{
                long bytes=1024L+64L*(prior.size()+1L)+decisionBytes(decision);
                for(var value:prior)bytes=Math.addExact(bytes,decisionBytes(value));
                var result=work.replace("cover",bytes,()->{
                    var values=new ArrayList<>(prior);values.add(decision);return List.copyOf(values);
                });
                if(right.exit().isEmpty()||right.s()>=to)return CompletableFuture.completedFuture(result);
                return cover(work,request,right.exit().orElseThrow(),to,result);
            },worker);
        },worker);
    }
    private CompletableFuture<GroundExitDecision> section(Key key,Exit left,Right right) {
        active();var cached=sealed.get(key);
        if(cached!=null) {
            var known=cached.value();
            if(!known.left().equals(left)||!known.right().equals(right.exit())
                    ||known.terminalSQ8()!=(right.exit().isPresent()?Long.MAX_VALUE:right.s()))
                throw fail(FailureCode.T0_CORRUPT,"accepted exits changed after sealing");
            return CompletableFuture.completedFuture(known);
        }
        var promise=new CompletableFuture<GroundExitDecision>();var old=sections.putIfAbsent(key,promise);
        if(old!=null)return old.copy();
        promise.whenComplete((v,e)->sections.remove(key,promise));
        try {
            execute(()->{
                var work=new Work();
                try {
                    long first=Math.floorDiv(left.sQ8(),GroundExitDecision.CELL_Q8),last=Math.floorDiv(right.s(),GroundExitDecision.CELL_Q8);
                    var initial=work.replace("evidence",1536,()->new Evidence(List.of(),digest("ground-exit-v1")));
                    return gather(work,key,first,last,initial)
                            .thenComposeAsync(e->decide(work,key,left,right,e),worker)
                            .thenComposeAsync(d->{active();return source.persist(d).thenApply(v->{remember(d);return d;});},worker)
                            .whenComplete((v,e)->{if(e==null)work.finish();else work.close();});
                }catch(Throwable error){work.close();throw error;}
            }).whenComplete((v,e)->complete(promise,v,e));
        }catch(Throwable error){promise.completeExceptionally(error);}
        return promise.copy();
    }
    private void remember(GroundExitDecision d) {
        long bytes=decisionBytes(d);
        state.compute("summary-bytes",(k,n)->{
            int used=n==null?0:n;
            var old=sealed.get(d.key());
            if(old!=null){if(!old.value().equals(d))throw fail(FailureCode.T0_CORRUPT,"two sealed decisions for one interval");return used;}
            if(!state.containsKey("closed")&&bytes<=cacheLimit-used&&sealed.size()<256) {
                var pin=reserveParent(bytes);
                try {sealed.put(d.key(),new Cached<>(d,pin));return Math.toIntExact(used+bytes);}
                catch(Throwable failure){if(pin!=null)pin.close();throw failure;}
            }
            return used;
        });
    }
    private CompletableFuture<Evidence> gather(Work work,Key key,long cell,long last,Evidence prior) {
        var result=new CompletableFuture<Evidence>();gatherNext(work,key,cell,last,prior,result);return result;
    }
    private void gatherNext(Work work,Key key,long cell,long last,Evidence prior,CompletableFuture<Evidence> result) {
        if(result.isDone())return;
        try {inspect(new CellKey(key.recipeHash(),key.dimension(),cell)).thenAcceptAsync(slice->{
            var next=work.replace("evidence",mergeBytes((long)prior.gaps().size()+slice.breaks().size())+512,()->{
                var gaps=new ArrayList<>(prior.gaps());gaps.addAll(slice.breaks());
                return new Evidence(mergeReserved(gaps),digest(prior.hash()+"\n"+cell+"\n"+slice.t0Hash()));
            });
            if(cell==last)result.complete(next);else gatherNext(work,key,cell+1,last,next,result);
        },worker).whenComplete((v,e)->{if(e!=null)result.completeExceptionally(e);});
        }catch(Throwable e){result.completeExceptionally(e);}
    }
    private CompletableFuture<GroundExitDecision> decide(Work work,Key key,Exit left,Right right,Evidence evidence) {
        long lo=left.footprint().toQ8(),hi=right.exit().map(e->e.footprint().fromQ8()).orElse(right.s());
        if(hi<lo)throw fail(FailureCode.OWNERSHIP_CONFLICT,"accepted ground exits overlap");
        // This reservation includes clipped ranges, sort/copy scratch, retained lists and all run continuations.
        var schedule=work.replace("schedule",4096L+1024L*(evidence.gaps().size()+1L)+exitTextBytes(left,right),()->{
            var clipped=new ArrayList<BreakInterval>();
            for(var b:evidence.gaps()) {
                long a=Math.max(lo,b.range().fromQ8()),z=Math.min(hi,b.range().toQ8());
                if(a<z)clipped.add(new BreakInterval(new ArcInterval(a,z),b.reason(),b.scope()));
            }
            var gaps=mergeReserved(clipped);
            var runs=new ArrayList<ArcInterval>();long cursor=lo;
            for(var b:gaps){if(cursor<b.range().fromQ8())runs.add(new ArcInterval(cursor,b.range().fromQ8()));cursor=b.range().toQ8();}
            if(cursor<hi)runs.add(new ArcInterval(cursor,hi));
            String evidenceHash=digest(evidence.hash()+"\n"+left+"\n"+right);
            return new Schedule(List.copyOf(runs),new Resolved(gaps,List.of(),List.of(),evidenceHash));
        });
        work.discard("evidence");
        CompletableFuture<Resolved> chain=CompletableFuture.completedFuture(schedule.initial());
        for(var run:schedule.runs())chain=chain.thenComposeAsync(prior->run(work,key,run,run.fromQ8()==lo,
                run.toQ8()==hi&&right.exit().isPresent(),prior),worker);
        return chain.thenApplyAsync(result->{
            long bytes=4096L+mergeBytes(result.gaps().size()+1L)+128L*result.exclusions().size()+1024L*result.caps().size()
                    +exitTextBytes(left,right)+stringBytes(key.dimension());
            for(var cap:result.caps())bytes=Math.addExact(bytes,stringBytes(cap.site().wallId()));
            var value=work.replace("decision",bytes,()->{
                var all=new ArrayList<>(result.gaps());right.terminal().ifPresent(all::add);
                return GroundExitCodec.hashed(new GroundExitDecision(key,left,right.exit(),right.exit().isPresent()?Long.MAX_VALUE:right.s(),
                        mergeReserved(all),result.exclusions(),result.caps(),result.hash(),"0".repeat(64)));
            });
            work.discard("resolved");work.discard("schedule");return value;
        },worker);
    }
    private CompletableFuture<Resolved> run(Work work,Key key,ArcInterval run,boolean groundedLeft,boolean groundedRight,Resolved prior) {
        var empty=new CapVote(Optional.empty(),digest("accepted-endpoint"));
        var first=groundedLeft?CompletableFuture.completedFuture(empty):vote(key,new Edge(run.fromQ8(),true,run));
        return first.thenComposeAsync(left->(groundedRight?CompletableFuture.completedFuture(empty):vote(key,new Edge(run.toQ8(),false,run)))
                .thenApplyAsync(right->work.replace("resolved",2048L+mergeBytes(prior.gaps().size()+1L)
                        +128L*(prior.exclusions().size()+2L)+1024L*(prior.caps().size()+2L),()->{
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
                    return new Resolved(mergeReserved(gaps),exclusions,caps,digest(prior.hash()+"\n"+run+"\n"+left.t0Hash()+"\n"+right.t0Hash()));
                }),worker),worker);
    }
    private CompletableFuture<CapVote> vote(Key key,Edge edge) {
        active();return source.cap(key,edge).thenApplyAsync(v->{
            active();v.cap().ifPresent(c->{if(!c.edge().equals(edge))throw fail(FailureCode.T0_CORRUPT,"cap voted for another edge");});return v;
        },worker);
    }
    private CompletableFuture<Slice> inspect(CellKey cell) {
        active();var known=summaries.get(cell);if(known!=null)return CompletableFuture.completedFuture(known.value());
        return source.inspect(cell).thenApplyAsync(s->{
            active();if(!s.cell().equals(cell))throw fail(FailureCode.RECIPE_MISMATCH,"foreign exit slice");
            long bytes=1024L+s.exits().size()*1024L+s.breaks().size()*128L;
            for(var exit:s.exits())bytes=Math.addExact(bytes,stringBytes(exit.site().wallId()));
            checkBytes(bytes);
            final long retained=bytes;
            state.compute("summary-bytes",(k,n)->{
                int used=n==null?0:n;
                var prior=summaries.get(cell);
                if(prior!=null){if(!prior.value().equals(s))throw fail(FailureCode.T0_CORRUPT,"same cell has two exit surveys");return used;}
                if(!state.containsKey("closed")&&retained<=cacheLimit-used&&summaries.size()<256){
                    var pin=reserveParent(retained);
                    try{summaries.put(cell,new Cached<>(s,pin));return Math.toIntExact(used+retained);}
                    catch(Throwable failure){if(pin!=null)pin.close();throw failure;}
                }
                return used;
            });
            return s;
        },worker);
    }
    public static List<BreakInterval> merge(List<BreakInterval> values) {
        try(var budget=new AllocationBudget(MAX_SUMMARY_BYTES)){return merge(values,budget);}
    }
    static List<BreakInterval> merge(List<BreakInterval> values,AllocationBudget budget) {
        try(var pin=budget.reserve(mergeBytes(values.size()))){return mergeReserved(values);}
    }
    private static List<BreakInterval> mergeReserved(List<BreakInterval> values) {
        var sorted=values.stream().sorted(Comparator.comparingLong((BreakInterval b)->b.range().fromQ8()).thenComparing(b->b.reason().name())).toList();
        var result=new ArrayList<BreakInterval>();
        for(var b:sorted) {
            if(result.isEmpty()||result.getLast().range().toQ8()<b.range().fromQ8())result.add(b);
            else {var old=result.removeLast();var reason=b.range().toQ8()==Long.MAX_VALUE?b:old;
                result.add(new BreakInterval(new ArcInterval(old.range().fromQ8(),Math.max(old.range().toQ8(),b.range().toQ8())),reason.reason(),reason.scope()));}
        }
        return List.copyOf(result);
    }
    private static long mergeBytes(long count){return Math.addExact(1024L,Math.multiplyExact(count,256L));}
    private static long stringBytes(String value){return 128L+16L*value.length();}
    private static long exitTextBytes(Exit left,Right right){
        return stringBytes(left.site().wallId())+right.exit().map(e->stringBytes(e.site().wallId())).orElse(0L);
    }
    private static long decisionBytes(GroundExitDecision value){
        long bytes=2048L+128L*value.breaks().size()+64L*value.exclusions().size()+512L*value.caps().size()
                +stringBytes(value.key().dimension())+stringBytes(value.left().site().wallId());
        if(value.right().isPresent())bytes=Math.addExact(bytes,stringBytes(value.right().get().site().wallId()));
        for(var cap:value.caps())bytes=Math.addExact(bytes,stringBytes(cap.site().wallId()));
        return bytes;
    }
    private AllocationBudget.Pin reserveParent(long bytes){
        if(compiledArena==null)return null;
        var current=Objects.requireNonNull(compiledArena.get(),"missing owner compiled arena");
        var prior=parentArena.putIfAbsent("owner",current);
        if(prior!=null&&prior!=current)throw fail(FailureCode.CLOSED,"resolver cannot migrate cached evidence to another compiled arena");
        return current.reserve(bytes);
    }
    private Charge reserve(long bytes){
        active();var local=temporary.reserve(bytes);
        try{return new Charge(local,reserveParent(bytes));}
        catch(Throwable failure){local.close();throw failure;}
    }
    /** Explicitly owned across futures. A replacement is fully charged while its predecessor is still live. */
    private final class Work implements AutoCloseable {
        private final ConcurrentHashMap<String,Charge> held=new ConcurrentHashMap<>();
        private final Set<String> stopped=ConcurrentHashMap.newKeySet();
        Work(){
            held.put("control",reserve(512));workSets.add(this);
            try{active();}catch(Throwable failure){close();throw failure;}
        }
        <T> T replace(String slot,long bytes,Supplier<T> producer){
            if(stopped.contains("closed"))throw fail(FailureCode.CLOSED,"exit work already closed");
            var next=reserve(bytes);
            try {
                T value=producer.get();active();
                var prior=held.put(slot,next);if(prior!=null)prior.close();
                if(stopped.contains("closed")){held.remove(slot,next);throw fail(FailureCode.CLOSED,"exit work closed during allocation");}
                return value;
            }catch(Throwable failure){next.close();throw failure;}
        }
        void discard(String slot){var charge=held.remove(slot);if(charge!=null)charge.close();}
        // Published immutable outputs can outlive their future. Their charges stay until owner/resolver close.
        void finish(){discard("control");}
        @Override public void close(){
            if(!stopped.add("closed"))return;
            held.values().forEach(Charge::close);held.clear();workSets.remove(this);
        }
    }
    private void checkBytes(long bytes){if(bytes>summaryBytes)throw fail(FailureCode.CAPACITY,"exit summary byte budget");}
    private void active(){
        var parent=parentArena.get("owner");
        if(state.containsKey("closed")||parent!=null&&parent.stats().closed())throw fail(FailureCode.CLOSED,"exit resolver or compiled arena closed");
    }
    private void release(){state.compute("admitted",(k,n)->n-1);}
    /** Tracks actual execution, independently of cancellable or exceptionally completed public views. */
    private <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> operation){
        state.compute("running",(k,n)->{active();return Math.addExact(n==null?0:n,1);});
        try{return operation.get().whenComplete((value,error)->executionEnded());}
        catch(Throwable error){executionEnded();return CompletableFuture.failedFuture(error);}
    }
    private void executionEnded(){
        state.compute("running",(k,n)->{
            if(n==null||n<=0)throw new IllegalStateException("unbalanced exit execution");
            return n-1;
        });
        drainIfIdle();
    }
    /** Completes only after close and all entered producers/queued continuations have terminated. */
    public CompletableFuture<Void> drained(){return drained.copy();}
    private void drainIfIdle(){
        state.compute("running",(k,n)->{
            if(state.containsKey("closed")&&(n==null||n==0)&&state.putIfAbsent("disposed",1)==null){
                state.compute("summary-bytes",(key,used)->{
                    summaries.values().forEach(Cached::release);sealed.values().forEach(Cached::release);
                    summaries.clear();sealed.clear();return 0;
                });
                workSets.forEach(Work::close);workSets.clear();temporary.close();
                drained.complete(null);
            }
            return n;
        });
    }
    private <T> void complete(CompletableFuture<T> promise,T value,Throwable error){
        if(error!=null)promise.completeExceptionally(error);
        else try{active();promise.complete(value);}catch(Throwable stopped){promise.completeExceptionally(stopped);}
    }
    private static CellKey at(CellKey key,long cell){return new CellKey(key.recipeHash(),key.dimension(),cell);}
    private static String digest(String s){return DeterministicHash.sha256(s.getBytes(StandardCharsets.UTF_8));}
    private static WallFailure fail(FailureCode code,String message){return new WallFailure(code,message);}
    public Map<String,Integer> diagnostics(){
        var scratch=temporary.stats();
        return Map.of("requests",requests.size(),"sections",sections.size(),"admitted",state.getOrDefault("admitted",0),
                "summary-bytes",state.getOrDefault("summary-bytes",0),"summaries",summaries.size(),"sealed",sealed.size(),
                "temporary-bytes",Math.toIntExact(scratch.used()),"temporary-peak",Math.toIntExact(scratch.peak()),
                "temporary-limit",Math.toIntExact(scratch.limit()),"cache-limit",Math.toIntExact(cacheLimit));
    }
    @Override public void close(){
        if(state.putIfAbsent("closed",1)!=null)return;
        var failure=fail(FailureCode.CLOSED,"exit resolver closed");
        requests.values().forEach(f->f.completeExceptionally(failure));sections.values().forEach(f->f.completeExceptionally(failure));
        drainIfIdle();
    }
}
