package local.plaincraft.greatwall.smoke;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

final class SeedSurveyLocationsTest {
    private static final String HASH = "0".repeat(64);
    private static final Axis AXIS = new Axis(new Point3i(0, 64, 0), 1 << 30, 0);

    private static RouteShape route(List<Point2q> points) {
        return new RouteShape(new SegmentKey(HASH, "minecraft:overworld", 0), points,
                ArcLength.accumulate(points), new Boundary(0, points.getFirst(), 84, "start"),
                new Boundary(1, points.getLast(), 84, "end"), 0, 2, 0, HASH);
    }
    private static RouteShape straight() {
        return route(List.of(new Point2q(0, 0), new Point2q(2048L * 256, 0)));
    }

    @Test void straightIntervalKeepsArcAndAxisBucketsEqual() {
        var a = SeedSurveyLocations.locate(straight(), AXIS, new ArcInterval(100 * 256, 140 * 256));
        assertEquals("FIRST_0_1024", a.arcBucket()); assertEquals(a.arcBucket(), a.axialBucket());
        assertEquals(100 * 256, a.minUQ8()); assertEquals(140 * 256, a.maxUQ8());
        assertFalse(a.endAlong384Band()); assertFalse(a.outsideProbeAcross48Band());
    }
    @Test void curvedArcPast1024CanStillHaveAxisUInside1024() {
        var curve = route(List.of(new Point2q(0, 0), new Point2q(1024L * 256, 384L * 256),
                new Point2q(2048L * 256, 0)));
        var a = SeedSurveyLocations.locate(curve, AXIS, new ArcInterval(1030 * 256, 1040 * 256));
        assertEquals("SECOND_1024_2048", a.arcBucket());
        assertEquals("FIRST_0_1024", a.axialBucket());
        assertTrue(a.outsideProbeAcross48Band());
    }
    @Test void endBandIsAnOverlappingTagNotAThirdExclusiveBucket() {
        var a = SeedSurveyLocations.locate(straight(), AXIS, new ArcInterval(1700 * 256, 1740 * 256));
        assertEquals("SECOND_1024_2048", a.arcBucket()); assertTrue(a.endAlong384Band());
    }
    @Test void straddlingIntervalsAreNotForcedIntoEitherHalf() {
        var a = SeedSurveyLocations.locate(straight(), AXIS, new ArcInterval(1000 * 256, 1100 * 256));
        assertEquals("STRADDLES_BOUNDARY", a.arcBucket()); assertEquals(a.arcBucket(), a.axialBucket());
    }
    @Test void signedHaloEvidenceRetainsRawRangeAndClipsOnlyForDistribution() {
        var a = SeedSurveyLocations.locate(straight(), AXIS, new ArcInterval(-32 * 256, 32 * 256));
        assertEquals(-32 * 256, a.rawFromSQ8()); assertEquals(0, a.clippedFromSQ8());
        assertEquals("FIRST_0_1024", a.arcBucket()); assertFalse(a.clippedEmpty());
        var b = SeedSurveyLocations.locate(straight(), AXIS, new ArcInterval(2050 * 256, 2060 * 256));
        assertTrue(b.clippedEmpty()); assertEquals("EMPTY", b.arcBucket());
    }
    @Test void actualSegmentArcCanExceed2048WithoutLeavingSegmentZero() {
        var curve = route(List.of(new Point2q(0, 0), new Point2q(1024L * 256, 384L * 256),
                new Point2q(2048L * 256, 0)));
        var a = SeedSurveyLocations.locate(curve, AXIS, new ArcInterval(2050 * 256, 2060 * 256));
        assertEquals("AFTER_2048", a.arcBucket()); assertEquals("SECOND_1024_2048", a.axialBucket());
        assertTrue(a.endAlong384Band()); assertFalse(a.clippedEmpty());
    }
}
