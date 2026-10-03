package local.plaincraft.greatwall.compat.road;

import net.shiroha233.roadweaver.api.interop.WallInteropTypes.*;

/** Continuous XZ tests: long edges and sparse polylines cannot skip an obstacle. */
final class WallObstacleGeometry {
    private WallObstacleGeometry() {}

    static boolean intersects(Strip strip, Rect bounds) {
        double minX = bounds.minX(), minZ = bounds.minZ();
        double maxX = bounds.maxXExclusive(), maxZ = bounds.maxZExclusive();
        for (int i = 1; i < strip.centerline().size(); i++) {
            Pos a = strip.centerline().get(i - 1), b = strip.centerline().get(i);
            if (clips(a.x(), a.z(), b.x(), b.z(), minX, minZ, maxX, maxZ)) return true;
            double reach = (double) strip.halfWidth() * strip.halfWidth();
            if (distanceSquared(a.x(), a.z(), b.x(), b.z(), minX, minZ, maxX, minZ) <= reach
                    || distanceSquared(a.x(), a.z(), b.x(), b.z(), maxX, minZ, maxX, maxZ) <= reach
                    || distanceSquared(a.x(), a.z(), b.x(), b.z(), maxX, maxZ, minX, maxZ) <= reach
                    || distanceSquared(a.x(), a.z(), b.x(), b.z(), minX, maxZ, minX, minZ) <= reach) return true;
        }
        return false;
    }

    static boolean swept(Edge road, Pos a, Pos b, int reach) {
        return distanceSquared(road.from().x(), road.from().z(), road.to().x(), road.to().z(),
                a.x(), a.z(), b.x(), b.z()) <= (double) reach * reach;
    }

    static boolean crosses(Edge edge, Passage gate, int coreRadius) {
        double[] a = local(edge.from(), gate), b = local(edge.to(), gate);
        if (a[1] == b[1] || a[1] * b[1] > 0) return false;
        double at = -a[1] / (b[1] - a[1]);
        if (at < 0 || at > 1) return false;
        double depth = gate.thickness() / 2.0;
        double first = Math.max(0, Math.min((-depth - a[1]) / (b[1] - a[1]),
                (depth - a[1]) / (b[1] - a[1])));
        double last = Math.min(1, Math.max((-depth - a[1]) / (b[1] - a[1]),
                (depth - a[1]) / (b[1] - a[1])));
        for (double t : new double[] {first, last}) {
            double along = a[0] + (b[0] - a[0]) * t;
            double y = edge.from().y() + ((double) edge.to().y() - edge.from().y()) * t;
            if (Math.abs(along) + coreRadius > gate.clearWidth() / 2.0
                    || y < gate.center().y() || y + 2 > gate.center().y() + gate.clearHeight()) return false;
        }
        return true;
    }

    static boolean opensSegment(Edge edge, Passage gate, Pos a, Pos b, int wallWidth, int radius) {
        if (!crosses(edge, gate, Math.min(2, radius))) return false;
        double[] p = local(a, gate), q = local(b, gate);
        // Only this gate's wall plane may be exempted. A second/curved wall remains solid.
        if (Math.abs(p[1] - q[1]) > 1e-7
                || Math.abs(p[1]) > 1e-7
                || Math.abs(p[1]) + wallWidth > gate.thickness() / 2.0) return false;
        double[] from = local(edge.from(), gate), to = local(edge.to(), gate);
        double depth = gate.thickness() / 2.0;
        // The shoulders taper over sixteen blocks outside the full wall thickness.
        for (double[] endpoint : new double[][] {from, to}) {
            double outside = Math.max(0, Math.abs(endpoint[1]) - depth);
            double shoulder = Math.min(radius, radius * outside / 16.0);
            if (outside > 0 && shoulder > outside + depth - Math.abs(p[1]) - wallWidth) return false;
        }
        return Math.min(p[0], q[0]) <= gate.clearWidth() / 2.0
                && Math.max(p[0], q[0]) >= -gate.clearWidth() / 2.0;
    }

    private static double[] local(Pos point, Passage gate) {
        double length = Math.hypot(gate.tangentXQ30(), gate.tangentZQ30());
        if (length == 0) throw new IllegalArgumentException("gate tangent is zero");
        double tx = gate.tangentXQ30() / length, tz = gate.tangentZQ30() / length;
        double x = (double) point.x() - gate.center().x(), z = (double) point.z() - gate.center().z();
        return new double[] {x * tx + z * tz, -x * tz + z * tx};
    }

    private static boolean clips(double ax, double az, double bx, double bz,
            double minX, double minZ, double maxX, double maxZ) {
        double low = 0, high = 1;
        double[] origin = {ax, az}, delta = {bx - ax, bz - az};
        double[] min = {minX, minZ}, max = {maxX, maxZ};
        for (int axis = 0; axis < 2; axis++) {
            if (delta[axis] == 0) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) return false;
            } else {
                double first = (min[axis] - origin[axis]) / delta[axis];
                double last = (max[axis] - origin[axis]) / delta[axis];
                low = Math.max(low, Math.min(first, last));
                high = Math.min(high, Math.max(first, last));
                if (low > high) return false;
            }
        }
        return true;
    }

    private static double distanceSquared(double ax, double az, double bx, double bz,
            double cx, double cz, double dx, double dz) {
        double cross1 = cross(bx - ax, bz - az, cx - ax, cz - az);
        double cross2 = cross(bx - ax, bz - az, dx - ax, dz - az);
        double cross3 = cross(dx - cx, dz - cz, ax - cx, az - cz);
        double cross4 = cross(dx - cx, dz - cz, bx - cx, bz - cz);
        if (cross1 * cross2 <= 0 && cross3 * cross4 <= 0
                && Math.max(Math.min(ax, bx), Math.min(cx, dx)) <= Math.min(Math.max(ax, bx), Math.max(cx, dx))
                && Math.max(Math.min(az, bz), Math.min(cz, dz)) <= Math.min(Math.max(az, bz), Math.max(cz, dz))) return 0;
        return Math.min(Math.min(pointDistance(ax, az, cx, cz, dx, dz), pointDistance(bx, bz, cx, cz, dx, dz)),
                Math.min(pointDistance(cx, cz, ax, az, bx, bz), pointDistance(dx, dz, ax, az, bx, bz)));
    }

    private static double cross(double ax, double az, double bx, double bz) { return ax * bz - az * bx; }

    private static double pointDistance(double x, double z, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az, length = dx * dx + dz * dz;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / length));
        double px = x - ax - t * dx, pz = z - az - t * dz;
        return px * px + pz * pz;
    }
}
