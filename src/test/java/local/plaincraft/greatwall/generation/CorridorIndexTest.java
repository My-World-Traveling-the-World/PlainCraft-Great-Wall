package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CorridorIndexTest {
    private static final String DIM = "minecraft:overworld";

    @Test void spatialQueriesMatchExactFullScanAcrossGapsAndNegativeCoordinates() {
        var boxes = new ArrayList<Bounds>();
        // 两条分离的折返走廊在根包围盒内部留下空洞，不能把节点命中当成实际命中。
        for (int i = -128; i <= 128; i += 4) {
            boxes.add(box(i, -100, i + 12, -70));
            boxes.add(box(i, 60 + Math.abs(i) / 4, i + 9, 90 + Math.abs(i) / 4));
        }
        var index = new CorridorIndex(DIM, boxes);
        assertEquals(boxes, index.boxes(), "空间排序不能改变公开保护盒顺序");
        assertFalse(index.intersects(box(-1, -1, 1, 1)));
        assertTrue(index.intersects(box(-1, -71, 1, -69)));
        assertFalse(index.intersects(box(-1, -70, 1, -69)), "相切的半开边界不算相交");
        var random = new Random(4406);
        for (int i = 0; i < 2_000; i++) {
            int x = random.nextInt(-240, 240), z = random.nextInt(-200, 200);
            var feature = box(x, z, x + random.nextInt(1, 29), z + random.nextInt(1, 29));
            assertEquals(boxes.stream().anyMatch(b -> CorridorBoxes.intersects(b, feature)), index.intersects(feature));
            assertEquals(CorridorBoxes.distance(boxes, x, z), index.distance(x, z));
        }
        assertEquals(CorridorBoxes.distance(boxes, Integer.MIN_VALUE, Integer.MAX_VALUE),
                index.distance(Integer.MIN_VALUE, Integer.MAX_VALUE));
        var empty = new CorridorIndex(DIM, List.of());
        assertEquals(Long.MAX_VALUE, empty.distance(0, 0));
        assertFalse(empty.intersects(box(-1, -1, 1, 1)));
    }

    @Test void sharedStartupIndexAcceptsLongWindowsButStillRejectsOversizedSegments() {
        var plans = new ArrayList<SegmentPlan>();
        for (int i = 0; i < 9; i++) plans.add(segment(i, 512));
        var longWindow = new PlanWindow(plans);
        // 9 段各 1024 盒，跨过旧整窗上限；仍须验证末段实际进入索引。
        assertEquals(FailureCode.CAPACITY, assertThrows(WallFailure.class,
                () -> CorridorIndex.prepare(longWindow, DIM)).code());
        var index = CorridorIndex.prepareStartupWindow(longWindow, DIM);
        assertEquals(9 * 1024, index.boxes().size());
        assertTrue(index.intersects(box(18_400, 0, 18_401, 1)));
        assertEquals(FailureCode.CAPACITY, assertThrows(WallFailure.class,
                () -> CorridorIndex.prepareStartupWindow(new PlanWindow(List.of(segment(0, 4097))), DIM)).code());
        assertThrows(IllegalArgumentException.class,
                () -> CorridorIndex.prepareStartupWindow(longWindow, "minecraft:the_nether"));
    }

    private static Bounds box(int x0, int z0, int x1, int z1) {
        return new Bounds(x0, 0, z0, x1, 1, z1);
    }

    private static SegmentPlan segment(int index, int intervals) {
        var recipe = PlanningFixtures.recipe(0);
        var geometry = PlanningFixtures.geometry(recipe, index, Optional.empty());
        var stations = new ArrayList<Station>();
        long start = index * 2048L * 256;
        for (int i = 0; i <= intervals; i++) {
            long s = start + i * 4L * 256;
            stations.add(new Station(s, new Point2q(s, 0), 84, 0));
        }
        var hash = PlanningFixtures.HASH;
        return new SegmentPlan(geometry, new PrefixSummary(index, start, Optional.empty(), hash),
                new StationTable(stations, List.of(), hash), List.of(), hash);
    }
}
