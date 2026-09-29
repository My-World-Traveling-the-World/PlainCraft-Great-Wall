package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Coordinate-only witnesses of the frozen specification, independent of template implementation. */
class RampSpecificationTest {
    @Test void frozenUpperEndRailSealsTheGroundLandingAtHeightTwenty() {
        int height=20,lower=-height,left=(height+1)/2,right=height-left,e=-27+left,t=e-right+1;
        assertEquals(-30,t-4,"filled upper end rail directly fronts the lower landing");
        for(int z=5;z<8;z++)for(int y=lower;y<lower+3;y++) {
            assertTrue(z>=4&&z<11&&y>=-33&&y<0,"old end-rail foundation occupies entrance clearance");
            assertTrue(-29>(t-4),"lower landing lies behind that closed front wall");
        }
    }
    @ParameterizedTest @ValueSource(ints={1,2})
    void frozenSmallRampPlatformsIntersectRequiredEntranceAir(int height) {
        int lower = 0, upper = height;
        int l = (height+1)/2, r = height-l, e = -27+l, t = r > 0 ? e-r+1 : e;
        int witnessX = -28, witnessZ = 6, upperFloorY = upper-1;
        assertTrue(witnessX >= -29 && witnessX < -26 && witnessZ >= 5 && witnessZ < 8,
                "witness lies in the prescribed bottom landing");
        assertTrue(witnessX >= t-3 && witnessX < t && witnessZ >= 4 && witnessZ < 11,
                "same witness lies in the prescribed top landing");
        assertTrue(upperFloorY >= lower && upperFloorY < lower+2,
                "prescribed upper floor occupies the required two-block entrance clearance");
    }

    @Test void height32LandingSupportFallsBelowFrozenLocalBounds() {
        int w = 84, lowerFeet = w-32;
        assertEquals(51,lowerFeet-1);
        assertTrue(lowerFeet-1 < w-32,
                "ground landing needs pre-existing support evidence or a larger write envelope");
    }
}
