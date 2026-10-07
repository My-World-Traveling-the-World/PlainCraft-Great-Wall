package local.plaincraft.greatwall.gameplay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.plaincraft.greatwall.model.WallTypes.DecayState;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link GarrisonController#allowed}, the pure spawn policy that decides whether a garrison may
 * spawn at all.
 *
 * <h2>Why this exists</h2>
 * The method is documented as "Pure spawn policy; unit-testable with no world" and had no test. It
 * is also the site of a real defect fixed on 2026-10-06: spec S3 stated two distance rules that
 * cannot both hold — "distance² &gt; 250000" (≥500 blocks) and "within 64 blocks and at least 24" —
 * and since 24..64 blocks is always ≤ 4096 &lt; 250000, {@link GarrisonController#allowed} returned
 * false for every legal input, so garrisons never spawned. The unified 24..64 band is what this test
 * protects: a regression back to either the 500-block gate or the old conjunction would make the
 * whole garrison feature silently dead, which no other test would notice.
 *
 * <p>These are plain unit tests: {@link GameplayTypes.PolicyInput} is pure data and the policy takes
 * no world, so no server is needed.
 */
class GarrisonControllerPolicyTest {
    /** A PolicyInput that satisfies every condition, so each test can vary exactly one field. */
    private static GameplayTypes.PolicyInput permissive() {
        return new GameplayTypes.PolicyInput(false, true, true, distanceSquaredAt(32), 18_000L,
                DecayState.INTACT);
    }

    private static long distanceSquaredAt(double blocks) {
        return (long) (blocks * blocks);
    }

    @Test void aFullyPermissiveInputIsAllowed() {
        assertTrue(GarrisonController.allowed(permissive()),
                "the baseline must be allowed, otherwise every negative case below proves nothing");
    }

    @Test void theNightWindowIsRespected() {
        // Allowed between 13000 and 23000 exclusive.
        assertTrue(GarrisonController.allowed(withDayTime(13_000L)), "13000 is the inclusive start");
        assertTrue(GarrisonController.allowed(withDayTime(22_999L)), "22999 is the last allowed tick");
        assertFalse(GarrisonController.allowed(withDayTime(12_999L)), "before 13000 is day");
        assertFalse(GarrisonController.allowed(withDayTime(23_000L)), "23000 is the exclusive end");
        assertFalse(GarrisonController.allowed(withDayTime(0L)), "midnight-day is not a spawn window");
        assertFalse(GarrisonController.allowed(withDayTime(6_000L)), "daytime is not a spawn window");
    }

    @Test void theDayTimeWrapsRatherThanEscapingTheWindow() {
        // floorMod means a raw clock beyond 24000 wraps; without it, a long-running world could pass
        // any day time and spawn at noon.
        assertTrue(GarrisonController.allowed(withDayTime(24_000L + 18_000L)),
                "24000 + 18000 must wrap to the allowed 18000");
        assertFalse(GarrisonController.allowed(withDayTime(24_000L + 6_000L)),
                "24000 + 6000 must wrap to disallowed 6000");
        assertTrue(GarrisonController.allowed(withDayTime(-6_000L)),
                "a negative day time must wrap into the window, not escape it");
    }

    @Test void theDistanceBandIsTwentyFourToSixtyFourBlocks() {
        // The documented band, and specifically NOT the 500-block gate that once made this dead.
        assertFalse(GarrisonController.allowed(withDistance(23.9)), "closer than 24 blocks is too close");
        assertTrue(GarrisonController.allowed(withDistance(24.0)), "24 blocks is the inclusive minimum");
        assertTrue(GarrisonController.allowed(withDistance(64.0)), "64 blocks is the inclusive maximum");
        assertFalse(GarrisonController.allowed(withDistance(64.1)), "beyond 64 blocks the site is inactive");
        assertFalse(GarrisonController.allowed(withDistance(500.0)),
                "the removed 500-block gate must not be reintroduced; 500 blocks is well outside the band");
    }

    @Test void peacefulAndNonSpawningWorldsNeverSpawn() {
        assertFalse(GarrisonController.allowed(new GameplayTypes.PolicyInput(true, true, true,
                distanceSquaredAt(32), 18_000L, DecayState.INTACT)), "peaceful must veto");
        assertFalse(GarrisonController.allowed(new GameplayTypes.PolicyInput(false, false, true,
                distanceSquaredAt(32), 18_000L, DecayState.INTACT)), "mobSpawning=false must veto");
    }

    @Test void aSiteWithNoNearbyPlayerNeverSpawns() {
        assertFalse(GarrisonController.allowed(new GameplayTypes.PolicyInput(false, true, false,
                distanceSquaredAt(32), 18_000L, DecayState.INTACT)), "no nearby player must veto");
    }

    @Test void onlyIntactOrPartialWallsGarrison() {
        assertTrue(GarrisonController.allowed(withDecay(DecayState.INTACT)), "an intact wall garrisons");
        assertTrue(GarrisonController.allowed(withDecay(DecayState.PARTIAL)), "a partial wall garrisons");
        assertFalse(GarrisonController.allowed(withDecay(DecayState.RUIN)),
                "a ruined wall must not garrison");
        assertFalse(GarrisonController.allowed(withDecay(DecayState.BURIED)),
                "a buried wall must not garrison");
    }

    @Test void thePolicyRejectsMalformedInputRatherThanGuessing() {
        assertThrows(NullPointerException.class, () -> GarrisonController.allowed(null));
        assertThrows(IllegalArgumentException.class, () -> new GameplayTypes.PolicyInput(false, true, true,
                -1L, 18_000L, DecayState.INTACT));
        assertThrows(NullPointerException.class, () -> new GameplayTypes.PolicyInput(false, true, true,
                distanceSquaredAt(32), 18_000L, null));
    }

    private static GameplayTypes.PolicyInput withDayTime(long dayTime) {
        var base = permissive();
        return new GameplayTypes.PolicyInput(base.peaceful(), base.mobSpawning(), base.playerNear(),
                base.nearestPlayerDistanceSquared(), dayTime, base.decay());
    }

    private static GameplayTypes.PolicyInput withDistance(double blocks) {
        var base = permissive();
        return new GameplayTypes.PolicyInput(base.peaceful(), base.mobSpawning(), base.playerNear(),
                distanceSquaredAt(blocks), base.dayTime(), base.decay());
    }

    private static GameplayTypes.PolicyInput withDecay(DecayState decay) {
        var base = permissive();
        return new GameplayTypes.PolicyInput(base.peaceful(), base.mobSpawning(), base.playerNear(),
                base.nearestPlayerDistanceSquared(), base.dayTime(), decay);
    }
}
