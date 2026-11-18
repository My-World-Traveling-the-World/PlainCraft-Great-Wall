package local.plaincraft.greatwall.planning;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlannerCacheIoTest {
    @Test void closingReturnsQueuedAndInFlightCapacityExactlyOnce() {
        var pump = new ManualExecutor();
        var disk = new ManualExecutor();
        var state = new ConcurrentHashMap<String, Long>();
        var writes = new ArrayList<Integer>();
        var io = new PlannerCacheIo(pump, disk, null, state, unexpected -> fail(unexpected));
        for (int i = 0; i < 128; i++) {
            int value = i;
            io.execute(() -> writes.add(value));
        }
        assertThrows(RejectedExecutionException.class, () -> io.execute(() -> fail("超出额度")));
        pump.runAll();
        assertEquals(1, disk.tasks.size(), "磁盘执行器只接收一个操作");
        disk.tasks.removeFirst().run();
        pump.runAll();
        assertEquals(1, disk.tasks.size());
        assertEquals(127L, state.get("io-pending"));

        // 关闭时同时有尚未入队、已入队、已送磁盘三种请求，只有第三种可以执行。
        io.execute(() -> fail("关闭后不能入队"));
        state.put("closed", 1L);
        pump.execute(io::discardQueued);
        pump.runAll();
        assertEquals(1L, state.get("io-pending"));
        disk.runAll();
        pump.runAll();
        assertEquals(0L, state.get("io-pending"));
        assertEquals(List.of(0, 1), writes, "关闭前派发的请求按 FIFO 写入");
        assertThrows(RejectedExecutionException.class, () -> io.execute(() -> fail("已关闭")));
    }

    @Test void diskRejectionReturnsCapacityAndPreservesCause() {
        var pump = new ManualExecutor();
        var state = new ConcurrentHashMap<String, Long>();
        var rejected = new RejectedExecutionException("磁盘已停止");
        var failures = new ArrayList<WallFailure>();
        var io = new PlannerCacheIo(pump, task -> { throw rejected; }, null, state, failures::add);
        // 用确定性的磁盘拒绝验证致命结案，而不是等超时判断队列是否泄漏。
        io.execute(() -> fail("被拒任务不能执行"));
        pump.runAll();
        assertEquals(0L, state.get("io-pending"));
        assertEquals(1L, state.get("cache-disabled"));
        assertEquals(FailureCode.IO_FAILED, failures.getFirst().code());
        assertSame(rejected, failures.getFirst().getCause());
    }

    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.addLast(task); }
        void runAll() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
    }
}
