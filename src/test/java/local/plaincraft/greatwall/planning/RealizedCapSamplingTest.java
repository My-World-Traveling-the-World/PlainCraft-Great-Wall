package local.plaincraft.greatwall.planning;

import java.util.*;
import local.plaincraft.greatwall.generation.CoveragePlanner;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 保护冲突必须在真实窄带之后出现，不能预塞计划断点替补采链背书。 */
class RealizedCapSamplingTest {
    static final String H=PlanningFixtures.HASH;
    record Fixture(WorldRecipe recipe,SegmentPlan plan,FineHeightIndex index,List<Bounds> protection) {}

    @BeforeAll static void bootstrap(){ImportedProductionPlanTest.bootstrap();}

    static Fixture fixture() {
        return fixture(0);
    }

    static Fixture fixture(int originZ) {
        var old=AdaptiveSiteSearchTest.recipe();
        var recipe=new WorldRecipe(old.instance(),old.wallId(),old.seed(),old.dimension(),old.frozenSpawn(),
                old.axis(),old.bounds(),old.config(),old.terrainHash(),old.paletteHash(),old.templatesHash(),
                DeterministicHash.sha256(CanonicalCodec.recipe(old)));
        var stations=new ArrayList<Station>();
        for(int s=0;s<=512;s+=4)stations.add(new Station(s*256L,new Point2q(s*256L+128,originZ*256L+128),84,0));
        var a=stations.getFirst().point();var b=stations.getLast().point();
        var raw=new SegmentGeometry(new SegmentKey(recipe.recipeHash(),recipe.dimension(),0),List.of(a,b),
                List.of(0L,512L*256),List.of(),Optional.empty(),new Boundary(0,a,84,H),new Boundary(1,b,84,H),0,0,H);
        var geometry=new SegmentGeometry(raw.key(),raw.points(),raw.localSQ8(),raw.water(),raw.terminal(),
                raw.start(),raw.end(),0,0,CanonicalCodec.hashGeometry(raw));
        var table=new StationTable(stations,List.of(),CanonicalCodec.hashStations(new StationTable(stations,List.of(),H)));
        var original=new SegmentPlan(geometry,PrefixSummaryIndex.origin(),table,List.of(),H);
        var plan=new SegmentPlan(geometry,original.prefix(),table,List.of(),DeterministicHash.sha256(CanonicalCodec.plan(original)));
        return new Fixture(recipe,plan,ImportedProductionBoundaryTest.exactIndex(plan,(x,z)->AnalyticTerrainOracle.dry(64)),
                List.of(new Bounds(250,0,originZ-1,252,128,originZ+2)));
    }

    static Map<Long,RealizationPlanner.Input> inputs(Fixture f,FineHeightIndex index,CoveragePlanner coverage) {
        var context=new PlanningCompileContext(index,f.protection());
        var provider=new PlanningColumnProvider(index);
        var result=new TreeMap<Long,RealizationPlanner.Input>();
        for(var owner:PlanningOwnerIndex.prepare(new PlanWindow(List.of(f.plan())),f.recipe().recipeHash(),f.recipe().dimension(),coverage))
            for(var work:owner.cells())result.computeIfAbsent(work.cell().arcCell(),ignored->
                    context.input(work,provider.columnsFor(work.captureOwners())));
        return result;
    }

    @Test void protectedBreakAcrossCellBoundaryNeedsEvidenceBeyondOriginalWallBand() {
        var f=fixture();
        assertTrue(f.plan().stations().breaks().isEmpty());
        assertTrue(ImportedSamplingFootprint.attachments(f.plan()).isEmpty());
        assertTrue(f.index().contains(250,0));
        assertFalse(f.index().contains(242,-15));
        var inputs=inputs(f,f.index(),new CoveragePlanner(3,true));
        var planner=ImportedProductionPlanTest.planner(f.recipe());
        var gaps=new ArrayList<BreakInterval>();
        try(var window=planner.openWindow()) {
            for(long cell:List.of(3L,4L)) {
                var original=window.inspect(inputs.get(cell)).breaks();
                var light=window.inspectBreaks(inputs.get(cell));
                assertEquals(original,light,"轻量预检必须沿用同一保护和楼体裁决");
                gaps.addAll(light);
            }
        }
        var gap=GroundExitResolver.merge(gaps).stream().filter(g->g.scope()==BreakScope.REALIZED
                &&g.reason()==BreakReason.STRUCTURE_CONFLICT).findFirst().orElseThrow();
        assertTrue(gap.range().fromQ8()<256L*256);
        assertTrue(gap.range().toQ8()>256L*256);
        var edge=new GroundExitDecision.Edge(gap.range().fromQ8(),false,new ArcInterval(0,gap.range().fromQ8()));
        // 先钉住当前实际症状，后续用生产补采入口证明同一输入的收口能够落块。
        assertEquals(FailureCode.T0_MISSING,assertThrows(WallFailure.class,()->planner.probeCap(inputs.get(3L),edge)).code());
    }

    @Test void breakInspectionAvoidsDigestingEveryCapturedColumn() {
        var f=fixture();
        var input=inputs(f,f.index(),new CoveragePlanner(3,true)).get(3L);
        var hashes=new java.util.concurrent.atomic.AtomicInteger();
        var columns=new HashMap<Point3i,local.plaincraft.greatwall.generation.SurfaceProbe.ColumnView>();
        input.columns().forEach((key,value)->columns.put(key,new local.plaincraft.greatwall.generation.SurfaceProbe.ColumnView(){
            public String state(int y){return value.state(y);}
            public boolean solidSupport(int y){return value.solidSupport(y);}
            public boolean protectedAt(int y){return value.protectedAt(y);}
            public boolean water(int y){return value.water(y);}
            public boolean airOrReplaceablePlant(int y){return value.airOrReplaceablePlant(y);}
            public int highestNonPlantFeet(){return value.highestNonPlantFeet();}
            public String hash(){hashes.incrementAndGet();return value.hash();}
        }));
        var counted=new RealizationPlanner.Input(input.key(),input.plans(),input.tiles(),columns,input.protectedBounds(),
                input.frozenGateY(),input.gateRevision(),input.protectionHash(),input.foundationFloor(),input.foundations());
        var planner=ImportedProductionPlanTest.planner(f.recipe());
        var light=planner.inspectBreaks(counted);int lightHashes=hashes.getAndSet(0);
        var full=planner.inspect(counted);
        assertEquals(full.breaks(),light);
        assertFalse(light.isEmpty());
        assertTrue(lightHashes>0,"实际保护探针仍要运行，不能直接返回空预检");
        assertEquals(lightHashes+columns.size(),hashes.get(),"补采预检不能提前生成补列前的全列摘要");
    }
}
