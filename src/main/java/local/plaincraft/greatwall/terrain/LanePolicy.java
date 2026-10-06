package local.plaincraft.greatwall.terrain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses how many native terrain oracle lanes may run at once.
 *
 * <h2>Why not just "use every core"</h2>
 * A lane is not free: each one owns a native {@code NoiseChunk} and its own {@code RandomState}
 * view, so memory and initialization transients scale with the count. More lanes also do not
 * multiply throughput linearly, because root search stays serial on lane 0 and the prefix reduction,
 * plan admission and cache write paths are shared. The honest shape is therefore a bounded
 * compromise, not "N cores equals N times faster".
 *
 * <h2>Startup versus gameplay</h2>
 * Startup may take the full budget: nothing else competes, and finishing before the player enters
 * is the entire point. Gameplay drops back to a small owner set so world generation cannot starve
 * the server thread.
 */
public final class LanePolicy {
    /** Hard ceiling regardless of hardware; keeps the native footprint predictable. */
    public static final int MAX_LANES = 16;
    /** Never fewer than the historical pair, so behaviour cannot regress below today's baseline. */
    public static final int MIN_LANES = 2;
    /** Concurrent per-lane initialization is capped so N RandomState instances never peak together. */
    public static final int MAX_CONCURRENT_INITIALIZATIONS = 4;

    private static final String PROPERTY = "greatwall.lanes";
    private final ConcurrentHashMap<String, Boolean> state = new ConcurrentHashMap<>();
    private final int startupLanes;
    private final int gameplayLanes;

    private LanePolicy(int startupLanes, int gameplayLanes) {
        this.startupLanes = startupLanes;
        this.gameplayLanes = gameplayLanes;
    }

    /** Highest lane index that may exist, so {@code lanes(0..maxIndex)} is the full set. */
    public int startupLaneCount() { return startupLanes; }
    /** Lanes admitted once ordinary gameplay has begun. */
    public int gameplayLaneCount() { return gameplayLanes; }
    public int maxIndex() { return startupLanes - 1; }
    public Map<String, Boolean> diagnostics() { return Map.copyOf(state); }

    /**
     * Derives the policy from the available processors, unless overridden by the
     * {@code greatwall.lanes} system property. The override exists for reproducible diagnostics and
     * for pinning a known-good count during acceptance runs; it is not a tuning knob for players.
     */
    public static LanePolicy resolve() {
        String override = System.getProperty(PROPERTY);
        int startup;
        if (override != null && !override.isBlank()) {
            startup = clamp(parse(override));
        } else {
            startup = clamp(Runtime.getRuntime().availableProcessors());
        }
        int gameplay = MIN_LANES;
        var policy = new LanePolicy(startup, gameplay);
        policy.state.put("lanes-from-property", override != null && !override.isBlank());
        return policy;
    }

    /** Explicit policy, used by tests and by callers that already know their budget. */
    public static LanePolicy of(int startupLanes, int gameplayLanes) {
        if (startupLanes < MIN_LANES) throw new IllegalArgumentException("startup lanes below minimum");
        if (gameplayLanes < MIN_LANES) throw new IllegalArgumentException("gameplay lanes below minimum");
        if (gameplayLanes > startupLanes) throw new IllegalArgumentException("gameplay lanes exceed startup lanes");
        return new LanePolicy(Math.min(startupLanes, MAX_LANES), Math.min(gameplayLanes, MAX_LANES));
    }

    private static int clamp(int value) {
        return Math.max(MIN_LANES, Math.min(MAX_LANES, value));
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("malformed -D" + PROPERTY + " value: " + value, malformed);
        }
    }
}
