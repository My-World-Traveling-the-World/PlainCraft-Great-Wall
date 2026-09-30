package local.plaincraft.greatwall.geometry;

import java.math.BigInteger;

/** Pure fixed-point operations; all overflow except hash mixing is an error. */
public final class FixedMath {
    public static final int Q8 = 256;
    public static final int Q16 = 65536;
    private static final long MAX_SQRT = 3_037_000_499L;
    private static final long MAX_LOCAL_DELTA = 1L << 30;

    private FixedMath() {}

    public static long mulDiv(long a, long b, long positiveDivisor) {
        requirePositive(positiveDivisor);
        try { return Math.floorDiv(Math.multiplyExact(a,b),positiveDivisor); }
        catch (ArithmeticException overflow) { /* Only overflowing products need the wide path. */ }
        BigInteger[] result = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
            .divideAndRemainder(BigInteger.valueOf(positiveDivisor));
        if (result[1].signum() < 0) result[0] = result[0].subtract(BigInteger.ONE);
        return result[0].longValueExact();
    }

    public static long roundDiv(long numerator, long positiveDivisor) {
        requirePositive(positiveDivisor);
        long quotient = Math.floorDiv(numerator, positiveDivisor);
        long remainder = Math.floorMod(numerator, positiveDivisor);
        return remainder > positiveDivisor - remainder ? Math.addExact(quotient, 1) : quotient;
    }

    public static long isqrt(long nonNegative) {
        if (nonNegative < 0) throw new IllegalArgumentException("square root input must be nonnegative");
        long low = 0;
        long high = Math.min(nonNegative, MAX_SQRT) + 1;
        while (low + 1 < high) {
            long middle = low + (high - low) / 2;
            if (middle <= nonNegative / middle) low = middle;
            else high = middle;
        }
        return low;
    }

    public static long hypotQ8(long dxQ8, long dzQ8) {
        if (dxQ8 < -MAX_LOCAL_DELTA || dxQ8 > MAX_LOCAL_DELTA
                || dzQ8 < -MAX_LOCAL_DELTA || dzQ8 > MAX_LOCAL_DELTA) {
            throw new IllegalArgumentException("local Q8 delta exceeds 2^30");
        }
        long squared = Math.addExact(Math.multiplyExact(dxQ8, dxQ8), Math.multiplyExact(dzQ8, dzQ8));
        long floor = isqrt(squared);
        long lowerDistance = squared - floor * floor;
        long upperDistance = (floor + 1) * (floor + 1) - squared;
        return lowerDistance > upperDistance ? floor + 1 : floor;
    }

    public static int smoothQ16(int tQ16) {
        requireFraction(tQ16);
        return (int) ((long) tQ16 * tQ16 * (3L * Q16 - 2L * tQ16) / ((long) Q16 * Q16));
    }

    public static long lerp(long a, long b, int tQ16) {
        requireFraction(tQ16);
        return Math.addExact(a, mulDiv(Math.subtractExact(b, a), tQ16, Q16));
    }

    public static int floorBlock(long q8) {
        return Math.toIntExact(Math.floorDiv(q8, Q8));
    }

    public static int chunk(int block) {
        return Math.floorDiv(block, 16);
    }

    public static long distanceSquared(int ax, int az, int bx, int bz) {
        long dx = (long) ax - bx;
        long dz = (long) az - bz;
        return Math.addExact(Math.multiplyExact(dx, dx), Math.multiplyExact(dz, dz));
    }

    private static void requirePositive(long divisor) {
        if (divisor <= 0) throw new IllegalArgumentException("divisor must be positive");
    }

    private static void requireFraction(int tQ16) {
        if (tQ16 < 0 || tQ16 > Q16) throw new IllegalArgumentException("fraction must be in [0,65536]");
    }
}
