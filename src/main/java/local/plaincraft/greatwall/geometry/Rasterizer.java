package local.plaincraft.greatwall.geometry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Pure inverse-cell rasterization. No Minecraft objects or shared mutable state. */
public final class Rasterizer {
    private static final BigInteger Q30 = BigInteger.ONE.shiftLeft(30);
    private static final BigInteger CELL = Q30.multiply(BigInteger.valueOf(256));
    private static final Comparator<Point3i> ORDER = Comparator.comparingInt(Point3i::y)
            .thenComparingInt(Point3i::z).thenComparingInt(Point3i::x);
    private static final int MAX_VISITS = 1_048_576;

    private Rasterizer() {}

    public static Bounds worldBounds(Frame frame, Bounds local) {
        Objects.requireNonNull(frame); Objects.requireNonNull(local);
        BigInteger minX = null, maxX = null, minZ = null, maxZ = null;
        for (int x : new int[] {local.minX(), local.maxXExclusive()}) {
            for (int z : new int[] {local.minZ(), local.maxZExclusive()}) {
                long n = frame.mirror() ? -(long) z : z;
                BigInteger wx = BigInteger.valueOf(frame.center().xQ8()).multiply(Q30)
                        .add(dot(x, frame.txQ30(), -n, frame.tzQ30()).multiply(BigInteger.valueOf(256)));
                BigInteger wz = BigInteger.valueOf(frame.center().zQ8()).multiply(Q30)
                        .add(dot(x, frame.tzQ30(), n, frame.txQ30()).multiply(BigInteger.valueOf(256)));
                minX = minX == null ? wx : minX.min(wx); maxX = maxX == null ? wx : maxX.max(wx);
                minZ = minZ == null ? wz : minZ.min(wz); maxZ = maxZ == null ? wz : maxZ.max(wz);
            }
        }
        return new Bounds(Math.subtractExact(floor(minX, CELL).intValueExact(), 1),
                Math.addExact(frame.walkY(), local.minY()),
                Math.subtractExact(floor(minZ, CELL).intValueExact(), 1),
                Math.addExact(ceil(maxX, CELL).intValueExact(), 1),
                Math.addExact(frame.walkY(), local.maxYExclusive()),
                Math.addExact(ceil(maxZ, CELL).intValueExact(), 1));
    }

    public static Point3i localCell(Frame frame, Point3i world) {
        Objects.requireNonNull(frame); Objects.requireNonNull(world);
        long dx = Math.subtractExact((long) world.x() * 256 + 128, frame.center().xQ8());
        long dz = Math.subtractExact((long) world.z() * 256 + 128, frame.center().zQ8());
        BigInteger x = dot(dx, frame.txQ30(), dz, frame.tzQ30());
        BigInteger z = dot(-dx, frame.tzQ30(), dz, frame.txQ30());
        if (frame.mirror()) z = z.negate();
        return new Point3i(floor(x, CELL).intValueExact(),
                Math.subtractExact(world.y(), frame.walkY()), floor(z, CELL).intValueExact());
    }

    public static List<Point3i> supercover(Point2q from, Point2q to, int y) {
        Objects.requireNonNull(from); Objects.requireNonNull(to);
        int x = FixedMath.floorBlock(from.xQ8()), z = FixedMath.floorBlock(from.zQ8());
        int endX = FixedMath.floorBlock(to.xQ8()), endZ = FixedMath.floorBlock(to.zQ8());
        long dx = Math.subtractExact(to.xQ8(), from.xQ8()), dz = Math.subtractExact(to.zQ8(), from.zQ8());
        long steps = Math.abs((long) endX - x) + Math.abs((long) endZ - z);
        if (steps > MAX_VISITS / 4) throw new IllegalArgumentException("supercover exceeds bounded traversal");
        int sx = Long.compare(dx, 0), sz = Long.compare(dz, 0);
        long ax = Math.abs(dx), az = Math.abs(dz);
        var result = new TreeSet<>(ORDER);
        addEndpoint(result, from, y); addEndpoint(result, to, y);
        long nx = sx > 0 ? ((long) x + 1) * 256 - from.xQ8() : from.xQ8() - (long) x * 256;
        long nz = sz > 0 ? ((long) z + 1) * 256 - from.zQ8() : from.zQ8() - (long) z * 256;
        boolean xBoundary = dx == 0 && Math.floorMod(from.xQ8(), 256) == 0;
        boolean zBoundary = dz == 0 && Math.floorMod(from.zQ8(), 256) == 0;
        addCell(result, x, y, z, xBoundary, zBoundary);
        while (x != endX || z != endZ) {
            int compare = x == endX ? 1 : z == endZ ? -1
                    : BigInteger.valueOf(nx).multiply(BigInteger.valueOf(az))
                            .compareTo(BigInteger.valueOf(nz).multiply(BigInteger.valueOf(ax)));
            if (compare == 0) {
                addCell(result, Math.addExact(x, sx), y, z, xBoundary, zBoundary);
                addCell(result, x, y, Math.addExact(z, sz), xBoundary, zBoundary);
            }
            if (compare <= 0) { x = Math.addExact(x, sx); nx = Math.addExact(nx, 256); }
            if (compare >= 0) { z = Math.addExact(z, sz); nz = Math.addExact(nz, 256); }
            addCell(result, x, y, z, xBoundary, zBoundary);
        }
        return List.copyOf(result);
    }

    public static List<ChunkKey> owners(String dimension, Bounds worldBounds) {
        Objects.requireNonNull(worldBounds);
        int minX = Math.floorDiv(worldBounds.minX(), 16), minZ = Math.floorDiv(worldBounds.minZ(), 16);
        int maxX = Math.floorDiv(worldBounds.maxXExclusive() - 1, 16);
        int maxZ = Math.floorDiv(worldBounds.maxZExclusive() - 1, 16);
        long count = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
        if (count > 256) throw new IllegalArgumentException("component spans more than 256 owners");
        var result = new ArrayList<ChunkKey>((int) count);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            result.add(new ChunkKey(dimension, x, z));
        return List.copyOf(result);
    }

    private static void addEndpoint(TreeSet<Point3i> cells, Point2q point, int y) {
        addCell(cells, FixedMath.floorBlock(point.xQ8()), y, FixedMath.floorBlock(point.zQ8()),
                Math.floorMod(point.xQ8(), 256) == 0, Math.floorMod(point.zQ8(), 256) == 0);
    }

    private static void addCell(TreeSet<Point3i> cells, int x, int y, int z, boolean bx, boolean bz) {
        cells.add(new Point3i(x, y, z));
        if (bx) cells.add(new Point3i(Math.subtractExact(x, 1), y, z));
        if (bz) cells.add(new Point3i(x, y, Math.subtractExact(z, 1)));
        if (bx && bz) cells.add(new Point3i(Math.subtractExact(x, 1), y, Math.subtractExact(z, 1)));
    }

    private static BigInteger dot(long a, long b, long c, long d) {
        return BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
                .add(BigInteger.valueOf(c).multiply(BigInteger.valueOf(d)));
    }

    private static BigInteger floor(BigInteger numerator, BigInteger divisor) {
        BigInteger[] pair = numerator.divideAndRemainder(divisor);
        return pair[1].signum() < 0 ? pair[0].subtract(BigInteger.ONE) : pair[0];
    }

    private static BigInteger ceil(BigInteger numerator, BigInteger divisor) {
        return floor(numerator.negate(), divisor).negate();
    }
}
