package local.plaincraft.greatwall.planning;

import java.math.BigInteger;
import java.util.Objects;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallRecipeRules;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Stateless Hermite carrier. Shared boundary values depend on the boundary index only. */
public final class AnchorField {
    private static final long LENGTH = 2048L * 256;
    private static final long Q30 = 1L << 30;
    private static final BigInteger HERMITE_DIVISOR = BigInteger.ONE.shiftLeft(72);
    private final WorldRecipe recipe;

    public AnchorField(WorldRecipe recipe) {
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        if (recipe.config().segmentBlocks() != 2048 || recipe.config().carrierAmplitude() != 384) {
            throw new IllegalArgumentException("carrier requires the frozen 2048/384 configuration");
        }
    }

    public long lateral(long boundaryIndex) {
        if (boundaryIndex < -1) throw new IllegalArgumentException("only -1 is a virtual boundary");
        Math.multiplyExact(Math.addExact(boundaryIndex, 1), LENGTH);
        // 新墙由真实地形驱动转向，不再随机移动段缝；这样出生点中轴不会先被偏移几百格。
        if (recipe.config().templateVersion() >= WallRecipeRules.INTACT) return 0;
        return boundaryIndex == 0 ? 0 : 256L * (DeterministicHash.bounded(
                DeterministicHash.fields(recipe.seed(), "anchor", boundaryIndex), 769) - 384);
    }

    public Anchor at(long boundaryIndex) {
        if (boundaryIndex < 0) throw new IllegalArgumentException("negative wall segment");
        long u = Math.multiplyExact(boundaryIndex, LENGTH);
        long b = lateral(boundaryIndex);
        long tangent = tangent(boundaryIndex);
        if (StrictMath.abs(StrictMath.atan(tangent / (double) (1 << 24))) > StrictMath.toRadians(40)) {
            throw new WallFailure(FailureCode.INVALID_RESOURCE, "anchor heading exceeds 40 degrees");
        }
        return new Anchor(boundaryIndex, world(u, b), b, tangent);
    }

    public Point2q carrier(long segment, int localUQ8) {
        if (segment < 0 || localUQ8 < 0 || localUQ8 > LENGTH) throw new IllegalArgumentException("carrier range");
        if (recipe.config().templateVersion() >= WallRecipeRules.INTACT)
            return world(Math.addExact(Math.multiplyExact(segment, LENGTH), localUQ8), 0);
        if (localUQ8 == 0) return at(segment).point();
        if (localUQ8 == LENGTH) return at(Math.addExact(segment, 1)).point();
        long b0 = lateral(segment), b1 = lateral(Math.addExact(segment, 1));
        long m0 = Math.multiplyExact(tangent(segment), LENGTH), m1 = Math.multiplyExact(tangent(segment + 1), LENGTH);
        checkEnvelope(b0, b1, m0, m1);
        long t = (long) localUQ8 * 65536 / LENGTH, t2 = t * t, t3 = t2 * t;
        long h00 = 2 * t3 - 3 * t2 * 65536 + (1L << 48);
        long h10 = t3 - 2 * t2 * 65536 + t * (1L << 32);
        long h01 = -2 * t3 + 3 * t2 * 65536;
        long h11 = t3 - t2 * 65536;
        BigInteger numerator = BigInteger.valueOf(h00).multiply(BigInteger.valueOf(b0)).shiftLeft(24)
                .add(BigInteger.valueOf(h01).multiply(BigInteger.valueOf(b1)).shiftLeft(24))
                .add(BigInteger.valueOf(h10).multiply(BigInteger.valueOf(m0)))
                .add(BigInteger.valueOf(h11).multiply(BigInteger.valueOf(m1)));
        BigInteger[] qr = numerator.divideAndRemainder(HERMITE_DIVISOR);
        if (qr[1].signum() < 0) { qr[0] = qr[0].subtract(BigInteger.ONE); qr[1] = qr[1].add(HERMITE_DIVISOR); }
        if (qr[1].shiftLeft(1).compareTo(HERMITE_DIVISOR) > 0) qr[0] = qr[0].add(BigInteger.ONE);
        return world(Math.addExact(Math.multiplyExact(segment, LENGTH), localUQ8), qr[0].longValueExact());
    }

    private long tangent(long index) {
        return FixedMath.roundDiv(Math.multiplyExact(lateral(Math.addExact(index, 1)) - lateral(index - 1), 1L << 24), 2 * LENGTH);
    }

    private Point2q world(long u, long b) {
        var axis = recipe.axis();
        long x = Math.subtractExact(Math.addExact(axis.origin().x() * 256L, FixedMath.mulDiv(u, axis.exQ30(), Q30)),
                FixedMath.mulDiv(b, axis.ezQ30(), Q30));
        long z = Math.addExact(Math.addExact(axis.origin().z() * 256L, FixedMath.mulDiv(u, axis.ezQ30(), Q30)),
                FixedMath.mulDiv(b, axis.exQ30(), Q30));
        long limit = recipe.bounds().horizontalLimit() * 256L;
        if (x < -limit || x > limit || z < -limit || z > limit) {
            throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "anchor outside frozen horizontal bounds");
        }
        return new Point2q(x, z);
    }

    private static void checkEnvelope(long b0, long b1, long m0Q24, long m1Q24) {
        double m0 = m0Q24 / (double) (1 << 24), m1 = m1Q24 / (double) (1 << 24);
        double a = 2 * b0 - 2 * b1 + m0 + m1, b = -3 * b0 + 3 * b1 - 2 * m0 - m1;
        double maximum = StrictMath.max(StrictMath.abs(b0), StrictMath.abs(b1));
        if (a == 0) {
            if (b != 0) maximum = maxAt(maximum, -m0 / (2 * b), a, b, m0, b0);
        } else {
            double disc = 4 * b * b - 12 * a * m0;
            if (disc >= 0) {
                double root = StrictMath.sqrt(disc);
                maximum = maxAt(maximum, (-2 * b - root) / (6 * a), a, b, m0, b0);
                maximum = maxAt(maximum, (-2 * b + root) / (6 * a), a, b, m0, b0);
            }
        }
        if (StrictMath.ceil(maximum) + 1 > 512 * 256) {
            throw new WallFailure(FailureCode.INVALID_RESOURCE, "carrier envelope exceeds 512 blocks");
        }
    }

    private static double maxAt(double max, double t, double a, double b, double c, double d) {
        return t > 0 && t < 1 ? StrictMath.max(max, StrictMath.abs(((a * t + b) * t + c) * t + d)) : max;
    }
}
