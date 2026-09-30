package local.plaincraft.greatwall.geometry;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MulDivFastPathTest {
    @Test void integerFastPathAndWideFallbackMatchIndependentFloorDivision() {
        long[] edges={Long.MIN_VALUE,Long.MIN_VALUE+1,-3037000500L,-256,-1,0,1,255,3037000500L,Long.MAX_VALUE};
        for(long a:edges)for(long b:edges)for(long d:new long[]{1,2,3,256,1024,Long.MAX_VALUE})verify(a,b,d);
        var random=new Random(19930429);
        for(int n=0;n<20000;n++) {
            long a=n%2==0?random.nextInt(2000001)-1000000L:random.nextLong();
            long b=n%2==0?random.nextInt(2049)-1024L:random.nextLong();
            long d=(random.nextLong()&Long.MAX_VALUE)|1;
            verify(a,b,d);
        }
    }

    @Test void invalidDivisorsAreStillRejectedBeforeMultiplication() {
        for(long divisor:new long[]{0,-1,Long.MIN_VALUE})
            assertThrows(IllegalArgumentException.class,()->FixedMath.mulDiv(Long.MAX_VALUE,Long.MAX_VALUE,divisor));
    }

    private static void verify(long a,long b,long divisor) {
        var qr=BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divideAndRemainder(BigInteger.valueOf(divisor));
        var floor=qr[1].signum()<0?qr[0].subtract(BigInteger.ONE):qr[0];
        if(floor.compareTo(BigInteger.valueOf(Long.MIN_VALUE))<0||floor.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0)
            assertThrows(ArithmeticException.class,()->FixedMath.mulDiv(a,b,divisor));
        else assertEquals(floor.longValueExact(),FixedMath.mulDiv(a,b,divisor),"a="+a+" b="+b+" divisor="+divisor);
    }
}
