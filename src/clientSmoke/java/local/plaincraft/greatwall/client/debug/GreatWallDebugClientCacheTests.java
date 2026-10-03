package local.plaincraft.greatwall.client.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import local.plaincraft.greatwall.diagnostics.DebugPayload;
import local.plaincraft.greatwall.model.WallTypes.Bounds;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Standalone cache assertions only; no automatic registration or visual acceptance. */
public final class GreatWallDebugClientCacheTests {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    private GreatWallDebugClientCacheTests() {}

    public static void main(String[] args) throws InterruptedException {
        require(args.length == 0, "cache runner takes no arguments");
        assemblesOutOfOrderWithoutPartialDisplay();
        newerRevisionDropsTheEntirePreviousBatch();
        signedRevisionComparisonDoesNotOverflow();
        duplicateAndConflictingPagesAreDistinct();
        mismatchedPageCountsRejectTheRevision();
        sessionChangeClearsAndRejectsRetiredPages();
        retirementHistoryIsBoundedAndFailClosed();
        completeAndIncompleteBatchesExpire();
        duplicateCannotRenewTheDeadline();
        retainedObjectBudgetRejectsTheEntireBatch();
        fullPageCountAndRepeatedReplacementStayBounded();
        clearReleasesHistoryAndPages();
        wrongThreadCannotReadOrMutateCache();
        rendererFiltersRequireCompleteBatchAndStrictRange();
        System.out.println("debug_cache_contracts=PASS");
        System.out.println("actual_client_thread=NOT_RUN networking=NOT_RUN overlay_visuals=NOT_RUN");
    }

    /** Optional actual-client owner check. Invoke explicitly on a real client, not in main(). */
    public static CompletionStage<Void> runClientOwnerCheck() {
        var client = Minecraft.getInstance();
        require(client != null && client.isSameThread(), "actual client owner required");
        var failure = new CompletableFuture<Void>();
        var worker = new Thread(() -> {
            try {
                expectWrongOwner(() -> GreatWallDebugClient.accept(page(A, 1, 0, 1)));
                expectWrongOwner(GreatWallDebugClient::pages);
                expectWrongOwner(GreatWallDebugClient::clear);
                expectWrongOwner(GreatWallDebugClient::initialize);
                expectWrongOwner(() -> OverlayRenderer.render(null, List.of()));
                failure.complete(null);
            } catch (Throwable error) {
                failure.completeExceptionally(error);
            }
        }, "pgw-debug-owner-check");
        worker.setDaemon(true);
        worker.start();
        return failure.orTimeout(5, TimeUnit.SECONDS).thenRunAsync(() -> {
            System.out.println("actual_client_owner_rejection=PASS networking=NOT_RUN overlay_visuals=NOT_RUN");
        }, client);
    }

    private static void assemblesOutOfOrderWithoutPartialDisplay() {
        var cache = new GreatWallDebugClient.PageCache();
        cache.accept(page(A, 4, 2, 3), 0);
        cache.accept(page(A, 4, 0, 3), 1);
        require(cache.pages(1).isEmpty(), "incomplete pages must not render");
        cache.accept(page(A, 4, 1, 3), 2);
        var pages = cache.pages(2);
        require(pages.size() == 3 && pages.get(0).page() == 0 && pages.get(2).page() == 2,
                "complete pages must be ordered");
        expectImmutable(() -> pages.clear());
        cache.clear();
        require(pages.size() == 3, "returned snapshot must be detached");
    }

    private static void newerRevisionDropsTheEntirePreviousBatch() {
        var cache = new GreatWallDebugClient.PageCache();
        cache.accept(page(A, 1, 0, 1), 0);
        require(cache.pages(0).size() == 1, "first revision complete");
        cache.accept(page(A, 2, 1, 2), 1);
        require(cache.pages(1).isEmpty(), "old complete revision must not remain visible");
        cache.accept(page(A, 1, 0, 1), 2);
        require(cache.pages(2).isEmpty(), "late older revision must not fill new batch");
        cache.accept(page(A, 2, 0, 2), 3);
        require(cache.pages(3).size() == 2 && cache.pages(3).getFirst().revision() == 2, "new revision complete");
    }

    private static void signedRevisionComparisonDoesNotOverflow() {
        var cache = new GreatWallDebugClient.PageCache();
        cache.accept(page(A, Long.MIN_VALUE, 0, 1), 0);
        cache.accept(page(A, Long.MAX_VALUE, 0, 1), 1);
        cache.accept(page(A, Long.MIN_VALUE, 0, 1), 2);
        require(cache.pages(2).getFirst().revision() == Long.MAX_VALUE, "revision subtraction must not wrap");
    }

    private static void duplicateAndConflictingPagesAreDistinct() {
        var cache = new GreatWallDebugClient.PageCache();
        var initial = page(A, 7, 0, 2);
        cache.accept(initial, 0);
        long bytes = cache.retainedBytes();
        cache.accept(initial, 1);
        require(cache.retainedBytes() == bytes, "identical duplicate must not consume another slot");
        var different = new DebugPayload(A, 7, 0, 2, List.of(), List.of(),
                List.of(new DebugPayload.Label(new Point3i(0, 5999, 0), "conflict")));
        cache.accept(different, 2);
        cache.accept(page(A, 7, 1, 2), 3);
        require(cache.pages(3).isEmpty(), "conflicting duplicate must invalidate the whole revision");
        cache.accept(page(A, 8, 0, 1), 4);
        require(cache.pages(4).size() == 1, "a higher revision can recover");
    }

    private static void mismatchedPageCountsRejectTheRevision() {
        var cache = new GreatWallDebugClient.PageCache();
        cache.accept(page(A, 1, 0, 2), 0);
        cache.accept(page(A, 1, 1, 3), 1);
        cache.accept(page(A, 1, 1, 2), 2);
        require(cache.pages(2).isEmpty(), "inconsistent totalPages must not be repaired in place");
    }

    private static void sessionChangeClearsAndRejectsRetiredPages() {
        var cache = new GreatWallDebugClient.PageCache();
        cache.accept(page(A, 100, 0, 1), 0);
        cache.accept(page(B, 1, 0, 2), 1);
        require(cache.pages(1).isEmpty(), "new session must clear previous complete pages");
        cache.accept(page(A, 101, 0, 1), 2);
        require(cache.pages(2).isEmpty(), "retired session must not replace the current session");
        cache.accept(page(B, 1, 1, 2), 3);
        require(cache.pages(3).getFirst().session().equals(B), "current session must remain active");
    }

    private static void retirementHistoryIsBoundedAndFailClosed() {
        var cache = new GreatWallDebugClient.PageCache();
        for (int i = 0; i <= 256; i++) {
            cache.accept(page(new UUID(1, i), 1, 0, 1), i);
            require(cache.pages(i).size() == 1, "bounded distinct session admission");
        }
        cache.accept(page(new UUID(1, 257), 1, 0, 1), 257);
        require(cache.pages(257).isEmpty(), "retirement capacity must fail closed, not forget stale sessions");
        cache.accept(page(new UUID(1, 256), 2, 0, 1), 258);
        require(cache.pages(258).isEmpty(), "exhausted connection remains closed until clear");
        cache.clear();
        cache.accept(page(A, 1, 0, 1), 259);
        require(cache.pages(259).size() == 1, "clear permits a new connection's session history");
    }

    private static void completeAndIncompleteBatchesExpire() {
        for (int total : new int[] {1, 2}) {
            var cache = new GreatWallDebugClient.PageCache();
            long baseline = cache.retainedBytes();
            cache.accept(page(A, 1, 0, total), 10);
            long expiry = 10 + GreatWallDebugClient.PageCache.TTL_NANOS;
            cache.expire(expiry);
            require(cache.pages(expiry).isEmpty() && cache.retainedBytes() == baseline, "30-second expiry frees records");
            cache.accept(page(A, 1, 0, total), expiry + 1);
            if (total == 2) cache.accept(page(A, 1, 1, total), expiry + 2);
            require(cache.pages(expiry + 2).isEmpty(), "expired revision must not be resurrected");
            cache.accept(page(A, 2, 0, 1), expiry + 3);
            require(cache.pages(expiry + 3).size() == 1, "a fresh revision may follow expiry");
        }
        var wrap = new GreatWallDebugClient.PageCache();
        long start = Long.MAX_VALUE - 10;
        wrap.accept(page(A, 1, 0, 1), start);
        require(wrap.pages(start + GreatWallDebugClient.PageCache.TTL_NANOS - 1).size() == 1,
                "monotonic clock wrap must not expire early");
        require(wrap.pages(start + GreatWallDebugClient.PageCache.TTL_NANOS).isEmpty(),
                "monotonic clock wrap must still expire");
    }

    private static void duplicateCannotRenewTheDeadline() {
        var cache = new GreatWallDebugClient.PageCache();
        var page = page(A, 1, 0, 1);
        cache.accept(page, 0);
        long deadline = GreatWallDebugClient.PageCache.TTL_NANOS;
        cache.accept(page, deadline - 1);
        require(cache.pages(deadline - 1).size() == 1, "page remains until the original deadline");
        require(cache.pages(deadline).isEmpty(), "duplicate must not reset the age");
    }

    private static void retainedObjectBudgetRejectsTheEntireBatch() {
        var cache = new GreatWallDebugClient.PageCache();
        long baseline = cache.retainedBytes();
        var lines = new ArrayList<DebugPayload.Line>();
        for (int index = 0; index < 256; index++) {
            lines.add(new DebugPayload.Line(new Point3i(index, 5999, 0),
                    new Point3i(index, 6000, 1), 0xFFFFFFFF));
        }
        for (int index = 0; index < 256; index++) {
            cache.accept(new DebugPayload(A, 1, index, 256, lines, List.of(), List.of()), index);
            require(cache.retainedBytes() <= GreatWallDebugClient.PageCache.BYTE_LIMIT, "retained budget is a hard limit");
        }
        require(cache.pages(256).isEmpty() && cache.retainedBytes() == baseline,
                "oversized revision must release partial payloads and never display");
        cache.accept(page(A, 2, 0, 1), 257);
        require(cache.pages(257).size() == 1, "smaller later revision is admissible");
    }

    private static void fullPageCountAndRepeatedReplacementStayBounded() {
        var cache = new GreatWallDebugClient.PageCache();
        for (int index = 255; index >= 0; index--) cache.accept(page(A, 1, index, 256), 0);
        require(cache.pages(0).size() == 256, "256 empty pages remain within the retained limit");
        for (int revision = 2; revision <= 10000; revision++) {
            cache.accept(page(A, revision, 0, 1), revision);
            require(cache.pages(revision).size() == 1, "completed previous revision must not accumulate");
            require(cache.retainedBytes() < 100000, "single-page working set must remain bounded");
        }
    }

    private static void clearReleasesHistoryAndPages() {
        var cache = new GreatWallDebugClient.PageCache();
        long baseline = cache.retainedBytes();
        cache.accept(page(A, 2, 0, 1), 0);
        cache.accept(page(B, 3, 0, 1), 1);
        cache.clear();
        require(cache.pages(2).isEmpty() && cache.retainedBytes() == baseline, "clear releases all payloads");
        cache.accept(page(A, 1, 0, 1), 3);
        require(cache.pages(3).size() == 1, "clear drops session/revision watermarks for a new connection");
        try {
            cache.accept(null, 4);
            throw new AssertionError("null page must be rejected");
        } catch (NullPointerException expected) {
            require(cache.pages(4).size() == 1, "null input must not destroy the batch");
        }
    }

    private static void wrongThreadCannotReadOrMutateCache() throws InterruptedException {
        var cache = new GreatWallDebugClient.PageCache();
        cache.accept(page(A, 1, 0, 1), 0);
        var failure = new AtomicReference<Throwable>();
        var finished = new CountDownLatch(1);
        var worker = new Thread(() -> {
            try {
                expectWrongOwner(() -> cache.accept(page(B, 1, 0, 1), 1));
                expectWrongOwner(() -> cache.pages(1));
                expectWrongOwner(() -> cache.expire(GreatWallDebugClient.PageCache.TTL_NANOS));
                expectWrongOwner(cache::clear);
                expectWrongOwner(cache::retainedBytes);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                finished.countDown();
            }
        }, "pgw-debug-cache-wrong-owner");
        worker.setDaemon(true);
        worker.start();
        require(finished.await(5, TimeUnit.SECONDS), "cache owner check exceeded its bounded timeout");
        if (failure.get() != null) throw new AssertionError("cache owner contract", failure.get());
        require(cache.pages(2).getFirst().session().equals(A), "wrong owner must not mutate the cache");
    }

    private static void rendererFiltersRequireCompleteBatchAndStrictRange() {
        require(!OverlayRenderer.complete(null) && !OverlayRenderer.complete(List.of()), "no pages must not render");
        var first = page(A, 1, 0, 2);
        var second = page(A, 1, 1, 2);
        require(OverlayRenderer.complete(List.of(first, second)), "ordered complete renderer input");
        require(!OverlayRenderer.complete(List.of(first)), "renderer must reject partial input");
        require(!OverlayRenderer.complete(List.of(second, first)), "renderer must reject out-of-order input");
        require(!OverlayRenderer.complete(List.of(first, page(B, 1, 1, 2))), "renderer must reject mixed sessions");
        require(!OverlayRenderer.complete(List.of(first, page(A, 2, 1, 2))), "renderer must reject mixed revisions");
        require(!OverlayRenderer.complete(List.of(first, first)), "renderer must reject duplicate pages");
        var camera = new Vec3(29_000_000, 5999, -29_000_000);
        require(OverlayRenderer.near(new Point3i(29_000_256, 5999, -29_000_000), camera),
                "inclusive 256m boundary with full-height coordinates");
        require(!OverlayRenderer.near(new Point3i(29_000_257, 5999, -29_000_000), camera), "outside 256m rejected");
        require(!OverlayRenderer.near(new Point3i(29_000_256, 6000, -29_000_000), camera),
                "range must be three-dimensional, not just horizontal");
        require(OverlayRenderer.near(new Bounds(28_999_999, 5998, -29_000_001,
                29_000_001, 6000, -28_999_999), camera), "near box is admissible");
        require(!OverlayRenderer.near(new Bounds(29_000_000, 5999, -29_000_000,
                29_000_257, 6000, -28_999_999), camera), "crossing box must not emit remote vertices");
    }

    private static DebugPayload page(UUID session, long revision, int page, int total) {
        return new DebugPayload(session, revision, page, total, List.of(), List.of(), List.of());
    }

    private static void expectWrongOwner(Runnable action) {
        try {
            action.run();
            throw new AssertionError("wrong owner must fail");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("owner"), "explicit owner failure required");
        }
    }

    private static void expectImmutable(Runnable action) {
        try {
            action.run();
            throw new AssertionError("page snapshot must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected detached immutable snapshot.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
