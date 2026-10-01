package local.plaincraft.greatwall.diagnostics;

import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;

/** Sub-allocation accounting inside an already admitted working-set reservation. */
public final class AllocationBudget implements AutoCloseable {
    public record Stats(long limit, long used, long peak, boolean closed) {}
    private final ConcurrentHashMap<String, Stats> state = new ConcurrentHashMap<>();
    public AllocationBudget(long limit) {
        if(limit<=0)throw new IllegalArgumentException("positive allocation budget required");
        state.put("budget",new Stats(limit,0,0,false));
    }
    public Pin reserve(long bytes) {
        consume(bytes);
        return new Pin(bytes);
    }
    public void consume(long bytes) {
        if(bytes<0)throw new IllegalArgumentException("negative allocation");
        state.compute("budget",(k,s)->{
            if(s.closed())throw new WallFailure(FailureCode.CLOSED,"allocation budget closed");
            if(bytes>s.limit()-s.used())throw new WallFailure(FailureCode.CAPACITY,
                    "allocation rejected before producer: requested="+bytes+" available="+(s.limit()-s.used())
                            +" limit="+s.limit()+" used="+s.used()+" peak="+s.peak());
            long next=s.used()+bytes;
            return new Stats(s.limit(),next,Math.max(next,s.peak()),false);
        });
    }
    public void release(long bytes) {
        if(bytes<0)throw new IllegalArgumentException("negative release");
        state.compute("budget",(k,s)->{
            if(s.closed())return s;
            if(s.used()<bytes)throw new IllegalStateException("allocation accounting underflow");
            return new Stats(s.limit(),s.used()-bytes,s.peak(),false);
        });
    }
    public Stats stats(){return state.get("budget");}
    /** Ends the owning working set, after all its captured data has been consumed. */
    @Override public void close(){state.compute("budget",(k,s)->new Stats(s.limit(),0,s.peak(),true));}
    public final class Pin implements AutoCloseable {
        private final long bytes;
        private final ConcurrentHashMap<String,Boolean> released=new ConcurrentHashMap<>();
        private Pin(long bytes){this.bytes=bytes;}
        public long bytes(){return bytes;}
        @Override public void close(){
            if(released.putIfAbsent("closed",true)!=null)return;
            release(bytes);
        }
    }
}
