package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import org.junit.jupiter.api.Test;

final class VegetationGuardTest {
    private static final ChunkKey OWNER = new ChunkKey("minecraft:overworld", 62, 0);
    @Test void originOutsideStillSuppressesIntersectingCompleteCanopy() {
        var guard = new VegetationGuard();
        guard.publish(OWNER, plans());
        assertTrue(guard.intersects(OWNER, new Bounds(1000, 0, 40, 1010, 100, 100)));
        assertFalse(guard.intersects(OWNER, new Bounds(1000, 0, 69, 1010, 100, 100)));
        assertEquals(1, guard.leases());
    }
    @Test void prospectiveGroundExitOutsideOrdinaryWallMarginStillHasItsFull32Margin() {
        var guard = new VegetationGuard();
        guard.publish(OWNER, plans());
        assertTrue(guard.intersects(OWNER, new Bounds(1000, 0, 60, 1001, 100, 61)),
                "a potential rotated cap/ramp can occupy beyond the basic wall+32 corridor");
        assertFalse(guard.intersects(OWNER, new Bounds(1000, 0, 69, 1001, 100, 70)));
        assertEquals(new Bounds(-30, -33, -15, 30, 3, 15),
                local.plaincraft.greatwall.component.EndCap.potentialLocalBounds());
    }
    /**
     * A missing snapshot means "outside the published window", which means "nothing to suppress".
     *
     * <p>This guard is queried from inside vanilla's tree placement on the chunk-generation thread.
     * Treating a miss as an error there aborted the chunk's FEATURES stage: a measured session
     * produced 51 {@code Failed to load chunk} entries around spawn, every one of them this
     * exception. Nothing in this class may throw on the worldgen path.
     */
    @Test void unknownOrReleasedOwnerIsNotSuppressedAndNeverThrows() {
        var guard = new VegetationGuard();
        var bounds = new Bounds(1000, 0, 0, 1010, 100, 1);
        assertFalse(guard.intersects(OWNER, bounds), "a missing snapshot suppresses nothing");
        assertEquals(1L, guard.misses(), "and the miss stays observable");
        guard.publish(OWNER, new PlanWindow(List.of()));
        assertFalse(guard.intersects(OWNER, bounds), "explicit empty proof differs from missing snapshot");
        // Re-planning the same window republishes the same owner; that must not abort the compile,
        // and the republished corridor must take effect.
        guard.publish(OWNER, plans());
        assertTrue(guard.intersects(OWNER, bounds), "the republished corridor suppresses again");
        guard.release(OWNER);
        assertFalse(guard.intersects(OWNER, bounds), "a released owner is not an error either");
        assertEquals(0, guard.leases());
    }

    /**
     * The spatial envelope is what keeps the worldgen thread off the missing-snapshot path: a chunk
     * that cannot reach any published corridor band is rejected without a lookup.
     */
    @Test void envelopeRejectsFarChunksAndAdmitsTheBandAndItsMargin() {
        var guard = new VegetationGuard();
        assertFalse(guard.mayAffect(OWNER, 8), "nothing published yet: nothing to suppress");
        guard.publish(OWNER, plans());
        // OWNER covers blocks x 992..1007, z 0..15; the published band sits at x 1000..1004.
        assertTrue(guard.mayAffect(OWNER, 8), "the owning chunk itself is inside the band");
        assertTrue(guard.mayAffect(new ChunkKey("minecraft:overworld", 62, 0), 8), "same chunk, same answer");
        assertFalse(guard.mayAffect(new ChunkKey("minecraft:overworld", 200, 0), 8),
                "a chunk far along x cannot touch the band");
        assertFalse(guard.mayAffect(new ChunkKey("minecraft:overworld", 62, 400), 8),
                "a chunk far along z cannot touch the band");
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
