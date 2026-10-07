package local.plaincraft.greatwall.compat.road;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Edge;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Passage;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Pos;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Rect;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Strip;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link WallObstacleGeometry}, the continuous XZ tests that decide whether a road may pass a
 * wall.
 *
 * <h2>Why this exists</h2>
 * The class had no test, and its own header states the property that matters: "Continuous XZ tests:
 * long edges and sparse polylines cannot skip an obstacle." The failure this guards against is a
 * missed intersection - if a long road edge steps over a wall between two sampled points, the road is
 * planned straight through it and no later stage re-checks. A sampling-based test would not catch
 * that either, so these cases deliberately use long edges, sparse polylines and degenerate shapes
 * (zero-length segments, axis-aligned boxes, a zero tangent) rather than dense ones.
 *
 * <p>Pure geometry: the only inputs are the RoadWeaver interop records, which are on the test
 * classpath as a compile-only stub. No world, no server.
 */
class WallObstacleGeometryTest {
    private static Pos p(int x, int y, int z) {
        return new Pos(x, y, z);
    }

    /**
     * A strip whose arc span is a valid 1..512 blocks.
     *
     * <p>{@code Strip} validates {@code 0 < toSQ8 - fromSQ8 <= 512} and rejects anything else with
     * "strip arc length 0 < to-from <= 512 blocks". Those fields are arc length along the route, not
     * world coordinates, so they are independent of the centerline's positions - the same separation
     * that tripped the biome writer fixture, where {@code Station.globalSQ8} is arc length while its
     * {@code point} is a world position. Every intersects case below failed on this before it was
     * fixed, which is why the helper exists rather than inlining the constructor.
     */
    private static Strip strip(String id, int halfWidth, List<Pos> centerline) {
        return new Strip(id, centerline, 0L, WallObstacleProvider.MAX_STRIP_ARC_Q8, halfWidth);
    }

    // --- intersects: a strip against an axis-aligned box -----------------------------------------

    @Test void aStripCrossingABoxIntersectsIt() {
        var s = strip("cross", 0, List.of(p(-50, 64, 0), p(50, 64, 0)));
        assertTrue(WallObstacleGeometry.intersects(s, new Rect(-10, -10, 10, 10)),
                "a strip passing straight through the box must intersect");
    }

    @Test void aStripWellClearOfABoxDoesNotIntersectIt() {
        var s = strip("clear", 0, List.of(p(-50, 64, 100), p(50, 64, 100)));
        assertFalse(WallObstacleGeometry.intersects(s, new Rect(-10, -10, 10, 10)),
                "a strip a hundred blocks away must not intersect");
    }

    @Test void halfWidthAloneCanReachABoxTheCenterlineMisses() {
        // The centerline passes 20 blocks away, so only the half-width can bridge the gap.
        var near = strip("near", 30, List.of(p(-50, 64, 20), p(50, 64, 20)));
        assertTrue(WallObstacleGeometry.intersects(near, new Rect(-10, -10, 10, 10)),
                "half-width 30 reaches a box 20 blocks away");
        var far = strip("far", 5, List.of(p(-50, 64, 20), p(50, 64, 20)));
        assertFalse(WallObstacleGeometry.intersects(far, new Rect(-10, -10, 10, 10)),
                "half-width 5 leaves a box 20 blocks away untouched");
    }

    @Test void aLongEdgeCannotStepOverANarrowBox() {
        // The box is one block wide and the edge spans 1000 blocks: a sampling-based test that only
        // checked endpoints would miss it entirely. This is the property the class header claims.
        var s = strip("over", 0, List.of(p(-500, 64, 0), p(500, 64, 0)));
        assertTrue(WallObstacleGeometry.intersects(s, new Rect(0, -1, 1, 1)),
                "a 1000-block edge must still be caught by a 1-block box");
    }

    @Test void aSparsePolylineIsTestedEverySegmentNotJustItsEndpoints() {
        // Only the middle segment enters the box. A test that looked at the polyline's extent, or only
        // at its first and last point, would report no intersection.
        var s = strip("sparse", 0, List.of(p(-100, 64, 100), p(-100, 64, 0), p(100, 64, 0), p(100, 64, 100)));
        assertTrue(WallObstacleGeometry.intersects(s, new Rect(-10, -5, 10, 5)),
                "the middle segment crosses the box even though both endpoints are far outside it");
    }

    @Test void aZeroLengthSegmentIsHandledRatherThanDividingByZero() {
        // A degenerate segment makes the slab test's delta zero on both axes; the guard for that is
        // what keeps this from producing NaN comparisons, which would silently answer false.
        var inside = strip("point-in", 0, List.of(p(0, 64, 0), p(0, 64, 0)));
        assertTrue(WallObstacleGeometry.intersects(inside, new Rect(-10, -10, 10, 10)),
                "a zero-length strip inside the box intersects");
        var outside = strip("point-out", 0, List.of(p(500, 64, 500), p(500, 64, 500)));
        assertFalse(WallObstacleGeometry.intersects(outside, new Rect(-10, -10, 10, 10)),
                "a zero-length strip outside the box does not intersect");
    }

    @Test void anEmptyCenterlineIsRejectedAtConstructionRatherThanIntersectingSilently() {
        // Strip refuses an empty centerline ("centerline non-empty required"), so the geometry never
        // sees one. This test originally asserted that an empty strip intersects nothing; it failed,
        // and the failure is the point: the guarantee lives in the record's constructor, not in
        // intersects(), so it is pinned here instead.
        assertThrows(IllegalArgumentException.class, () -> strip("empty", 10, List.of()),
                "an empty centerline must be rejected before it reaches the geometry");

        // The smallest legal strip is a single point, and it must behave.
        var single = strip("single", 0, List.of(p(0, 64, 0)));
        assertFalse(WallObstacleGeometry.intersects(single, new Rect(-10, -10, 10, 10)),
                "a one-point centerline forms no segment, so it cannot intersect");
    }

    @Test void boxEdgesAreHalfOpenAtTheHighCorner() {
        // A strip that only touches the box's max corner must not count as intersecting: the rect is
        // documented as maxExclusive, and treating it as inclusive would widen every footprint by one.
        var touching = strip("touch", 0, List.of(p(50, 64, 10), p(50, 64, 20)));
        assertFalse(WallObstacleGeometry.intersects(touching, new Rect(-10, -10, 10, 10)),
                "a strip starting exactly at maxX (exclusive) is outside");
        var entering = strip("enter", 0, List.of(p(9, 64, 10), p(9, 64, 20)));
        assertTrue(WallObstacleGeometry.intersects(entering, new Rect(-10, -10, 10, 10)),
                "a strip one block inside the box intersects");
    }

    // --- swept: a road edge against a wall segment -----------------------------------------------

    @Test void sweptDetectsACloseWallSegment() {
        var edge = new Edge(p(-10, 64, 0), p(10, 64, 0));
        assertTrue(WallObstacleGeometry.swept(edge, p(0, 64, 5), p(0, 64, 6), 10),
                "a wall five blocks from the road is within reach 10");
        assertFalse(WallObstacleGeometry.swept(edge, p(0, 64, 50), p(0, 64, 51), 10),
                "a wall fifty blocks away is out of reach");
    }

    @Test void sweptUsesTheSegmentNotJustItsEndpoints() {
        // The road edge spans x=-100..100; the wall segment sits at x=0, far from both road endpoints.
        var edge = new Edge(p(-100, 64, 0), p(100, 64, 0));
        assertTrue(WallObstacleGeometry.swept(edge, p(0, 64, 1), p(0, 64, 2), 5),
                "the closest approach is in the middle of the road edge, not at an endpoint");
    }

    @Test void sweptUsesTheSquareOfTheReachSoTheBoundaryIsExact() {
        var edge = new Edge(p(0, 64, 0), p(0, 64, 0));
        assertTrue(WallObstacleGeometry.swept(edge, p(10, 64, 0), p(10, 64, 0), 10),
                "exactly at the reach is inside (<=)");
        assertFalse(WallObstacleGeometry.swept(edge, p(11, 64, 0), p(11, 64, 0), 10),
                "one block beyond the reach is outside");
    }

    // --- crosses: a road edge against a gate -----------------------------------------------------

    @Test void aZeroGateTangentIsRejectedRatherThanProducingNaN() {
        // local() divides by the tangent length; a zero tangent must fail loudly instead of yielding
        // NaN coordinates that would make every comparison silently false.
        var gate = new Passage("g", p(0, 64, 0), 0, 0, 8, 4, 4, 1L, true);
        assertThrows(IllegalArgumentException.class,
                () -> WallObstacleGeometry.crosses(new Edge(p(-10, 64, 0), p(10, 64, 0)), gate, 0),
                "a zero tangent must be rejected, not silently treated as no crossing");
    }

    @Test void anEdgeEntirelyOnOneSideOfTheGateDoesNotCrossIt() {
        // The gate's local Z axis is its tangent; with tangent (1,0) that is world X, so an edge at a
        // constant positive X stays on one side.
        var gate = new Passage("g", p(0, 64, 0), 1 << 30, 0, 8, 4, 4, 1L, true);
        assertFalse(WallObstacleGeometry.crosses(new Edge(p(5, 64, -10), p(5, 64, 10)), gate, 0),
                "an edge that never changes side cannot cross the wall plane");
    }
}
