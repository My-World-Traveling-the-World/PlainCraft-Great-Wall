package local.plaincraft.greatwall.lifecycle;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 测长城自己的保留信号，不依赖真实MinecraftServer构造或道路侧的Fake。 */
class RetainedRiverSignalTest {
    @Test
    void sessionBeforeRiversReceivesPublish() {
        var signal = new RetainedRiverSignal<String>();
        AtomicInteger calls = new AtomicInteger();
        signal.attach("world", calls::incrementAndGet);
        assertEquals(0, calls.get());
        signal.publish();
        assertEquals(1, calls.get());
    }

    @Test
    void riversBeforeSessionAreReplayed() {
        var signal = new RetainedRiverSignal<String>();
        signal.publish();
        AtomicInteger calls = new AtomicInteger();
        signal.attach("world", calls::incrementAndGet);
        assertEquals(1, calls.get());
    }

    @Test
    void stoppedSessionIsNotCalledAndReopenDoesNotInheritReadiness() {
        var signal = new RetainedRiverSignal<String>();
        AtomicInteger old = new AtomicInteger();
        signal.attach("old", old::incrementAndGet);
        signal.publish();
        assertEquals(1, old.get());
        signal.detach("old");
        signal.publish();
        assertEquals(1, old.get());
        signal.clear();
        AtomicInteger reopened = new AtomicInteger();
        signal.attach("new", reopened::incrementAndGet);
        assertEquals(0, reopened.get());
        signal.publish();
        assertEquals(1, old.get());
        assertEquals(1, reopened.get());
    }

    @Test
    void repeatedRiverBroadcastsStillReachTheSameSession() {
        var signal = new RetainedRiverSignal<String>();
        AtomicInteger calls = new AtomicInteger();
        signal.attach("world", calls::incrementAndGet);
        signal.publish();
        signal.publish();
        assertEquals(2, calls.get());
    }

    @Test
    void clearingWithoutPriorDetachDropsOldCallbacks() {
        var signal = new RetainedRiverSignal<String>();
        AtomicInteger old = new AtomicInteger();
        signal.attach("old", old::incrementAndGet);
        signal.publish();
        assertEquals(1, old.get());
        // 单独验证clear；若先detach再clear，即使clear漏清会话也会保持全绿。
        signal.clear();
        AtomicInteger reopened = new AtomicInteger();
        signal.attach("new", reopened::incrementAndGet);
        assertEquals(0, reopened.get());
        signal.publish();
        assertEquals(1, old.get());
        assertEquals(1, reopened.get());
    }

    @Test
    void registrationAndPublishCannotLoseSignalAtHandoff() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            // 两个顺序都有独立确定性测试；这里另用共同起跑线检查并发交接不丢、不重复。
            for (int i = 0; i < 128; i++) {
                var signal = new RetainedRiverSignal<String>();
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger calls = new AtomicInteger();
                var attach = pool.submit(() -> {
                    await(start);
                    signal.attach("world", calls::incrementAndGet);
                });
                var publish = pool.submit(() -> {
                    await(start);
                    signal.publish();
                });
                start.countDown();
                attach.get(2, TimeUnit.SECONDS);
                publish.get(2, TimeUnit.SECONDS);
                assertEquals(1, calls.get(), "handoff iteration " + i);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch start) {
        try {
            if (!start.await(2, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
