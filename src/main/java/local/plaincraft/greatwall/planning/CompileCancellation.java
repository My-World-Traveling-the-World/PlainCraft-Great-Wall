package local.plaincraft.greatwall.planning;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;

/** 同一同步编译的调用线程、工作线程共享取消原因，避免关闭被吞成单块失败。 */
final class CompileCancellation {
    private final Thread owner=Thread.currentThread();
    private final Runnable active;
    private final AtomicReference<Throwable> stopped=new AtomicReference<>();
    CompileCancellation(){this(()->{});}
    CompileCancellation(Runnable active){this.active=Objects.requireNonNull(active);}
    void stop(Throwable reason){stopped.compareAndSet(null,reason);}
    void check(){
        if(owner.isInterrupted()||Thread.currentThread().isInterrupted())
            stop(new CancellationException("长城编译已中断"));
        if(stopped.get()==null){
            try{active.run();}catch(RuntimeException failure){stop(failure);}
        }
        Throwable failure=stopped.get();
        if(failure instanceof RuntimeException runtime)throw runtime;
        if(failure instanceof Error error)throw error;
        if(failure!=null)throw new IllegalStateException("长城编译子任务失败",failure);
    }
    void rethrowCancellation(Throwable failure){
        if(failure instanceof CancellationException || failure instanceof InterruptedException
                || failure instanceof WallFailure wall && wall.code()==FailureCode.CLOSED)stop(failure);
        check();
    }
}
