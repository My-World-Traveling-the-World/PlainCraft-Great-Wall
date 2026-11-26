package local.plaincraft.greatwall.planning;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.WallFinePlanner.FineColumn;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import local.plaincraft.greatwall.terrain.FineSampleCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 写盘次数、冷重开、失败重试和关闭并发都沿真实文件边界验证。 */
class FineSamplePersistenceTest {
    @TempDir Path directory;

    @Test void initialAndBothSupplementalPassesJoinLoadedColumnsInOneWrite() throws Exception {
        var file=directory.resolve("columns.bin");
        FineSampleCache.load(file,"world").store(List.of(entry(9,10),entry(1,5)));
        byte[] before=Files.readAllBytes(file);
        var cache=FineSampleCache.load(file,"world");var writes=new AtomicInteger();
        var persistence=new FineSamplePersistence(cache,Runnable::run,entries->{writes.incrementAndGet();return cache.tryStore(entries);});
        var evidence=new FineSampleEvidence(persistence);
        assertTrue(evidence.accept(0,List.of(column(1,64)),List.of(column(1,64))));
        assertEquals(64,evidence.finish(true).at(1,0).groundTopY());
        assertArrayEquals(before,Files.readAllBytes(file),"初采不能先覆盖磁盘C");
        assertTrue(persistence.accept(List.of(column(2,65))));
        assertTrue(persistence.accept(List.of(column(3,66),censored(4,false),censored(5,true))));
        assertEquals(0,writes.get(),"每轮补采只追加，不重写全缓存");
        assertTrue(persistence.finish().join());
        var reloaded=FineSampleCache.load(file,"world");
        assertEquals(4,reloaded.size());
        for(int x=1;x<=3;x++)assertEquals(63+x,reloaded.get(x,0).groundTopY());
        assertEquals(10,reloaded.get(9,0).groundTopY(),"磁盘旧列C必须保留");
        assertNull(reloaded.get(4,0));assertNull(reloaded.get(5,0));
        assertEquals(1,writes.get());
        assertFalse(persistence.accept(List.of(column(6,70))),"稳定后的迟到数据不能改批次");
        Files.delete(file);persistence.close();
        assertFalse(Files.exists(file),"成功提交后关闭不得重写");
        assertEquals(1,writes.get());
    }

    @Test void actualWriteFailureRetainsTheWholeBatchForCloseRetry() throws Exception {
        var file=directory.resolve("retry.bin");
        FineSampleCache.load(file,"world").store(List.of(entry(9,10)));
        var cache=FineSampleCache.load(file,"world");var writes=new AtomicInteger();
        var persistence=new FineSamplePersistence(cache,Runnable::run,entries->{writes.incrementAndGet();return cache.tryStore(entries);});
        assertTrue(persistence.accept(List.of(column(1,64))));
        assertTrue(persistence.accept(List.of(column(2,65))));
        var blocker=Files.createDirectory(directory.resolve("retry.bin.tmp"));
        assertFalse(persistence.finish().join());
        assertEquals(1,FineSampleCache.load(file,"world").size());
        Files.delete(blocker);persistence.close();
        var reloaded=FineSampleCache.load(file,"world");
        assertEquals(3,reloaded.size());
        assertEquals(64,reloaded.get(1,0).groundTopY());assertEquals(65,reloaded.get(2,0).groundTopY());
        assertEquals(10,reloaded.get(9,0).groundTopY());assertEquals(2,writes.get());
    }

    @Test void closeWaitsForSubmittedIoWithoutConcurrentTempFileWriters() throws Exception {
        var file=directory.resolve("async.bin");var cache=FineSampleCache.load(file,"world");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closing=new CountDownLatch(1);
        var writes=new AtomicInteger();
        try(var io=Executors.newSingleThreadExecutor();var closer=Executors.newSingleThreadExecutor()) {
            var persistence=new FineSamplePersistence(cache,io,entries->{
                writes.incrementAndGet();entered.countDown();
                try {if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("未释放IO夹具");}
                catch(InterruptedException failure){throw new AssertionError(failure);}
                return cache.tryStore(entries);
            });
            assertTrue(persistence.accept(List.of(column(1,64))));
            var saved=persistence.finish();assertTrue(entered.await(5,TimeUnit.SECONDS));
            var closed=closer.submit(()->{closing.countDown();persistence.close();});
            try {
                assertTrue(closing.await(5,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,()->closed.get(100,TimeUnit.MILLISECONDS),"提交中的IO必须等完，不能提前宣布关闭");
                assertFalse(saved.isDone());assertEquals(1,writes.get());
            } finally {release.countDown();}
            closed.get(5,TimeUnit.SECONDS);assertTrue(saved.join());
            assertEquals(64,FineSampleCache.load(file,"world").get(1,0).groundTopY());assertEquals(1,writes.get());
        }
    }

    @Test void closingBeforeFinalPreflightSavesAcceptedColumnsOnce() {
        var file=directory.resolve("early.bin");var cache=FineSampleCache.load(file,"world");var writes=new AtomicInteger();
        var persistence=new FineSamplePersistence(cache,task->{throw new AssertionError("提前关闭直接同步保存，不派新IO");},entries->{writes.incrementAndGet();return cache.tryStore(entries);});
        assertTrue(persistence.accept(List.of(column(2,65))));persistence.close();persistence.close();
        assertEquals(65,FineSampleCache.load(file,"world").get(2,0).groundTopY());assertEquals(1,writes.get());
        assertFalse(persistence.accept(List.of(column(3,66))));
    }

    @Test void closeClaimingTheWriteFirstPreventsALateFinishFromSubmittingAgain() throws Exception {
        var cache=FineSampleCache.load(directory.resolve("close-first.bin"),"world");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var writes=new AtomicInteger();
        try(var closer=Executors.newSingleThreadExecutor()) {
            var persistence=new FineSamplePersistence(cache,task->{throw new AssertionError("关闭已占有提交，不能再派发IO");},entries->{
                writes.incrementAndGet();entered.countDown();
                try {if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("未释放关闭夹具");}
                catch(InterruptedException failure){throw new AssertionError(failure);}
                return cache.tryStore(entries);
            });
            assertTrue(persistence.accept(List.of(column(1,64))));var closed=closer.submit(persistence::close);
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                var saved=persistence.finish();assertFalse(saved.isDone());assertSame(saved,persistence.finish());
                assertEquals(1,writes.get());
            } finally {release.countDown();}
            closed.get(5,TimeUnit.SECONDS);assertTrue(persistence.finish().join());assertEquals(1,writes.get());
        }
    }

    @Test void ioRejectionRemainsRetryableAndEmptyBatchDoesNotSubmitWork() {
        var file=directory.resolve("rejected.bin");var submits=new AtomicInteger();
        java.util.concurrent.Executor reject=task->{submits.incrementAndGet();throw new RejectedExecutionException("测试拒绝");};
        var persistence=new FineSamplePersistence(FineSampleCache.load(file,"world"),reject);
        assertTrue(persistence.accept(List.of(column(1,64))));assertFalse(persistence.finish().join());
        persistence.close();assertEquals(64,FineSampleCache.load(file,"world").get(1,0).groundTopY());
        var empty=new FineSamplePersistence(FineSampleCache.load(file,"world"),reject);
        assertTrue(empty.finish().join());empty.close();assertEquals(1,submits.get());
    }

    private static FineColumn column(int x,int y){return new FineColumn(x,0,0,AnalyticTerrainOracle.dry(y));}
    private static FineColumn censored(int x,boolean upper){return new FineColumn(x,0,0,new ColumnSample(70,OptionalInt.empty(),OptionalInt.empty(),"minecraft:plains",upper?Quality.REFERENCE:Quality.CENSORED,upper));}
    private static FineSampleCache.Entry entry(int x,int y){return new FineSampleCache.Entry(x,0,AnalyticTerrainOracle.dry(y));}
}
