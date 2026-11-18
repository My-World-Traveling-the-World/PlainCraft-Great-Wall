package local.plaincraft.greatwall.lifecycle;

import static local.plaincraft.greatwall.lifecycle.StartupSampleHandoff.State.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.Quality;
import local.plaincraft.greatwall.planning.FineHeightIndex;
import local.plaincraft.greatwall.planning.WallFinePlanner.FineColumn;
import org.junit.jupiter.api.Test;

class StartupSampleHandoffTest {
    @Test void waitingCompileCanClaimTheSampleExactlyOnce() {
        var slot = new StartupSampleHandoff();
        assertTrue(slot.claim().waiting());
        assertTrue(slot.begin());
        assertFalse(slot.begin(), "两条启动入口不能重复提交整条路线");
        assertTrue(slot.claim().waiting());
        assertFalse(slot.settled());

        var index = sample();
        assertTrue(slot.complete(index));
        assertTrue(slot.settled());
        assertSame(index, slot.claim().index());
        assertEquals(new StartupSampleHandoff.Claim(TAKEN, null), slot.claim());
        assertFalse(slot.complete(sample()), "迟到回调不得让已经领走的样本重新驻留");
    }

    @Test void twoCompilersCompetingForAnEarlyResultCannotBothTakeIt() throws Exception {
        var slot = new StartupSampleHandoff();
        var index = sample();
        assertTrue(slot.begin());
        assertTrue(slot.complete(index));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var entered = new CountDownLatch(2);
            // 两方在同一条起跑线竞争已就绪的数据，不能靠线程启动顺序通过。
            java.util.concurrent.Callable<StartupSampleHandoff.Claim> claim = () -> {
                entered.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return slot.claim();
            };
            var first = workers.submit(claim);
            var second = workers.submit(claim);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            var results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(r -> r.index() == index).count());
            assertEquals(1, results.stream().filter(r -> r.state() == TAKEN && r.index() == null).count());
        }
    }

    @Test void failureEndsTheWaitWithoutGivingTheCompilerTerrain() {
        var slot = new StartupSampleHandoff();
        assertTrue(slot.begin());
        assertTrue(slot.complete(null));
        assertTrue(slot.settled());
        assertFalse(slot.claim().waiting());
        assertEquals(new StartupSampleHandoff.Claim(FAILED, null), slot.claim());
        assertFalse(slot.complete(sample()));
    }

    @Test void closeDropsAReadyResultAndRejectsAnInFlightResult() {
        var ready = new StartupSampleHandoff();
        assertTrue(ready.begin());
        assertTrue(ready.complete(sample()));
        ready.close();
        assertEquals(new StartupSampleHandoff.Claim(CLOSED, null), ready.claim());

        var running = new StartupSampleHandoff();
        assertTrue(running.begin());
        running.close();
        assertFalse(running.complete(sample()));
        assertFalse(running.begin());
        assertEquals(new StartupSampleHandoff.Claim(CLOSED, null), running.claim());
    }

    private static FineHeightIndex sample() {
        return FineHeightIndex.of(List.of(new FineColumn(3, -4, 0,
                new ColumnSample(64, OptionalInt.empty(), OptionalInt.empty(), "minecraft:plains", Quality.REFERENCE, false))));
    }
}
