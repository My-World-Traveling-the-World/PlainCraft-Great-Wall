package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrefixSummaryIndexTest {
    @TempDir Path directory;

    @Test void earliestOceanWinsAllOrders() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        for (boolean reverse : new boolean[] {false, true}) {
            var futures = new ConcurrentHashMap<Long, CompletableFuture<SegmentGeometry>>();
            try (var owner = Executors.newSingleThreadExecutor();
                 var index = new PrefixSummaryIndex(i -> new PlanLease<>(UUID.randomUUID(),
                         futures.computeIfAbsent(i, ignored -> new CompletableFuture<>()), () -> {}), owner,
                         new SegmentCacheStore(directory.resolve("cache"), recipe))) {
                var request = index.requireBefore(63);
                var order = new ArrayList<Integer>();
                for (int i = 0; i < 64; i++) order.add(i);
                if (reverse) Collections.reverse(order);
                for (int i : order) futures.computeIfAbsent((long) i, ignored -> new CompletableFuture<>()).complete(
                        PlanningFixtures.geometry(recipe, i, i == 3 || i == 8 ? Optional.of(new Terminal(i, 100, TerminalKind.OCEAN)) : Optional.empty()));
                var result = request.future().get(5, TimeUnit.SECONDS);
                assertEquals(4, result.beforeSegment());
                assertEquals(3, result.terminal().orElseThrow().segment());
                request.close();
            }
        }
    }

    @Test void coldPrefixDoesNotClaimConstantTime() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        var requests = new ConcurrentHashMap<Long, Boolean>();
        try (var owner = Executors.newSingleThreadExecutor();
             var index = new PrefixSummaryIndex(i -> {
                 requests.put(i, true);
                 return new PlanLease<>(UUID.randomUUID(), CompletableFuture.completedFuture(
                         PlanningFixtures.geometry(recipe, i, Optional.empty())), () -> {});
             }, owner, new SegmentCacheStore(directory.resolve("cache"), recipe))) {
            try (var request = index.requireBefore(32)) {
                assertEquals(32, request.future().get(5, TimeUnit.SECONDS).beforeSegment());
                assertEquals(32, requests.size());
            }
        }
    }

    @Test void callerCancellationAndCloseAreIsolated() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        var source = new CompletableFuture<SegmentGeometry>();
        try (var owner = Executors.newSingleThreadExecutor()) {
            var index = new PrefixSummaryIndex(i -> new PlanLease<>(UUID.randomUUID(), source, () -> {}),
                    owner, new SegmentCacheStore(directory.resolve("cache"), recipe));
            var first = index.requireBefore(1); var second = index.requireBefore(1);
            first.close();
            source.complete(PlanningFixtures.geometry(recipe, 0, Optional.empty()));
            assertEquals(1, second.future().get(5, TimeUnit.SECONDS).beforeSegment());
            second.close(); index.close();
            var late = index.requireBefore(1);
            assertTrue(late.future().isCompletedExceptionally());
            late.close();
        }
    }
}
