package local.plaincraft.greatwall.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.decay.PaletteTypes;
import org.junit.jupiter.api.Test;

import static local.plaincraft.greatwall.model.WallTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelContractTest {
    private static final String HASH = "0".repeat(64);
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void q30UnitDirectionsAreAccepted() {
        for (int[] direction : new int[][] {{1 << 30, 0}, {0, 1 << 30}, {-(1 << 30), 0}}) {
            assertDoesNotThrow(() -> new Axis(new Point3i(0, 80, 0), direction[0], direction[1]));
            assertDoesNotThrow(() -> new Frame(new Point2q(0, 0), direction[0], direction[1], 100, false));
        }
    }

    @Test
    void twiceQ30UnitLengthIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new Axis(new Point3i(0, 80, 0), Integer.MAX_VALUE, 0));
    }

    @Test
    void componentRoutesAreDeeplyImmutable() {
        var route = new ArrayList<>(List.of(new Point3i(0, 100, 0)));
        var value = new ComponentVoxels(new SiteId(HASH, SiteKind.GATE, 1),
            new Bounds(0, 0, 0, 4, 120, 4), List.of(), List.of(), List.of(), List.of(route), HASH);
        route.add(new Point3i(1, 100, 0));
        assertEquals(1, value.requiredRoutes().getFirst().size());
        assertThrows(UnsupportedOperationException.class,
            () -> value.requiredRoutes().getFirst().add(new Point3i(2, 100, 0)));
    }

    @Test
    void routeBoundariesCannotBeNull() {
        assertThrows(NullPointerException.class, () -> new RouteShape(
            new SegmentKey(HASH, DIMENSION, 0), List.of(new Point2q(0, 0), new Point2q(256, 0)),
            List.of(0L, 256L), null, null, 0, 0, 0, HASH));
    }

    @Test
    void neighborhoodRejectsNonAdjacentSegment() {
        assertThrows(IllegalArgumentException.class, () -> new RouteNeighborhood(
            route(1, HASH), Optional.of(route(0, HASH)), Optional.of(route(3, HASH))));
    }

    @Test
    void neighborhoodRejectsForeignRecipe() {
        assertThrows(IllegalArgumentException.class, () -> new RouteNeighborhood(
            route(1, HASH), Optional.of(route(0, "1".repeat(64))), Optional.of(route(2, HASH))));
    }

    @Test
    void sharedLeaseCancellationIsIsolatedAndReleaseIsOnce() {
        var shared = new CompletableFuture<String>();
        var releases = new ConcurrentHashMap<String, Integer>();
        var first = new PlanLease<>(UUID.randomUUID(), shared,
            () -> releases.merge("first", 1, Integer::sum));
        var second = new PlanLease<>(UUID.randomUUID(), shared,
            () -> releases.merge("second", 1, Integer::sum));
        first.close();
        first.close();
        assertFalse(shared.isCancelled());
        assertTrue(first.future().isCancelled());
        shared.complete("ready");
        assertEquals("ready", second.future().resultNow());
        assertEquals(Map.of("first", 1), releases);
        second.close();
        assertEquals(Map.of("first", 1, "second", 1), releases);
    }

    @Test
    void failureKeepsCodeAndOriginalCause() {
        var cause = new IllegalStateException("source");
        var failure = new WallFailure(FailureCode.IO_FAILED, "failed", cause);
        assertSame(cause, failure.getCause());
        assertEquals(FailureCode.IO_FAILED, failure.code());
    }

    @Test
    void paletteTableCannotPassByIntegerOverflow() {
        var stone = new BlockSpec("minecraft:stone", Map.of());
        assertThrows(IllegalArgumentException.class, () -> new PaletteTypes.Table(
            PaletteTypes.Variant.STONE, DecayState.INTACT, Part.SKIN,
            List.of(new PaletteTypes.WeightedState(stone, Integer.MAX_VALUE),
                new PaletteTypes.WeightedState(stone, Integer.MAX_VALUE),
                new PaletteTypes.WeightedState(stone, 102))));
    }

    private static RouteShape route(long index, String recipe) {
        var first = new Point2q(index * 524288, 0);
        var last = new Point2q((index + 1) * 524288, 0);
        return new RouteShape(new SegmentKey(recipe, DIMENSION, index), List.of(first, last),
            List.of(0L, 524288L), new Boundary(index, first, 100, HASH),
            new Boundary(index + 1, last, 100, HASH), 0, 2, 0, HASH);
    }
}
