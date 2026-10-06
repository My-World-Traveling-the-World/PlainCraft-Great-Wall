package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** Pins the lane-count policy: hardware-derived, bounded, and overridable only deliberately. */
final class LanePolicyTest {
    @Test void derivedPolicyStaysWithinDocumentedBounds() {
        var policy = LanePolicy.resolve();
        assertTrue(policy.startupLaneCount() >= LanePolicy.MIN_LANES,
                "never below the historical pair: " + policy.startupLaneCount());
        assertTrue(policy.startupLaneCount() <= LanePolicy.MAX_LANES,
                "never above the native ceiling: " + policy.startupLaneCount());
        assertEquals(policy.startupLaneCount() - 1, policy.maxIndex());
    }

    @Test void gameplayNeverExceedsStartupAndNeverDropsBelowThePair() {
        var policy = LanePolicy.resolve();
        assertTrue(policy.gameplayLaneCount() >= LanePolicy.MIN_LANES);
        assertTrue(policy.gameplayLaneCount() <= policy.startupLaneCount());
        assertEquals(LanePolicy.MIN_LANES, policy.gameplayLaneCount(),
                "gameplay retreats to a small owner set regardless of hardware");
    }

    @Test void explicitPolicyRejectsIncoherentCounts() {
        assertThrows(IllegalArgumentException.class, () -> LanePolicy.of(1, 2));
        assertThrows(IllegalArgumentException.class, () -> LanePolicy.of(4, 8));
        assertEquals(4, LanePolicy.of(4, 2).startupLaneCount());
        assertEquals(2, LanePolicy.of(4, 2).gameplayLaneCount());
    }

    @Test void explicitPolicyClampsAboveTheCeiling() {
        var policy = LanePolicy.of(64, 2);
        assertEquals(LanePolicy.MAX_LANES, policy.startupLaneCount());
    }
}
