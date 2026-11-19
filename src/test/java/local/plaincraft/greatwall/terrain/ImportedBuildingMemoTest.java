package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.diagnostics.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningColumnView;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 缓存键与容量单测不冒充楼体生成测试，正式产物另由ImportedBuildingReuseTest覆盖。 */
class ImportedBuildingMemoTest {
    private static final String H="a".repeat(64);
    private static final DimensionBounds LIMITS=new DimensionBounds(-64,320,30000000);
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    @Test void lruAndByteBudgetBoundRetainedBodiesAndKeys() {
        long limit=200000;
        var memo=new ImportedBuildingMemo(limit);
        for(int i=0;i<30;i++) {
            var c=candidate(i);var input=input(c,0,H,List.of(),Map.of(),columns(c));
            memo.get(c,input,()->body(c));
            assertTrue(memo.stats().bytes()<=limit);
            assertTrue(memo.stats().entries()<=8);
        }
        assertTrue(memo.stats().entries()>0);assertTrue(memo.stats().entries()<30);
        assertTrue(memo.stats().peak()<=limit);
        assertEquals(30,memo.stats().loads());
        memo.clear();assertEquals(0,memo.stats().bytes());assertEquals(0,memo.stats().entries());
    }

    @Test void exactProtectionGateAndRecipeVersionsNeverShareResults() {
        var memo=new ImportedBuildingMemo();var c=candidate(1);var columns=columns(c);
        var original=input(c,0,H,List.of(),Map.of(),columns);
        var calls=new AtomicInteger();
        java.util.function.Supplier<ComponentVoxels> produce=()->{calls.incrementAndGet();return body(c);};
        memo.get(c,original,produce);memo.get(c,original,produce);
        assertEquals(1,calls.get());assertEquals(1,memo.stats().hits());
        memo.get(c,input(c,1,H,List.of(),Map.of(),columns),produce);
        memo.get(c,input(c,0,"b".repeat(64),List.of(),Map.of(),columns),produce);
        memo.get(c,input(c,0,H,List.of(new Bounds(0,200,0,1,201,1)),Map.of(),columns),produce);
        memo.get(c,input(c,0,H,List.of(),Map.of(new GateId("wall","planned",1),80),columns),produce);
        var foreign=new RealizationPlanner.Input(new CellKey("b".repeat(64),"minecraft:overworld",0),
                original.plans(),Map.of(),columns,List.of(),Map.of(),0,H);
        memo.get(c,foreign,produce);
        assertEquals(6,calls.get());
        assertEquals(1,memo.stats().hits());
    }

    @Test void unknownColumnImplementationIsNeverAssumedImmutable() {
        var c=candidate(1);var columns=columns(c);
        columns.put(columns.keySet().iterator().next(),new SurfaceProbe.ColumnView(){
            public String state(int y){return "minecraft:air{}";}
            public boolean solidSupport(int y){return false;} public boolean protectedAt(int y){return false;}
            public boolean water(int y){return false;} public boolean airOrReplaceablePlant(int y){return true;}
            public int highestNonPlantFeet(){return 64;} public String hash(){return H;}
        });
        var memo=new ImportedBuildingMemo();var input=input(c,0,H,List.of(),Map.of(),columns);
        memo.get(c,input,()->body(c));memo.get(c,input,()->body(c));
        assertEquals(2,memo.stats().loads());assertEquals(0,memo.stats().hits());assertEquals(0,memo.stats().bytes());
    }

    @Test void hitStillChargesTheReceivingAllocationArena() {
        var c=candidate(1);var input=input(c,0,H,List.of(),Map.of(),columns(c));
        var memo=new ImportedBuildingMemo();var value=memo.get(c,input,()->body(c));
        try(var budget=new AllocationBudget(1024*1024)) {
            var hit=CompiledAllocation.run(budget,()->memo.get(c,input,()->fail("必须命中")),
                    CompiledAllocation::bytes);
            assertSame(value,hit);
            assertEquals(CompiledAllocation.bytes(hit),budget.stats().used());
        }
    }

    private static ComponentInput candidate(long ordinal) {
        var id=new SiteId("wall",SiteKind.BEACON,ordinal);
        var frame=new Frame(new Point2q(128,128),1<<30,0,79,false);
        var site=new SiteRecord(id,256,frame,ImportedSiteGeometry.layout(id,frame).boundsWithStraightWalls(0),
                SiteState.PLANNED,Optional.empty(),Optional.empty());
        return new ComponentInput(site,List.of(),Map.of(),new DecaySample(DecayState.INTACT,0,20),
                PaletteFamily.STONE,false,List.of(),List.of(),new GroundEntranceContract.Evidence(
                        GroundEntranceContract.Source.DURABLE_T0,LIMITS,Map.of()));
    }
    private static Map<Point3i,SurfaceProbe.ColumnView> columns(ComponentInput c) {
        var result=new HashMap<Point3i,SurfaceProbe.ColumnView>();var b=c.site().bounds();
        for(int z=b.minZ();z<b.maxZExclusive();z++)for(int x=b.minX();x<b.maxXExclusive();x++)
            result.put(new Point3i(x,0,z),new PlanningColumnView(x,z,AnalyticTerrainOracle.dry(64)));
        return result;
    }
    private static RealizationPlanner.Input input(ComponentInput c,long revision,String protection,List<Bounds> boxes,
            Map<GateId,Integer> gates,Map<Point3i,SurfaceProbe.ColumnView> columns) {
        return new RealizationPlanner.Input(new CellKey(H,"minecraft:overworld",0),new PlanWindow(List.of()),
                Map.of(),columns,boxes,gates,revision,protection);
    }
    private static ComponentVoxels body(ComponentInput c) {
        return new ComponentVoxels(c.site().id(),c.site().bounds(),List.of(),List.of(),List.of(),List.of(),H);
    }
}
