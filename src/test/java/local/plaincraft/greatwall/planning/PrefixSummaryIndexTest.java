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
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test void adjacentWindowsDoNotReplayAlreadyReducedGeometry() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        var acquired = new AtomicInteger();
        var released = new AtomicInteger();
        try (var owner = Executors.newSingleThreadExecutor();
             var index = new PrefixSummaryIndex(i -> {
                 acquired.incrementAndGet();
                 return new PlanLease<>(UUID.randomUUID(), CompletableFuture.completedFuture(
                         PlanningFixtures.geometry(recipe, i, Optional.empty())), released::incrementAndGet);
             }, owner, new SegmentCacheStore(directory.resolve("windows"), recipe))) {
            var expected = PrefixSummaryIndex.origin();
            int[] boundaries = {6, 12, 18, 255, 256, 257, 263};
            // 直接从起点按顺序归约，校验复用不会跳过几何或改变累计弧长、链哈希。
            for (int boundary : boundaries) {
                while (expected.beforeSegment() < boundary) {
                    expected = PrefixSummaryIndex.reduce(expected,
                            PlanningFixtures.geometry(recipe, expected.beforeSegment(), Optional.empty()));
                }
                assertEquals(expected, await(index, boundary));
            }
            assertEquals(263, acquired.get(), "连续窗口不应重复归约已完成前缀");
            assertEquals(acquired.get(), released.get(), "摘要不能持有几何租约");

            // 向后请求不能使用更新的摘要；旧摘要被窗口容量淘汰后仍应可重算。
            var earlier = PrefixSummaryIndex.reduce(PrefixSummaryIndex.origin(),
                    PlanningFixtures.geometry(recipe, 0, Optional.empty()));
            assertEquals(earlier, await(index, 1));
            assertEquals(264, acquired.get());
        }
    }

    @Test void earliestTerminalIsReusedWithoutRequestingLaterGeometry() throws Exception {
        var recipe = PlanningFixtures.recipe(0);
        var acquired = new AtomicInteger();
        try (var owner = Executors.newSingleThreadExecutor();
             var index = new PrefixSummaryIndex(i -> {
                 acquired.incrementAndGet();
                 var terminal = i == 3 ? Optional.of(new Terminal(i, 100, TerminalKind.OCEAN))
                         : Optional.<Terminal>empty();
                 return new PlanLease<>(UUID.randomUUID(), CompletableFuture.completedFuture(
                         PlanningFixtures.geometry(recipe, i, terminal)), () -> {});
             }, owner, new SegmentCacheStore(directory.resolve("terminal"), recipe))) {
            var terminal = await(index, 8);
            assertEquals(4, terminal.beforeSegment());
            assertEquals(3, terminal.terminal().orElseThrow().segment());
            int initialRequests = acquired.get();
            assertTrue(initialRequests >= 4, "冷请求必须先归约到终点");
            assertEquals(terminal, await(index, 40));
            assertEquals(initialRequests, acquired.get(), "确认终点以后不应再取几何");
        }
    }

    /** 限时等真实异步归约，避免未结案时把回归卡死。 */
    private static PrefixSummary await(PrefixSummaryIndex index, long segment) throws Exception {
        try (var request = index.requireBefore(segment)) {
            return request.future().get(5, TimeUnit.SECONDS);
        }
    }
}
