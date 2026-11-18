package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.model.PlanLease;

/** 给一个采样段保留几何和备用路线，直到最后一个切片不再引用它们。 */
final class FineSampleSegmentLease implements AutoCloseable {
    // 提交端先持一个引用；即时完成的切片不能在其余切片登记前释放。同一阶段只提交一次。
    private final AtomicInteger users = new AtomicInteger(1);
    private final Runnable finished;
    private List<PlanLease<?>> leases = new ArrayList<>(2);

    FineSampleSegmentLease(Runnable finished) { this.finished = finished; }

    <T> PlanLease<T> own(PlanLease<T> lease) {
        synchronized (this) {
            if (leases != null) {
                leases.add(lease);
                return lease;
            }
        }
        // 另一线程可能在超时回调取得备用路线前已结束整轮；迟到的租约立即归还。
        lease.close();
        return lease;
    }

    void retainSlice() { users.incrementAndGet(); }

    void releaseUser() {
        if (users.decrementAndGet() == 0) close();
    }

    @Override public void close() {
        List<PlanLease<?>> owned;
        synchronized (this) {
            owned = leases;
            leases = null;
        }
        if (owned == null) return;
        // 先摘除引用，再通知缓存归还钉住额度；取消和正常结案可重入。
        try { owned.forEach(PlanLease::close); }
        finally { finished.run(); }
    }
}
