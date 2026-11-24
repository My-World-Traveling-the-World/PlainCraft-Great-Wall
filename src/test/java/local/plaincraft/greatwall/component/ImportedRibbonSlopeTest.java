package local.plaincraft.greatwall.component;

import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import static org.junit.jupiter.api.Assertions.*;

/** 检查真实落块的台阶层，不能用名义脚点连通代替坡面高度正确。 */
class ImportedRibbonSlopeTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void ascendingAndDescendingStairsSitAboveTheLowPlatformRatherThanInsideItsFloor() {
        for (int sign : new int[] {-1, 1}) {
            var stations = new ArrayList<Station>();
            for (int x = 0; x <= 64; x += 4)
                stations.add(new Station(x * 256L, new Point2q(x * 256L + 128, 128), 84 + sign * x / 4, 0));
            var blocks = compile(stations).voxels();
            var stairs = blocks.stream().filter(v -> v.part() == Part.FLOOR
                    && v.state().id().equals("minecraft:stone_brick_stairs")).toList();
            assertTrue(stairs.size() > 20, "必须真的生成上下坡楼梯，不能空集合通过");
            var index = new HashMap<Point3i, VoxelIntent>();
            for (var v : blocks) assertNull(index.put(v.pos(), v), "同一格不能既声明空气又声明楼梯");
            for (var stair : stairs) {
                var contract = new ImportedRibbonProjection(stations, List.of()).contract(stair.pos().x(), stair.pos().z());
                assertEquals(contract.walkY(), stair.pos().y(), "楼梯底面应齐低平台顶面");
                var below = index.get(new Point3i(stair.pos().x(), stair.pos().y() - 1, stair.pos().z()));
                assertNotNull(below);
                assertFalse(below.state().id().endsWith("_stairs"), "低平台不能被挖成另一级半格凹槽");
                assertEquals(sign > 0 ? "east" : "west", stair.state().properties().get("facing"));
            }
            assertEquals(1, compile(stations).requiredRoutes().size());
        }
    }

    @Test void aBendOnARisingWallKeepsSolidSupportBelowEveryStair() {
        var points = List.of(new Point2q(128, 128), new Point2q(32L * 256 + 128, 128),
                new Point2q(64L * 256 + 128, 24L * 256 + 128));
        var stations = List.of(new Station(0, points.get(0), 84, 0),
                new Station(32L * 256, points.get(1), 92, 0), new Station(72L * 256, points.get(2), 102, 0));
        var body = compile(stations);
        var blocks = new HashMap<Point3i, VoxelIntent>();
        body.voxels().forEach(v -> assertNull(blocks.put(v.pos(), v)));
        var stairs = body.voxels().stream().filter(v -> v.part() == Part.FLOOR
                && v.state().id().endsWith("_stairs")).toList();
        assertTrue(stairs.size() > 20);
        assertTrue(stairs.stream().anyMatch(v -> v.pos().z() > 3), "须实际走到弯后");
        var projection = new ImportedRibbonProjection(stations, List.of());
        for (var v : stairs) {
            assertEquals(projection.contract(v.pos().x(), v.pos().z()).walkY(), v.pos().y());
            assertNotNull(blocks.get(new Point3i(v.pos().x(), v.pos().y() - 1, v.pos().z())));
        }
        assertEquals(1, body.requiredRoutes().size());
    }

    private static ComponentVoxels compile(List<Station> stations) {
        var id = new SiteId("slope", SiteKind.WALL, 0);
        var site = new SiteRecord(id, 32L * 256, new Frame(ImportedRibbonSection.at(stations, 32L * 256).point(), 1 << 30, 0, 84, false),
                new Bounds(0, 0, -8, 65, 120, 9), SiteState.PLANNED, Optional.empty(), Optional.empty());
        var supports = new HashMap<Point3i, ProbeResult>();
        for (int x = -9; x <= 73; x++) for (int z = -25; z <= 40; z++)
            supports.put(new Point3i(x, 84, z), new ProbeResult(ProbeKind.SUPPORTED, 32, OptionalInt.empty(),
                    OptionalInt.empty(), 0, "0".repeat(64)));
        var input = new ComponentInput(site, stations, supports, new DecaySample(DecayState.INTACT, 0, 20),
                PaletteFamily.STONE, false, List.of(), List.of());
        return new ImportedRibbonSection(1).compileIntervals(input, List.of(new ArcInterval(0, 64L * 256)),
                List.of(new ArcInterval(0, 64L * 256)), ignored -> input.decay(), List.of(), List.of());
    }
}
