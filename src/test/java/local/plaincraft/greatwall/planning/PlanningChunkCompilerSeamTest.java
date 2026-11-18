package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.CoveragePlanner;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.terrain.RealizationPlanner;
import org.junit.jupiter.api.Test;

import static local.plaincraft.greatwall.model.WallTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanningChunkCompilerSeamTest {
    private static final String DIM = "minecraft:overworld";
    private static final String HASH = "a".repeat(64);
    private static final CellKey SEAM = new CellKey(HASH, DIM, 1);
    private static final Point3i WEST = new Point3i(-24, 64, 0);
    // 左段最后一站仍覆盖 x=16 所在区块，右侧独占夹具必须越过整个区块。
    private static final Point3i EAST = new Point3i(40, 64, 0);
    private static final Point3i CENTER = new Point3i(0, 65, 0);

    @Test void sameArcInDifferentSegmentsUsesEachSegmentsOwnTerrainEvidence() {
        var fixture = new Fixture();
        var visits = new HashMap<Long, Integer>();
        var compiler = fixture.compiler(input -> {
            if (!input.key().equals(SEAM)) return result(input, List.of());
            var segment = input.plans().plans().getFirst().geometry().key().index();
            visits.merge(segment, 1, Integer::sum);
            var pos = segment == 0 ? WEST : EAST;
            var column = input.columns().get(new Point3i(pos.x(), 0, pos.z()));
            assertNotNull(column, "必须取到枚举该单元的段的地形列");
            assertEquals(segment == 0 ? 64 : 66, column.highestNonPlantFeet());
            return result(input, segment == 0 ? List.of(component(WEST, 1), component(CENTER, 2))
                    : List.of(component(EAST, 1)));
        });
        var together = compiler.compileWindow(fixture.both(), HASH, DIM);
        assertEquals(Map.of(0L, 1, 1L, 1), visits, "同段多人共享一次，相同弧长的另一段单独处理");
        var west = together.get(owner(WEST));
        var east = together.get(owner(EAST));
        var shared = together.get(owner(CENTER));
        assertNotNull(west);
        assertNotNull(east);
        assertNotNull(shared);
        assertEquals(List.of(WEST), west.voxels().stream().map(VoxelIntent::pos).toList());
        assertEquals(List.of(EAST), east.voxels().stream().map(VoxelIntent::pos).toList());
        assertEquals("minecraft:air{}", west.proofs().getFirst().beforeState());
        assertEquals("minecraft:stone{}", east.proofs().getFirst().beforeState());
        assertEquals(List.of(CENTER), shared.voxels().stream().map(VoxelIntent::pos).toList());
        // 只归右段的区块单独编译时，证据、覆盖和最终计划字节也应该相同。
        var isolated = compiler.compileWindow(new PlanWindow(List.of(fixture.right)), HASH, DIM);
        assertArrayEquals(CanonicalCodec.writePlan(east), CanonicalCodec.writePlan(isolated.get(owner(EAST))));
    }

    @Test void failureOfLeftSeamDoesNotRejectRightOnlyOwner() {
        var fixture = new Fixture();
        var compiler = fixture.compiler(input -> {
            if (!input.key().equals(SEAM)) return result(input, List.of());
            if (input.plans().plans().getFirst().geometry().key().index() == 0) {
                throw new local.plaincraft.greatwall.model.WallFailure(FailureCode.T0_MISSING, "左段哨兵故障");
            }
            return result(input, List.of(component(EAST, 1)));
        });
        var compiled = compiler.compileWindow(fixture.both(), HASH, DIM);
        assertFalse(compiled.containsKey(owner(WEST)), "先确认故障真的命中左侧施工");
        var east = compiled.get(owner(EAST));
        assertNotNull(east);
        assertEquals(List.of(EAST), east.voxels().stream().map(VoxelIntent::pos).toList());
    }

    private static final class Fixture {
        final SegmentPlan left = plan(0, 0, -96, 64);
        final SegmentPlan right = plan(1, 96, 0, 66);
        final FineHeightIndex heights = FineHeightIndex.of(List.of(column(WEST, 64), column(EAST, 66), column(CENTER, 64)));

        Fixture() {
            var lookup = new CoveragePlanner();
            var western = lookup.realizationOwners(SEAM, new PlanWindow(List.of(left)));
            var eastern = lookup.realizationOwners(SEAM, new PlanWindow(List.of(right)));
            // 三个断言锁定边界条件，防止前置过滤让接缝测试无效。
            assertTrue(western.contains(owner(WEST)) && !eastern.contains(owner(WEST)));
            assertTrue(eastern.contains(owner(EAST)) && !western.contains(owner(EAST)));
            assertTrue(western.contains(owner(CENTER)) && eastern.contains(owner(CENTER)));
        }

        PlanWindow both() { return new PlanWindow(List.of(left, right)); }

        PlanningChunkCompiler compiler(PlanningChunkCompiler.CellResolver resolver) {
            return new PlanningChunkCompiler(resolver, new ChunkPlanCompiler(), heights, List.of());
        }
    }

    private static SegmentPlan plan(long segment, int prefix, int startX, int height) {
        var stations = new ArrayList<Station>();
        for (int x = 0; x <= 96; x += 4) {
            int walkY = x == 0 && segment == 1 ? 64 : height;
            stations.add(new Station((prefix + x) * 256L, new Point2q((startX + x) * 256L, 0), walkY, 0));
        }
        var a = new Boundary(segment, stations.getFirst().point(), stations.getFirst().walkY(), HASH);
        var b = new Boundary(segment + 1, stations.getLast().point(), height, HASH);
        var geometry = new SegmentGeometry(new SegmentKey(HASH, DIM, segment), List.of(a.point(), b.point()),
                List.of(0L, 96L * 256), List.of(), Optional.empty(), a, b, 0, 0, HASH);
        return new SegmentPlan(geometry, new PrefixSummary(segment, prefix * 256L, Optional.empty(), HASH),
                new StationTable(stations, List.of(), HASH), List.of(), HASH);
    }

    private static RealizationCell result(RealizationPlanner.Input input, List<ComponentVoxels> components) {
        return new RealizationCell(input.key(), List.of(), components, HASH, 0, input.protectionHash(), HASH);
    }

    private static ComponentVoxels component(Point3i pos, long ordinal) {
        var id = new SiteId("wall", SiteKind.WALL, ordinal);
        var bounds = new Bounds(pos.x(), pos.y(), pos.z(), pos.x() + 1, pos.y() + 1, pos.z() + 1);
        var voxel = new VoxelIntent(pos, IntentKind.SOLID, Part.CORE, new BlockSpec("minecraft:stone", Map.of()),
                id, 1, true, false);
        var value = new ComponentVoxels(id, bounds, List.of(voxel), List.of(), List.of(), List.of(), HASH);
        return new ComponentVoxels(id, bounds, value.voxels(), List.of(), List.of(), List.of(), CanonicalCodec.componentHash(value));
    }

    private static WallFinePlanner.FineColumn column(Point3i pos, int y) {
        return new WallFinePlanner.FineColumn(pos.x(), pos.z(), 0, new ColumnSample(y, OptionalInt.empty(),
                OptionalInt.empty(), "minecraft:plains", Quality.REFERENCE, false));
    }

    private static ChunkKey owner(Point3i pos) { return new ChunkKey(DIM, pos.x() >> 4, pos.z() >> 4); }
}
