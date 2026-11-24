package local.plaincraft.greatwall.planning;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Consumer;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;

/** 缓存 IO 共用已有推进线程和磁盘执行器；队列归推进线程，磁盘每次只接一个操作。 */
final class PlannerCacheIo {
    private static final int MAX_PENDING = 128;
    private final Executor pump, io;
    private final SegmentCacheStore store;
    private final ConcurrentHashMap<String, Long> state;
    private final Consumer<WallFailure> fatal;
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private boolean active;

    PlannerCacheIo(Executor pump, Executor io, SegmentCacheStore store,
            ConcurrentHashMap<String, Long> state, Consumer<WallFailure> fatal) {
        this.pump = pump;
        this.io = io;
        this.store = store;
        this.state = state;
        this.fatal = fatal;
    }

    CompletableFuture<Optional<SegmentPlan>> readPlan(SegmentKey key) {
        return read(() -> store.readPlan(key));
    }

    CompletableFuture<Optional<SegmentGeometry>> readGeometry(SegmentKey key) {
        return read(() -> store.readGeometry(key));
    }

    private <T> CompletableFuture<Optional<T>> read(IoRead<T> operation) {
        if (state.containsKey("cache-disabled")) return CompletableFuture.completedFuture(Optional.empty());
        var result = new CompletableFuture<Optional<T>>();
        try {
            execute(() -> {
                try { result.complete(operation.read()); }
                catch (IOException error) { state.put("cache-disabled", 1L); result.complete(Optional.empty()); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException full) {
            noteRejection();
            result.complete(Optional.empty());
        }
        return result;
    }

    void persist(SegmentGeometry value) { write(() -> store.writeGeometry(value)); }
    void persist(SegmentPlan value) { write(() -> store.writePlan(value)); }

    CompletableFuture<Void> persistFinalPlans(java.util.List<SegmentPlan> plans) {
        var result = new CompletableFuture<Void>();
        // 与前序原始计划共用串行IO队列，最终高度不能被迟到的旧写盘覆盖。
        try { execute(() -> {
            try {
                for (var plan : plans) store.writePlan(plan);
                result.complete(null);
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        }); } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result;
    }

    private void write(IoWrite operation) {
        if (state.containsKey("cache-disabled") || closed()) return;
        try {
            execute(() -> {
                try { operation.write(); }
                catch (IOException error) { state.put("cache-disabled", 1L); }
            });
        } catch (RejectedExecutionException full) {
            noteRejection();
        }
    }

    private void noteRejection() {
        // 队列满只跳过本次操作，后续还可以命中缓存。
        if (closed()) state.put("cache-disabled", 1L);
        else state.merge("cache-backpressure", 1L, Long::sum);
    }

    void execute(Runnable task) {
        if (closed()) throw new RejectedExecutionException("planner closed");
        boolean[] admitted = {false};
        state.compute("io-pending", (key, old) -> {
            long count = old == null ? 0 : old;
            if (count >= MAX_PENDING) return count;
            admitted[0] = true;
            return count + 1;
        });
        if (!admitted[0]) throw new RejectedExecutionException("planner cache IO queue capacity");
        try {
            pump.execute(() -> {
                if (closed()) { release(1); return; }
                queue.addLast(task);
                drain();
            });
        } catch (RuntimeException rejected) {
            release(1);
            throw rejected;
        }
    }

    private void drain() {
        if (active || queue.isEmpty() || closed()) return;
        active = true;
        Runnable next = queue.removeFirst();
        try {
            io.execute(() -> {
                try { next.run(); }
                finally {
                    release(1);
                    try { pump.execute(() -> { active = false; drain(); }); }
                    catch (RejectedExecutionException closing) { /* 关闭已结案公开规划请求。 */ }
                }
            });
        } catch (RuntimeException rejected) {
            active = false;
            release(1);
            state.put("cache-disabled", 1L);
            fatal.accept(new WallFailure(FailureCode.IO_FAILED, "IO executor rejected a required continuation", rejected));
        }
    }

    void discardQueued() {
        // 仅归还未送往磁盘的额度；在跑的任务退出时会归还自己的额度。
        release(queue.size());
        queue.clear();
    }

    private void release(int count) {
        if (count != 0) state.computeIfPresent("io-pending", (key, old) -> old - count);
    }

    private boolean closed() { return state.containsKey("closed"); }

    @FunctionalInterface private interface IoRead<T> { Optional<T> read() throws IOException; }
    @FunctionalInterface private interface IoWrite { void write() throws IOException; }
}
