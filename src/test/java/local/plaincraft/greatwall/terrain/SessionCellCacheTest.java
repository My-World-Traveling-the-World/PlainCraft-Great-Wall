package local.plaincraft.greatwall.terrain;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** 验同单元合并、失败释放和关闭唤醒，不靠扩大线程数或睡眠碰运气。 */
@Timeout(10)
class SessionCellCacheTest {
    @Test void concurrentLanesBakeOnceAndDifferentSessionsNeverShare() throws Exception {
        try(var cache=new SessionCellCache<String>(2);var pool=Executors.newFixedThreadPool(2)) {
            var started=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
            var owner=pool.submit(()->cache.get(1,()->{calls.incrementAndGet();started.countDown();await(release);return "height-water-support";}));
            assertTrue(started.await(2,TimeUnit.SECONDS));
            var waiterThread=new java.util.concurrent.atomic.AtomicReference<Thread>();
            var waiter=pool.submit(()->{waiterThread.set(Thread.currentThread());return cache.get(1,()->{calls.incrementAndGet();return "wrong";});});
            awaitWaiting(waiterThread);
            release.countDown();
            assertEquals("height-water-support",owner.get(2,TimeUnit.SECONDS).value());
            assertEquals("height-water-support",waiter.get(2,TimeUnit.SECONDS).value());
            assertTrue(owner.get().built());assertFalse(waiter.get().built());assertEquals(1,calls.get());
            try(var other=new SessionCellCache<String>(2)) {
                assertEquals("another-world",other.get(1,()->"another-world").value());
            }
            assertEquals("height-water-support",cache.get(1,()->"wrong").value());
        }
    }

    @Test void failedOwnerReleasesClaimAndAllowsFreshRetry() {
        try(var cache=new SessionCellCache<String>(2)) {
            assertThrows(IllegalArgumentException.class,()->cache.get(1,()->{throw new IllegalArgumentException("noise failed");}));
            assertEquals(0,cache.inFlight());assertEquals(0,cache.size());
            assertTrue(cache.get(1,()->"retry").built());
            assertFalse(cache.get(1,()->"wrong").built());
        }
    }

    @Test void differentCellsBakeInParallelAndFailureWakesSameCellWaiter() throws Exception {
        try(var cache=new SessionCellCache<String>(2);var pool=Executors.newFixedThreadPool(3)) {
            var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
            var owner=pool.submit(()->cache.get(1,()->{entered.countDown();await(release);throw new IllegalArgumentException("failed owner");}));
            var other=pool.submit(()->cache.get(2,()->{entered.countDown();await(release);return "other";}));
            assertTrue(entered.await(2,TimeUnit.SECONDS),"不同单元烘焙不可持有全局锁串行运行");
            var waiterThread=new java.util.concurrent.atomic.AtomicReference<Thread>();
            var waiter=pool.submit(()->{waiterThread.set(Thread.currentThread());return cache.get(1,()->"wrong");});
            awaitWaiting(waiterThread);release.countDown();
            assertInstanceOf(IllegalArgumentException.class,assertThrows(ExecutionException.class,()->owner.get(2,TimeUnit.SECONDS)).getCause());
            assertInstanceOf(IllegalArgumentException.class,assertThrows(ExecutionException.class,()->waiter.get(2,TimeUnit.SECONDS)).getCause());
            assertEquals("other",other.get(2,TimeUnit.SECONDS).value());
            assertEquals(0,cache.inFlight());assertTrue(cache.get(1,()->"retry").built());
        }
    }

    @Test void capacityIsSharedAndCloseRejectsAdmissionAndDropsResults() {
        var cache=new SessionCellCache<Integer>(2);
        for(long i=0;i<100;i++){long value=i;assertEquals((int)i,cache.get(i,()->(int)value).value());assertTrue(cache.size()<=2);}
        assertTrue(cache.get(0,()->123).built(),"旧结果应驱逐，而非无限持有");
        cache.close();cache.close();assertEquals(0,cache.size());assertEquals(0,cache.inFlight());
        var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->cache.get(0,()->calls.incrementAndGet()));
        assertEquals(0,calls.get(),"关闭后不得再烘焙");
    }

    @Test void closingWakesBlockedWaiterAndLateOwnerCannotRepublish() throws Exception {
        var cache=new SessionCellCache<String>(2);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var owner=pool.submit(()->cache.get(1,()->{entered.countDown();await(release);return "late";}));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            var waiterThread=new java.util.concurrent.atomic.AtomicReference<Thread>();
            var waiter=pool.submit(()->{waiterThread.set(Thread.currentThread());return cache.get(1,()->"wrong");});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime()<deadline && (waiterThread.get()==null || waiterThread.get().getState()!=Thread.State.WAITING))Thread.yield();
            assertNotNull(waiterThread.get());assertEquals(Thread.State.WAITING,waiterThread.get().getState());
            cache.close();
            assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->waiter.get(2,TimeUnit.SECONDS)).getCause());
            release.countDown();
            assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,()->owner.get(2,TimeUnit.SECONDS)).getCause());
            assertEquals(0,cache.size());assertEquals(0,cache.inFlight());
        } finally { release.countDown();cache.close(); }
    }

    private static void await(CountDownLatch latch) {
        try { if(!latch.await(3,TimeUnit.SECONDS))throw new AssertionError("fixture release timed out"); }
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
    }

    private static void awaitWaiting(java.util.concurrent.atomic.AtomicReference<Thread> thread) {
        // 先证明第二通道确实等待同一认领，再放行owner，避免只测到普通缓存命中。
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<deadline && (thread.get()==null||thread.get().getState()!=Thread.State.WAITING))Thread.yield();
        assertNotNull(thread.get());assertEquals(Thread.State.WAITING,thread.get().getState());
    }
}
