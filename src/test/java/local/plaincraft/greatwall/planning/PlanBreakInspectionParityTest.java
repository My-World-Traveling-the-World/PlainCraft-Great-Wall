package local.plaincraft.greatwall.planning;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.generation.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 保留逐单元原判据作独立对照；集中预检只能减少重复扫描，不能换断点或理由。 */
class PlanBreakInspectionParityTest {
    @BeforeAll static void bootstrap(){ImportedProductionPlanTest.bootstrap();}

    @ParameterizedTest @EnumSource(Asset.class)
    void currentTemplateBuildingsAndTheirProtectedRejectionsMatchTheCellUnion(Asset asset) {
        var f=ImportedProductionPlanTest.fixture(asset,asset.ordinal()%4,3);
        assertEquals(SiteState.PLANNED,f.site().state());
        var index=ImportedProductionBoundaryTest.exactIndex(f.plan(),(x,z)->AnalyticTerrainOracle.dry(64));
        for(var protection:List.of(List.<Bounds>of(),List.of(f.site().bounds()))) {
            var result=compare(new RealizedCapSamplingTest.Fixture(f.recipe(),f.plan(),index,protection),false);
            if(!protection.isEmpty())assertTrue(result.breaks().stream().anyMatch(b->b.reason()==BreakReason.CONFLICT),
                    "建筑必须确实被拒绝，不能拿空断点给整段预检背书");
        }
    }

    @Test void protectedStraightWallKeepsEveryReasonWithFewerActualSupportProbes() {
        var result=compare(RealizedCapSamplingTest.fixture(8),true);
        assertFalse(result.breaks().isEmpty());assertTrue(result.planProbes()>0);
        assertTrue(result.cellProbes()>result.planProbes(),"必须真正减少探针，不能仅重命名外层计数");
    }

    @Test void terminalUsesEnumeratedCoreRangeInsteadOfTheUnbuiltStationTail() {
        var f=RealizedCapSamplingTest.fixture();var g=f.plan().geometry();
        var terminal=new SegmentGeometry(g.key(),g.points(),g.localSQ8(),g.water(),
                Optional.of(new Terminal(0,384L*256,TerminalKind.OCEAN)),g.start(),g.end(),g.logicalColumns(),g.degradation(),g.hash());
        var plan=new SegmentPlan(terminal,f.plan().prefix(),f.plan().stations(),f.plan().sites(),f.plan().hash());
        var result=compare(new RealizedCapSamplingTest.Fixture(f.recipe(),plan,f.index(),f.protection()),false);
        assertTrue(result.range().toQ8()<plan.stations().stations().getLast().globalSQ8());
        assertTrue(result.breaks().stream().anyMatch(b->b.range().fromQ8()==384L*256));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void bankedWaterGateSuccessAndMissingSupportMatchTheCellUnion(boolean unsupported) {
        var f=RealizedCapSamplingTest.fixture();var g=f.plan().geometry();
        var water=new WaterEvidence(new ArcInterval(120L*256,134L*256),WaterClass.NARROW,14L*256,14L*256,63,
                List.of(new Point3i(119,63,0),new Point3i(134,63,0)),List.of(new Point3i(122,58,0),new Point3i(131,58,0)),g.hash());
        var wet=new SegmentGeometry(g.key(),g.points(),g.localSQ8(),List.of(water),g.terminal(),g.start(),g.end(),0,0,g.hash());
        var plan=new SegmentPlan(wet,f.plan().prefix(),f.plan().stations(),List.of(),f.plan().hash());
        var index=ImportedProductionBoundaryTest.exactIndex(plan,(x,z)->x>=120&&x<134
                ?AnalyticTerrainOracle.water(63,unsupported?-40:58):AnalyticTerrainOracle.dry(64));
        var result=compare(new RealizedCapSamplingTest.Fixture(f.recipe(),plan,index,List.of()),false);
        assertEquals(unsupported,result.breaks().stream().anyMatch(b->b.reason()==BreakReason.NO_SUPPORT),
                "必须分别真正走通水门成功和无支撑拒绝");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void rightAngleAndNearbyReturningWallKeepIndependentColumnDecisions(boolean hairpin) {
        var f=RealizedCapSamplingTest.fixture();var stations=new ArrayList<Station>();
        for(int s=0;s<=512;s+=4) {
            int x,z;
            if(hairpin){x=s<=248?s:s<=264?248:512-s;z=s<=248?0:s<=264?s-248:16;}
            else{x=Math.min(s,256);z=Math.max(0,s-256);}
            stations.add(new Station(s*256L,new Point2q(x*256L+128,z*256L+128),84,0));
        }
        var g=f.plan().geometry();var a=stations.getFirst().point();var b=stations.getLast().point();
        var geometry=new SegmentGeometry(g.key(),stations.stream().map(Station::point).toList(),
                stations.stream().map(Station::globalSQ8).toList(),List.of(),Optional.empty(),
                new Boundary(0,a,84,g.hash()),new Boundary(1,b,84,g.hash()),0,0,g.hash());
        var plan=new SegmentPlan(geometry,f.plan().prefix(),new StationTable(stations,List.of(),f.plan().hash()),List.of(),f.plan().hash());
        var index=ImportedProductionBoundaryTest.exactIndex(plan,(x,z)->AnalyticTerrainOracle.dry(64));
        assertFalse(compare(new RealizedCapSamplingTest.Fixture(f.recipe(),plan,index,f.protection()),false).breaks().isEmpty());
    }

    private record Counts(List<BreakInterval> breaks,int cellProbes,int planProbes,ArcInterval range) {}
    private static Counts compare(RealizedCapSamplingTest.Fixture f,boolean counted) {
        var inputs=RealizedCapSamplingTest.inputs(f,f.index(),new CoveragePlanner(3,true));assertFalse(inputs.isEmpty());
        var all=new HashMap<Point3i,SurfaceProbe.ColumnView>();
        var expected=new ArrayList<BreakInterval>();var counter=new AtomicInteger();
        long from=inputs.keySet().stream().mapToLong(Long::longValue).min().orElseThrow()*64L*256;
        long to=(inputs.keySet().stream().mapToLong(Long::longValue).max().orElseThrow()+1)*64L*256;
        var range=new ArcInterval(from,to);
        try(var cellWindow=ImportedProductionPlanTest.planner(f.recipe()).openWindow();
                var planWindow=ImportedProductionPlanTest.planner(f.recipe()).openWindow()) {
            for(var input:inputs.values()) {
                var columns=columns(input.columns(),counter,counted);all.putAll(columns);
                expected.addAll(cellWindow.inspectBreaks(withColumns(input,columns)));
            }
            int cellProbes=counter.getAndSet(0);
            var actual=planWindow.inspectPlanBreaks(withColumns(inputs.values().iterator().next(),all),range);
            assertEquals(GroundExitResolver.merge(expected),GroundExitResolver.merge(actual),"范围、断点原因和scope必须逐项等价");
            return new Counts(actual,cellProbes,counter.get(),range);
        }
    }

    private static RealizationPlanner.Input withColumns(RealizationPlanner.Input i,Map<Point3i,SurfaceProbe.ColumnView> columns){
        return new RealizationPlanner.Input(i.key(),i.plans(),i.tiles(),columns,i.protectedBounds(),i.frozenGateY(),
                i.gateRevision(),i.protectionHash(),i.foundationFloor(),i.foundations());
    }
    private static Map<Point3i,SurfaceProbe.ColumnView> columns(Map<Point3i,SurfaceProbe.ColumnView> input,AtomicInteger count,boolean counted) {
        if(!counted)return input;
        var output=new HashMap<Point3i,SurfaceProbe.ColumnView>();
        input.forEach((p,v)->output.put(p,new SurfaceProbe.ColumnView(){
            public String state(int y){return v.state(y);}public boolean solidSupport(int y){return v.solidSupport(y);}
            public boolean protectedAt(int y){return v.protectedAt(y);}public boolean water(int y){return v.water(y);}
            public boolean airOrReplaceablePlant(int y){return v.airOrReplaceablePlant(y);}
            public int highestNonPlantFeet(){return v.highestNonPlantFeet();}
            public String hash(){count.incrementAndGet();return v.hash();}
        }));
        return output;
    }
}
