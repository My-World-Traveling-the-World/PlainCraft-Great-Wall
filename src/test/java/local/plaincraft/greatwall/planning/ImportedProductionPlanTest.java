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
}
