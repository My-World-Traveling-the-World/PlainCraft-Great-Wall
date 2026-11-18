package local.plaincraft.greatwall.generation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import local.plaincraft.greatwall.component.ImportedGateBody;
import local.plaincraft.greatwall.component.ImportedGateGeometry;
import local.plaincraft.greatwall.geometry.Rasterizer;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImportedGatePlanTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void lowTerrainFillsOnlyUnderTheOriginalBottomAndLeavesTheGateOpen() {
        var feet = new Point3i(-19, 81, 29);
        var site = new SiteId("fixture", SiteKind.GATE, 31);
        var rotation = Rotation.CLOCKWISE_90;
        var geometry = ImportedGateGeometry.gate();
        var obstacle = geometry.place(new Point3i(0, -1, 19), feet, rotation);
        var columns = new HashSet<Point3i>();
        var body = ImportedGateBody.compileOnTerrain(site, feet, rotation, 42,
                new DimensionBounds(-64, 320, 29_999_000), key -> {
                    assertTrue(columns.add(key), "每个底板列只取一次施工前证据");
                    return ground(64, columnKey(obstacle).equals(key) ? obstacle.y() : null, null);
                });
        var foundation = new HashMap<Point3i, VoxelIntent>();
        for (var v : body.voxels()) if (v.part() == Part.FOUNDATION) assertNull(foundation.put(v.pos(), v));
        // 实物底板有 2322 列；两格落差减去一格现有方块，其余用众数石头补齐。
        assertEquals(2322, columns.size());
        assertEquals(4643, foundation.size());
        assertTrue(foundation.values().stream().allMatch(v -> v.state().id().equals("minecraft:stone")));
        assertEquals(64, body.bounds().minY());
        assertFalse(foundation.containsKey(obstacle));
        assertTrue(foundation.containsKey(new Point3i(obstacle.x(), 64, obstacle.z())));
        // 五格宽门洞的拱顶有方块，但底板留空：即使下方是低地，也不能让拱顶向下长成柱子。
        for (int x = 54; x < 59; x++) for (int z = 0; z < 32; z++) {
            var p = geometry.place(new Point3i(x, -1, z), feet, rotation);
            assertFalse(columns.contains(columnKey(p)), "门洞列不参与底板下填：" + p);
            assertFalse(foundation.containsKey(p));
        }
    }

    @Test void missingOrProtectedFoundationEvidenceRejectsTheBody() {
        var site = new SiteId("fixture", SiteKind.GATE, 31);
        var feet = new Point3i(0, 81, 0);
        var limits = new DimensionBounds(-64, 320, 29_999_000);
        // 用需要下填的底板列触发保护判据，避免平地夹具根本走不到写前检查。
        var missing = assertThrows(WallFailure.class, () -> ImportedGateBody.compileOnTerrain(
                site, feet, Rotation.NONE, 42, limits, p -> null));
        assertEquals(FailureCode.T0_MISSING, missing.code());
        var protectedFill = assertThrows(WallFailure.class, () -> ImportedGateBody.compileOnTerrain(
                site, feet, Rotation.NONE, 42, limits, p -> ground(64, null, 65)));
        assertEquals(FailureCode.OWNERSHIP_CONFLICT, protectedFill.code());
    }

    private static Point3i columnKey(Point3i p) { return new Point3i(p.x(), 0, p.z()); }

    private static SurfaceProbe.ColumnView ground(int surface, Integer occupiedY, Integer protectedY) {
        return new SurfaceProbe.ColumnView() {
            @Override public String state(int y) {
                return solidSupport(y) || java.util.Objects.equals(occupiedY, y) ? "minecraft:stone{}" : "minecraft:air{}";
            }
            @Override public boolean solidSupport(int y) { return y < surface; }
            @Override public boolean protectedAt(int y) { return java.util.Objects.equals(protectedY, y); }
            @Override public boolean water(int y) { return false; }
            @Override public boolean airOrReplaceablePlant(int y) { return y >= surface && !java.util.Objects.equals(occupiedY, y); }
            @Override public int highestNonPlantFeet() { return surface; }
            @Override public String hash() { return "a".repeat(64); }
        };
    }

    @Test void rotatedWholeGatePreservesLootAndBothPassagesAcrossOwners() {
        var rotation = Rotation.CLOCKWISE_90;
        var feet = new Point3i(-19, 81, 29);
        var body = ImportedGateBody.compile(new SiteId("fixture", SiteKind.GATE, 31), feet, rotation, 42);
        String hash = "a".repeat(64), dimension = "minecraft:overworld";
        var cell = new RealizationCell(new CellKey(hash, dimension, 1), List.of(), List.of(body), hash, 0, hash, hash);
        var before = new HashMap<Point3i, String>();
        for (var voxel : body.voxels()) before.put(voxel.pos(), "minecraft:air{}:fluid=minecraft:empty:be=false");
        var compiler = new ChunkPlanCompiler().window();
        var owners = Rasterizer.owners(dimension, body.bounds());
        var claimed = new HashMap<Point3i, IntentKind>();
        var walk = new HashSet<Point3i>();
        int containers = 0, entities = 0;
        for (var owner : owners) {
            var plan = compiler.compile(owner, hash, List.of(cell), before);
            for (var voxel : plan.voxels()) assertNull(claimed.putIfAbsent(voxel.pos(), voxel.kind()));
            walk.addAll(plan.walkwayFeet());
            for (var entity : plan.blockEntities()) {
                entities++;
                if (PlannedBlockEntityNbt.encode(entity).contains("LootTable")) containers++;
            }
        }
        // 取实物已知计数，可抓到整类装饰或箱桶随归属裁切消失。
        assertEquals(44, entities);
        assertEquals(18, containers);
        assertTrue(owners.size() > 10);
        assertEquals(1, compiler.resolutions());
        assertEquals(37521, claimed.values().stream().filter(v -> v == IntentKind.SOLID).count());
        assertTrue(walk.size() >= 113);
        assertTrue(walk.contains(new Point3i(-19, 81, -27)));
        assertTrue(walk.contains(new Point3i(-19, 81, 85)));
        var geometry = ImportedGateGeometry.gate();
        for (int z = 0; z < 32; z++) for (int y = 0; y < 5; y++) for (int x = 54; x < 59; x++) {
            assertEquals(IntentKind.OWNED_VOID, claimed.get(geometry.place(new Point3i(x, y, z), feet, rotation)));
        }
        // 包围盒角的原始空气应留给地形；门洞里的外部实心则拒绝整张区块计划。
        assertFalse(claimed.containsKey(geometry.place(new Point3i(0, 41, 0), feet, rotation)));
        var blocked = geometry.place(new Point3i(56, 0, 0), feet, rotation);
        var owner = new ChunkKey(dimension, blocked.x() >> 4, blocked.z() >> 4);
        before.put(blocked, "minecraft:stone{}:fluid=minecraft:empty:be=false");
        assertEquals(FailureCode.OWNERSHIP_CONFLICT, assertThrows(WallFailure.class,
                () -> compiler.compile(owner, hash, List.of(cell), Map.copyOf(before))).code());
    }
}
