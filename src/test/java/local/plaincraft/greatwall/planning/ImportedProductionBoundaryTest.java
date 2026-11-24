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
        var path = ImportedSamplePath.of(f.plan(), true);
        try (var oracle = new AnalyticTerrainOracle((x,z) -> AnalyticTerrainOracle.dry(64), true)) {
            var cursor = WallFinePlanner.begin(path, oracle, 0, path.points().size(), FineSampleCache.none(),
                    ignored -> true, new java.util.HashMap<>(), null);
            while (WallFinePlanner.advance(cursor, 4096) != WorkState.DONE) {}
            var index = FineHeightIndex.of(WallFinePlanner.finish(cursor), true);
            var coverage = new CoveragePlanner(2, true);
            var cell = new CellKey(f.recipe().recipeHash(), f.recipe().dimension(),
                    Math.floorDiv(f.site().globalSQ8(), 64L * 256));
            var owners = coverage.realizationOwners(cell, new PlanWindow(List.of(f.plan())));
            var columns = new PlanningColumnProvider(index).columnsFor(owners);
            var box = f.site().bounds();
            // 端面出口也必须来自真实样本，不能在模板恰好跨区块时靠邻列补洞。
            for (int z = box.minZ() - 1; z <= box.maxZExclusive(); z++) for (int x = box.minX() - 1; x <= box.maxXExclusive(); x++) {
                assertTrue(index.contains(x, z), asset + " 未实采: " + x + "," + z);
                assertTrue(columns.containsKey(new Point3i(x, 0, z)), asset + " 编译输入漏列: " + x + "," + z);
            }
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

    @Test void shortWaterAcrossAPlanningSeamKeepsOneGateAndBothWallApproaches() {
        var recipe = AdaptiveSiteSearchTest.recipe();
        var terrain = (java.util.function.BiFunction<Integer, Integer, ColumnSample>) (x, z) ->
                x >= 120 && x < 134 ? AnalyticTerrainOracle.water(63, 58) : AnalyticTerrainOracle.dry(64);
        var parts = new ArrayList<ComponentVoxels>();
        // 两段各自收到完整跨水证据；水门中心只归一段，另一段仍须给它让出两岸接口。
        for (int i = 0; i < 2; i++) {
            int start = 128 * i, end = start + 128;
            var stations = new ArrayList<Station>();
            for (int x = start; x <= end; x += 4)
                stations.add(new Station(x * 256L, new Point2q(x * 256L + 128, 128), 84, 0));
            var water = new WaterEvidence(new ArcInterval((120L - start) * 256, (134L - start) * 256),
                    WaterClass.NARROW, 14 * 256L, 14 * 256L, 63,
                    List.of(new Point3i(119, 63, 0), new Point3i(134, 63, 0)),
                    List.of(new Point3i(122, 58, 0), new Point3i(131, 58, 0)), PlanningFixtures.HASH);
            var a = stations.getFirst().point();
            var b = stations.getLast().point();
            var geometry = new SegmentGeometry(new SegmentKey(recipe.recipeHash(), recipe.dimension(), i),
                    List.of(a, b), List.of(0L, 128 * 256L), List.of(water), Optional.empty(),
                    new Boundary(i, a, 84, PlanningFixtures.HASH), new Boundary(i + 1, b, 84, PlanningFixtures.HASH),
                    0, 0, PlanningFixtures.HASH);
            var plan = new SegmentPlan(geometry, new PrefixSummary(i, start * 256L, Optional.empty(), PlanningFixtures.HASH),
                    new StationTable(stations, List.of(), PlanningFixtures.HASH), List.of(), PlanningFixtures.HASH);
            var index = exactIndex(plan, terrain);
            var cell = new CellKey(recipe.recipeHash(), recipe.dimension(), 1 + i);
            var owners = new CoveragePlanner(3, true).realizationOwners(cell, new PlanWindow(List.of(plan)));
            var fixture = new ImportedProductionPlanTest.Fixture(recipe, plan, null, new PlanningColumnProvider(index).columnsFor(owners));
            var result = ImportedProductionPlanTest.planner(recipe).resolve(ImportedProductionPlanTest.input(fixture, cell.arcCell(), List.of()));
            assertTrue(result.breaks().isEmpty(), result.breaks().toString());
            parts.addAll(result.components());
        }
        var gates = parts.stream().filter(c -> c.id().kind() == SiteKind.WATER_GATE).toList();
        assertEquals(1, gates.size());
        assertTrue(gates.getFirst().voxels().stream().anyMatch(v -> v.state().id().equals("minecraft:water")));
        var floors = new java.util.HashSet<Integer>();
        for (var part : parts) for (var v : part.voxels())
            if (v.part() == Part.FLOOR && v.pos().z() == 0) floors.add(v.pos().x());
        for (int x = 106; x <= 147; x++) assertTrue(floors.contains(x), "跨段马道缺列 X=" + x);
    }

    @Test void anUnevenGateSiteRaisesThePlatformWithoutFillingTheDoorway() {
        var original = ImportedProductionPlanTest.fixture(Asset.GATE, 0);
        var recipe = AdaptiveSiteSearchTest.recipe();
        var site = original.site();
        var geometry = original.plan().geometry();
        var prefix = original.plan().prefix();
        var box = site.bounds();
        // 单个离开中线的高点抬升整楼，其余低地仍应填到实物底板。
        java.util.function.BiFunction<Integer, Integer, ColumnSample> terrain = (x, z) ->
                AnalyticTerrainOracle.dry(x == box.minX() + 2 && z == box.minZ() + 2 ? 89 : 64);
        var height = new HeightContractPlanner(recipe);
        var columns = new java.util.HashMap<Point2q, ColumnSample>();
        for (var p : height.samplePoints(geometry, prefix, List.of(site)))
            columns.put(p, terrain.apply(FixedMath.floorBlock(p.xQ8()), FixedMath.floorBlock(p.zQ8())));
        var solved = height.solveWithSites(geometry, prefix, List.of(site), columns);
        assertTrue(solved.stations().breaks().isEmpty());
        var raised = new SiteScheduler(recipe).bindHeights(solved.sites(), solved.stations()).getFirst();
        assertEquals(SiteState.PLANNED, raised.state());
        assertEquals(104, raised.frame().walkY());
        for (long s = 350; s < 670; s++)
            assertTrue(Math.abs(height.walkY(solved.stations(), s * 256) - height.walkY(solved.stations(), (s + 1) * 256)) <= 1,
                    "墙楼接坡在里程 " + s + " 跳级");
        var plan = new SegmentPlan(geometry, prefix, solved.stations(), List.of(raised), original.plan().hash());
        var cell = new CellKey(recipe.recipeHash(), recipe.dimension(), raised.globalSQ8() / (64 * 256));
        var owners = new CoveragePlanner(3, true).realizationOwners(cell, new PlanWindow(List.of(plan)));
        var fixture = new ImportedProductionPlanTest.Fixture(recipe, plan, raised,
                new PlanningColumnProvider(exactIndex(plan, terrain)).columnsFor(owners));
        var body = ImportedProductionPlanTest.planner(recipe).resolve(ImportedProductionPlanTest.input(fixture, cell.arcCell(), List.of()))
                .components().stream().filter(c -> c.id().equals(raised.id())).findFirst().orElseThrow();
        var frame = raised.frame();
        var feet = new Point3i(FixedMath.floorBlock(frame.center().xQ8()), frame.walkY(), FixedMath.floorBlock(frame.center().zQ8()));
        var layout = local.plaincraft.greatwall.component.ImportedSiteGeometry.layout(raised);
        var gate = local.plaincraft.greatwall.component.ImportedGateGeometry.gate();
        var doorway = gate.place(new Point3i(56, -1, 15), feet, layout.rotation());
        assertTrue(body.voxels().stream().anyMatch(v -> v.part() == Part.FOUNDATION && v.pos().y() <= 65));
        assertFalse(body.voxels().stream().anyMatch(v -> v.part() == Part.FOUNDATION
                && v.pos().x() == doorway.x() && v.pos().z() == doorway.z()), "不能把门洞底下填成实心柱");
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

    // 通过真实精采游标生成索引，夹具只控制自然地表输入。
    static FineHeightIndex exactIndex(SegmentPlan plan, java.util.function.BiFunction<Integer, Integer, ColumnSample> terrain) {
        var path = ImportedSamplePath.of(plan, true);
        try (var oracle = new AnalyticTerrainOracle(terrain, true)) {
            var cursor = WallFinePlanner.begin(path, oracle, 0, path.points().size(), FineSampleCache.none(),
                    ignored -> true, new java.util.HashMap<>(), new java.util.HashSet<>());
            while (WallFinePlanner.advance(cursor, 4096) != WorkState.DONE) {}
            return FineHeightIndex.of(WallFinePlanner.finish(cursor), true);
        }
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
