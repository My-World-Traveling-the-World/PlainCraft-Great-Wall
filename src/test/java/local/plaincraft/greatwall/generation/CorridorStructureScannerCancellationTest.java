package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CorridorStructureScannerCancellationTest {
    @Test
    void closeCompletesRunningAndDiscardedQueuedFuturesAsCancelled() throws Exception {
        var started = new CountDownLatch(1);
        var hold = new CountDownLatch(1);
        try (var scanner = new CorridorStructureScanner(1)) {
            var running = scanner.submit(() -> {
                started.countDown();
                hold.await();
                return List.of("running");
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var queued = scanner.submit(() -> List.of("queued"));
            // 单线程被闩锁占住，第二个任务一定还在队列里。
            scanner.close();
            assertTrue(running.isCancelled());
            assertTrue(queued.isCancelled());
            assertThrows(CancellationException.class, scanner.submit(List::of)::join);
        } finally {
            hold.countDown();
        }
    }

    @Test
    void invalidWorldIsNotAValidEmptyScan() {
        try (var scanner = new CorridorStructureScanner(1)) {
            assertInstanceOf(IllegalArgumentException.class, assertThrows(CompletionException.class,
                    scanner.scan(null, 0, 0, 0, 0)::join).getCause());
        }
    }
}
