package local.plaincraft.greatwall.planning;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import local.plaincraft.greatwall.terrain.FineSampleCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 冷重开必须只有命中；初采的命中列表也不能让会话误判为有新增列而重写全缓存。 */
class FineSampleProbeAccountingTest {
    @TempDir Path directory;

    @Test void awaitingRoutesAloneDoesNotTriggerTheLandFirstFineSamplerWatchdog() {
        // 规划尚未交出占地不是精采卡死；一旦有未完成切片，仍应检测停滞。
        assertFalse(FineSampleStage.hasFineWork(true,8,0));
        assertTrue(FineSampleStage.hasFineWork(true,0,1));
        assertTrue(FineSampleStage.hasFineWork(false,1,0));
        assertFalse(FineSampleStage.hasFineWork(false,0,0));
    }

    @Test void cachedInitialSamplingEmitsIdenticalColumnsWithoutProbingOrPendingWrites() {
        var file=directory.resolve("initial.bin");var route=route();var reads=new AtomicInteger();
        var cache=FineSampleCache.load(file,"world");
        var cold=sample(route,cache,reads);
        assertTrue(reads.get()>100);assertEquals(reads.get(),WallFinePlanner.probedColumns(cold));
        assertEquals(reads.get(),WallFinePlanner.probed(cold).size());
        cache.store(WallFinePlanner.probed(cold).stream().map(c->new FineSampleCache.Entry(c.x(),c.z(),c.sample())).toList());
        reads.set(0);var warm=sample(route,FineSampleCache.load(file,"world"),reads);
        assertEquals(WallFinePlanner.finish(cold),WallFinePlanner.finish(warm));
        assertEquals(0,reads.get());assertEquals(0,WallFinePlanner.probedColumns(warm));
        assertEquals(0,WallFinePlanner.probed(warm).size(),"命中列不能让本轮缓存批次变脏");
    }

    @Test void actualProbeCounterWorksWithoutPersistentCacheOrAProbedList() {
        var reads=new AtomicInteger();var cursor=sample(route(),FineSampleCache.none(),reads);
        assertTrue(reads.get()>100);assertFalse(WallFinePlanner.finish(cursor).isEmpty());
        assertEquals(reads.get(),WallFinePlanner.probedColumns(cursor));
        assertTrue(WallFinePlanner.probed(cursor).isEmpty(),"无持久化时不分配待写样本列表");
    }

    private static WallFinePlanner.Cursor sample(ImportedSamplePath route,FineSampleCache cache,AtomicInteger reads) {
        try(var oracle=new AnalyticTerrainOracle((x,z)->{reads.incrementAndGet();return AnalyticTerrainOracle.dry(64);},true)) {
            var cursor=WallFinePlanner.begin(route,oracle,0,route.points().size(),cache);
            while(WallFinePlanner.advance(cursor,4096)!=WorkState.DONE){}
            return cursor;
        }
    }
    private static ImportedSamplePath route() {
        var recipe=PlanningFixtures.recipe(9282026);var arcs=LongStream.range(0,16).map(i->i*256).boxed().toList();
        return new ImportedSamplePath(new SegmentKey(recipe.recipeHash(),recipe.dimension(),0),
                arcs.stream().map(x->new Point2q(x,0)).toList(),arcs);
    }
}
