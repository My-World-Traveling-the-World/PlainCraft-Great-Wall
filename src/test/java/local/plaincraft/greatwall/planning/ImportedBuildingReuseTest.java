package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 正式编译路径跨单元复用；以完整输出摘要证明没有只顾命中数而漏掉实体与玩法。 */
class ImportedBuildingReuseTest {
    private static final String H = PlanningFixtures.HASH;
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    @ParameterizedTest @EnumSource(Asset.class)
    void twoCellsCompileOneWholeBodyWithIdenticalProducts(Asset asset) {
        var f = planningFixture(asset);
        var planner = ImportedProductionPlanTest.planner(f.recipe()).openWindow();
        var reference = ImportedProductionPlanTest.planner(f.recipe());
        long center = Math.floorDiv(f.site().globalSQ8(),64L*256), neighbour = center == 0 ? 1 : center-1;
        int bodies=0;
        for(long cell:List.of(neighbour,center)) {
            var original=ImportedProductionPlanTest.input(f,cell,List.of());
            // 实际编排的普通墙地基按单元变化；它不参与实物楼体，不能让相邻单元必失配。
            var input=new RealizationPlanner.Input(original.key(),original.plans(),original.tiles(),original.columns(),
                    original.protectedBounds(),original.frozenGateY(),original.gateRevision(),original.protectionHash(),
                    Map.of(cell,64),List.of());
            var actual=planner.resolve(input);var expected=reference.resolve(input);
            assertEquals(expected.hash(),actual.hash());
            assertEquals(expected.components(),actual.components());
            bodies+=actual.components().stream().filter(c->c.id().equals(f.site().id())).count();
        }
        assertEquals(1,bodies,"只有中心单元发布，不能为复用改变发布归属");
        var stats=planner.buildingMemoStats();
        assertEquals(1,stats.loads());assertEquals(1,stats.hits());
        assertEquals(1,stats.entries());assertTrue(stats.bytes()>0);
        assertTrue(stats.bytes()<=ImportedBuildingMemo.MAX_BYTES);
        assertEquals(1,planner.projectionMemoStats().builds());
        assertEquals(1,planner.projectionMemoStats().hits());
        assertTrue(planner.projectionMemoStats().bytes()>0);
        planner.clearCompiledBuildings();
        assertEquals(0,planner.buildingMemoStats().entries());assertEquals(0,planner.buildingMemoStats().bytes());
        assertEquals(0,planner.projectionMemoStats().bytes());
    }

    @Test void actualCompilerReleasesMemoOnSuccessAndPublishingFailure() {
        var f=planningFixture(Asset.BEACON);
        var planner=ImportedProductionPlanTest.planner(f.recipe()).openWindow();
        var compiler=new PlanningChunkCompiler(planner,new ChunkPlanCompiler(),FineHeightIndex.of(List.of()),List.of());
        var input=ImportedProductionPlanTest.input(f,8,List.of());
        planner.resolve(input);
        assertTrue(planner.buildingMemoStats().bytes()>0);
        assertEquals(0,compiler.compileOwners(List.of(),f.recipe().recipeHash(),null,1,1,p->fail("无区块不得发布")));
        assertEquals(0,planner.buildingMemoStats().bytes());
        assertEquals(0,planner.projectionMemoStats().bytes());
        planner.resolve(input);
        assertTrue(planner.buildingMemoStats().bytes()>0);
        var owners=List.of(new PlanningOwnerIndex.OwnerWork(new ChunkKey(f.recipe().dimension(),0,0),List.of()));
        var error=assertThrows(IllegalStateException.class,()->compiler.compileOwners(owners,f.recipe().recipeHash(),null,
                1,1,p->{throw new IllegalStateException("故意拒绝发布");}));
        assertEquals("故意拒绝发布",error.getMessage());
        assertEquals(0,planner.buildingMemoStats().bytes(),"发布中断也必须归还强引用");
        assertEquals(0,planner.buildingMemoStats().entries());
        assertEquals(0,planner.projectionMemoStats().bytes());
    }

    @Test void cancellationBeforeOwnerEnumerationStillReleasesTheWindow(){
        var f=planningFixture(Asset.BEACON);var window=ImportedProductionPlanTest.planner(f.recipe()).openWindow();
        window.resolve(ImportedProductionPlanTest.input(f,8,List.of()));
        assertTrue(window.windowMemoStats().buildingBytes()>0);assertTrue(window.windowMemoStats().derivedBytes()>0);
        var compiler=new PlanningChunkCompiler(window,new ChunkPlanCompiler(),FineHeightIndex.of(List.of()),List.of());
        var cause=new java.util.concurrent.CancellationException("枚举前取消");
        assertSame(cause,assertThrows(java.util.concurrent.CancellationException.class,
                ()->compiler.compileInto(new PlanWindow(List.of(f.plan())),f.recipe().recipeHash(),f.recipe().dimension(),null,
                        p->fail("取消之后不能发布"),()->{throw cause;})));
        assertEquals(0,window.windowMemoStats().total());
    }

    @Test void changedActualRoofProtectionCannotReuseAnEarlierSuccess() {
        var f=planningFixture(Asset.GATE);
        var planner=ImportedProductionPlanTest.planner(f.recipe()).openWindow();
        var first=planner.resolve(ImportedProductionPlanTest.input(f,8,List.of()));
        var body=first.components().stream().filter(c->c.id().equals(f.site().id())).findFirst().orElseThrow();
        var roof=body.voxels().stream().filter(v->v.kind()==IntentKind.SOLID)
                .max(Comparator.comparingInt(v->v.pos().y())).orElseThrow().pos();
        var blocked=planner.resolve(ImportedProductionPlanTest.input(f,8,List.of(
                new Bounds(roof.x(),roof.y(),roof.z(),roof.x()+1,roof.y()+1,roof.z()+1))));
        assertFalse(blocked.components().stream().anyMatch(c->c.id().equals(f.site().id())));
        assertTrue(blocked.breaks().stream().anyMatch(b->b.reason()==BreakReason.CONFLICT));
        assertEquals(0,planner.buildingMemoStats().hits());
    }

    @Test void changedFrozenColumnCannotBeHiddenByIdenticalHashes() {
        var f=planningFixture(Asset.GATE);
        var planner=ImportedProductionPlanTest.planner(f.recipe()).openWindow();
        var first=planner.resolve(ImportedProductionPlanTest.input(f,8,List.of()));
        var body=first.components().stream().filter(c->c.id().equals(f.site().id())).findFirst().orElseThrow();
        var voidPoint=body.voxels().stream().filter(v->v.kind()==IntentKind.OWNED_VOID).findFirst().orElseThrow().pos();
        var changed=new HashMap<>(f.columns());
        changed.put(new Point3i(voidPoint.x(),0,voidPoint.z()),new PlanningColumnView(voidPoint.x(),voidPoint.z(),
                AnalyticTerrainOracle.dry(voidPoint.y()+1)));
        var input=new RealizationPlanner.Input(new CellKey(f.recipe().recipeHash(),f.recipe().dimension(),8),
                new PlanWindow(List.of(f.plan())),Map.of(),changed,List.of(),Map.of(),0,H);
        var after=planner.resolve(input);
        assertFalse(after.components().stream().anyMatch(c->c.id().equals(f.site().id())));
        assertEquals(0,planner.buildingMemoStats().hits());
    }

    static ImportedProductionPlanTest.Fixture planningFixture(Asset asset) {
        var f=ImportedProductionPlanTest.fixture(asset,0);
        var columns=new HashMap<Point3i,SurfaceProbe.ColumnView>();
        for(var p:f.columns().keySet()) columns.put(p,new PlanningColumnView(p.x(),p.z(),AnalyticTerrainOracle.dry(64)));
        return new ImportedProductionPlanTest.Fixture(f.recipe(),f.plan(),f.site(),Collections.unmodifiableMap(columns));
    }
}
