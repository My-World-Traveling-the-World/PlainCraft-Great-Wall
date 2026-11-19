package local.plaincraft.greatwall.planning;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实推进器和BudgetCache，直接验证迁出后的额度、共享及关闭契约。 */
class PlannerAdmissionsTest {
    @Test void fourParentsReserveDependencyCapacityAndQueuedWorkResumes() throws Exception {
        try(var h=new Harness()) {
            var geometry=new ArrayList<CompletableFuture<Object>>();
            var plans=new ArrayList<CompletableFuture<Object>>();
            var leases=new ArrayList<PlanLease<String>>();
            var gc=new AtomicInteger();var pc=new AtomicInteger();
            for(int i=0;i<5;i++) {
                var g=new CompletableFuture<Object>();geometry.add(g);
                var p=new CompletableFuture<Object>();plans.add(p);
                leases.add(h.admissions.acquire(new PlannerAdmissions.Key("geometry",i),1024,String.class,()->{gc.incrementAndGet();return g;}));
                leases.add(h.admissions.acquire(new PlannerAdmissions.Key("plan",i),1024,String.class,()->{pc.incrementAndGet();return p;}));
            }
            h.until(()->gc.get()>=4&&pc.get()>=4);
            assertEquals(4,gc.get());assertEquals(4,pc.get(),"第五个父请求必须等待，不能吃掉依赖空间");
            try(var route=h.admissions.acquire(new PlannerAdmissions.Key("route",100),1024,String.class,
                    ()->CompletableFuture.completedFuture("dependency"))) {
                assertEquals("dependency",route.future().get(5,TimeUnit.SECONDS),"父队列饱和不能挡住依赖");
            }
            geometry.getFirst().complete("g");plans.getFirst().complete("p");
            h.until(()->gc.get()==5&&pc.get()==5);
            geometry.forEach(f->f.complete("g"));plans.forEach(f->f.complete("p"));
            for(var lease:leases){assertNotNull(lease.future().get(5,TimeUnit.SECONDS));lease.close();}
            assertEquals(0L,h.state.get("leases"));
        }
    }

    @Test void twoCallersShareLoaderAndClosingOneNeverCancelsTheOther() throws Exception {
        try(var h=new Harness()) {
            var source=new CompletableFuture<Object>();var loads=new AtomicInteger();
            var key=new PlannerAdmissions.Key("route",7);
            var first=h.admissions.acquire(key,1024,String.class,()->{loads.incrementAndGet();return source;});
            var second=h.admissions.acquire(key,1024,String.class,()->{loads.incrementAndGet();return source;});
            h.until(()->loads.get()==1);first.close();
            assertTrue(first.future().isCancelled());assertFalse(source.isDone());assertFalse(second.future().isDone());
            source.complete("shared");assertEquals("shared",second.future().get(5,TimeUnit.SECONDS));
            assertEquals(1,loads.get());second.close();h.barrier();
            assertEquals(0L,h.state.get("leases"));assertEquals(0L,h.state.get("acquisitions"));
        }
    }

    @Test void closingFailsRunningAndQueuedRequestsWithoutLateResurrection() throws Exception {
        var h=new Harness();var sources=new ArrayList<CompletableFuture<Object>>();
        var leases=new ArrayList<PlanLease<String>>();var loads=new AtomicInteger();
        try {
            for(int i=0;i<6;i++) {
                var source=new CompletableFuture<Object>();sources.add(source);
                leases.add(h.admissions.acquire(new PlannerAdmissions.Key("plan",i),1024,String.class,
                        ()->{loads.incrementAndGet();return source;}));
            }
            h.until(()->loads.get()==4);
            h.stopAdmissions();
            for(var lease:leases)assertTrue(lease.future().isDone(),"关闭必须结案运行中和未准入请求");
            assertEquals(0L,h.state.get("leases"));assertEquals(0L,h.state.get("acquisitions"));
            sources.forEach(f->f.complete("late"));h.barrier();
            assertEquals(4,loads.get(),"关闭后队列里的两个loader不得被迟到回调启动");
            for(var lease:leases) {
                assertTrue(lease.future().isCompletedExceptionally());
                assertThrows(RuntimeException.class,()->lease.future().join());
            }
        } finally {h.close();}
    }

    @Test void originalFailureAndClosedCauseRemainVisible() throws Exception {
        try(var h=new Harness()) {
            var failure=new WallFailure(FailureCode.INVALID_RESOURCE,"原采样原因");
            try(var lease=h.admissions.acquire(new PlannerAdmissions.Key("route",1),1024,String.class,
                    ()->CompletableFuture.failedFuture(failure))) {
                var thrown=assertThrows(ExecutionException.class,()->lease.future().get(5,TimeUnit.SECONDS));
                assertSame(failure,thrown.getCause());
            }
            h.stopAdmissions();
            try(var lease=h.admissions.acquire(new PlannerAdmissions.Key("route",2),1024,String.class,
                    ()->fail("关闭后不得启动loader"))) {
                var thrown=assertThrows(ExecutionException.class,()->lease.future().get(5,TimeUnit.SECONDS));
                assertSame(h.closedFailure,thrown.getCause());
            }
        }
    }

    private static final class Harness implements AutoCloseable {
        final ConcurrentHashMap<String,Long> state=new ConcurrentHashMap<>();
        final PlannerPump pump=new PlannerPump(r->{var t=new Thread(r,"admission-test");t.setDaemon(true);return t;});
        final WallFailure closedFailure=new WallFailure(FailureCode.CLOSED,"本会话关闭原因");
        final PlannerAdmissions admissions=new PlannerAdmissions(64L<<20,pump,state,()->state.containsKey("closed"),
                ()->closedFailure,value->64);
        void barrier()throws Exception{pump.submit(()->{}).get(5,TimeUnit.SECONDS);}
        void until(java.util.function.BooleanSupplier condition)throws Exception{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(1);
            assertTrue(condition.getAsBoolean());barrier();
        }
        void stopAdmissions()throws Exception{
            if(state.putIfAbsent("closed",1L)!=null)return;
            admissions.failPending();admissions.closeLeases();admissions.closeLeases();admissions.closeMemory();
            pump.submit(admissions::clearQueued).get(5,TimeUnit.SECONDS);
        }
        public void close()throws Exception{
            stopAdmissions();pump.shutdown();assertTrue(pump.awaitTermination(5,TimeUnit.SECONDS));
        }
    }
}
