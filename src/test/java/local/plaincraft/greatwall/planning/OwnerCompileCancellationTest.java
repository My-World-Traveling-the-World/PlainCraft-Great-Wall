package local.plaincraft.greatwall.planning;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 闩锁固定取消发生于前两项已启动时，不能用空集合或纯计数器替身冒充停止工作。 */
class OwnerCompileCancellationTest {
    @Test void interruptStopsUnclaimedJobsAndWaitsForAlreadyRunningChildren() throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        var running=new AtomicInteger();var started=new AtomicInteger();var ended=new AtomicInteger();
        var failure=new AtomicReference<Throwable>();var interrupted=new AtomicBoolean();
        Thread owner=new Thread(()->{
            try(var batch=new OwnerCompileBatch(8,2)){
                for(int i=0;i<8;i++)batch.add(()->{
                    started.incrementAndGet();running.incrementAndGet();entered.countDown();
                    try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
                    finally{running.decrementAndGet();ended.incrementAndGet();}
                });
            }catch(Throwable e){failure.set(e);}finally{interrupted.set(Thread.currentThread().isInterrupted());}
        });
        owner.start();
        try{
            assertTrue(entered.await(10,TimeUnit.SECONDS));owner.interrupt();
            // 退出闩锁尚未释放时，拥有者必须仍持有共享状态，不能提前返回清理。
            owner.join(100);assertTrue(owner.isAlive());assertEquals(2,running.get());
        }finally{release.countDown();owner.join(10000);}
        assertFalse(owner.isAlive());assertEquals(2,started.get());assertEquals(2,ended.get());
        assertEquals(0,running.get());assertInstanceOf(CancellationException.class,failure.get());assertTrue(interrupted.get());
    }

    @Test void serialInterruptDoesNotRunTheRemainingBatch() {
        var calls=new AtomicInteger();
        try(var batch=new OwnerCompileBatch(3,1)){
            batch.add(()->{calls.incrementAndGet();Thread.currentThread().interrupt();});
            batch.add(calls::incrementAndGet);
            assertThrows(CancellationException.class,()->batch.add(calls::incrementAndGet));
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,calls.get());
        }finally{Thread.interrupted();}
    }

    @Test void preInterruptedOwnerCannotStartAnyJob() {
        var calls=new AtomicInteger();
        try(var batch=new OwnerCompileBatch(1,2)){
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class,()->batch.add(calls::incrementAndGet));
            assertEquals(0,calls.get());assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
    }
}
