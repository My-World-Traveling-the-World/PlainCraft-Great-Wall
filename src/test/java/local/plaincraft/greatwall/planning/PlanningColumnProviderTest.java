package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

/**
 * Guards the two contracts the realization stage depends on: the column map must cover every cell a
 * component's axis-aligned bounds reach (not only the chunk being written), and the holes of the
 * sampled ladder must be filled without ever overriding a real probe.
 */
class PlanningColumnProviderTest {
    private static final String DIM = "minecraft:overworld";

    private static WallFinePlanner.FineColumn column(int x, int z, int ground) {
        return new WallFinePlanner.FineColumn(x, z, 0, new ColumnSample(ground, OptionalInt.empty(),
                OptionalInt.empty(), "minecraft:plains", Quality.REFERENCE, false));
    }

    private static ChunkKey chunk(int x, int z) {
        return new ChunkKey(DIM, x, z);
    }

    private static int feet(Map<Point3i, SurfaceProbe.ColumnView> columns, int x, int z) {
        var view = columns.get(new Point3i(x, 0, z));
        assertNotNull(view, "no column at " + x + "," + z);
        return view.highestNonPlantFeet();
    }

    @Test void returnsTheSampledColumnsOfTheRequestedOwner() {
        var provider = new PlanningColumnProvider(FineHeightIndex.of(List.of(
                column(0, 0, 64), column(5, 7, 70), column(20, 20, 80))));
        var columns = provider.columnsFor(List.of(chunk(0, 0)));
        assertEquals(64, feet(columns, 0, 0));
        assertEquals(70, feet(columns, 5, 7));
    }

    @Test void columnsOutsideTheRequestedOwnersAreNotEmitted() {
        var provider = new PlanningColumnProvider(FineHeightIndex.of(List.of(
                column(0, 0, 64), column(20, 20, 80))));
        var columns = provider.columnsFor(List.of(chunk(0, 0)));
        assertFalse(columns.containsKey(new Point3i(20, 0, 20)),
                "a column of another chunk must not leak into the map");
        assertTrue(columns.keySet().stream().noneMatch(p -> (p.x() >> 4) != 0 || (p.z() >> 4) != 0));
    }

    @Test void fillsThePinholesOfTheSampledLadder() {
        // Two rungs of a diagonal ladder: what the route samples is one cell wide, so the cells beside
        // the staircase are holes - and a single hole inside a component's box drops that component.
        var provider = new PlanningColumnProvider(FineHeightIndex.of(List.of(
                column(0, 0, 64), column(1, 1, 65))));
        var columns = provider.columnsFor(List.of(chunk(0, 0)));
        assertEquals(64, feet(columns, 0, 0), "a real probe must keep its own value");
        assertEquals(65, feet(columns, 1, 1), "a real probe must keep its own value");
        assertEquals(64, feet(columns, 1, 0), "the hole takes a neighbouring column");
        assertEquals(64, feet(columns, 0, 1));
    }

    @Test void fillsAWindowEdgeFromAdjacentChunksWithoutPublishingTheirColumns() {
        var west = chunk(-1, 0);
        var east = chunk(0, 0);
        var provider = new PlanningColumnProvider(FineHeightIndex.of(List.of(
                column(-4, 5, 61), column(0, 5, 74))), 3);
        // 西窗边缘 -1 的最近实测列在东窗；裁掉东窗会改成远处的 61，接缝承重随之变化。
        var whole = provider.columnsFor(List.of(west, east));
        var westOnly = provider.columnsFor(List.of(west));
        var scoped = provider.restrict(List.of(west));
        var local = scoped.columnsFor(List.of(west));
        assertEquals(74, feet(whole, -1, 5));
        assertEquals(74, feet(westOnly, -1, 5));
        assertEquals(74, feet(local, -1, 5), "局部证据须携带填洞所需的邻区块");
        assertEquals(whole.get(new Point3i(-1, 0, 5)).hash(), westOnly.get(new Point3i(-1, 0, 5)).hash());
        assertFalse(westOnly.containsKey(new Point3i(0, 0, 5)), "邻区块样本只供取证，不能算作本窗产物");
        assertFalse(local.containsKey(new Point3i(0, 0, 5)));
        assertThrows(IllegalArgumentException.class, () -> scoped.columnsFor(List.of(east)),
                "未登记取证的区块不能以空样本冒充成功");
    }

    @Test void takesNearestOriginalSampleAndKeepsTheOldTieBreak() {
        var provider = new PlanningColumnProvider(FineHeightIndex.of(List.of(
                column(2, 5, 91), column(4, 5, 65), column(5, 4, 82))), 3);
        var evidence = provider.columnsFor(List.of(chunk(0, 0)));
        // 最近两列等距，旧循环先遇到的是较小 dx；更早遇到的远列不能抢占。
        assertEquals(65, feet(evidence, 5, 5));
        assertEquals(82, feet(evidence, 6, 4));
        assertFalse(evidence.containsKey(new Point3i(10, 0, 5)), "不能用填出来的列继续向外生长");
    }

    @Test void matchesBruteForceForSparseAndDenseSamplesAcrossOverlappingOwners() {
        var owners = List.of(chunk(-2, -1), chunk(-1, -1), chunk(-2, 0), chunk(-1, 0), chunk(-2, -1));
        var ownerSet = new java.util.HashSet<>(owners);
        for (int radius : List.of(3, 4)) {
            for (int step : List.of(1, 4, 9)) {
                var samples = new java.util.ArrayList<WallFinePlanner.FineColumn>();
                // 采样穿过负坐标区块边界，区块边缘同时遇到空列与邻区块实测列。
                for (int x = -36; x <= 4; x += step) {
                    for (int z = -13; z <= 18; z += step) {
                        if (Math.floorMod(31 * x + 17 * z, 13) > 2) samples.add(column(x, z, 90 + x + z));
                    }
                }
                var index = FineHeightIndex.of(samples);
                var actual = new PlanningColumnProvider(index, radius).columnsFor(owners);
                var expected = new java.util.HashMap<Point3i, Integer>();
                for (var owner : ownerSet) {
                    for (int x = owner.x() << 4; x < (owner.x() + 1) << 4; x++) {
                        for (int z = owner.z() << 4; z < (owner.z() + 1) << 4; z++) {
                            var sample = index.at(x, z);
                            if (sample == null) {
                                // 旧口径全扫，平方距离只在严格变小时更新，因此先遇到者保留。
                                int distance = Integer.MAX_VALUE;
                                for (int dx = -radius; dx <= radius; dx++) {
                                    for (int dz = -radius; dz <= radius; dz++) {
                                        var candidate = index.at(x + dx, z + dz);
                                        int squared = dx * dx + dz * dz;
                                        if (candidate != null && squared < distance) {
                                            sample = candidate;
                                            distance = squared;
                                        }
                                    }
                                }
                            }
                            if (sample != null) expected.put(new Point3i(x, 0, z), sample.groundTopY());
                        }
                    }
                }
                assertFalse(expected.isEmpty(), "夹具必须确实生成取证列");
                assertEquals(expected.keySet(), actual.keySet(), "半径=" + radius + " 网格=" + step);
                expected.forEach((point, height) -> assertEquals(height, actual.get(point).highestNonPlantFeet(), point.toString()));
            }
        }
    }

    @Test void anEmptyIndexStaysEmpty() {
        var provider = new PlanningColumnProvider(FineHeightIndex.of(List.of()));
        assertTrue(provider.columnsFor(List.of(chunk(0, 0))).isEmpty(),
                "nothing is invented where the corridor never sampled");
    }

    @Test void theFillDoesNotDependOnTheOwnerOrder() {
        var columns = new java.util.ArrayList<WallFinePlanner.FineColumn>();
        for (int i = 0; i < 24; i++) columns.add(column(i, i, 64 + i));
        var provider = new PlanningColumnProvider(FineHeightIndex.of(columns));
        var forward = provider.columnsFor(List.of(chunk(0, 0), chunk(1, 1)));
        var reversed = provider.columnsFor(List.of(chunk(1, 1), chunk(0, 0)));
        assertEquals(forward.size(), reversed.size());
        for (var entry : forward.entrySet()) {
            assertEquals(entry.getValue().hash(), reversed.get(entry.getKey()).hash(),
                    "cell " + entry.getKey());
        }
    }

    @Test void aComponentBoxOnTheSampledBandIsFullyCovered() {
        // What componentInput and naturalExit require: a column for every cell of a component's box.
        // A real route samples a full cross-section every block, so the band is dense and only the odd
        // cell of the staircase between two rungs is missing - every other rung is left out here to
        // stand in for exactly those cells.
        var columns = new java.util.ArrayList<WallFinePlanner.FineColumn>();
        for (int x = 0; x < 32; x += 2) {
            for (int z = 0; z < 32; z++) columns.add(column(x, z, 64));
        }
        var provider = new PlanningColumnProvider(FineHeightIndex.of(columns));
        var map = provider.columnsFor(List.of(chunk(0, 0), chunk(1, 0), chunk(0, 1), chunk(1, 1)));
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                assertNotNull(map.get(new Point3i(x, 0, z)), "hole at " + x + "," + z);
            }
        }
    }
}
