package local.plaincraft.greatwall.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;

/** Cache accounting and LRU are owner-confined; published values and Stats are immutable. */
public final class BudgetCache<K, V> implements AutoCloseable {
    public record Stats(long bytes, long reservedBytes, long pinnedBytes, int entries,
            int waiting, long evictions, long rejected) {}
    private final ConcurrentHashMap<K, V> completed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Stats> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> state = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<PlanLease<V>>> requests = new ConcurrentHashMap<>();
    private final Executor owner;
    private final long byteLimit;
    private final int entryLimit, waiterLimit;
    private final ToLongFunction<V> retainedBytes;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<K, Loading<K, V>> loading = new HashMap<>();
    private final ArrayDeque<Loading<K, V>> waiting = new ArrayDeque<>();
    private long bytes, reserved, evictions, rejected;

    public BudgetCache(long byteLimit, int entryLimit, int waiterLimit, Executor owner, ToLongFunction<V> retainedBytes) {
        if (byteLimit <= 0 || entryLimit <= 0 || waiterLimit <= 0) throw new IllegalArgumentException("positive cache limits required");
        this.byteLimit = byteLimit; this.entryLimit = entryLimit; this.waiterLimit = waiterLimit;
        this.owner = Objects.requireNonNull(owner); this.retainedBytes = Objects.requireNonNull(retainedBytes);
        snapshots.put("stats", new Stats(0, 0, 0, 0, 0, 0, 0));
    }

    public CompletionStage<PlanLease<V>> acquire(K key, long reservationUpperBound, Supplier<CompletionStage<V>> loader) {
        Objects.requireNonNull(key); Objects.requireNonNull(loader);
        if (reservationUpperBound <= 0) throw new IllegalArgumentException("positive reservation required");
        if (state.containsKey("closed")) return CompletableFuture.failedFuture(failure(FailureCode.CLOSED, "cache closed"));
        boolean[] accepted = {false};
        state.compute("admitted", (k, old) -> {
            int count = old == null ? 0 : old;
            if (count >= waiterLimit) return count;
            accepted[0] = true; return count + 1;
        });
        if (!accepted[0]) {
            return CompletableFuture.failedFuture(failure(FailureCode.CAPACITY, "cache caller limit"));
        }
        var future = new CompletableFuture<PlanLease<V>>();
        UUID id = UUID.randomUUID();
        requests.put(id, future);
        future.whenComplete((v, error) -> { requests.remove(id); state.compute("admitted", (k, old) -> old - 1); });
        try { owner.execute(() -> acquireOwned(key, reservationUpperBound, loader, future)); }
        catch (RuntimeException rejection) { future.completeExceptionally(rejection); }
        return future;
    }

    public Optional<V> peek(K key) { return Optional.ofNullable(completed.get(key)); }

    public void invalidate(K key) {
        if (state.containsKey("closed")) return;
        owner.execute(() -> {
            var entry = entries.get(key);
            if (entry == null) return;
            if (entry.pins > 0) entry.invalidated = true; else remove(key, entry);
            drain(); publish();
        });
    }

    public Stats stats() { return snapshots.get("stats"); }

    private void acquireOwned(K key, long upper, Supplier<CompletionStage<V>> loader, CompletableFuture<PlanLease<V>> future) {
        if (future.isDone()) return;
        if (state.containsKey("closed")) { future.completeExceptionally(failure(FailureCode.CLOSED, "cache closed")); return; }
        var entry = entries.get(key);
        if (entry != null && !entry.invalidated) { deliver(key, entry, future); publish(); return; }
        if (upper > byteLimit) {
            rejected++; future.completeExceptionally(failure(FailureCode.CAPACITY, "single reservation exceeds cache limit")); publish(); return;
        }
        var existing = loading.get(key);
        if (existing != null) { existing.callers.add(future); publish(); return; }
        var job = new Loading<>(key, upper, loader); job.callers.add(future); loading.put(key, job);
        waiting.add(job); drain(); publish();
    }

    private void drain() {
        if (state.containsKey("closed")) return;
        while (!waiting.isEmpty()) {
            var next = waiting.getFirst();
            var invalidated = entries.get(next.key);
            if (invalidated != null && invalidated.pins > 0) break;
            if (invalidated != null) remove(next.key, invalidated);
            while (bytes + reserved > byteLimit - next.upper || entries.size() + activeLoads() >= entryLimit) {
                K victim = null;
                for (var candidate : entries.entrySet()) if (candidate.getValue().pins == 0) { victim = candidate.getKey(); break; }
                if (victim == null) break;
                remove(victim, entries.get(victim)); evictions++;
            }
            if (bytes + reserved > byteLimit - next.upper || entries.size() + activeLoads() >= entryLimit) break;
            waiting.removeFirst(); next.active = true; reserved += next.upper;
            try {
                next.loader.get().whenCompleteAsync((value, error) -> finish(next, value, error), owner);
            } catch (Throwable error) { finish(next, null, error); }
        }
    }

    private int activeLoads() { return loading.size() - waiting.size(); }

    private void finish(Loading<K, V> job, V value, Throwable error) {
        if (loading.get(job.key) != job) return;
        loading.remove(job.key); reserved -= job.upper;
        if (state.containsKey("closed")) error = failure(FailureCode.CLOSED, "cache closed during load");
        long actual = 0;
        if (error == null) {
            try {
                actual = retainedBytes.applyAsLong(Objects.requireNonNull(value));
                if (actual <= 0 || actual > job.upper) throw failure(FailureCode.CAPACITY, "retained size exceeds reserved upper bound");
            } catch (Throwable invalid) { error = invalid; }
        }
        if (error != null) for (var caller : job.callers) caller.completeExceptionally(error);
        else {
            var entry = new Entry<>(value, actual);
            entries.put(job.key, entry); completed.put(job.key, value); bytes += actual;
            for (var caller : job.callers) deliver(job.key, entry, caller);
        }
        drain(); publish();
    }

    private void deliver(K key, Entry<V> entry, CompletableFuture<PlanLease<V>> caller) {
        if (caller.isDone()) return;
        entry.pins++;
        var lease = new PlanLease<>(UUID.randomUUID(), CompletableFuture.completedFuture(entry.value), () -> {
            if (state.containsKey("closed")) return;
            owner.execute(() -> {
                if (entries.get(key) == entry) {
                    if (--entry.pins < 0) throw new IllegalStateException("negative cache pins");
                    if (entry.pins == 0 && entry.invalidated) remove(key, entry);
                    drain(); publish();
                }
            });
        });
        if (!caller.complete(lease)) lease.close();
    }

    private void remove(K key, Entry<V> entry) {
        if (entries.remove(key, entry)) { completed.remove(key, entry.value); bytes -= entry.bytes; }
    }

    private void publish() {
        long pinned = 0; for (var value : entries.values()) if (value.pins > 0) pinned += value.bytes;
        snapshots.put("stats", new Stats(bytes, reserved, pinned, entries.size(), waiting.size(), evictions, rejected));
    }

    @Override public void close() {
        if (state.putIfAbsent("closed", 1) != null) return;
        requests.forEach((id, request) -> request.completeExceptionally(failure(FailureCode.CLOSED, "cache closed")));
        completed.clear();
        owner.execute(() -> {
            for (var job : loading.values()) for (var caller : job.callers) caller.completeExceptionally(failure(FailureCode.CLOSED, "cache closed"));
            entries.clear(); loading.clear(); waiting.clear(); bytes = 0; reserved = 0; publish();
        });
    }

    private static WallFailure failure(FailureCode code, String message) { return new WallFailure(code, message); }
    private static final class Entry<V> {
        private final V value;
        private final long bytes;
        private int pins;
        private boolean invalidated;
        private Entry(V value, long bytes) { this.value = value; this.bytes = bytes; }
    }
    private static final class Loading<K, V> {
        private final K key;
        private final long upper;
        private final Supplier<CompletionStage<V>> loader;
        private final List<CompletableFuture<PlanLease<V>>> callers = new ArrayList<>();
        private boolean active;
        private Loading(K key, long upper, Supplier<CompletionStage<V>> loader) { this.key = key; this.upper = upper; this.loader = loader; }
    }
}
