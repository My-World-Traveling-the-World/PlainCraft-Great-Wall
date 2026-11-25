package local.plaincraft.greatwall.planning;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SupplementalColumnStageTest {
    @TempDir Path directory;

    @Test void overlappingRectanglesOnlySubmitUniqueMissingColumns() {
        var existing=FineHeightIndex.of(List.of(new WallFinePlanner.FineColumn(1,1,0,AnalyticTerrainOracle.dry(64))),true);
        var boxes=List.of(new Bounds(-3,0,-2,3,1,3),new Bounds(0,0,0,5,1,4),new Bounds(-3,0,-2,3,1,3));
        var expected=new HashSet<String>();
        for(var b:boxes)for(int z=b.minZ();z<b.maxZExclusive();z++)for(int x=b.minX();x<b.maxXExclusive();x++)
            if(!existing.contains(x,z))expected.add(x+","+z);
        var targets=SupplementalColumns.missing(boxes,existing);
        assertEquals(expected,targets.stream().map(t->t.x()+","+t.z()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(expected.size(),targets.size(),"重叠候选不能重复提交原生探测");
        assertTrue(targets.size()>20);
        assertEquals(targets,SupplementalColumns.missing(List.of(boxes.get(1),boxes.get(0)),existing));
    }

    @Test void missingColumnsProgressWithoutInitialSamplerOrServerTicks() throws Exception {
        var recipe=PlanningFixtures.recipe(0);
        var reads=new ConcurrentHashMap<String,AtomicInteger>();
        try(var io=Executors.newSingleThreadExecutor();var scheduler=new PlannerScheduler(recipe,
                ()->new AnalyticTerrainOracle((x,z)->{
                    reads.computeIfAbsent(x+","+z,k->new AtomicInteger()).incrementAndGet();
                    return AnalyticTerrainOracle.dry(x-z);
                },true),new SegmentCacheStore(directory.resolve("columns"),recipe),io)) {
            var targets=List.of(new WallFinePlanner.Target(-2,3,0),new WallFinePlanner.Target(16,-1,0),new WallFinePlanner.Target(31,31,0));
            // 不调用startupPump/tick/requireFineSamples，专用驱动必须独立完成这三列。
            var columns=scheduler.requireSupplementalColumns(targets,Priority.PLAYER_PREFETCH).get(10,TimeUnit.SECONDS);
            assertEquals(3,columns.size());
            assertEquals(List.of(-5,17,0),columns.stream().map(c->c.sample().groundTopY()).toList());
            assertEquals(Set.of("-2,3","16,-1","31,31"),reads.keySet());
            assertTrue(reads.values().stream().allMatch(n->n.get()==1));
            assertTrue(columns.stream().allMatch(c->c.sample().quality()==Quality.REFERENCE));
        }
    }

    @Test void noMissingColumnsDoNotInitializeNativeTerrain() throws Exception {
        var recipe=PlanningFixtures.recipe(0);var opens=new AtomicInteger();
        try(var io=Executors.newSingleThreadExecutor();var scheduler=new PlannerScheduler(recipe,()->{
            opens.incrementAndGet();return new AnalyticTerrainOracle((x,z)->AnalyticTerrainOracle.dry(64),true);
        },new SegmentCacheStore(directory.resolve("empty"),recipe),io)) {
            assertEquals(List.of(),scheduler.requireSupplementalColumns(List.of(),Priority.PLAYER).get(1,TimeUnit.SECONDS));
            assertEquals(0,opens.get());
        }
    }

    @Test void closingSessionReleasesAnUnfinishedSupplementalRequest() throws Exception {
        var recipe=PlanningFixtures.recipe(0);var entered=new CountDownLatch(1);
        try(var io=Executors.newSingleThreadExecutor();var scheduler=new PlannerScheduler(recipe,()->new NaturalTerrainOracle(){
            private final ColumnCursor cursor=()->false;
            public ColumnCursor begin(int x,int z,SampleKind kind){entered.countDown();return cursor;}
            public WorkState advance(ColumnCursor cursor,int limit){return WorkState.MORE;}
            public ColumnSample finish(ColumnCursor cursor){throw new AssertionError("未完成的列不能结案");}
            public String fingerprint(){return recipe.terrainHash();}
            public void close(){}
        },new SegmentCacheStore(directory.resolve("close"),recipe),io)) {
            var request=scheduler.requireSupplementalColumns(List.of(new WallFinePlanner.Target(1,2,0)),Priority.PLAYER);
            assertTrue(entered.await(5,TimeUnit.SECONDS),"必须确实进入采样，不能只测已关闭前置拒绝");
            scheduler.close();
            assertThrows(ExecutionException.class,()->request.get(5,TimeUnit.SECONDS));
            scheduler.drain().get(5,TimeUnit.SECONDS);
        }
    }
}
