package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 按小批持有待编译区块及施工前证据；跨批共用线程池，避免每批重新建线程。 */
final class OwnerCompileBatch implements AutoCloseable {
    private final ArrayList<Runnable> jobs = new ArrayList<>();
    private final int limit;
    private final int workers;
    private final CompileCancellation cancellation;
    private ExecutorService pool;
    private int peak;

    OwnerCompileBatch(int limit, int workers) { this(limit,workers,new CompileCancellation()); }

    OwnerCompileBatch(int limit, int workers, CompileCancellation cancellation) {
        this.cancellation=cancellation;
        if (limit < 1) throw new IllegalArgumentException("区块编译批次必须大于零");
        this.limit = limit;
        this.workers = workers;
    }

    /** 返回本次是否已经结案；发布方只能在工作线程都退出之后读取产物。 */
    boolean add(Runnable job) {
        cancellation.check();
        jobs.add(job);
        peak = Math.max(peak, jobs.size());
        if (jobs.size() < limit) return false;
        drain();
        return true;
    }

    int peak() { return peak; }

    void drain() {
        cancellation.check();
        int count = jobs.size();
        if (count == 0) return;
        try {
            if (workers <= 1 || count == 1) {
                for(var job:jobs){cancellation.check();job.run();}
            } else {
                if (pool == null) pool = Executors.newFixedThreadPool(workers, task -> {
                    var thread = new Thread(task, "pgw-compile");
                    thread.setDaemon(true);
                    return thread;
                });
                var cursor = new AtomicInteger();
                var futures = new CompletableFuture<?>[Math.min(workers, count)];
                for (int p = 0; p < futures.length; p++) {
                    futures[p] = CompletableFuture.runAsync(() -> {
                        try {
                            while(true){
                                cancellation.check();
                                int i=cursor.getAndIncrement();
                                if(i>=count)return;
                                cancellation.check();
                                jobs.get(i).run();
                            }
                        } catch(Throwable failure){cancellation.stop(failure);throw failure;}
                    }, pool);
                }
                // get可被拥有者中断唤醒；仍等待全部已启动子任务退出，再允许外层finally释放共享证据。
                boolean interrupted=false;
                for(var future:futures){
                    boolean joined=false;
                    while(!joined){
                        try{future.get();joined=true;}
                        catch(InterruptedException failure){
                            interrupted=true;
                            cancellation.stop(new java.util.concurrent.CancellationException("长城编译等待已中断"));
                        }catch(java.util.concurrent.ExecutionException failure){
                            cancellation.stop(failure.getCause());joined=true;
                        }
                    }
                }
                if(interrupted)Thread.currentThread().interrupt();
            }
            cancellation.check();
        } finally {jobs.clear();}
    }

    @Override public void close() {
        if (pool != null) pool.shutdownNow();
    }
}
