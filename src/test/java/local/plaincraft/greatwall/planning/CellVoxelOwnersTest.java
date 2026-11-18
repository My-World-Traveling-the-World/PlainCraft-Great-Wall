package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class CellVoxelOwnersTest {
    private static final String DIM = "minecraft:overworld";
    private static final String HASH = "a".repeat(64);
    private static final BlockSpec FENCE = new BlockSpec("minecraft:oak_fence", Map.of());
    private static final Point3i WEST = new Point3i(-1, 1, 0);
    private static final Point3i EAST = new Point3i(0, 1, 0);

    @Test
    void ownerBucketsKeepSeamProofsAndConnectionsIdentical() {
        var westSite = new SiteId("wall", SiteKind.WALL, 0);
        var eastSite = new SiteId("wall", SiteKind.WALL, 1);
        var left = cell(0, component(westSite, WEST));
        var right = cell(1, component(eastSite, EAST));
        var cells = List.of(left, right, left);
        var buckets = List.of(new CellVoxelOwners(left), new CellVoxelOwners(right));
        var first = Map.<Point3i, SurfaceProbe.ColumnView>of(columnKey(WEST), column("minecraft:grass_block"));
        var second = Map.<Point3i, SurfaceProbe.ColumnView>of(columnKey(EAST), column("minecraft:dirt"));
        var repeated = Map.<Point3i, SurfaceProbe.ColumnView>of(columnKey(WEST), column("minecraft:stone"));
        var views = List.of(first, second, repeated);
        var compiler = new ChunkPlanCompiler();
        for (int x : new int[]{-2, -1, 0, 1}) {
            var owner = new ChunkKey(DIM, x, 0);
            var expectedBefore = new LinkedHashMap<Point3i, String>();
            var actualBefore = new LinkedHashMap<Point3i, String>();
            for (int i = 0; i < cells.size(); i++) {
                collectByScanning(cells.get(i), owner, views.get(i), expectedBefore);
                buckets.get(i % 2).collectBefore(owner, views.get(i), actualBefore);
            }
            assertEquals(expectedBefore, actualBefore, owner.toString());
            var expected = compiler.compile(owner, HASH, cells, expectedBefore);
            var actual = compiler.compile(owner, HASH, cells, actualBefore);
            assertEquals(expected, actual, owner.toString());
            assertArrayEquals(CanonicalCodec.writePlan(expected), CanonicalCodec.writePlan(actual));
            if (x == -1 || x == 0) {
                assertEquals(1, actual.voxels().size());
                assertEquals("true", actual.voxels().getFirst().state().properties().get(x < 0 ? "east" : "west"));
            }
        }
        assertEquals(2L, buckets.stream().mapToLong(CellVoxelOwners::indexedVoxels).sum());
    }

    // 保留原先的全量扫描当参照，覆盖负坐标以及重复证据先到者胜出。
    private static void collectByScanning(RealizationCell cell, ChunkKey owner,
            Map<Point3i, SurfaceProbe.ColumnView> columns, Map<Point3i, String> result) {
        for (var component : cell.components()) for (var voxel : component.voxels()) {
            var pos = voxel.pos();
            if (Math.floorDiv(pos.x(), 16) != owner.x() || Math.floorDiv(pos.z(), 16) != owner.z()) continue;
            var column = columns.get(columnKey(pos));
            if (column != null) result.putIfAbsent(pos, column.state(pos.y()));
        }
    }

    private static ComponentVoxels component(SiteId site, Point3i pos) {
        var bounds = new Bounds(pos.x(), pos.y(), pos.z(), pos.x() + 1, pos.y() + 1, pos.z() + 1);
        var voxels = List.of(new VoxelIntent(pos, IntentKind.SOLID, Part.FENCE, FENCE, site, 1, true, false));
        var input = new ComponentVoxels(site, bounds, voxels, List.of(), List.of(), List.of(), HASH);
        return new ComponentVoxels(site, bounds, voxels, List.of(), List.of(), List.of(), CanonicalCodec.componentHash(input));
    }

    private static RealizationCell cell(long index, ComponentVoxels component) {
        return new RealizationCell(new CellKey(HASH, DIM, index), List.of(), List.of(component), HASH, 0, HASH, HASH);
    }

    private static Point3i columnKey(Point3i p) { return new Point3i(p.x(), 0, p.z()); }

    private static SurfaceProbe.ColumnView column(String id) {
        return new SurfaceProbe.ColumnView() {
            public String state(int y) { return id + "{}:fluid=minecraft:empty:be=false"; }
            public boolean solidSupport(int y) { return true; }
            public boolean protectedAt(int y) { return false; }
            public boolean water(int y) { return false; }
            public boolean airOrReplaceablePlant(int y) { return false; }
            public int highestNonPlantFeet() { return 2; }
            public String hash() { return HASH; }
        };
    }
}
