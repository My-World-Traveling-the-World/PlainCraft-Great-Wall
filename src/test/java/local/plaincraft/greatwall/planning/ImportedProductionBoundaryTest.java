package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.generation.CoveragePlanner;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import local.plaincraft.greatwall.terrain.FineSampleCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** 用生产采样和相邻段输入核对端面，避免手工补全列的夹具掩盖漏采。 */
class ImportedProductionBoundaryTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { ImportedProductionPlanTest.bootstrap(); }
    @ParameterizedTest @EnumSource(Asset.class)
    void finalRouteSamplingCoversEveryColumnOfTheRealBody(Asset asset) {
        var f = ImportedProductionPlanTest.fixture(asset, 1);
        var path = ImportedSamplePath.of(f.plan());
        try (var oracle = new AnalyticTerrainOracle((x,z) -> AnalyticTerrainOracle.dry(64), true)) {
            var cursor = WallFinePlanner.begin(path, oracle, 0, path.points().size(), FineSampleCache.none(),
                    ignored -> true, new java.util.HashMap<>(), null);
            while (WallFinePlanner.advance(cursor, 4096) != WorkState.DONE) {}
            var index = FineHeightIndex.of(WallFinePlanner.finish(cursor));
            var coverage = new CoveragePlanner(2);
            var cell = new CellKey(f.recipe().recipeHash(), f.recipe().dimension(),
                    Math.floorDiv(f.site().globalSQ8(), 64L * 256));
            var owners = coverage.realizationOwners(cell, new PlanWindow(List.of(f.plan())));
            var columns = new PlanningColumnProvider(index).columnsFor(owners);
            var box = f.site().bounds();
            for (int z = box.minZ(); z < box.maxZExclusive(); z++) for (int x = box.minX(); x < box.maxXExclusive(); x++)
                assertTrue(columns.containsKey(new Point3i(x, 0, z)), asset + " 正式采样漏列: " + x + "," + z);
            var actual = new ImportedProductionPlanTest.Fixture(f.recipe(), f.plan(), f.site(), columns);
            var result = ImportedProductionPlanTest.planner(f.recipe()).resolve(ImportedProductionPlanTest.input(actual, cell.arcCell(), List.of()));
            assertTrue(result.components().stream().anyMatch(c -> c.id().equals(f.site().id())),
                    "正式精采样结果必须真的能编译楼体");
        }
    }

    @Test void aNonAlignedSegmentBoundaryKeepsBothHalfWalls() {
        var f = ImportedProductionPlanTest.fixture(Asset.GATE, 0);
        long split = 450L * 256 + 128;
        var first = slice(f.plan(), 0, 0, split);
        var second = slice(f.plan(), 1, split, f.plan().stations().stations().getLast().globalSQ8());
        var window = new PlanWindow(List.of(first, second));
        var prepared = PlanningOwnerIndex.prepare(window, f.recipe().recipeHash(), f.recipe().dimension(), new CoveragePlanner(2));
        assertTrue(prepared.stream().anyMatch(w -> w.cells().stream().filter(c -> c.cell().arcCell() == 7).count() == 2),
                "同一单元的前后半段必须同时进入正式编译工作项");
        var parts = new ArrayList<ComponentVoxels>();
        for (var plan : List.of(first, second)) {
            var partial = new ImportedProductionPlanTest.Fixture(f.recipe(), plan, f.site(), f.columns());
            var result = ImportedProductionPlanTest.planner(f.recipe()).resolve(ImportedProductionPlanTest.input(partial, 7, List.of()));
            parts.add(result.components().stream().filter(c -> c.id().kind() == SiteKind.WALL).findFirst().orElseThrow());
        }
        assertNotEquals(parts.get(0).id(), parts.get(1).id());
        var whole = ImportedProductionPlanTest.planner(f.recipe()).resolve(ImportedProductionPlanTest.input(f, 7, List.of()))
                .components().stream().filter(c -> c.id().kind() == SiteKind.WALL).findFirst().orElseThrow();
        var expected = whole.voxels().stream().collect(Collectors.toMap(VoxelIntent::pos, VoxelIntent::state));
        var actual = new java.util.HashMap<Point3i, BlockSpec>();
        for (var part : parts) for (var v : part.voxels()) actual.put(v.pos(), v.state());
        assertFalse(expected.isEmpty());
        assertEquals(expected.size(), actual.size(), () -> "拆段后意图数不一致，新增="
                +actual.keySet().stream().filter(p->!expected.containsKey(p)).limit(4).toList()
                +" 缺少="+expected.keySet().stream().filter(p->!actual.containsKey(p)).limit(4).toList());
        for(var entry:expected.entrySet())assertEquals(entry.getValue(),actual.get(entry.getKey()),"拆段改变方块: "+entry.getKey());
    }

    @Test void aHigherBuildingPlatformDoesNotCutOffItsFirstWallColumns() {
        var f=ImportedProductionPlanTest.fixture(Asset.CHENGHAI_TOWER, 0);
        var frame=f.site().frame();
        var raisedFrame=new Frame(frame.center(),frame.txQ30(),frame.tzQ30(),frame.walkY()+24,frame.mirror());
        var site=new SiteRecord(f.site().id(),0,raisedFrame,
                local.plaincraft.greatwall.component.ImportedSiteGeometry.layout(f.site().id(),raisedFrame).boundsWithStraightWalls(0),
                SiteState.PLANNED,Optional.empty(),Optional.empty());
        var stations=f.plan().stations().stations().stream().map(s->new Station(s.globalSQ8(),s.point(),s.walkY()+24,s.flags())).toList();
        var plan=new SegmentPlan(f.plan().geometry(),f.plan().prefix(),new StationTable(stations,List.of(),f.plan().hash()),
                List.of(site),f.plan().hash());
        var elevated=new ImportedProductionPlanTest.Fixture(f.recipe(),plan,site,f.columns());
        var resolved=ImportedProductionPlanTest.planner(f.recipe()).resolve(ImportedProductionPlanTest.input(elevated,0,List.of()));
        var wall=resolved.components().stream().filter(c->c.id().kind()==SiteKind.WALL).findFirst().orElseThrow();
        var port=local.plaincraft.greatwall.component.ImportedSiteGeometry.layout(site).ports().getFirst();
        for(int n=1;n<=8;n++) {
            var floor=port.cell(n,0,-1);
            assertTrue(wall.voxels().stream().anyMatch(v->v.pos().equals(floor)&&v.walkable()),
                    "远处高点抬升整楼后，第一列墙仍应向下填基: "+floor);
        }
    }

    @Test void aKnownWaterTerminalBoundsSamplingAndCompileEnumeration() {
        var f=ImportedProductionPlanTest.fixture(Asset.GATE,0);
        var g=f.plan().geometry();
        var geometry=new SegmentGeometry(g.key(),g.points(),g.localSQ8(),g.water(),
                Optional.of(new Terminal(0,600L*256,TerminalKind.OCEAN)),g.start(),g.end(),g.logicalColumns(),g.degradation(),g.hash());
        var plan=new SegmentPlan(geometry,f.plan().prefix(),f.plan().stations(),f.plan().sites(),f.plan().hash());
        var path=ImportedSamplePath.of(plan);
        assertTrue(path.points().size()>100,"夹具不能空转");
        assertTrue(path.localSQ8().getLast()<=632L*256,"不能因在途 Future 已清理而重采终点之后的整条路线");
        var owners=PlanningOwnerIndex.prepare(new PlanWindow(List.of(plan)),f.recipe().recipeHash(),f.recipe().dimension(),new CoveragePlanner(2));
        assertFalse(owners.isEmpty());
        assertTrue(owners.stream().flatMap(o->o.cells().stream()).allMatch(c->c.cell().arcCell()<=10),
                "不能发布终点远处的空施工单元");
    }

    private static SegmentPlan slice(SegmentPlan source, int index, long from, long to) {
        var stations = new ArrayList<Station>();
        stations.add(at(source.stations().stations(), from));
        source.stations().stations().stream().filter(s -> s.globalSQ8() > from && s.globalSQ8() < to).forEach(stations::add);
        stations.add(at(source.stations().stations(), to));
        var key = new SegmentKey(source.geometry().key().recipeHash(), source.geometry().key().dimension(), index);
        var geometry = new SegmentGeometry(key, stations.stream().map(Station::point).toList(),
                stations.stream().map(s -> s.globalSQ8() - from).toList(), List.of(), Optional.empty(),
                new Boundary(index, stations.getFirst().point(), stations.getFirst().walkY(), source.hash()),
                new Boundary(index + 1, stations.getLast().point(), stations.getLast().walkY(), source.hash()), 0, 0, source.hash());
        return new SegmentPlan(geometry, new PrefixSummary(index, from, Optional.empty(), source.hash()),
                new StationTable(stations, List.of(), source.hash()), source.sites(), source.hash());
    }

    private static Station at(List<Station> stations, long s) {
        for (int i = 1; i < stations.size(); i++) if (s <= stations.get(i).globalSQ8()) {
            var a = stations.get(i-1); var b = stations.get(i);
            long t=s-a.globalSQ8(), n=b.globalSQ8()-a.globalSQ8();
            return new Station(s,new Point2q(a.point().xQ8()+FixedMath.mulDiv(b.point().xQ8()-a.point().xQ8(),t,n),
                    a.point().zQ8()+FixedMath.mulDiv(b.point().zQ8()-a.point().zQ8(),t,n)),
                    a.walkY()+(int)FixedMath.roundDiv((long)(b.walkY()-a.walkY())*t,n),0);
        }
        throw new IllegalArgumentException("站表外的测试分段");
    }
}
