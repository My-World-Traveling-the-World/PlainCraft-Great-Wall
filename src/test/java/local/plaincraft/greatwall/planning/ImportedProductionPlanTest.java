package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import local.plaincraft.greatwall.component.*;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.CoveragePlanner;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import local.plaincraft.greatwall.terrain.RealizationPlanner;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class ImportedProductionPlanTest {
    static final String H = PlanningFixtures.HASH;
    record Fixture(WorldRecipe recipe, SegmentPlan plan, SiteRecord site, Map<Point3i, SurfaceProbe.ColumnView> columns) {}

    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void intactRecipeKeepsAuthoredWalkwayWhereLegacyMakesARuin() {
        var base = fixture(Asset.GATE, 0);
        var original = base.plan();
        // 去掉楼体，避免接口两侧原本就强制完好的规则遮住破坏反例。
        var ribbon = new SegmentPlan(original.geometry(), original.prefix(), original.stations(), List.of(), original.hash());
        var legacy = new Fixture(base.recipe(), ribbon, null, base.columns());
        var intact = new Fixture(ImportedSiteScheduleTest.recipe(3), ribbon, null, base.columns());
        var v2 = planner(legacy.recipe());
        long cell = -1;
        for (long candidate = 1; candidate < 12; candidate++) {
            long middle = candidate * 64L * 256 + 32L * 256;
            if (v2.resolve(input(legacy, candidate, List.of())).breaks().stream()
                    .anyMatch(b -> b.reason() == BreakReason.BURIED
                            && b.range().fromQ8() <= middle && middle < b.range().toQ8())) {
                cell = candidate;
                break;
            }
        }
        assertTrue(cell > 0, "旧配方必须先复现实际马道被截的单元");
        var result = planner(intact.recipe()).resolve(input(intact, cell, List.of()));
        assertTrue(result.breaks().stream().noneMatch(b -> b.reason() == BreakReason.BURIED));
        var wall = result.components().stream().filter(c -> c.id().kind() == SiteKind.WALL).findFirst().orElseThrow();
        assertTrue(wall.voxels().stream().filter(v -> v.part() == Part.FLOOR).count() > 300,
                "真实模板墙必须恢复足量马道方块");
        assertTrue(wall.voxels().stream().anyMatch(v -> v.part() == Part.PARAPET));
    }

    @Test void waterGateBuildsOnShortWaterAndWideWaterLeavesBothBanks() {
        var recipe = ImportedSiteScheduleTest.recipe(3);
        var stations = new ArrayList<Station>();
        for (int x = 0; x <= 384; x += 4)
            stations.add(new Station(x * 256L, new Point2q(x * 256L + 128, 128), 84, 0));
        var shortWater = new WaterEvidence(new ArcInterval(84 * 256L, 98 * 256L),
                WaterClass.NARROW, 14 * 256L, 14 * 256L, 63,
                List.of(new Point3i(83, 63, 0), new Point3i(98, 63, 0)),
                List.of(new Point3i(88, 58, 0), new Point3i(94, 58, 0)), H);
        var wideWater = new WaterEvidence(new ArcInterval(176 * 256L, 231 * 256L),
                WaterClass.WIDE, 0, 55 * 256L, 63, List.of(), List.of(), H);
        var a = stations.getFirst().point();
        var b = stations.getLast().point();
        var geometry = new SegmentGeometry(new SegmentKey(recipe.recipeHash(), recipe.dimension(), 0),
                List.of(a, b), List.of(0L, 384 * 256L), List.of(shortWater, wideWater), Optional.empty(),
                new Boundary(0, a, 84, H), new Boundary(1, b, 84, H), 0, 0, H);
        var ribbon = new SegmentPlan(geometry, PrefixSummaryIndex.origin(), new StationTable(stations, List.of(), H),
                List.of(), H);
        var columns = new HashMap<Point3i, SurfaceProbe.ColumnView>();
        for (int x = -16; x < 400; x++) for (int z = -40; z <= 40; z++)
            columns.put(new Point3i(x, 0, z), x >= 84 && x < 98 || x >= 176 && x < 231 ? WATER : FLAT);
        var fixture = new Fixture(recipe, ribbon, null, columns);
        var planner = planner(recipe);
        var cross = planner.resolve(input(fixture, 1, List.of()));
        var gate = cross.components().stream().filter(c -> c.id().kind() == SiteKind.WATER_GATE).findFirst().orElseThrow();
        // 短水的地面不是假设在桥下平地：真实构件必须既有马道也保住水源。
        assertTrue(gate.voxels().stream().anyMatch(v -> v.part() == Part.FLOOR));
        assertTrue(gate.voxels().stream().anyMatch(v -> v.state().id().equals("minecraft:water")));
        var left = planner.resolve(input(fixture, 2, List.of()));
        var right = planner.resolve(input(fixture, 4, List.of()));
        assertTrue(left.breaks().stream().anyMatch(g -> g.reason() == BreakReason.RIVER_WIDE && g.range().equals(wideWater.crossing())));
        var placed = java.util.stream.Stream.concat(left.components().stream(), right.components().stream())
                .filter(c -> c.id().kind() == SiteKind.WALL)
                .flatMap(c -> c.voxels().stream()).filter(v -> v.part() == Part.FLOOR).toList();
        assertTrue(placed.stream().anyMatch(v -> v.pos().x() < 176));
        assertTrue(placed.stream().anyMatch(v -> v.pos().x() > 231));
        assertFalse(placed.stream().anyMatch(v -> v.pos().x() >= 176 && v.pos().x() < 231));
    }

    @ParameterizedTest @EnumSource(Asset.class)
    void productionRealizationKeepsEveryBodyAndConnectsBothActualEnds(Asset asset) {
        for (int orientation = 0; orientation < 4; orientation++) {
            var f = fixture(asset, orientation);
            var planner = planner(f.recipe());
            var layout = ImportedSiteGeometry.layout(f.site());
            var components = new HashMap<SiteId, ComponentVoxels>();
            var touched = new HashSet<Long>();
            touched.add(Math.floorDiv(f.site().globalSQ8(), 64L * 256));
            for (int p = 0; p < layout.ports().size(); p++) {
                long arc = ImportedSiteGeometry.portArc(f.site(), p);
                for (int delta : new int[] {-4, -1, 0, 1, 4}) if (arc + delta * 256L >= 0)
                    touched.add(Math.floorDiv(arc + delta * 256L, 64L * 256));
            }
            for (long cell : touched) {
                var resolved = planner.resolve(input(f, cell, List.of()));
                for (var c : resolved.components()) components.put(c.id(), c);
            }
            var body = components.get(f.site().id());
            assertNotNull(body, "必须走正式实现器生成整座楼，不能用独立模板编译器冒充接线");
            assertEquals(asset.template().cells().size(), body.voxels().stream()
                    .filter(v -> v.kind() == IntentKind.SOLID && v.part() != Part.FOUNDATION).count());
            assertEquals(asset.template().cells().stream().filter(c -> c.entity() != null).count(), body.blockEntities().size());
            if (asset == Asset.ENEMY_TOWER) {
                assertEquals(3, body.spawnSlots().size(), "替换楼体不能丢掉三个驻军点");
                assertEquals(3, body.spawnSlots().stream().map(SpawnSlot::feet).distinct().count());
                for (var slot : body.spawnSlots()) assertTrue(body.requiredRoutes().getFirst().contains(slot.feet()));
            }
            if (asset == Asset.BEACON) {
                var fires = body.blockEntities().stream().filter(e -> e.type().equals("minecraft:campfire")).toList();
                assertFalse(fires.isEmpty(), "真实烽火不能是只有空列表的身份断言");
                assertEquals(fires.size(), fires.stream().map(e -> e.fields().get("firepitOrdinal")).distinct().count());
                for (var fire : fires) assertEquals(body.id().wallId(), fire.fields().get("beaconWall"));
            }
            var blocks = new HashMap<Point3i, VoxelIntent>();
            for (var c : components.values()) for (var v : c.voxels()) blocks.put(v.pos(), v);
            assertTrue(components.values().stream().anyMatch(c -> c.id().kind() == SiteKind.WALL && !c.voxels().isEmpty()));
            for (var port : layout.ports()) {
                for (int step = 0; step <= 4; step++) {
                    var feet = port.cell(step, 0, 0);
                    var floor = blocks.get(new Point3i(feet.x(), feet.y() - 1, feet.z()));
                    assertNotNull(floor, "建筑接口到墙的第一格不能空: " + asset + " " + orientation + " step=" + step + " " + feet
                            + " ports=" + layout.ports() + " components=" + components.keySet());
                    assertTrue(floor.walkable(), "两侧必须保留真实可走底板: " + feet);
                    for (int t : new int[] {-5, 5}) assertEquals("minecraft:quartz_block",
                            blocks.get(port.cell(step, t, -1)).state().id(), "石英腰线必须逐列齐平");
                }
                for (int step = 1; step <= 3; step++) assertEquals("minecraft:stone_brick_stairs",
                        blocks.get(port.cell(step, 5, 2)).state().id(), "端面缺之后从三列垛起步");
                var gap = blocks.get(port.cell(4, 5, 2));
                assertTrue(gap == null || gap.kind() == IntentKind.OWNED_VOID, "四列循环末尾必须留缺");
            }
            // 对实际区块计划也做核对，防止整件有体素但裁切/实体匹配把它全丢掉。
            var port = layout.ports().getFirst();
            var point = port.cell(1, 0, -1);
            var owner = new ChunkKey(f.recipe().dimension(), Math.floorDiv(point.x(), 16), Math.floorDiv(point.z(), 16));
            var before = new HashMap<Point3i, String>();
            for (var v : blocks.values()) before.put(v.pos(), "minecraft:air{}:fluid=minecraft:empty:be=false");
            var cell = new RealizationCell(new CellKey(f.recipe().recipeHash(), f.recipe().dimension(), 0), List.of(),
                    List.copyOf(components.values()), H, 0, H, H);
            var chunk = new ChunkPlanCompiler().compile(owner, f.recipe().recipeHash(), List.of(cell), before);
            assertTrue(chunk.voxels().stream().anyMatch(v -> v.pos().equals(point) && v.walkable()));
            for (var v : chunk.voxels()) if (v.component().equals(body.id()))
                assertEquals(blocks.get(v.pos()).state(), v.state(), "区块编译不能重新塑形实物的楼梯");
            assertTrue(new CoveragePlanner(2).realizationOwners(new CellKey(f.recipe().recipeHash(), f.recipe().dimension(),
                    Math.floorDiv(f.site().globalSQ8(), 64L * 256)), new PlanWindow(List.of(f.plan()))).contains(owner));
        }
    }

    @Test void actualRoofProtectionRejectsTheWholeBuildingWithAReason() {
        var f = fixture(Asset.GATE, 0);
        var body = planner(f.recipe()).resolve(input(f, 8, List.of())).components().stream()
                .filter(c -> c.id().equals(f.site().id())).findFirst().orElseThrow();
        var roof = body.voxels().stream().filter(v -> v.kind() == IntentKind.SOLID)
                .max(java.util.Comparator.comparingInt(v -> v.pos().y())).orElseThrow().pos();
        var blocked = planner(f.recipe()).resolve(input(f, 8,
                List.of(new Bounds(roof.x(), roof.y(), roof.z(), roof.x() + 1, roof.y() + 1, roof.z() + 1))));
        assertTrue(blocked.components().stream().noneMatch(c -> c.id().equals(f.site().id())));
        assertTrue(blocked.breaks().stream().anyMatch(b -> b.reason() == BreakReason.CONFLICT),
                "拒绝必须保留原因，不能伪装成这里没有建筑");
    }

    @Test void highestInteriorColumnRaisesTheWholeBuildingAndKeepsTheDoorVoid() {
        var f = fixture(Asset.GATE, 0);
        var height = new HeightContractPlanner(f.recipe());
        var provisional = f.site();
        var samples = new HashMap<Point2q, ColumnSample>();
        for (var p : height.samplePoints(f.plan().geometry(), f.plan().prefix(), List.of(provisional)))
            samples.put(p, AnalyticTerrainOracle.dry(64));
        var b = provisional.bounds();
        var interior = new Point2q((b.minX() + 17L) * 256 + 128, (b.minZ() + 7L) * 256 + 128);
        assertTrue(samples.containsKey(interior), "必须采模板内部，不仅采九个代表点");
        samples.put(interior, AnalyticTerrainOracle.dry(89));
        var table = height.solve(f.plan().geometry(), f.plan().prefix(), List.of(provisional), samples);
        var bound = new SiteScheduler(f.recipe()).bindHeights(List.of(provisional), table).getFirst();
        assertEquals(104, bound.frame().walkY(), "最高地表 + 模板 15 格脚底偏移");
        var template = ImportedWallTemplate.Asset.GATE.template();
        assertTrue(template.cells().stream().noneMatch(c -> c.local().x() >= 54 && c.local().x() <= 58
                && c.local().y() <= 4), "门洞底层空气不应进入地基实心集合");
    }

    static Fixture fixture(Asset asset, int rotation) {
        var recipe = ImportedSiteScheduleTest.recipe();
        long s = asset == Asset.CHENGHAI_TOWER ? 0 : 512L * 256;
        int[][] direction = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        int dx = direction[rotation][0], dz = direction[rotation][1];
        // 原路线故意斜行，并穿过负区块；构件的正交旋转不能偷偷依赖世界正坐标。
        var points = List.of(new Point2q(-100 * 256L + 128, -70 * 256L + 128),
                new Point2q((-100L + dx * 1024 - dz * 512) * 256 + 128,
                        (-70L + dz * 1024 + dx * 512) * 256 + 128));
        var arcs = ArcLength.accumulate(points);
        var geometry = new SegmentGeometry(new SegmentKey(recipe.recipeHash(), recipe.dimension(), 0), points, arcs, List.of(), Optional.empty(),
                new Boundary(0, points.getFirst(), 84, H), new Boundary(1, points.getLast(), 84, H), 0, 0, H);
        var kind = switch (asset) { case GATE -> SiteKind.GATE; case ENEMY_TOWER -> SiteKind.ENEMY_TOWER;
            case BEACON -> SiteKind.BEACON; default -> SiteKind.FORT; };
        var id = new SiteId(recipe.wallId(), kind, asset == Asset.CHENGHAI_TOWER ? 0 : 1);
        var center = local.plaincraft.greatwall.geometry.ArcLength.pointAt(geometry, s);
        var frame = ImportedSiteGeometry.cardinal(new Frame(center, dx * (1 << 30), dz * (1 << 30), 84, true));
        var provisional = new SiteRecord(id, s, frame, ImportedSiteGeometry.layout(id, frame).boundsWithStraightWalls(0),
                SiteState.PLANNED, Optional.empty(), Optional.empty());
        var heights = new HeightContractPlanner(recipe);
        var columns = new HashMap<Point2q, ColumnSample>();
        var prefix = PrefixSummaryIndex.origin();
        for (var p : heights.samplePoints(geometry, prefix, List.of(provisional))) columns.put(p, AnalyticTerrainOracle.dry(64));
        var table = heights.solve(geometry, prefix, List.of(provisional), columns);
        var site = new SiteScheduler(recipe).bindHeights(List.of(provisional), table).getFirst();
        var plan = new SegmentPlan(geometry, prefix, table, List.of(site), H);
        var evidence = new HashMap<Point3i, SurfaceProbe.ColumnView>();
        for (var station : table.stations()) {
            int x = FixedMath.floorBlock(station.point().xQ8()), z = FixedMath.floorBlock(station.point().zQ8());
            for (int ox = -20; ox <= 20; ox++) for (int oz = -20; oz <= 20; oz++)
                evidence.putIfAbsent(new Point3i(x + ox, 0, z + oz), flat());
        }
        var box = site.bounds();
        for (int x = box.minX() - 2; x <= box.maxXExclusive() + 2; x++)
            for (int z = box.minZ() - 2; z <= box.maxZExclusive() + 2; z++)
                evidence.putIfAbsent(new Point3i(x, 0, z), flat());
        return new Fixture(recipe, plan, site, java.util.Collections.unmodifiableMap(evidence));
    }

    static RealizationPlanner planner(WorldRecipe recipe) {
        return new RealizationPlanner(recipe, new SurfaceProbe(), S2TemplateSet.create(recipe.seed(),
                (i, p, l, w, s, h) -> S1TemplateSet.material(i.family(), i.redSandstone(), p)));
    }
    static RealizationPlanner.Input input(Fixture f, long cell, List<Bounds> protection) {
        return new RealizationPlanner.Input(new CellKey(f.recipe().recipeHash(), f.recipe().dimension(), cell), new PlanWindow(List.of(f.plan())),
                Map.of(), f.columns(), protection, Map.of(), 0, H);
    }
    private static SurfaceProbe.ColumnView flat() {
        // 共享不可变地表，降低离线夹具内存；具体 XZ 只在施工意图里记录。
        return FLAT;
    }
    private static final SurfaceProbe.ColumnView FLAT = new SurfaceProbe.ColumnView() {
        public String state(int y) { return y < 64 ? "minecraft:stone{}" : "minecraft:air{}"; }
        public boolean solidSupport(int y) { return y < 64; }
        public boolean protectedAt(int y) { return false; }
        public boolean water(int y) { return false; }
        public boolean airOrReplaceablePlant(int y) { return y >= 64; }
        public int highestNonPlantFeet() { return 64; }
        public String hash() { return H; }
    };
    private static final SurfaceProbe.ColumnView WATER = new SurfaceProbe.ColumnView() {
        public String state(int y) { return y <= 58 ? "minecraft:stone{}" : y <= 63 ? "minecraft:water{level=0}" : "minecraft:air{}"; }
        public boolean solidSupport(int y) { return y <= 58; }
        public boolean protectedAt(int y) { return false; }
        public boolean water(int y) { return y > 58 && y <= 63; }
        public boolean airOrReplaceablePlant(int y) { return y > 63; }
        public int highestNonPlantFeet() { return 64; }
        public String hash() { return H; }
    };
}
