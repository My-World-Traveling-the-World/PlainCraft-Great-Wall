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
             var cache = new BudgetCache<String, String>(100, 2, 4096, owner, value -> 60)) {
            var first = cache.acquire("a", 60, () -> CompletableFuture.completedFuture("a")).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var calls = new AtomicInteger();
            var second = cache.acquire("b", 60, () -> { calls.incrementAndGet(); return CompletableFuture.completedFuture("b"); }).toCompletableFuture();
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertFalse(second.isDone()); assertEquals(0, calls.get());
            assertEquals(1, cache.stats().waiting()); assertEquals(60, cache.stats().pinnedBytes());
            first.close();
            try (var lease = second.get(5, TimeUnit.SECONDS)) { assertEquals("b", lease.future().get(5, TimeUnit.SECONDS)); }
            assertEquals(1, calls.get()); assertEquals(1, cache.stats().evictions());
        }
    }

    @Test void hundredCallersShareOneComputeAndIndependentPins() throws Exception {
        try (var owner = Executors.newSingleThreadExecutor();
             var cache = new BudgetCache<String, String>(100, 2, 4096, owner, value -> 60)) {
            var calls = new AtomicInteger(); var source = new CompletableFuture<String>();
            var requests = new ArrayList<CompletableFuture<PlanLease<String>>>();
            for (int i = 0; i < 100; i++) requests.add(cache.acquire("a", 60, () -> { calls.incrementAndGet(); return source; }).toCompletableFuture());
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
            var cache = new BudgetCache<String, String>(100, 2, 4096, owner, value -> 60);
            var source = new CompletableFuture<String>();
            var first = cache.acquire("a", 60, () -> source).toCompletableFuture();
            var second = cache.acquire("b", 60, () -> CompletableFuture.completedFuture("b")).toCompletableFuture();
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            cache.close(); source.complete("a");
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertTrue(first.isCompletedExceptionally()); assertTrue(second.isCompletedExceptionally());
            assertTrue(cache.peek("a").isEmpty()); assertEquals(0, cache.stats().bytes());
        }
    }

    @Test void retainedSizeUnderestimationFailsExplicitly() throws Exception {
        try (var owner = Executors.newSingleThreadExecutor();
             var cache = new BudgetCache<String, String>(100, 2, 4096, owner, value -> 61)) {
            var request = cache.acquire("a", 60, () -> CompletableFuture.completedFuture("a")).toCompletableFuture();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> request.get(5, TimeUnit.SECONDS));
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, cache.stats().reservedBytes()); assertTrue(cache.peek("a").isEmpty());
        }
    }
}
