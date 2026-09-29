package local.plaincraft.greatwall.geometry;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import local.plaincraft.greatwall.model.WallTypes.SiteId;
import local.plaincraft.greatwall.model.WallTypes.SiteKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FixedMathTest {
    @Test
    void negativeCoordinatesUseFloorDivision() {
        assertEquals(-1, FixedMath.floorBlock(-1));
        assertEquals(-1, FixedMath.chunk(-1));
        assertEquals(-2, FixedMath.chunk(-17));
        assertEquals(-2, FixedMath.floorBlock(-257));
        assertEquals(-3, FixedMath.mulDiv(-5, 1, 2));
        assertEquals(-3, FixedMath.roundDiv(-5, 2));
        assertEquals(2, FixedMath.roundDiv(5, 2));
    }

    @Test
    void millionBlockMathDoesNotOverflow() {
        assertEquals(8_000_000_000_000L, FixedMath.distanceSquared(-1_000_000, -1_000_000, 1_000_000, 1_000_000));
        assertEquals(7_200_000_000_000_000L,
            FixedMath.distanceSquared(-30_000_000, -30_000_000, 30_000_000, 30_000_000));
        assertEquals(Long.MAX_VALUE, FixedMath.mulDiv(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(30_000_000, FixedMath.floorBlock(30_000_000L * FixedMath.Q8));
        assertEquals(1280, FixedMath.hypotQ8(768, 1024));
    }

    @Test
    void integerSquareRootMatchesBigInteger() {
        for (long n : new long[] {0, 1, 2, 3, 4, 9, Long.MAX_VALUE, 9_223_372_030_926_249_001L}) {
            assertEquals(BigInteger.valueOf(n).sqrt().longValueExact(), FixedMath.isqrt(n));
        }
        var random = new Random(9282026);
        for (int i = 0; i < 20_000; i++) {
            long n = random.nextLong() & Long.MAX_VALUE;
            assertEquals(BigInteger.valueOf(n).sqrt().longValueExact(), FixedMath.isqrt(n));
        }
    }

    @Test
    void multiplicationMatchesExactFloorReference() {
        var random = new Random(100);
        for (int i = 0; i < 5000; i++) {
            long a = random.nextInt();
            long b = random.nextInt();
            long d = 1L + random.nextInt(Integer.MAX_VALUE);
            BigInteger numerator = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
            BigInteger[] qr = numerator.divideAndRemainder(BigInteger.valueOf(d));
            if (qr[1].signum() < 0) qr[0] = qr[0].subtract(BigInteger.ONE);
            assertEquals(qr[0].longValueExact(), FixedMath.mulDiv(a, b, d));
        }
    }

    @Test
    void smoothAndLerpHaveExactEndpoints() {
        assertEquals(0, FixedMath.smoothQ16(0));
        assertEquals(32768, FixedMath.smoothQ16(32768));
        assertEquals(65536, FixedMath.smoothQ16(65536));
        int last = -1;
        for (int t = 0; t <= 65536; t++) {
            int value = FixedMath.smoothQ16(t);
            assertTrue(value >= last);
            last = value;
        }
        assertEquals(-7, FixedMath.lerp(-7, 15, 0));
        assertEquals(15, FixedMath.lerp(-7, 15, 65536));
        assertEquals(4, FixedMath.lerp(-7, 15, 32768));
    }

    @Test
    void overflowAndInvalidInputsAreRejected() {
        assertThrows(ArithmeticException.class, () -> FixedMath.mulDiv(Long.MAX_VALUE, 2, 1));
        assertThrows(ArithmeticException.class,
            () -> FixedMath.distanceSquared(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0));
        assertThrows(ArithmeticException.class, () -> FixedMath.floorBlock(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> FixedMath.isqrt(-1));
        assertThrows(IllegalArgumentException.class, () -> FixedMath.hypotQ8(Long.MIN_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> FixedMath.roundDiv(1, 0));
        assertThrows(IllegalArgumentException.class, () -> FixedMath.smoothQ16(65537));
    }

    @Test
    void splitmixAndShaHaveFixedVectors() {
        assertEquals(0xE220A8397B1DCDAFL, DeterministicHash.mix64(0));
        assertEquals(0x910A2DEC89025CC1L, DeterministicHash.mix64(1));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DeterministicHash.sha256("abc".getBytes(StandardCharsets.UTF_8)));
        for (int bound : new int[] {1, 201, 769, Integer.MAX_VALUE}) {
            long value = Long.MIN_VALUE;
            int expected = new BigInteger(Long.toUnsignedString(value)).mod(BigInteger.valueOf(bound)).intValueExact();
            assertEquals(expected, DeterministicHash.bounded(value, bound));
        }
    }

    @Test
    void fieldOrderChangesHash() {
        assertNotEquals(DeterministicHash.fields(1, "anchor", 2, 3), DeterministicHash.fields(1, "anchor", 3, 2));
        assertNotEquals(DeterministicHash.fields(1, "anchor", 2), DeterministicHash.fields(1, "gate", 2));
        assertNotEquals(DeterministicHash.fields(1, "anchor", 2), DeterministicHash.fields(1, "anchor", 2, 0));
        assertEquals(DeterministicHash.fields(1, "anchor", 2, 3), DeterministicHash.fields(1, "anchor", 2, 3));
    }

    @Test
    void entityIdentityIsStableAndNamespaced() {
        var site = new SiteId("0".repeat(64), SiteKind.ENEMY_TOWER, 9);
        var uuid = DeterministicHash.entityUuid(site, 1);
        assertEquals(uuid, DeterministicHash.entityUuid(site, 1));
        assertNotEquals(uuid, DeterministicHash.entityUuid(site, 2));
        assertEquals(5, uuid.version());
        assertEquals(2, uuid.variant());
    }
}
