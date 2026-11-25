package local.plaincraft.greatwall.planning;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.diagnostics.CompileWindowMemory;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.terrain.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 从原生产窄带起步，补列、重判、最终owner及实际落块必须在同一条链上成立。 */
class StartupCapEvidenceTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){ImportedProductionPlanTest.bootstrap();}

    @Test void actualProtectedGapCompletesItsCapsAndPublishesTheirNewChunks() throws Exception {
        // 让真实17格采样带跨区块，避免测试只覆盖坐标原点的对称情况。
        var f=RealizedCapSamplingTest.fixture(8);var reads=new HashSet<String>();
        var planner=ImportedProductionPlanTest.planner(f.recipe());
        try(var io=Executors.newSingleThreadExecutor();var scheduler=new PlannerScheduler(f.recipe(),
                ()->new AnalyticTerrainOracle((x,z)->{synchronized(reads){reads.add(x+","+z);}return AnalyticTerrainOracle.dry(64);},true),
                new SegmentCacheStore(directory.resolve("protected"),f.recipe()),io);
                var prepared=StartupCapEvidence.prepare(List.of(f.plan()),f.index(),f.protection(),planner,scheduler,()->{})
                        .get(60,TimeUnit.SECONDS)) {
            assertTrue(prepared.supplementalColumns()>0,"必须确实发生补采，不能用预填完整列的夹具");
            assertEquals(reads.size(),prepared.supplementalColumns());
            assertTrue(prepared.terrain().contains(242,-7));
            assertFalse(prepared.terrain().contains(0,28),"收口补采不能变成整线宽带");
            assertTrue(prepared.passes()>=2);
            var inputs=RealizedCapSamplingTest.inputs(f,prepared.terrain(),prepared.coverage());
            var expected=new HashMap<Point3i,BlockSpec>();
            for(long cell:List.of(3L,4L))for(var part:prepared.realization().resolve(inputs.get(cell)).components())
                if(part.id().kind()==SiteKind.END_CAP)for(var voxel:part.voxels())expected.put(voxel.pos(),voxel.state());
            assertTrue(expected.size()>100,"同一真实保护缺口必须能生成非空收口");
            var emitted=new HashMap<ChunkKey,ChunkWritePlan>();
            var report=prepared.compiler(new ChunkPlanCompiler(),f.protection(),new CompileWindowMemory()).compileIntoReport(
                    new PlanWindow(List.of(f.plan())),f.recipe().recipeHash(),f.recipe().dimension(),null,
                    p->emitted.put(p.owner(),p),()->{});
            assertEquals(0,report.failed());assertEquals(report.owners(),report.emitted());
            var actual=new HashMap<Point3i,BlockSpec>();
            for(var plan:emitted.values())for(var voxel:plan.voxels())actual.put(voxel.pos(),voxel.state());
            // 逐个实际体素核对，不用“至少一个收口”掩盖横向新owner丢块。
            for(var e:expected.entrySet())assertEquals(e.getValue(),actual.get(e.getKey()),"最终施工漏收口体素: "+e.getKey());
            assertTrue(actual.keySet().stream().anyMatch(p->p.x()==64&&p.z()==8));
            assertTrue(actual.keySet().stream().anyMatch(p->p.x()==448&&p.z()==8));
            for(var b:f.protection())assertFalse(actual.keySet().stream().anyMatch(p->inside(p,b)),"保护占地仍必须留空");
        }
    }

    @Test void unchangedDryWallDoesNotOpenTerrainOrReplaceItsIndex() throws Exception {
        var f=RealizedCapSamplingTest.fixture();var opens=new AtomicInteger();
        try(var io=Executors.newSingleThreadExecutor();var scheduler=new PlannerScheduler(f.recipe(),()->{
            opens.incrementAndGet();return new AnalyticTerrainOracle((x,z)->AnalyticTerrainOracle.dry(64),true);
        },new SegmentCacheStore(directory.resolve("dry"),f.recipe()),io);
                var prepared=StartupCapEvidence.prepare(List.of(f.plan()),f.index(),List.of(),
                        ImportedProductionPlanTest.planner(f.recipe()),scheduler,()->{}).get(30,TimeUnit.SECONDS)) {
            assertSame(f.index(),prepared.terrain());assertEquals(0,opens.get());
            assertEquals(0,prepared.supplementalColumns());assertEquals(1,prepared.passes());
        }
    }

    @Test void preparedCompilerRetainsAdditionalOwnersIncludingValidEmptyPlans() {
        var f=RealizedCapSamplingTest.fixture();
        // 当前直线收口的实物仍被旧±16格owner罩住；另外注入明确越界的候选取证足迹守住覆盖端口。
        var box=new Bounds(240,0,48,260,128,64);
        var finalCoverage=new local.plaincraft.greatwall.generation.CoveragePlanner(3,true,Map.of(0L,List.of(
                new ImportedSamplingFootprint.Attachment(256L*256,256L*256,box))));
        var owner=new ChunkKey(f.recipe().dimension(),15,3);
        var before=PlanningOwnerIndex.prepare(new PlanWindow(List.of(f.plan())),f.recipe().recipeHash(),f.recipe().dimension(),
                new local.plaincraft.greatwall.generation.CoveragePlanner(3,true));
        assertTrue(before.stream().noneMatch(w->w.owner().equals(owner)),"负对照必须确实不在原区块集合");
        try(var prepared=new StartupCapEvidence.Prepared(f.index(),finalCoverage,
                ImportedProductionPlanTest.planner(f.recipe()).openWindow(),1,0,0)) {
            var emitted=new HashMap<ChunkKey,ChunkWritePlan>();
            var report=prepared.compiler(new ChunkPlanCompiler(),List.of(),new CompileWindowMemory()).compileIntoReport(
                    new PlanWindow(List.of(f.plan())),f.recipe().recipeHash(),f.recipe().dimension(),null,
                    p->emitted.put(p.owner(),p),()->{});
            assertEquals(0,report.failed());assertNotNull(emitted.get(owner),"最终覆盖新增的owner不能被旧compiler裁掉");
            assertTrue(emitted.get(owner).voxels().isEmpty(),"仅取证且无实物的区块仍应明确发布有效空计划");
            assertTrue(report.owners()>before.size());
        }
    }

    @Test void newCaptureOwnersAreRecheckedEvenWhenTheirColumnsAlreadyExist() throws Exception {
        var f=RealizedCapSamplingTest.fixture();var planner=ImportedProductionPlanTest.planner(f.recipe());
        var inputs=RealizedCapSamplingTest.inputs(f,f.index(),new local.plaincraft.greatwall.generation.CoveragePlanner(3,true));
        var gaps=GroundExitResolver.merge(inputs.values().stream().flatMap(i->planner.inspectBreaks(i).stream()).toList());
        assertFalse(gaps.isEmpty(),"必须先从真实保护探针发现断点，不能猜断点位置");
        var targets=SupplementalColumns.missing(ImportedSamplingFootprint.realizedCaps(f.plan(),gaps).stream()
                .map(ImportedSamplingFootprint.Attachment::bounds).toList(),f.index());
        var complete=f.index().withSupplemental(targets.stream().map(t->new WallFinePlanner.FineColumn(t.x(),t.z(),0,
                AnalyticTerrainOracle.dry(64))).toList());
        try(var io=Executors.newSingleThreadExecutor();var scheduler=new PlannerScheduler(f.recipe(),()->{
            throw new AssertionError("全部候选已采，不应再次初始化原生探测");
        },new SegmentCacheStore(directory.resolve("existing"),f.recipe()),io);
                var prepared=StartupCapEvidence.prepare(List.of(f.plan()),complete,f.protection(),
                        ImportedProductionPlanTest.planner(f.recipe()),scheduler,()->{}).get(30,TimeUnit.SECONDS)) {
            assertEquals(0,prepared.supplementalColumns());
            assertTrue(prepared.passes()>=2,"无需补列仍须用新captureOwners复核，不能只看missing为空");
        }
    }

    private static boolean inside(Point3i p,Bounds b){
        return p.x()>=b.minX()&&p.x()<b.maxXExclusive()&&p.y()>=b.minY()&&p.y()<b.maxYExclusive()
                &&p.z()>=b.minZ()&&p.z()<b.maxZExclusive();
    }
}
