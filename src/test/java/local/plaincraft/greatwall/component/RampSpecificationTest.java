package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Coordinate-only witnesses of the frozen specification, independent of template implementation. */
class RampSpecificationTest {
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
