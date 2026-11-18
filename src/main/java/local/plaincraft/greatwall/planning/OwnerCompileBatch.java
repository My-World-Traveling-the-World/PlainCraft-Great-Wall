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
    private ExecutorService pool;
    private int peak;

    OwnerCompileBatch(int limit, int workers) {
        if (limit < 1) throw new IllegalArgumentException("区块编译批次必须大于零");
        this.limit = limit;
        this.workers = workers;
    }

    /** 返回本次是否已经结案；发布方只能在工作线程都退出之后读取产物。 */
    boolean add(Runnable job) {
        jobs.add(job);
        peak = Math.max(peak, jobs.size());
        if (jobs.size() < limit) return false;
        drain();
        return true;
    }

    int peak() { return peak; }

    void drain() {
        int count = jobs.size();
        if (count == 0) return;
        if (workers <= 1 || count == 1) {
            jobs.forEach(Runnable::run);
        } else {
            if (pool == null) pool = Executors.newFixedThreadPool(workers, task -> {
                var thread = new Thread(task, "pgw-compile");
                thread.setDaemon(true);
                return thread;
            });
            // 每个工作线程领下一项，等本批全部退出才清掉列表，避免证据被提前释放。
            var cursor = new AtomicInteger();
            var futures = new CompletableFuture<?>[Math.min(workers, count)];
            for (int p = 0; p < futures.length; p++) {
                futures[p] = CompletableFuture.runAsync(() -> {
                    int i;
                    while ((i = cursor.getAndIncrement()) < count) jobs.get(i).run();
                }, pool);
            }
            CompletableFuture.allOf(futures).join();
        }
        jobs.clear();
    }

    @Override public void close() {
        if (pool != null) pool.shutdownNow();
    }
}
