package local.plaincraft.greatwall.diagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;

/** Asynchronous FIFO admission. Shared accounting is an immutable snapshot in a CHM. */
public final class BudgetReservation implements AutoCloseable {
    public record Stats(long limit, long reserved, int waiting, int admitted,
                        long rejected, boolean closed) {}
    public record Reservation(long bytes) {
        public Reservation {
            if (bytes <= 0) throw new IllegalArgumentException("positive reservation required");
        }
    }
    private record Job(UUID id, long bytes, CompletableFuture<PlanLease<Reservation>> future) {}
    private record State(long reserved, List<Job> waiting, Map<UUID, Job> held, long rejected, boolean closed) {
        State { waiting = List.copyOf(waiting); held = Map.copyOf(held); }
    }
    private final long limit;
    private final int waiterLimit;
    private final Executor owner;
    private final ConcurrentHashMap<String, State> state = new ConcurrentHashMap<>();

    public BudgetReservation(long limit, int waiterLimit, Executor owner) {
        if (limit <= 0 || waiterLimit <= 0) throw new IllegalArgumentException("positive limits required");
        this.limit = limit;
        this.waiterLimit = waiterLimit;
        this.owner = Objects.requireNonNull(owner);
        state.put("budget", new State(0, List.of(), Map.of(), 0, false));
    }

    /** Completion grants capacity before the producer may allocate; closing the lease releases it. */
    public CompletionStage<PlanLease<Reservation>> acquire(long bytes) {
        if (bytes <= 0 || bytes > limit)
            return CompletableFuture.failedFuture(failure(FailureCode.CAPACITY, "reservation outside budget"));
        var job = new Job(UUID.randomUUID(), bytes, new CompletableFuture<>());
        WallFailure[] rejected = {null};
        state.compute("budget", (key, old) -> {
            if (old.closed()) rejected[0] = failure(FailureCode.CLOSED, "reservation budget closed");
            else if (old.waiting().size() >= waiterLimit)
                rejected[0] = failure(FailureCode.CAPACITY, "reservation waiter limit");
            if (rejected[0] != null)
                return new State(old.reserved(), old.waiting(), old.held(), old.rejected() + 1, old.closed());
            var waiting = new ArrayList<>(old.waiting());
            waiting.add(job);
            return new State(old.reserved(), waiting, old.held(), old.rejected(), false);
        });
        if (rejected[0] != null) job.future().completeExceptionally(rejected[0]);
        else {
            job.future().whenComplete((lease, error) -> { if (error != null) release(job.id()); });
            schedule();
        }
        return job.future();
    }

    public Stats stats() {
        var s = state.get("budget");
        return new Stats(limit, s.reserved(), s.waiting().size(), s.waiting().size() + s.held().size(), s.rejected(), s.closed());
    }

    private void schedule() {
        if (state.get("budget").closed()) return;
        try { owner.execute(this::drain); }
        catch (RejectedExecutionException error) { stop(error); }
    }

    private void drain() {
        var granted = new ArrayList<Job>();
        state.compute("budget", (key, old) -> {
            if (old.closed()) return old;
            long reserved = old.reserved();
            var held = new HashMap<>(old.held());
            int n = 0;
            for (var job : old.waiting()) {
                if (job.future().isDone()) { n++; continue; }
                if (reserved > limit - job.bytes()) break;
                n++;
                reserved += job.bytes();
                held.put(job.id(), job);
                granted.add(job);
            }
            return new State(reserved, old.waiting().subList(n, old.waiting().size()), held, old.rejected(), false);
        });
        // Completion can run arbitrary continuations; never run one inside CHM.compute.
        for (var job : granted) {
            var lease = new PlanLease<>(job.id(), CompletableFuture.completedFuture(new Reservation(job.bytes())),
                    () -> release(job.id()));
            if (!job.future().complete(lease)) lease.close();
        }
    }

    private void release(UUID id) {
        state.compute("budget", (key, old) -> {
            var held = new HashMap<>(old.held());
            var removed = held.remove(id);
            var waiting = old.waiting().stream().filter(j -> !j.id().equals(id)).toList();
            return new State(old.reserved() - (removed == null ? 0 : removed.bytes()), waiting, held, old.rejected(), old.closed());
        });
        schedule();
    }

    private void stop(Throwable reason) {
        var cancelled = new ArrayList<Job>();
        state.compute("budget", (key, old) -> {
            if (old.closed()) return old;
            cancelled.addAll(old.waiting());
            cancelled.addAll(old.held().values());
            return new State(old.reserved(), List.of(), old.held(), old.rejected(), true);
        });
        cancelled.forEach(j -> j.future().completeExceptionally(reason));
        // Delivered pins stay accounted for until the consumer closes them, even after shutdown.
    }

    @Override public void close() { stop(failure(FailureCode.CLOSED, "reservation budget closed")); }
    private static WallFailure failure(FailureCode code, String message) { return new WallFailure(code, message); }
}
