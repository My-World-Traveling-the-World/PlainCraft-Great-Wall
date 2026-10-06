package local.plaincraft.greatwall.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.model.PlanLease;
import org.junit.jupiter.api.Test;

class BudgetCacheTest {
    @Test void pinnedCacheBackpressuresWithoutStartingLoader() throws Exception {
        try (var owner = Executors.newSingleThreadExecutor();
             var cache = new BudgetCache<String, String>(100000, 2, 8, owner, value -> 60000)) {
            var first = cache.acquire("a", 60000, () -> CompletableFuture.completedFuture("a")).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var calls = new AtomicInteger();
            var second = cache.acquire("b", 60000, () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture("b"); }).toCompletableFuture();
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertFalse(second.isDone()); assertEquals(0, calls.get());
            assertEquals(1, cache.stats().waiting()); assertEquals(60000, cache.stats().pinnedBytes());
            assertTrue(cache.stats().bytes() + cache.stats().reservedBytes() <= 100000);
            first.close();
            try (var lease = second.get(5, TimeUnit.SECONDS)) { assertEquals("b", lease.future().get(5, TimeUnit.SECONDS)); }
            assertEquals(1, calls.get()); assertEquals(1, cache.stats().evictions());
        }
    }

    @Test void hundredCallersShareOneComputeAndIndependentPins() throws Exception {
        try (var owner = Executors.newSingleThreadExecutor();
             var cache = new BudgetCache<String, String>(2000000, 2, 256, owner, value -> 60000)) {
            var calls = new AtomicInteger(); var source = new CompletableFuture<String>();
            var requests = new ArrayList<CompletableFuture<PlanLease<String>>>();
            for (int i = 0; i < 100; i++) requests.add(cache.acquire("a", 60000, () -> { calls.incrementAndGet(); return source; }).toCompletableFuture());
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            requests.getFirst().cancel(false);
            source.complete("a");
            for (int i = 1; i < requests.size(); i++) requests.get(i).get(5, TimeUnit.SECONDS).close();
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get()); assertEquals(0, cache.stats().pinnedBytes());
            assertTrue(cache.peek("a").isPresent());
        }
    }

    @Test void closeTerminatesWaitersAndLateLoaderCannotPublish() throws Exception {
        try (var owner = Executors.newSingleThreadExecutor()) {
            var cache = new BudgetCache<String, String>(100000, 2, 8, owner, value -> 60000);
            var source = new CompletableFuture<String>();
            var first = cache.acquire("a", 60000, () -> source).toCompletableFuture();
            var second = cache.acquire("b", 60000, () -> CompletableFuture.completedFuture("b")).toCompletableFuture();
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            cache.close(); source.complete("a");
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertTrue(first.isCompletedExceptionally()); assertTrue(second.isCompletedExceptionally());
            assertTrue(cache.peek("a").isEmpty()); assertEquals(0, cache.stats().bytes());
        }
    }

    @Test void retainedSizeUnderestimationFailsExplicitly() throws Exception {
        try (var owner = Executors.newSingleThreadExecutor();
             var cache = new BudgetCache<String, String>(100000, 2, 8, owner, value -> 60001)) {
            var request = cache.acquire("a", 60000, () -> CompletableFuture.completedFuture("a")).toCompletableFuture();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> request.get(5, TimeUnit.SECONDS));
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, cache.stats().reservedBytes()); assertTrue(cache.peek("a").isEmpty());
        }
    }

    @Test void cancelledWaiterLeavesTheSingleFlightListWithoutWaitingForTheLoad() throws Exception {
        try (var owner = Executors.newSingleThreadExecutor();
             var cache = new BudgetCache<String, String>(2000000, 4, 64, owner, value -> 60000)) {
            var source = new CompletableFuture<String>();
            var calls = new AtomicInteger();
            // One caller starts the load; the rest join it as waiters on the same in-flight job.
            var starter = cache.acquire("a", 60000, () -> { calls.incrementAndGet(); return source; })
                    .toCompletableFuture();
            var waiters = new ArrayList<CompletableFuture<PlanLease<String>>>();
            for (int i = 0; i < 20; i++) {
                waiters.add(cache.acquire("a", 60000, () -> { calls.incrementAndGet(); return source; })
                        .toCompletableFuture());
            }
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get(), "all callers must share a single in-flight load");
            assertEquals(21, cache.stats().liveCallers(), "every waiter is counted while the load runs");

            // Cancel them while the load is still pending: they must drop out without waiting for it.
            for (var waiter : waiters) waiter.cancel(false);
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, cache.stats().liveCallers(),
                    "cancelled waiters must leave the single-flight list, leaving only the live starter");

            // The load still completes normally and serves the one caller that stayed interested.
            source.complete("a");
            starter.get(5, TimeUnit.SECONDS).close();
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
            assertEquals(0, cache.stats().liveCallers());
        }
    }

    @Test void cancelledWaitersDoNotReceiveLeasesWhenTheLoadFinishes() throws Exception {
        try (var owner = Executors.newSingleThreadExecutor();
             var cache = new BudgetCache<String, String>(2000000, 4, 64, owner, value -> 60000)) {
            var source = new CompletableFuture<String>();
            var starter = cache.acquire("a", 60000, () -> source).toCompletableFuture();
            var cancelled = cache.acquire("a", 60000, () -> source).toCompletableFuture();
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertTrue(cancelled.cancel(false));
            source.complete("a");
            assertTrue(cancelled.isCancelled(), "a cancelled waiter must stay cancelled");
            starter.get(5, TimeUnit.SECONDS).close();
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            // The cancelled caller never took a pin, so the entry is unpinned once the live caller closes.
            assertEquals(0, cache.stats().pinnedBytes());
            assertTrue(cache.peek("a").isPresent(), "the load still populated the cache");
        }
    }
}
