package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import org.junit.jupiter.api.Test;

final class VegetationGuardTest {
    private static final ChunkKey OWNER = new ChunkKey("minecraft:overworld", 62, 0);
    @Test void originOutsideStillSuppressesIntersectingCompleteCanopy() {
        var guard = new VegetationGuard();
        guard.publish(OWNER, plans());
        assertTrue(guard.intersects(OWNER, new Bounds(1000, 0, 40, 1010, 100, 100)));
        assertFalse(guard.intersects(OWNER, new Bounds(1000, 0, 45, 1010, 100, 100)));
        assertEquals(1, guard.leases());
    }
    @Test void unknownOrReleasedOwnerCannotSilentlyPlaceTrees() {
        var guard = new VegetationGuard();
        var bounds = new Bounds(1000, 0, 0, 1010, 100, 1);
        assertThrows(WallFailure.class, () -> guard.intersects(OWNER, bounds));
        guard.publish(OWNER, new PlanWindow(List.of()));
        assertFalse(guard.intersects(OWNER, bounds), "explicit empty proof differs from missing snapshot");
        assertThrows(WallFailure.class, () -> guard.publish(OWNER, plans()));
        guard.release(OWNER);
        assertThrows(WallFailure.class, () -> guard.intersects(OWNER, bounds));
        assertEquals(0, guard.leases());
    }
    private static PlanWindow plans() {
        var recipe = PlanningFixtures.recipe(0);
        var geometry = PlanningFixtures.geometry(recipe, 0, java.util.Optional.empty());
        var stations = new StationTable(List.of(new Station(0, new Point2q(1000L * 256, 0), 84, 0),
                new Station(4 * 256, new Point2q(1004L * 256, 0), 84, 0)), List.of(), PlanningFixtures.HASH);
        return new PlanWindow(List.of(new SegmentPlan(geometry,
                new PrefixSummary(0, 0, java.util.Optional.empty(), PlanningFixtures.HASH), stations, List.of(), PlanningFixtures.HASH)));
    }
}
