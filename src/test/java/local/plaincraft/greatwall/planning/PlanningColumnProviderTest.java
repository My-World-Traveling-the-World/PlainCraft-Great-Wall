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
