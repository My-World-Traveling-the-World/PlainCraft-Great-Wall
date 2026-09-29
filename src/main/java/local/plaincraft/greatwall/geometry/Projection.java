package local.plaincraft.greatwall.geometry;

import java.util.Comparator;
import java.util.HashMap;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Pure finite-window projection; it never requests another segment or any Minecraft object. */
public final class Projection {
    private Projection() {}

    public static long axisUQ8(Axis axis, int x, int z) {
        long dot = Math.addExact(Math.multiplyExact((long) x - axis.origin().x(), axis.exQ30()),
                Math.multiplyExact((long) z - axis.origin().z(), axis.ezQ30()));
        return FixedMath.roundDiv(dot, 1L << 22);
    }

    public static long segmentAt(long uQ8) { return Math.floorDiv(uQ8, 2048L * 256); }

    public static ProjectionResult nearest(int x, int z, GeometryWindow window) {
        long px = x * 256L + 128, pz = z * 256L + 128;
        var prefixes = new HashMap<Long, PrefixSummary>();
        for (var p : window.prefixes()) {
            if (prefixes.putIfAbsent(p.beforeSegment(), p) != null) throw new IllegalArgumentException("duplicate prefix");
        }
        ProjectionResult result = null;
        long best = Long.MAX_VALUE;
        for (var g : window.segments().stream().sorted(Comparator.comparingLong(v -> v.key().index())).toList()) {
            var prefix = prefixes.get(g.key().index());
            if (prefix == null) throw new IllegalArgumentException("missing exact segment prefix");
            for (int e = 0; e < g.points().size() - 1; e++) {
                Point2q a = g.points().get(e), b = g.points().get(e + 1);
                long guard = 1024L * 256;
                if (px < Math.min(a.xQ8(), b.xQ8()) - guard || px > Math.max(a.xQ8(), b.xQ8()) + guard
                        || pz < Math.min(a.zQ8(), b.zQ8()) - guard || pz > Math.max(a.zQ8(), b.zQ8()) + guard) continue;
                long vx = Math.subtractExact(b.xQ8(), a.xQ8()), vz = Math.subtractExact(b.zQ8(), a.zQ8());
                long wx = Math.subtractExact(px, a.xQ8()), wz = Math.subtractExact(pz, a.zQ8());
                long den = dot(vx, vz, vx, vz);
                long num = Math.clamp(dot(wx, wz, vx, vz), 0L, den);
                long nx = Math.addExact(a.xQ8(), FixedMath.roundDiv(Math.multiplyExact(vx, num), den));
                long nz = Math.addExact(a.zQ8(), FixedMath.roundDiv(Math.multiplyExact(vz, num), den));
                long dx = px - nx, dz = pz - nz, dist = dot(dx, dz, dx, dz);
                if (dist >= best) continue;
                long edgeLength = g.localSQ8().get(e + 1) - g.localSQ8().get(e);
                long s = Math.addExact(prefix.lengthBeforeQ8(), Math.addExact(g.localSQ8().get(e),
                        FixedMath.roundDiv(Math.multiplyExact(edgeLength, num), den)));
                long cross = Math.subtractExact(Math.multiplyExact(vx, wz), Math.multiplyExact(vz, wx));
                long lateral = Long.signum(cross) * FixedMath.hypotQ8(dx, dz);
                double norm = StrictMath.hypot(vx, vz);
                int tx = Math.toIntExact((long) StrictMath.floor(vx * (double) (1L << 30) / norm + 0.5));
                int tz = Math.toIntExact((long) StrictMath.floor(vz * (double) (1L << 30) / norm + 0.5));
                result = new ProjectionResult(g.key().index(), e, s, lateral, new Point2q(nx, nz), tx, tz);
                best = dist;
            }
        }
        if (result == null) throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "no edge in projection window");
        return result;
    }

    public static Point2q local(Frame frame, long alongQ8, long lateralQ8) {
        long x = Math.subtractExact(Math.multiplyExact(alongQ8, frame.txQ30()), Math.multiplyExact(lateralQ8, frame.tzQ30()));
        long z = Math.addExact(Math.multiplyExact(alongQ8, frame.tzQ30()), Math.multiplyExact(lateralQ8, frame.txQ30()));
        return new Point2q(Math.addExact(frame.center().xQ8(), Math.floorDiv(x, 1L << 30)),
                Math.addExact(frame.center().zQ8(), Math.floorDiv(z, 1L << 30)));
    }

    private static long dot(long ax, long az, long bx, long bz) {
        return Math.addExact(Math.multiplyExact(ax, bx), Math.multiplyExact(az, bz));
    }
}
