package local.plaincraft.greatwall.diagnostics;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import local.plaincraft.greatwall.model.WallTypes.ComponentVoxels;

/** Invocation-local allocation scopes. The only shared counter is AllocationBudget's immutable CHM snapshot. */
public final class CompiledAllocation {
    private static final ThreadLocal<Frame> CURRENT=new ThreadLocal<>();
    private static final class Frame {
        final AllocationBudget budget;
        long charged;
        Frame(AllocationBudget budget){this.budget=budget;}
    }
    private CompiledAllocation() {}
    public static void charge(long bytes) {
        var f=CURRENT.get();
        if(f==null)return; // Pure fixture callers do not own a production working set.
        f.budget.consume(bytes);f.charged=Math.addExact(f.charged,bytes);
    }
    public static void discard(long bytes) {
        var f=CURRENT.get();if(f==null)return;
        if(bytes<0||bytes>f.charged)throw new IllegalStateException("discard outside allocation frame");
        f.budget.release(bytes);f.charged-=bytes;
    }
    public static long bytes(ComponentVoxels value) {
        return 512L+256L*value.voxels().size()+256L*value.blockEntities().size();
    }
    public static <T> T run(AllocationBudget budget,Supplier<T> producer,ToLongFunction<T> retained) {
        Objects.requireNonNull(budget);var parent=CURRENT.get();
        if(parent!=null&&parent.budget!=budget)throw new IllegalStateException("nested allocation budgets differ");
        var frame=new Frame(budget);CURRENT.set(frame);
        long keep=0;
        try {
            T value=producer.get();keep=retained.applyAsLong(value);
            if(keep<0||keep>frame.charged)throw new IllegalStateException("producer omitted a pre-allocation charge");
            return value;
        } catch(Throwable failure) {keep=0;throw failure;}
        finally {
            budget.release(frame.charged-keep);
            if(parent==null)CURRENT.remove();
            else {parent.charged=Math.addExact(parent.charged,keep);CURRENT.set(parent);}
        }
    }
    /** Transfers all precharged output to the caller's arena; the caller closes that arena after use. */
    public static <T> T retainAll(AllocationBudget budget,Supplier<T> producer) {
        return run(budget,producer,ignored->CURRENT.get().charged);
    }
    public static ComponentVoxels component(Supplier<ComponentVoxels> producer) {
        var frame=CURRENT.get();if(frame==null)return producer.get();
        return run(frame.budget,()->{charge(512);return producer.get();},CompiledAllocation::bytes);
    }
}
