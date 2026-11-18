package local.plaincraft.greatwall.planning;

import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OwnerCompileBatchTest {
    @Test void fullBatchCompletesBeforeMoreEvidenceCanBeQueuedAndTailRunsOnce() {
        var visits = new AtomicIntegerArray(5);
        try (var batch = new OwnerCompileBatch(2, 2)) {
            batch.add(() -> visits.incrementAndGet(0));
            assertEquals(0, visits.get(0), "未满的批次须保留输入，等到批满后才执行");
            batch.add(() -> visits.incrementAndGet(1));
            assertEquals(1, visits.get(0), "批满时必须完成，不能继续积累区块证据");
            assertEquals(1, visits.get(1));
            batch.add(() -> visits.incrementAndGet(2));
            batch.add(() -> visits.incrementAndGet(3));
            batch.add(() -> visits.incrementAndGet(4));
            assertEquals(0, visits.get(4));
            // 最后一批不会凑满；重复结案也不能重跑已完成的工作。
            batch.drain();
            batch.drain();
            assertEquals(2, batch.peak());
            for (int i = 0; i < visits.length(); i++) assertEquals(1, visits.get(i), "区块任务 " + i);
        }
    }
}
