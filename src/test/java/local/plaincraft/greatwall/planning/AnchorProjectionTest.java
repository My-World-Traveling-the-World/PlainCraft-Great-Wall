package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

class AnchorProjectionTest {
    @Test void carrierSeamsAndTangentsAreIndexOnly() {
        for (long seed : new long[] {0, 1, 9282026, -1}) {
            var a = new AnchorField(PlanningFixtures.recipe(seed));
            for (int i = 0; i < 64; i++) {
                assertEquals(a.at(i).point(), a.carrier(i, 0));
                assertEquals(a.at(i + 1).point(), a.carrier(i, 2048 * 256));
                assertEquals(a.carrier(i, 2048 * 256), a.carrier(i + 1, 0));
                assertEquals(a.at(i + 1), new AnchorField(PlanningFixtures.recipe(seed)).at(i + 1));
                var left = a.carrier(i, 2047 * 256); var seam = a.at(i + 1).point();
                var right = a.carrier(i + 1, 256);
                assertTrue(Math.abs((seam.zQ8() - left.zQ8()) - (right.zQ8() - seam.zQ8())) <= 2);
            }
        }
    }

    @Test void envelopeAndHeadingHoldAtEveryBlock() {
        var a = new AnchorField(PlanningFixtures.recipe(9282026));
        for (int segment = 0; segment < 16; segment++) {
            Point2q previous = a.at(segment).point();
            for (int u = 1; u <= 2048; u++) {
                var p = a.carrier(segment, u * 256);
                assertTrue(Math.abs(p.zQ8()) <= 512L * 256);
                assertTrue(p.xQ8() > previous.xQ8());
                assertTrue(Math.abs(p.zQ8() - previous.zQ8()) <= 215);
                previous = p;
            }
        }
    }

    @Test void negativeProjectionAndMillionCoordinatesAreSafe() {
        var axis = new Axis(new Point3i(0, 0, 0), 1 << 30, 0);
        assertEquals(-256, Projection.axisUQ8(axis, -1, -1));
        assertEquals(-1, Projection.segmentAt(-1));
        assertEquals(1_000_000L * 256, Projection.axisUQ8(axis, 1_000_000, -1_000_000));
        int diagonal = (int) StrictMath.round((1L << 30) / StrictMath.sqrt(2));
        var a = new AnchorField(PlanningFixtures.recipe(1, diagonal, -diagonal, 29_000_000));
        assertTrue(a.at(1000).point().zQ8() < -1_000_000L * 256);
        assertThrows(IllegalArgumentException.class, () -> a.at(-1));
        assertThrows(WallFailure.class, () -> a.at(100_000));
    }

    @Test void projectionUsesTrueArcAndStableEdgeTies() {
        var points = List.of(new Point2q(0, 0), new Point2q(2560, 0), new Point2q(2560, 2560));
        var g = new SegmentGeometry(new SegmentKey(PlanningFixtures.HASH, "minecraft:overworld", 0), points,
                ArcLength.accumulate(points), List.of(), Optional.empty(),
                new Boundary(0, points.getFirst(), 84, "start"), new Boundary(1, points.getLast(), 84, "end"), 0, 0, PlanningFixtures.HASH);
        var p = new PrefixSummary(0, 0, Optional.empty(), PlanningFixtures.HASH);
        var window = new GeometryWindow(List.of(g), List.of(p));
        var result = Projection.nearest(9, 2, window);
        assertEquals(1, result.edge());
        assertEquals(2560 + 640, result.globalSQ8());
        assertEquals(128, result.lateralQ8());
        assertEquals(new Point2q(2560, 640), result.nearest());
        assertEquals(0, Projection.nearest(9, 0, window).edge());
        assertThrows(WallFailure.class, () -> Projection.nearest(5000, 5000, window));
        assertEquals(new Point2q(512, 768), Projection.local(new Frame(new Point2q(0, 0), 1 << 30, 0, 84, false), 512, 768));
    }
}
