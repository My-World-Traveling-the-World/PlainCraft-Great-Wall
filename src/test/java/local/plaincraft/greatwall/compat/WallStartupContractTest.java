package local.plaincraft.greatwall.compat;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import local.plaincraft.greatwall.generation.ShowcaseGate;
import local.plaincraft.greatwall.lifecycle.PrewarmProgress;
import net.shiroha233.roadweaver.api.incoming.GreatWallReadinessApi;
import net.shiroha233.roadweaver.api.incoming.RiverReadinessApi;
import net.shiroha233.roadweaver.api.outgoing.GreatWallReadinessRegistry;
import net.shiroha233.roadweaver.api.outgoing.RiverReadinessBroadcast;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 在长城仓库直接经过契约注册表；端口只替换长城内部会话和状态读取。 */
class WallStartupContractTest {
    @BeforeEach
    @AfterEach
    void clear() {
        RiverReadinessApi.clearListenersForTest();
        GreatWallReadinessRegistry.unregister(GreatWallReadinessProvider.OWNER_ID);
        PrewarmProgress.clear();
        ShowcaseGate.reset();
    }

    @Test
    void riverBroadcastActuallyReachesWallSubscription() {
        AtomicInteger calls = new AtomicInteger();
        var listener = RoadWeaverLifecycleBridge.register(calls::incrementAndGet);
        assertNotNull(listener);
        assertEquals(1, RiverReadinessApi.listenerCount());
        assertEquals(0, calls.get());
        assertEquals(1, RiverReadinessBroadcast.publishReady("streams"));
        assertEquals(1, calls.get());
        RiverReadinessApi.removeListener(listener);
        RiverReadinessBroadcast.publishReady("streams");
        assertEquals(1, calls.get());
    }

    @Test
    void lateSubscriptionReceivesAlreadyReadyRiverImmediately() {
        RiverReadinessBroadcast.publishReady("streams");
        AtomicInteger calls = new AtomicInteger();
        RoadWeaverLifecycleBridge.register(calls::incrementAndGet);
        assertEquals(1, calls.get());
    }

    @Test
    void resetKeepsSubscriptionButDoesNotReplayPreviousWorld() {
        AtomicInteger calls = new AtomicInteger();
        RoadWeaverLifecycleBridge.register(calls::incrementAndGet);
        RiverReadinessBroadcast.publishReady("streams");
        assertEquals(1, calls.get());
        RiverReadinessBroadcast.reset();
        assertFalse(RiverReadinessApi.isReady());
        assertEquals(1, calls.get());
        AtomicInteger late = new AtomicInteger();
        RoadWeaverLifecycleBridge.register(late::incrementAndGet);
        assertEquals(0, late.get());
        RiverReadinessBroadcast.publishReady("streams");
        assertEquals(2, calls.get());
        assertEquals(1, late.get());
    }

    @Test
    void wallCallbackFailureCannotEscapeIntoRiverPublisher() {
        AtomicInteger attempted = new AtomicInteger();
        RoadWeaverLifecycleBridge.register(() -> {
            attempted.incrementAndGet();
            throw new AssertionError("wall session failed");
        });
        AtomicInteger healthy = new AtomicInteger();
        RoadWeaverLifecycleBridge.register(healthy::incrementAndGet);
        assertDoesNotThrow(() -> RiverReadinessBroadcast.publishReady("streams"));
        assertEquals(1, attempted.get());
        assertEquals(1, healthy.get());
    }

    @Test
    void publicRegistrationInstallsTheWallSubscriber() {
        // 这里只验证公开注册；会话通知端口由广播测试验证，避免污染全局会话保留信号。
        RoadWeaverLifecycleBridge.register();
        assertEquals(1, RiverReadinessApi.listenerCount());
        assertFalse(RiverReadinessApi.isReady());
    }

    @Test
    void registeredProviderUsesStrictWallGateAndIncludesShowcase() {
        GreatWallReadinessProvider.register();
        var provider = GreatWallReadinessRegistry.get();
        assertNotNull(provider);
        assertEquals("great-wall", provider.ownerId());
        assertFalse(provider.isReady());
        PrewarmProgress.begin(2, 1);
        PrewarmProgress.signalReady();
        assertFalse(provider.isReady());
        PrewarmProgress.finish(false);
        assertFalse(provider.isReady());
        PrewarmProgress.clear();
        PrewarmProgress.begin(2, 1);
        PrewarmProgress.finish(true);
        assertTrue(provider.isReady());
        ShowcaseGate.expect("piece");
        assertFalse(provider.isReady());
        ShowcaseGate.done("piece");
        assertTrue(provider.isReady());
        PrewarmProgress.clear();
        assertFalse(provider.isReady());
    }

    @Test
    void explicitFailureIsPublishedWithoutOpeningTheGateAndClearsForTheNextWorld() {
        GreatWallReadinessProvider.register();
        // 使用实际失败和清理入口，防止只把进度签名的变化误当作终态信号。
        PrewarmProgress.begin(2, 1);
        assertFalse(GreatWallReadinessApi.isTerminalFailure());
        PrewarmProgress.finish(false);
        assertTrue(GreatWallReadinessApi.isTerminalFailure());
        assertFalse(GreatWallReadinessApi.isReady());
        PrewarmProgress.clear();
        assertFalse(GreatWallReadinessApi.isTerminalFailure());
        PrewarmProgress.begin(2, 1);
        PrewarmProgress.finish(true);
        assertFalse(GreatWallReadinessApi.isTerminalFailure());
        assertTrue(GreatWallReadinessApi.isReady());
    }

    @Test
    void timeoutAndPollUseTheirOriginalUnitsAndResults() {
        List<String> calls = new ArrayList<>();
        var ready = new AtomicReference<>(false);
        var provider = new GreatWallReadinessProvider((timeout, unit) -> {
            calls.add(timeout + ":" + unit);
            return ready.get();
        }, () -> null);
        assertFalse(provider.isReady());
        assertFalse(provider.awaitReady(-10));
        assertFalse(provider.awaitReady(75));
        ready.set(true);
        assertTrue(provider.awaitReady(25));
        assertEquals(List.of("0:NANOSECONDS", "0:NANOSECONDS", "75:MILLISECONDS", "25:MILLISECONDS"), calls);
    }

    @Test
    void failedProbeNeverReportsReadyOrThrows() {
        var provider = new GreatWallReadinessProvider((timeout, unit) -> {
            throw new LinkageError("state reader unavailable");
        }, () -> { throw new IllegalStateException("snapshot unavailable"); });
        assertFalse(provider.isReady());
        assertFalse(provider.awaitReady(250));
        assertNull(provider.progressSignature());
    }

    @Test
    void missingSnapshotHasNoInventedProgress() {
        var provider = new GreatWallReadinessProvider((timeout, unit) -> false, () -> null);
        assertNull(provider.progressSignature());
    }

    @Test
    void signatureTracksWorkButNeverWallClock() {
        AtomicReference<PrewarmProgress.Snapshot> snapshot = new AtomicReference<>(progress(3, 1));
        var provider = new GreatWallReadinessProvider((timeout, unit) -> false, snapshot::get);
        // 即使没有验收切片，也必须保留切片数的位置；时间变化不能改变签名。
        assertEquals("true|false|COMPILE|3|true|4|5|6|7|0|ASSEMBLE_WINDOW|0", provider.progressSignature());
        String first = provider.progressSignature();
        snapshot.set(progress(3, 999999));
        assertEquals(first, provider.progressSignature());
        snapshot.set(progress(4, 999999));
        assertEquals("true|false|COMPILE|4|true|4|5|6|7|0|ASSEMBLE_WINDOW|0", provider.progressSignature());
        assertNotEquals(first, provider.progressSignature());
    }

    private static PrewarmProgress.Snapshot progress(int completed, long elapsed) {
        // 构造两个只改变时钟的快照，避免靠sleep间接猜测签名是否带时间。
        return new PrewarmProgress.Snapshot(true, 10, completed, 2, elapsed, true, false,
                4, 20, 5, 6, 30, PrewarmProgress.Stage.COMPILE, 7, 100, Map.of());
    }
}
