package local.plaincraft.greatwall.planning;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongFunction;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Resolves absolute S through authoritative plan prefixes, never by guessing S from axial u. */
public final class ArcPlanProvider implements AutoCloseable {
    private final LongFunction<PlanLease<SegmentPlan>> plans;
    private final Executor worker;
    private final ConcurrentHashMap<UUID,PlanLease<?>> leases=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID,CompletableFuture<PlanWindow>> requests=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Boolean> state=new ConcurrentHashMap<>();
    public ArcPlanProvider(LongFunction<PlanLease<SegmentPlan>> plans,Executor worker) {
        this.plans=Objects.requireNonNull(plans);this.worker=Objects.requireNonNull(worker);
    }
    public PlanLease<PlanWindow> require(CellKey cell) {
        if(cell.arcCell()<0)throw new IllegalArgumentException("negative cell");
        long from=Math.max(0,Math.multiplyExact(cell.arcCell(),64L*256)-192L*256);
        long to=Math.addExact(Math.multiplyExact(cell.arcCell()+1,64L*256),192L*256);
        var id=UUID.randomUUID();var result=new CompletableFuture<PlanWindow>();requests.put(id,result);
        result.whenComplete((v,e)->requests.remove(id));
        try {active();advance(cell,0,from,to,List.of(),result);}
        catch(Throwable error){result.completeExceptionally(error);}
        return new PlanLease<>(id,result,()->result.cancel(false));
    }
    private void advance(CellKey cell,long segment,long from,long to,List<SegmentPlan> retained,CompletableFuture<PlanWindow> result) {
        if(result.isDone())return;
        active();var lease=plans.apply(segment);leases.put(lease.id(),lease);
        if(state.containsKey("closed")){release(lease);result.completeExceptionally(closed());return;}
        lease.future().thenAcceptAsync(plan->{
            active();
            if(!plan.geometry().key().recipeHash().equals(cell.recipeHash())||!plan.geometry().key().dimension().equals(cell.dimension())
                    ||plan.geometry().key().index()!=segment||plan.prefix().beforeSegment()!=segment)
                throw new WallFailure(FailureCode.RECIPE_MISMATCH,"arc plan prefix mismatch");
            long end=Math.addExact(plan.prefix().lengthBeforeQ8(),plan.geometry().localSQ8().getLast());
            var copy=new ArrayList<>(retained);
            if(end>=from||plan.geometry().terminal().isPresent())copy.add(plan);
            if(copy.size()>5)throw new WallFailure(FailureCode.CAPACITY,"arc window requires more than five plans");
            release(lease);
            if(plan.geometry().terminal().isPresent()||end>=to)result.complete(new PlanWindow(copy));
            else advance(cell,segment+1,from,to,List.copyOf(copy),result);
        },worker).whenComplete((v,error)->{
            release(lease);if(error!=null)result.completeExceptionally(error);
        });
    }
    private void release(PlanLease<?> lease){leases.remove(lease.id());lease.close();}
    private void active(){if(state.containsKey("closed"))throw closed();}
    private static WallFailure closed(){return new WallFailure(FailureCode.CLOSED,"arc-plan provider closed");}
    public Map<String,Integer> diagnostics(){return Map.of("leases",leases.size(),"requests",requests.size());}
    @Override public void close(){
        if(state.putIfAbsent("closed",true)!=null)return;
        requests.values().forEach(f->f.completeExceptionally(closed()));leases.values().forEach(PlanLease::close);leases.clear();
    }
}
