package local.plaincraft.greatwall.geometry;

import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.model.WallTypes.Point2q;
import local.plaincraft.greatwall.model.WallTypes.SegmentGeometry;

/** Arc distance is independent of projection-axis distance. All lengths are Q8. */
public final class ArcLength {
    private ArcLength() {}

    public static List<Long> accumulate(List<Point2q> points) {
        if (points.size() < 2) throw new IllegalArgumentException("at least two points required");
        var result = new ArrayList<Long>(points.size());
        long sum = 0;
        result.add(sum);
        for (int i = 1; i < points.size(); i++) {
            Point2q a = points.get(i - 1), b = points.get(i);
            long length = FixedMath.hypotQ8(Math.subtractExact(b.xQ8(), a.xQ8()), Math.subtractExact(b.zQ8(), a.zQ8()));
            if (length == 0) throw new IllegalArgumentException("zero-length edge");
            sum = Math.addExact(sum, length);
            result.add(sum);
        }
        return List.copyOf(result);
    }

    public static Point2q pointAt(SegmentGeometry geometry, long localSQ8) {
        int edge = edgeAt(geometry.localSQ8(), localSQ8);
        var a = geometry.points().get(edge); var b = geometry.points().get(edge + 1);
        long offset = localSQ8 - geometry.localSQ8().get(edge);
        long length = geometry.localSQ8().get(edge + 1) - geometry.localSQ8().get(edge);
        return new Point2q(Math.addExact(a.xQ8(), FixedMath.roundDiv(Math.multiplyExact(b.xQ8() - a.xQ8(), offset), length)),
                Math.addExact(a.zQ8(), FixedMath.roundDiv(Math.multiplyExact(b.zQ8() - a.zQ8(), offset), length)));
    }

    public static int edgeAt(List<Long> lengths, long localSQ8) {
        if (lengths.size() < 2 || localSQ8 < 0 || localSQ8 > lengths.getLast()) {
            throw new IllegalArgumentException("distance outside arc");
        }
        int lo = 0, hi = lengths.size() - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (lengths.get(mid) <= localSQ8) lo = mid; else hi = mid;
        }
        return Math.min(lo, lengths.size() - 2);
    }
}
