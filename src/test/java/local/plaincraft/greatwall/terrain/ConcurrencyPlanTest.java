package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The concurrency plan decides how much of the machine a startup prewarm may use.
 *
 * <p>Two properties matter and are both pinned here. The value must respect the lane
 * ceiling, because a geometry occupies exactly one lane while it runs and extra in-flight
 * jobs would only queue. And it must be stable for a given machine, because it is written
 * into the world recipe whose hash keys the entire plan cache - a value that wobbled
 * between launches would discard that cache every time the game started.
 */
class ConcurrencyPlanTest {
    @Test
    void startupTakesTheWholeLaneBudget() {
        int startup = ConcurrencyPlan.startupGeometryInflight();
        assertTrue(startup >= ConcurrencyPlan.MIN_GEOMETRY_INFLIGHT,
                "a startup prewarm must not run with fewer geometries than the historical value");
        assertTrue(startup <= ConcurrencyPlan.MAX_GEOMETRY_INFLIGHT,
                "geometry in flight must not exceed the lanes available to run it");
    }

    @Test
    void gameplayStaysAtTheFloor() {
        // During play the server thread and world generation compete, so this must not
        // follow the hardware the way startup does.
        assertEquals(ConcurrencyPlan.MIN_GEOMETRY_INFLIGHT, ConcurrencyPlan.gameplayGeometryInflight());
    }

    @Test
    void theValueIsStableAcrossCallsOnTheSameMachine() {
        // The recipe hash depends on this number, so two calls in one process - and by
        // extension two launches on one machine - must agree.
        assertEquals(ConcurrencyPlan.startupGeometryInflight(), ConcurrencyPlan.startupGeometryInflight());
    }

    @Test
    void theValueIsAPowerOfTwoSoItDoesNotWobbleWithCoreCount() {
        int startup = ConcurrencyPlan.startupGeometryInflight();
        assertEquals(Integer.highestOneBit(startup), startup,
                "a quantised value keeps the recipe hash stable across machine-class boundaries");
    }

    @Test
    void anOverrideIsHonouredWithinBounds() {
        String previous = System.getProperty(ConcurrencyPlan.GEOMETRY_PROPERTY);
        try {
            // Below the floor clamps up, above the ceiling clamps down, so an override can
            // tune the value but cannot make it unsafe.
            System.setProperty(ConcurrencyPlan.GEOMETRY_PROPERTY, "1");
            assertEquals(ConcurrencyPlan.MIN_GEOMETRY_INFLIGHT, ConcurrencyPlan.startupGeometryInflight());
            System.setProperty(ConcurrencyPlan.GEOMETRY_PROPERTY, "9999");
            assertEquals(ConcurrencyPlan.MAX_GEOMETRY_INFLIGHT, ConcurrencyPlan.startupGeometryInflight());
            System.setProperty(ConcurrencyPlan.GEOMETRY_PROPERTY, "8");
            assertEquals(8, ConcurrencyPlan.startupGeometryInflight());
        } finally {
            if (previous == null) System.clearProperty(ConcurrencyPlan.GEOMETRY_PROPERTY);
            else System.setProperty(ConcurrencyPlan.GEOMETRY_PROPERTY, previous);
        }
    }

    @Test
    void aMalformedOverrideFailsLoudlyRatherThanSilentlyDefaulting() {
        String previous = System.getProperty(ConcurrencyPlan.GEOMETRY_PROPERTY);
        try {
            System.setProperty(ConcurrencyPlan.GEOMETRY_PROPERTY, "eight");
            assertThrows(IllegalArgumentException.class, ConcurrencyPlan::startupGeometryInflight,
                    "a typo in a diagnostic property must not be mistaken for the default");
        } finally {
            if (previous == null) System.clearProperty(ConcurrencyPlan.GEOMETRY_PROPERTY);
            else System.setProperty(ConcurrencyPlan.GEOMETRY_PROPERTY, previous);
        }
    }

    @Test
    void laneInitializationMayUseEveryLane() {
        // A lane retains its oracle for the life of the session, so refusing to build them
        // together saved nothing and cost the batches' serial time instead.
        assertEquals(LanePolicy.MAX_LANES, LanePolicy.MAX_CONCURRENT_INITIALIZATIONS);
    }
}
