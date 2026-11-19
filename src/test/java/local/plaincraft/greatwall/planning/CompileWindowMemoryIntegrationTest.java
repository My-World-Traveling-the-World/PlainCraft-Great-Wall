package local.plaincraft.greatwall.planning;

import java.util.List;
import java.util.concurrent.*;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.diagnostics.CompileWindowMemory;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 两个真实模板实现器与正式编译器重叠；取消一个不能抹掉另一个的保留量。 */
class CompileWindowMemoryIntegrationTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    @Test void simultaneousCompilersReportRealMemosAndReleaseOnSuccessAndCancellation() throws Exception {
        var f=ImportedBuildingReuseTest.planningFixture(Asset.BEACON);
        var memory=new CompileWindowMemory();
        // 同源窗口必须各自拥有状态，构造第二个编译器不能替换第一个窗口的缓存。
        var source=ImportedProductionPlanTest.planner(f.recipe());
        var a=source.openWindow();var b=source.openWindow();
        var ca=new PlanningChunkCompiler(a,new ChunkPlanCompiler(),FineHeightIndex.of(List.of()),List.of(),memory);
        var cb=new PlanningChunkCompiler(b,new ChunkPlanCompiler(),FineHeightIndex.of(List.of()),List.of(),memory);
        var input=ImportedProductionPlanTest.input(f,8,List.of());
        a.resolve(input);b.resolve(input);
        var expectedA=a.windowMemoStats();var expectedB=b.windowMemoStats();
        assertEquals(0,source.windowMemoStats().total(),"源实现器不能被编译器装入可变缓存");
        assertTrue(expectedA.buildingBytes()>0);assertTrue(expectedA.projectionBytes()>0);assertTrue(expectedA.derivedBytes()>0);
        var reached=new CountDownLatch(2);var releaseA=new CountDownLatch(1);var releaseB=new CountDownLatch(1);
        var owners=List.of(new PlanningOwnerIndex.OwnerWork(new ChunkKey(f.recipe().dimension(),0,0),List.of()));
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->ca.compileOwners(owners,f.recipe().recipeHash(),null,1,1,p->{
                reached.countDown();await(releaseA);
            }));
            var second=workers.submit(()->cb.compileOwners(owners,f.recipe().recipeHash(),null,1,1,p->{
                reached.countDown();await(releaseB);throw new CancellationException("模拟会话换代取消发布");
            }));
            try {
                assertTrue(reached.await(10,TimeUnit.SECONDS));
                assertEquals(2,memory.snapshot().activeWindows());
                assertEquals(expectedA.total()+expectedB.total(),memory.snapshot().totalBytes());
                assertEquals(expectedA.total()+expectedB.total(),memory.snapshot().peakBytes());
                releaseA.countDown();assertEquals(1,first.get(10,TimeUnit.SECONDS));
                assertEquals(0,a.windowMemoStats().total(),"正常结束必须清全部派生memo");
                assertEquals(1,memory.snapshot().activeWindows());
                assertEquals(expectedB.total(),memory.snapshot().totalBytes(),"未结束窗口的实际保留量不得消失");
                assertTrue(b.windowMemoStats().derivedBytes()>0);
            } finally {releaseA.countDown();releaseB.countDown();}
            assertInstanceOf(CancellationException.class,assertThrows(ExecutionException.class,
                    ()->second.get(10,TimeUnit.SECONDS)).getCause());
        }
        assertEquals(0,b.windowMemoStats().total(),"取消发布也必须释放计划和列索引强引用");
        var stats=memory.snapshot();
        assertEquals(0,stats.activeWindows());assertEquals(0,stats.totalBytes());assertEquals(2,stats.closedWindows());
        assertEquals(2,stats.peakWindows());assertTrue(stats.samples()>=2);
        // 同一实现器下一次重算的完整产物不变，释放缓存不能损失模板实体/玩法数据。
        var recompiled=a.resolve(input);
        assertEquals(ImportedProductionPlanTest.planner(f.recipe()).resolve(input).components(),recompiled.components());
        a.clearCompiledBuildings();
        assertEquals(0,a.windowMemoStats().total());
    }

    private static void await(CountDownLatch latch) {
        try{assertTrue(latch.await(10,TimeUnit.SECONDS));}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
    }
}
