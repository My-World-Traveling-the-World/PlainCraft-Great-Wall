package local.plaincraft.greatwall.compat.road;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import org.junit.jupiter.api.Test;

/**
 * Pins the gate freeze solver.
 *
 * <p>This solver is what lets several roads share one physical arch. Before it existed the adapter
 * threw "sharedProfile unavailable" for every adopted gate, so the bidirectional handshake could never
 * complete. The cases below pin the contract: the height must be reachable from every approach, an
 * impossible set must be reported as a conflict rather than guessed, and the result must not depend on
 * the order the approaches arrive in.
 *
 * <p>Note the structural consequence of the one-block-per-station rule: the station where a road first
 * meets the wall has no slack, so it pins the gate height whenever a solution exists. Later stations
 * widen the interval symmetrically and therefore cannot move it.
 */
class GateFreezeSolverTest {
    private static final int WALL_TOP = 230;
    private static final int WALL_BOTTOM = 100;
    /** Admissible window for the constants above: [max(100, 230-32), 230-7] = [198, 223]. */
    private static final int WINDOW_LOW = 198;
    private static final int WINDOW_HIGH = 223;

    private static RoadTypes.Approach road(String id) {
        return new RoadTypes.Approach(id, List.of(new Point3i(0, 64, 0), new Point3i(1, 64, 0)));
    }

    @Test void gateSitsAtTheTerrainWhereTheRoadMeetsTheWall() {
        var frozen = assertInstanceOf(GateFreezeSolver.Outcome.Frozen.class,
                GateFreezeSolver.solve(List.of(road("a")), List.of(200, 200, 200), WALL_TOP, WALL_BOTTOM));
        assertEquals(200, frozen.groundY());
        assertEquals(200, frozen.sharedProfile().getFirst(), "the profile starts at the gate ground");
    }

    @Test void terrainAboveTheWallWindowIsAConflictNotAClamp() {
        // 5000 is far above the parapet; clamping would cut a gate through the wall's crown, so this
        // must be reported as an impossible agreement instead.
        var conflict = assertInstanceOf(GateFreezeSolver.Outcome.Conflict.class,
                GateFreezeSolver.solve(List.of(road("a")), List.of(5000), WALL_TOP, WALL_BOTTOM));
        assertTrue(conflict.reason().contains("no shared gate height"), conflict.reason());
    }

    @Test void terrainBelowTheWallWindowIsAConflictNotAClamp() {
        var conflict = assertInstanceOf(GateFreezeSolver.Outcome.Conflict.class,
                GateFreezeSolver.solve(List.of(road("a")), List.of(50), WALL_TOP, WALL_BOTTOM));
        assertTrue(conflict.reason().contains("no shared gate height"), conflict.reason());
    }

    @Test void terrainThatClimbsFasterThanOneBlockPerStationCannotShare() {
        // Station 0 pins to 200; station 1 can only reach [201, 203] from 202, so no single height works.
        var conflict = assertInstanceOf(GateFreezeSolver.Outcome.Conflict.class,
                GateFreezeSolver.solve(List.of(road("a")), List.of(200, 202), WALL_TOP, WALL_BOTTOM));
        assertTrue(conflict.reason().contains("no shared gate height"), conflict.reason());
    }

    @Test void terrainThatClimbsExactlyOneBlockPerStationStillShares() {
        var frozen = assertInstanceOf(GateFreezeSolver.Outcome.Frozen.class,
                GateFreezeSolver.solve(List.of(road("a")), List.of(200, 201), WALL_TOP, WALL_BOTTOM));
        assertEquals(200, frozen.groundY());
    }

    @Test void noApproachesIsAConflictNotASilentDefault() {
        assertInstanceOf(GateFreezeSolver.Outcome.Conflict.class,
                GateFreezeSolver.solve(List.of(), List.of(200), WALL_TOP, WALL_BOTTOM));
    }

    @Test void noTerrainSamplesIsAConflictNotASilentDefault() {
        assertInstanceOf(GateFreezeSolver.Outcome.Conflict.class,
                GateFreezeSolver.solve(List.of(road("a")), List.of(), WALL_TOP, WALL_BOTTOM));
    }

    @Test void resultIsIndependentOfApproachOrder() {
        var forward = GateFreezeSolver.solve(List.of(road("a"), road("b")),
                List.of(200, 200, 200), WALL_TOP, WALL_BOTTOM);
        var reversed = GateFreezeSolver.solve(List.of(road("b"), road("a")),
                List.of(200, 200, 200), WALL_TOP, WALL_BOTTOM);
        assertEquals(forward, reversed,
                "the solver intersects every interval, so arrival order cannot matter");
    }

    @Test void profileNeverStepsMoreThanOneBlockPerStation() {
        var frozen = assertInstanceOf(GateFreezeSolver.Outcome.Frozen.class,
                GateFreezeSolver.solve(List.of(road("a")),
                        List.of(200, 200, 200, 200, 200, 200), WALL_TOP, WALL_BOTTOM));
        var profile = frozen.sharedProfile();
        assertEquals(6, profile.size(), "every supplied station appears in the profile");
        for (int index = 1; index < profile.size(); index++) {
            assertTrue(Math.abs(profile.get(index) - profile.get(index - 1)) <= 1,
                    "station " + index + " stepped more than one block");
        }
    }

    @Test void adoptedProfileMustStartAtGroundAndStepLegally() {
        GateFreezeSolver.requireAdoptable(100, List.of(100, 101, 100));
        assertThrows(WallFailure.class, () -> GateFreezeSolver.requireAdoptable(100, List.of()),
                "an empty profile is never adoptable");
        assertThrows(WallFailure.class, () -> GateFreezeSolver.requireAdoptable(100, List.of(101)),
                "the profile must start at the recorded ground height");
        assertThrows(WallFailure.class, () -> GateFreezeSolver.requireAdoptable(100, List.of(100, 105)),
                "a two-block step violates the one-per-station contract");
    }

    @Test void wallWindowIsReportedAndDetectedAsEmptyWhenInverted() {
        assertEquals(WINDOW_LOW, GateFreezeSolver.window(WALL_TOP, WALL_BOTTOM).orElseThrow()[0]);
        assertEquals(WINDOW_HIGH, GateFreezeSolver.window(WALL_TOP, WALL_BOTTOM).orElseThrow()[1]);
        assertTrue(GateFreezeSolver.window(10, 100).isEmpty(),
                "a wall too short to host a 25-block gate has no admissible window");
    }
}
