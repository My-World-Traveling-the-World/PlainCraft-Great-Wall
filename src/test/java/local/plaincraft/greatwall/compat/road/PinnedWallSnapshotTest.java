package local.plaincraft.greatwall.compat.road;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.PlanWindow;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.*;
import org.junit.jupiter.api.Test;

class PinnedWallSnapshotTest {
    private static Strip wall(int z) {
        return new Strip("wall/" + z, List.of(new Pos(-128, 64, z), new Pos(128, 64, z)),
                0, 256L * 256, 2);
    }
    private static Passage gate(boolean ready) {
        return new Passage("gate", new Pos(0, 64, 0), 1 << 30, 0, 4, 5, 10, 1, ready);
    }
    private static Edge crossing(int x) { return new Edge(new Pos(x, 64, -8), new Pos(x, 64, 8)); }
    private static PinnedWallSnapshot snapshot(List<Strip> strips, List<Passage> gates) {
        return new PinnedWallSnapshot(strips, gates, List.of(), "fixture");
    }

    @Test void eightBlockEdgeCrossesWallWithBothEndsOutside() {
        try (var snapshot = snapshot(List.of(wall(0)), List.of())) {
            var penalty = snapshot.evaluateSweptEdge(new Edge(new Pos(0, 64, -4), new Pos(0, 64, 4)), 0);
            assertEquals(100000, penalty.cost());
            assertTrue(penalty.protectedGap());
        }
    }

    @Test void sparsePolylineCrossingTheQueryIsNotLostByEndpointFiltering() {
        try (var snapshot = snapshot(List.of(wall(0)), List.of())) {
            assertEquals(List.of(wall(0)), snapshot.queryXZ(new Rect(-2, -2, 2, 2)));
            assertTrue(snapshot.queryXZ(new Rect(-2, 8, 2, 12)).isEmpty());
            assertEquals(100000, snapshot.evaluateSweptEdge(crossing(90), 2).cost());
        }
    }

    @Test void gateZeroDoesNotEraseAnotherIntersectingWall() {
        for(int offset:List.of(3,7))try (var snapshot = snapshot(List.of(wall(0), wall(offset)), List.of(gate(true)))) {
            assertEquals(100000, snapshot.evaluateSweptEdge(crossing(0), 2).cost(),
                    "a different wall plane remains solid even inside the gate's coarse depth box");
        }
    }

    @Test void fullGateSectionNarrowsShouldersButKeepsFourClearBlocks() {
        try (var snapshot = snapshot(List.of(wall(0)), List.of(gate(true)))) {
            assertEquals(0, snapshot.evaluateSweptEdge(crossing(0), 2).cost());
            assertEquals(100000, snapshot.evaluateSweptEdge(crossing(1), 2).cost(),
                    "a shifted four-wide core does not fit a four-wide aperture");
            var parallel = new Edge(new Pos(-8, 64, 0), new Pos(8, 64, 0));
            assertTrue(snapshot.gateForCrossing(parallel).isEmpty(),
                    "a bounding-box hit parallel to the gate plane is not a passage");
            var tooHigh = new Edge(new Pos(0, 68, -8), new Pos(0, 68, 8));
            assertTrue(snapshot.gateForCrossing(tooHigh).isEmpty(), "two-block headroom is part of the section");
        }
    }

    @Test void frozenButNotPhysicalGateCostsZeroAndStillPausesConstruction() {
        try (var snapshot = snapshot(List.of(wall(0)), List.of(gate(false)))) {
            var penalty = snapshot.evaluateSweptEdge(crossing(0), 2);
            assertEquals(0, penalty.cost());
            assertTrue(penalty.protectedGap());
            assertEquals(Optional.of("gate"), penalty.gateId());
        }
    }

    @Test void closeReleasesEachLeaseOnceWithoutCancellingTheSharedPlanner() {
        var counts = new ConcurrentHashMap<String, Integer>();
        var shared = new CompletableFuture<PlanWindow>();
        var lease = new PlanLease<>(new UUID(1, 2), shared, () -> counts.merge("release", 1, Integer::sum));
        var snapshot = new PinnedWallSnapshot(List.of(wall(0)), List.of(), List.of(lease), "fixture");
        snapshot.close(); snapshot.close();
        assertEquals(1, counts.get("release"));
        assertFalse(shared.isCancelled());
        assertThrows(IllegalStateException.class, () -> snapshot.queryXZ(new Rect(-1, -1, 1, 1)));
    }

    @Test void longSweptEdgeCannotJumpAnOffCentreWall() {
        try (var snapshot = snapshot(List.of(wall(91)), List.of())) {
            var edge = new Edge(new Pos(0, 64, -10000), new Pos(0, 64, 10000));
            assertEquals(100000, snapshot.evaluateSweptEdge(edge, 0).cost());
        }
    }

    @Test void failedReleaseDoesNotLeakTheOtherSnapshotLeases() {
        var counts = new ConcurrentHashMap<String, Integer>();
        var shared = new CompletableFuture<PlanWindow>();
        var broken = new PlanLease<PlanWindow>(new UUID(1, 1), shared,
                () -> { throw new IllegalStateException("retained release failure"); });
        var other = new PlanLease<PlanWindow>(new UUID(1, 2), shared,
                () -> counts.merge("release", 1, Integer::sum));
        var snapshot = new PinnedWallSnapshot(List.of(wall(0)), List.of(),
                List.of(broken, other), "fixture");
        assertThrows(IllegalStateException.class, snapshot::close);
        snapshot.close();
        assertEquals(1, counts.get("release"));
        assertFalse(shared.isCancelled());
    }

    @Test void diagonalAndShortEdgesCannotEscapeTheFullGateSection() {
        try (var snapshot = snapshot(List.of(wall(0)), List.of(gate(true)))) {
            var diagonal = new Edge(new Pos(-4, 64, -8), new Pos(4, 64, 8));
            assertEquals(100000, snapshot.evaluateSweptEdge(diagonal, 2).cost(),
                    "the centre-plane point fits but the full four-wide section clips the jamb");
            var shoulder = new Edge(new Pos(3, 64, -3), new Pos(3, 64, 3));
            assertEquals(100000, snapshot.evaluateSweptEdge(shoulder, 2).cost());
        }
    }
}
