package local.plaincraft.greatwall.smoke;

import java.util.Objects;
import local.plaincraft.greatwall.geometry.ArcLength;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Test-only coordinates. A longitudinal interval is not a certificate for the nine root probes. */
public final class SeedSurveyLocations {
    private SeedSurveyLocations() {}

    public record Location(long rawFromSQ8, long rawToSQ8, long clippedFromSQ8, long clippedToSQ8,
            long segmentLengthSQ8, long minUQ8, long maxUQ8, long minLateralQ8, long maxLateralQ8,
            String arcBucket, String axialBucket, boolean endAlong384Band,
            boolean outsideProbeAcross48Band, boolean clippedEmpty) {}

    public static Location locate(RouteShape route, Axis axis, ArcInterval crossing) {
        Objects.requireNonNull(route); Objects.requireNonNull(axis); Objects.requireNonNull(crossing);
        long length = route.localSQ8().getLast();
        long from = Math.clamp(crossing.fromQ8(), 0, length), to = Math.clamp(crossing.toQ8(), 0, length);
        if (to <= from) return new Location(crossing.fromQ8(), crossing.toQ8(), from, to, length,
                0, 0, 0, 0, "EMPTY", "EMPTY", false, false, true);
        long[] extremes = {Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE};
        include(extremes, axis, pointAt(route, from));
        include(extremes, axis, pointAt(route, to));
        for (int i = ArcLength.edgeAt(route.localSQ8(), from); i < route.points().size(); i++) {
            long s = route.localSQ8().get(i);
            if (s > to) break;
            if (s >= from) include(extremes, axis, route.points().get(i));
        }
        // This is a declared diagnostic band, not the carrier's lateral displacement.
        boolean endBand = extremes[1] >= 1664L * 256 && extremes[0] <= 2048L * 256;
        return new Location(crossing.fromQ8(), crossing.toQ8(), from, to, length,
                extremes[0], extremes[1], extremes[2], extremes[3], bucket(from, to),
                bucket(extremes[0], extremes[1]), endBand,
                extremes[2] < -48L * 256 || extremes[3] > 48L * 256, false);
    }

    public static Point2q pointAt(RouteShape route, long s) {
        int edge = ArcLength.edgeAt(route.localSQ8(), s);
        var a = route.points().get(edge); var b = route.points().get(edge + 1);
        long length = route.localSQ8().get(edge + 1) - route.localSQ8().get(edge), offset = s - route.localSQ8().get(edge);
        return new Point2q(a.xQ8() + FixedMath.roundDiv(Math.multiplyExact(b.xQ8() - a.xQ8(), offset), length),
                a.zQ8() + FixedMath.roundDiv(Math.multiplyExact(b.zQ8() - a.zQ8(), offset), length));
    }

    private static void include(long[] extrema, Axis axis, Point2q point) {
        long dx = point.xQ8() - axis.origin().x() * 256L, dz = point.zQ8() - axis.origin().z() * 256L;
        long u = FixedMath.roundDiv(Math.addExact(Math.multiplyExact(dx, axis.exQ30()),
                Math.multiplyExact(dz, axis.ezQ30())), 1L << 30);
        long b = FixedMath.roundDiv(Math.addExact(-Math.multiplyExact(dx, axis.ezQ30()),
                Math.multiplyExact(dz, axis.exQ30())), 1L << 30);
        extrema[0] = Math.min(extrema[0], u); extrema[1] = Math.max(extrema[1], u);
        extrema[2] = Math.min(extrema[2], b); extrema[3] = Math.max(extrema[3], b);
    }

    private static String bucket(long from, long to) {
        if (from >= 0 && to <= 1024L * 256) return "FIRST_0_1024";
        if (from >= 1024L * 256 && to <= 2048L * 256) return "SECOND_1024_2048";
        if (from >= 2048L * 256) return "AFTER_2048";
        return "STRADDLES_BOUNDARY";
    }
}
