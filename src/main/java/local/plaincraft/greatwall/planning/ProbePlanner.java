package local.plaincraft.greatwall.planning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import local.plaincraft.greatwall.diagnostics.StageTrace;
import local.plaincraft.greatwall.lifecycle.BootstrapState;

/** S0 fake planner only. It has no terrain access and cannot place blocks. */
public final class ProbePlanner implements AutoCloseable {
    public enum Outcome { SUCCESS, FAIL, CANCEL }
    public record Key(String dimension, long segment) {}
    public record Plan(Key key, String hash) {}
    private record Snapshot(BootstrapState state) {}

    private final UUID session = UUID.randomUUID();
    private final long seed;
    private final long delayNanos;
    private final Outcome outcome;
    private final StageTrace trace = new StageTrace(session);
    private final ConcurrentHashMap<Key, CompletableFuture<Plan>> flights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, Plan> completed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Snapshot> state = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> stats = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor startupPump;

    public ProbePlanner(long seed, Duration delay, Outcome outcome) {
        if (delay.isNegative() || delay.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("S0 delay must be 0..30000 ms");
        }
        this.seed = seed;
        this.delayNanos = delay.toNanos();
        this.outcome = outcome;
        state.put("state", new Snapshot(BootstrapState.BOOTSTRAP));
        startupPump = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "greatwall-startup-pump-" + session);
            thread.setDaemon(true);
            return thread;
        });
        startupPump.setRemoveOnCancelPolicy(true);
        startupPump.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public CompletableFuture<Plan> require(Key key) {
        trace.assertOutsideLock();
        if (!accepting()) return CompletableFuture.failedFuture(new CancellationException("Session closed"));
        Plan cached = completed.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        var candidate = new CompletableFuture<Plan>();
        var prior = flights.putIfAbsent(key, candidate);
        var shared = prior == null ? candidate : prior;
        if (prior == null) {
            Plan raced = completed.get(key);
            // Publish the promise before scheduling; a callback may reenter require().
            if (!accepting()) {
                candidate.completeExceptionally(new CancellationException("Session closed"));
                flights.remove(key, candidate);
            } else if (raced != null) {
                candidate.complete(raced);
                flights.remove(key, candidate);
            } else {
                stats.merge("computations", 1L, Long::sum);
                try {
                    long readyAt = System.nanoTime() + delayNanos;
                    startupPump.execute(() -> pump(key, candidate, readyAt));
                } catch (RejectedExecutionException stopped) {
                    candidate.completeExceptionally(new CancellationException("Startup pump stopped"));
                    flights.remove(key, candidate);
                }
            }
        }
        // A distinct lease: canceling this promise cannot cancel another requester.
        var lease = new CompletableFuture<Plan>();
        shared.whenComplete((plan, failure) -> {
            if (failure != null) lease.completeExceptionally(failure);
            else if (!accepting()) lease.completeExceptionally(new CancellationException("Stale session"));
            else lease.complete(plan);
        });
        return lease;
    }

    private void pump(Key key, CompletableFuture<Plan> result, long readyAt) {
        if (!accepting() || result.isDone()) {
            result.completeExceptionally(new CancellationException("Startup canceled"));
            flights.remove(key, result);
            return;
        }
        stats.merge("startupPumpSlices", 1L, Long::sum);
        if (System.nanoTime() < readyAt) {
            try {
                startupPump.schedule(() -> pump(key, result, readyAt), 1, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException stopped) {
                result.completeExceptionally(new CancellationException("Startup pump stopped"));
                flights.remove(key, result);
            }
            return;
        }
        try {
            switch (outcome) {
                case FAIL -> result.completeExceptionally(new IllegalStateException("Injected planner failure"));
                case CANCEL -> result.completeExceptionally(new CancellationException("Injected planner cancellation"));
                case SUCCESS -> {
                    Plan plan = new Plan(key, hash(seed + "\n" + key.dimension() + "\n" + key.segment()));
                    // Only this owner publishes; the cache is bounded independently of request order.
                    if (completed.size() >= 512) completed.clear();
                    if (accepting()) completed.put(key, plan);
                    if (!accepting()) completed.remove(key, plan);
                    if (accepting()) result.complete(plan);
                    else result.completeExceptionally(new CancellationException("Stale session"));
                }
            }
        } finally {
            flights.remove(key, result);
        }
    }

    public void transition(BootstrapState expected, BootstrapState next) {
        state.compute("state", (unused, snapshot) -> {
            if (snapshot.state() != expected) {
                throw new IllegalStateException("Expected " + expected + ", got " + snapshot.state());
            }
            return new Snapshot(next);
        });
    }

    public BootstrapState state() { return state.get("state").state(); }
    public boolean accepting() {
        return switch (state()) {
            case STOPPING, CLOSED, FAILED -> false;
            default -> true;
        };
    }
    public UUID session() { return session; }
    public StageTrace trace() { return trace; }
    public Map<String, Long> stats() { return Map.copyOf(stats); }
    public int inFlightCount() { return flights.size(); }
    public void monitorStop(BooleanSupplier stopRequested) {
        startupPump.scheduleWithFixedDelay(() -> {
            if (stopRequested.getAsBoolean()) close();
        }, 10, 10, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        state.compute("state", (unused, old) -> new Snapshot(BootstrapState.STOPPING));
        startupPump.shutdownNow();
        flights.forEach((key, future) -> future.completeExceptionally(new CancellationException("World closed")));
        flights.clear();
        completed.clear();
        state.put("state", new Snapshot(BootstrapState.CLOSED));
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
