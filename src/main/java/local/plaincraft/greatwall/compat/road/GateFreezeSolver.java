package local.plaincraft.greatwall.compat.road;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/**
 * Computes the shared height profile that a gate must present to every road meeting it.
 *
 * <h2>Why a solver is required</h2>
 * A gate is a single physical arch. Several roads can approach it, each at its own terrain height,
 * and all of them must be able to enter through the same opening. Freezing the gate therefore needs a
 * height that is simultaneously reachable from every approach; picking one road's height would strand
 * the others. This class derives that height, or proves that none exists.
 *
 * <h2>The contract (docs/S1-S5实施规格/S2-道路联动.md)</h2>
 * <ol>
 *   <li>For route {@code r} at station {@code j}, the road can reach a Y within
 *       {@code [terrainY_j - j, terrainY_j + j]} — a road may climb or descend one block per station.</li>
 *   <li>Intersect those intervals across every route and station.</li>
 *   <li>Intersect the result with the wall's own admissible window {@code [W-32, W-7]}.</li>
 *   <li>No intersection means the roads cannot share one opening: a conflict, never a guess.</li>
 *   <li>Otherwise choose the lowest admissible Y; ties resolve downward.</li>
 *   <li>{@code sharedProfile[0] = groundY}, then each later station clamps to
 *       {@code clamp(min terrainY over routes, previous - 1, previous + 1)}.</li>
 * </ol>
 *
 * <p>Pure and deterministic: no world access, no clock, no randomness. The order in which approaches
 * arrive cannot change the result, because every interval is intersected into one set before a
 * decision is made.
 */
public final class GateFreezeSolver {
    /** A road may change height by at most this much per station. */
    public static final int MAX_STEP_PER_STATION = 1;

    private GateFreezeSolver() {}

    /** The outcome: either a usable profile, or an explicit reason it could not be frozen. */
    public sealed interface Outcome {
        record Frozen(int groundY, List<Integer> sharedProfile) implements Outcome {}
        record Conflict(String reason) implements Outcome {}
    }

    /**
     * Solves for a gate height from the approaches that must share it.
     *
     * @param approaches   every road meeting this gate; must be non-empty
     * @param terrainY     the terrain height sampled at the gate, per approach station
     * @param wallTopY     the wall's top Y, defining the admissible window
     * @param wallBottomY  the wall's base Y
     */
    public static Outcome solve(List<RoadTypes.Approach> approaches, List<Integer> terrainY,
                                int wallTopY, int wallBottomY) {
        Objects.requireNonNull(approaches, "approaches");
        Objects.requireNonNull(terrainY, "terrainY");
        if (approaches.isEmpty()) {
            return new Outcome.Conflict("no road approaches this gate, so no shared height exists");
        }
        if (terrainY.isEmpty()) {
            return new Outcome.Conflict("no terrain samples were supplied for this gate");
        }

        // 1-2. Intersect the reachable interval of every route and station. A road at station j can
        //      reach [terrainY - j, terrainY + j], so the lower bound falls and the upper rises with j.
        long low = Long.MIN_VALUE;
        long high = Long.MAX_VALUE;
        int minTerrain = Integer.MAX_VALUE;
        for (int station = 0; station < terrainY.size(); station++) {
            int terrain = terrainY.get(station);
            minTerrain = Math.min(minTerrain, terrain);
            low = Math.max(low, (long) terrain - station * MAX_STEP_PER_STATION);
            high = Math.min(high, (long) terrain + station * MAX_STEP_PER_STATION);
        }

        // 3. Intersect with the wall's admissible window. The window is expressed relative to the wall
        //    top so a gate can never be cut above the parapet or below the foundation.
        long windowLow = (long) wallTopY - 32;
        long windowHigh = (long) wallTopY - 7;
        if (wallBottomY > windowLow) windowLow = wallBottomY;
        low = Math.max(low, windowLow);
        high = Math.min(high, windowHigh);

        // 4. An empty intersection is a real, reported outcome: the roads cannot share one opening and
        //    the gate must stay a protected gap rather than silently taking somebody's height.
        if (low > high) {
            return new Outcome.Conflict("no shared gate height: reachable [" + low + ", " + high
                    + "] is empty within wall window [" + windowLow + ", " + windowHigh + "]");
        }

        // 5. Lowest admissible height, which keeps the arch as low as possible for every approach.
        int groundY = Math.toIntExact(low);

        // 6. Build the profile. Station 0 is the gate ground; each later station is
        //    clamp(min terrainY over routes, previous - 1, previous + 1), i.e. it would like to sit at
        //    the lowest terrain the roads present but may only move one block per station.
        var profile = new ArrayList<Integer>(terrainY.size());
        profile.add(groundY);
        int previous = groundY;
        for (int station = 1; station < terrainY.size(); station++) {
            int floor = previous - MAX_STEP_PER_STATION;
            int ceiling = previous + MAX_STEP_PER_STATION;
            // Preferred value is the lowest terrain across routes; clamping it into [floor, ceiling]
            // yields the closest legal height, and because floor <= previous <= ceiling the result can
            // never invert the ordering.
            int preferred = Math.max(minTerrain, floor);
            int next = Math.min(preferred, ceiling);
            profile.add(next);
            previous = next;
        }
        return new Outcome.Frozen(groundY, List.copyOf(profile));
    }

    /**
     * Validates a profile that arrived from outside (an adopted frozen record) against the same rules.
     *
     * <p>An adopted record is trusted input, but "trusted" must still mean "checked": the profile is
     * rejected unless it starts at the recorded ground and never moves more than one block per station.
     */
    public static void requireAdoptable(int groundY, List<Integer> sharedProfile) {
        Objects.requireNonNull(sharedProfile, "sharedProfile");
        if (sharedProfile.isEmpty()) {
            throw new WallFailure(FailureCode.INVALID_RESOURCE, "shared profile must not be empty");
        }
        if (sharedProfile.getFirst() != groundY) {
            throw new WallFailure(FailureCode.INVALID_RESOURCE,
                    "shared profile must start at the gate ground height " + groundY
                            + ", found " + sharedProfile.getFirst());
        }
        int previous = sharedProfile.getFirst();
        for (int index = 1; index < sharedProfile.size(); index++) {
            int value = sharedProfile.get(index);
            if (Math.abs(value - previous) > MAX_STEP_PER_STATION) {
                throw new WallFailure(FailureCode.INVALID_RESOURCE,
                        "shared profile steps more than " + MAX_STEP_PER_STATION
                                + " block at station " + index);
            }
            previous = value;
        }
    }

    /** The admissible gate window for a wall, as a half-open-friendly inclusive pair. */
    public static Optional<int[]> window(int wallTopY, int wallBottomY) {
        int low = Math.max(wallBottomY, wallTopY - 32);
        int high = wallTopY - 7;
        if (low > high) return Optional.empty();
        return Optional.of(new int[] {low, high});
    }
}
