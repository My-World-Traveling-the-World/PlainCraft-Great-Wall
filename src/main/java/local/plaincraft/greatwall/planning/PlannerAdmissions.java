package local.plaincraft.greatwall.planning;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import local.plaincraft.greatwall.diagnostics.BudgetCache;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;

/** 单独拥有缓存准入与调用方租约；父任务和依赖的额度、关闭次序保持原契约。 */
final class PlannerAdmissions {
    record Key(String kind, long segment) {}
    private final ConcurrentHashMap<UUID, PlanLease<?>> callerLeases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<PlanLease<Object>>> acquisitions = new ConcurrentHashMap<>();
    private final ArrayDeque<Runnable> planAdmissions = new ArrayDeque<>(), geometryAdmissions = new ArrayDeque<>();
    private final ConcurrentHashMap<String, Long> state;
    private final PlannerPump pump;
    private final BudgetCache<Key,Object> memory;
    private final BooleanSupplier closedPredicate;
    private final Supplier<WallFailure> failureFactory;
    private int admittedPlans, admittedGeometries;

    PlannerAdmissions(long bytes, PlannerPump pump, ConcurrentHashMap<String,Long> state,
            BooleanSupplier closed, Supplier<WallFailure> failure, ToLongFunction<Object> retained) {
        this.pump=pump; this.state=state; this.closedPredicate=closed; this.failureFactory=failure;
        this.memory=new BudgetCache<>(bytes,256,4096,pump,retained);
    }
    private boolean closed(){return closedPredicate.getAsBoolean();}
    private WallFailure closedFailure(){return failureFactory.get();}

    <T> PlanLease<T> acquire(Key key, long upper, Class<T> type, Supplier<CompletionStage<Object>> loader) {
        if (closed()) return new PlanLease<>(UUID.randomUUID(), CompletableFuture.failedFuture(closedFailure()), () -> {});
        var acquired = new CompletableFuture<PlanLease<Object>>();
        UUID id = UUID.randomUUID();
        boolean[] accepted = {false};
        state.compute("acquisitions", (k, old) -> {
            long count = old == null ? 0 : old;
            if (count >= 4096) return count;
            accepted[0] = true; return count + 1;
        });
        if (!accepted[0]) return new PlanLease<>(id, CompletableFuture.failedFuture(
                new WallFailure(FailureCode.CAPACITY, "planner acquisition capacity")), () -> {});
        acquisitions.put(id, acquired);
        acquired.whenComplete((v, e) -> {
            acquisitions.remove(id); state.compute("acquisitions", (k, old) -> old - 1);
        });
        pump.execute(() -> {
            if (closed()) { acquired.completeExceptionally(closedFailure()); return; }
            Runnable start = () -> startAcquire(key, upper, loader, acquired);
            if (key.kind.equals("plan")) planAdmissions.add(start);
            else if (key.kind.equals("geometry")) geometryAdmissions.add(start);
            else start.run();
            drainAdmissions();
        });
        CompletableFuture<T> view = acquired.thenCompose(lease -> lease.future().thenApply(type::cast));
        return outer(view, () -> acquired.thenAccept(PlanLease::close));
    }

    private void startAcquire(Key key, long upper, Supplier<CompletionStage<Object>> loader,
            CompletableFuture<PlanLease<Object>> result) {
        if (key.kind.equals("plan")) admittedPlans++;
        if (key.kind.equals("geometry")) admittedGeometries++;
        memory.acquire(key, upper, loader).whenCompleteAsync((lease, failure) -> {
            if (key.kind.equals("plan")) admittedPlans--;
            if (key.kind.equals("geometry")) admittedGeometries--;
            if (failure != null) result.completeExceptionally(failure);
            else if (!result.complete(lease)) lease.close();
            drainAdmissions();
        }, pump);
    }

    private void drainAdmissions() {
        // 预留依赖容量，避免父任务占满缓存后互等。
        while (!closed() && admittedGeometries < 4 && !geometryAdmissions.isEmpty()) geometryAdmissions.removeFirst().run();
        while (!closed() && admittedPlans < 4 && !planAdmissions.isEmpty()) planAdmissions.removeFirst().run();
    }

    <T> PlanLease<T> outer(CompletionStage<T> shared, Runnable release) {
        UUID id = UUID.randomUUID();
        boolean[] admitted = {false};
        state.compute("leases", (k, old) -> {
            long count = old == null ? 0 : old;
            if (closed() || count >= 4096) return count;
            admitted[0] = true; return count + 1;
        });
        if (!admitted[0]) {
            release.run();
            return new PlanLease<>(id, CompletableFuture.failedFuture(closed() ? closedFailure()
                    : new WallFailure(FailureCode.CAPACITY, "planner lease limit")), () -> {});
        }
        var lease = new PlanLease<T>(id, shared, () -> {
            callerLeases.remove(id); state.compute("leases", (k, old) -> old - 1); release.run();
        });
        callerLeases.put(id, lease);
        if (closed()) lease.close();
        return lease;
    }

    // 关闭分成三个原有阶段，调度器仍在原位置关闭前缀、通道和推进器。
    void failPending(){acquisitions.forEach((id,future)->future.completeExceptionally(closedFailure()));}
    void closeLeases(){callerLeases.forEach((id,lease)->lease.close());}
    void closeMemory(){memory.close();}
    void clearQueued(){planAdmissions.clear();geometryAdmissions.clear();}
}
