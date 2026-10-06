package local.plaincraft.greatwall.compat.biome;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.plaincraft.greatwall.generation.CorridorBoxes;
import local.plaincraft.greatwall.generation.VegetationGuard;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;
import org.junit.jupiter.api.Test;

/**
 * Pins the corridor mask contract. The hard domain {@code H} must agree with the legacy vegetation
 * guard box for box, and the outward bands must be exact at their documented half-open boundaries.
 */
final class RouteBiomeMaskTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final RouteBiomeMask.MaskKey KEY =
            new RouteBiomeMask.MaskKey(SESSION, OVERWORLD, PlanningFixtures.HASH, 1L, 0, 0);

    @Test void hardBoxesMatchLegacyVegetationGuardExactly() {
        var plans = plans();
        var legacy = new VegetationGuard();
        var owner = new ChunkKey(OVERWORLD, 0, 0);
        legacy.publish(owner, plans);

        var boxes = CorridorBoxes.hard(plans, OVERWORLD);
        assertFalse(boxes.isEmpty(), "fixture must produce at least one protection box");

        // Every box interior corner must be reported as protected by BOTH the legacy guard and the
        // new distance function; this is the regression pin for the shared-helper extraction.
        for (var box : boxes) {
            assertTrue(legacy.intersects(owner, unit(box.minX(), box.minZ())), "min corner inside " + box);
            assertTrue(legacy.intersects(owner, unit(box.maxXExclusive() - 1, box.maxZExclusive() - 1)),
                    "max inclusive corner inside " + box);
            assertEquals(0L, CorridorBoxes.distance(box, box.minX(), box.minZ()), "inside => zero " + box);
        }
        // And the two must agree outside as well.
        assertFalse(legacy.intersects(owner, unit(0, 0)), "far away is unprotected");
        assertNotEquals(0L, CorridorBoxes.distance(boxes, 0, 0), "far away has positive distance");
    }

    @Test void distanceIsChebyshevAndHalfOpenAware() {
        var box = new Bounds(100, Integer.MIN_VALUE, 200, 110, Integer.MAX_VALUE, 210);
        assertEquals(0L, CorridorBoxes.distance(box, 100, 200));
        assertEquals(0L, CorridorBoxes.distance(box, 109, 209));
        // 110 and 210 are outside: the rectangle is half-open.
        assertEquals(1L, CorridorBoxes.distance(box, 110, 209));
        assertEquals(1L, CorridorBoxes.distance(box, 109, 210));
        assertEquals(5L, CorridorBoxes.distance(box, 99, 195));
        // Chebyshev: the larger of the two axis gaps wins. 217 - 209 = 8.
        assertEquals(1L, CorridorBoxes.distance(box, 99, 202));
        assertEquals(8L, CorridorBoxes.distance(box, 103, 217));
    }

    @Test void negativeCoordinatesAreNotTruncated() {
        var box = new Bounds(-10, Integer.MIN_VALUE, -10, 0, Integer.MAX_VALUE, 0);
        assertEquals(0L, CorridorBoxes.distance(box, -1, -1));
        assertEquals(0L, CorridorBoxes.distance(box, -10, -10));
        assertEquals(1L, CorridorBoxes.distance(box, -11, -1));
        assertEquals(1L, CorridorBoxes.distance(box, -1, 0));
        assertEquals(2L, CorridorBoxes.distance(box, -12, 1));
    }

    @Test void bandsAreExactAtDocumentedHalfOpenBoundaries() {
        var plans = plans();
        var boxes = CorridorBoxes.hard(plans, OVERWORLD);
        var box = boxes.get(0);
        int probeZ = Math.floorDiv(box.minZ() + box.maxZExclusive(), 2);

        // The corridor is a UNION of overlapping boxes (the ±44 segment rectangle is contained in
        // the ±68 exit ribbon), so distance is measured to the union, not to any single box. Drive
        // the assertions from the union distance to prove the band edges exactly.
        // Walk the full outward range measured from the UNION's left edge, so the probe is
        // guaranteed to cross d = BIOME_GROWTH regardless of how the boxes overlap.
        int unionMinX = box.minX();
        for (var candidate : boxes) unionMinX = Math.min(unionMinX, candidate.minX());
        int from = unionMinX - (RouteBiomeMask.EDGE_MAX + 12);
        int until = unionMinX + 12;
        var mask = RouteBiomeMask.compile(KEY, plans, 4,
                Math.floorDiv((from + until) / 2, 16) * 16, 0);

        int seenBoundary = 0;
        for (int x = from; x <= until; x++) {
            long d = CorridorBoxes.distance(boxes, x, probeZ);
            assertEquals(d == 0L, mask.hardAt(x, probeZ), "hard at x=" + x + " d=" + d);
            assertEquals(d <= RouteBiomeMask.BIOME_GROWTH, mask.biomeAt(x, probeZ), "biome at x=" + x);
            assertEquals(d >= RouteBiomeMask.LOW_MIN && d < RouteBiomeMask.LOW_MAX,
                    mask.lowAt(x, probeZ), "low band at x=" + x + " d=" + d);
            assertEquals(d >= RouteBiomeMask.EDGE_MIN && d < RouteBiomeMask.EDGE_MAX,
                    mask.edgeAt(x, probeZ), "edge band at x=" + x + " d=" + d);
            if (d == RouteBiomeMask.BIOME_GROWTH) seenBoundary++;
        }
        assertTrue(seenBoundary > 0, "the probe range must actually cross the B boundary");
    }

    @Test void tileUnionIsBoundedAndOutOfDomainFailsClosed() {
        var mask = RouteBiomeMask.compile(KEY, plans(), 1, -48, -48);
        assertEquals(9, mask.tileCount(), "writeRadius 1 is a 3x3 tile union");
        assertTrue(mask.domain().minX() <= -48 && mask.domain().maxXExclusive() >= -32,
                "domain covers the owner chunk: " + mask.domain());
        // Missing data must fail loudly rather than answer "no corridor".
        assertThrows(WallFailure.class, () -> mask.hardAt(1_000_000, 1_000_000));
    }

    @Test void emptyPlanWindowYieldsNoFabricatedCorridor() {
        var recipe = PlanningFixtures.recipe(0);
        var geometry = PlanningFixtures.geometry(recipe, 0, Optional.empty());
        var stations = new StationTable(List.of(), List.of(), PlanningFixtures.HASH);
        var prefix = new PrefixSummary(0, 0, Optional.of(new Terminal(0, 0, TerminalKind.OCEAN)),
                PlanningFixtures.HASH);
        var window = new PlanWindow(List.of(
                new SegmentPlan(geometry, prefix, stations, List.of(), PlanningFixtures.HASH)));
        assertTrue(CorridorBoxes.hard(window, OVERWORLD).isEmpty(),
                "no stations and no sites yields no protection box");
    }

    @Test void foreignDimensionPlanIsRejected() {
        var plans = plans();
        assertThrows(IllegalArgumentException.class, () -> CorridorBoxes.hard(plans, "minecraft:the_nether"));
    }

    @Test void quartPaintIsConservativeAndBlockExactHardTestIsNot() {
        var plans = plans();
        var boxes = CorridorBoxes.hard(plans, OVERWORLD);
        var box = boxes.get(0);
        int probeX = box.minX();
        int probeZ = Math.floorDiv(box.minZ() + box.maxZExclusive(), 2);
        int ownerTileX = Math.floorDiv(probeX, 16);
        var mask = RouteBiomeMask.compile(KEY, plans, 1, ownerTileX * 16, 0);

        // The quart covering the boundary must paint, because it contains a hard block. This is the
        // deliberate conservative over-coverage the writer relies on.
        assertTrue(mask.paintQuart(Math.floorDiv(probeX, 4) * 4, Math.floorDiv(probeZ, 4) * 4),
                "quart covering a hit must paint");

        // Block-exact tree test refuses any footprint touching the box, at block resolution.
        assertTrue(mask.touchesHard(new Bounds(probeX, 0, probeZ, probeX + 1, 8, probeZ + 1)));
        assertTrue(mask.touchesHard(new Bounds(probeX - 3, 0, probeZ, probeX + 3, 8, probeZ + 1)),
                "a footprint straddling the boundary is refused");
        assertFalse(mask.touchesHard(new Bounds(probeX - 1000, 0, probeZ, probeX - 990, 8, probeZ + 1)),
                "a far footprint is allowed");
    }

    private static Bounds unit(int x, int z) {
        return new Bounds(x, 0, z, x + 1, 8, z + 1);
    }

    private static PlanWindow plans() {
        var recipe = PlanningFixtures.recipe(0);
        var geometry = PlanningFixtures.geometry(recipe, 0, Optional.empty());
        var stations = new StationTable(List.of(new Station(0, new Point2q(1000L * 256, 0), 84, 0),
                new Station(4 * 256, new Point2q(1004L * 256, 0), 84, 0)), List.of(), PlanningFixtures.HASH);
        return new PlanWindow(List.of(new SegmentPlan(geometry,
                new PrefixSummary(0, 0, Optional.empty(), PlanningFixtures.HASH), stations, List.of(),
                PlanningFixtures.HASH)));
    }
}
