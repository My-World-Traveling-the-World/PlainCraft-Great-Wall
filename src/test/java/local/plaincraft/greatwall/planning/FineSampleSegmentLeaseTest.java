package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import local.plaincraft.greatwall.model.PlanLease;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FineSampleSegmentLeaseTest {
    @Test void lastSliceReleasesItsSegmentWhileAnotherSegmentIsStillPending() {
        var events = new ArrayList<String>();
        var head = new FineSampleSegmentLease(() -> events.add("首段结案"));
        var tail = new FineSampleSegmentLease(() -> events.add("末段结案"));
        head.own(lease(events, "首段几何"));
        head.own(lease(events, "首段备用路线"));
        var pending = tail.own(lease(events, "末段几何"));
        head.retainSlice();
        head.releaseUser(); // 快切片可在提交方结束登记前完成。
        assertTrue(events.isEmpty());
        head.retainSlice();
        head.releaseUser(); // 提交方结束登记，剩最后一片。
        assertTrue(events.isEmpty());
        head.releaseUser();
        assertEquals(List.of("首段几何", "首段备用路线", "首段结案"), events);
        assertFalse(pending.future().isDone());
        tail.close();
    }

    @Test void closingReturnsLateFallbackAndDoesNotReleaseTwice() {
        var events = new ArrayList<String>();
        var segment = new FineSampleSegmentLease(() -> events.add("段结案"));
        var geometry = segment.own(lease(events, "几何"));
        segment.retainSlice();
        segment.close();
        var late = segment.own(lease(events, "迟到路线"));
        segment.releaseUser();
        segment.releaseUser();
        segment.close();
        assertEquals(List.of("几何", "段结案", "迟到路线"), events);
        assertTrue(geometry.future().isCancelled());
        assertTrue(late.future().isCancelled());
    }

    private static PlanLease<Void> lease(List<String> events, String name) {
        return new PlanLease<>(UUID.randomUUID(), new CompletableFuture<>(), () -> events.add(name));
    }
}
