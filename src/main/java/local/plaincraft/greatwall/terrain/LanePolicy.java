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
    /**
     * Upper bound on how many lanes may build their native oracle at the same time.
     *
     * <h2>Why this is no longer a constant of four</h2>
     * Four was chosen against the fixed lane count the scheduler used to have. With lanes
     * now derived from the hardware, a constant of four meant a sixteen-core machine
     * initialised sixteen lanes in four batches: measured at roughly 232 seconds of the
     * startup prewarm, spent before any planning began.
     *
     * <p>It is set to the lane ceiling rather than to a hardware-derived number because the
     * peak it guards is not proportional to cores - each lane allocates one native
     * {@code RandomState} view, and the whole set is bounded by {@link #MAX_LANES} anyway.
     * There is no configuration in which initialising all of them at once is worse than
     * doing it in batches, so the bound only needs to exist, not to be tuned.
     */
    public static final int MAX_CONCURRENT_INITIALIZATIONS = MAX_LANES;

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
            startup = laneCeiling();
        }
        // Heap guard (mirrors RoadWeaver's StartupHardwareProfile memory budget): each lane owns a
        // native NoiseChunk plus a RandomState view, so a small JVM must not start as many lanes as
        // a big one, or a 4G heap on a 16-core box would OOM before any planning begins.
        startup = clamp(Math.min(startup, memoryBudgetLanes()));
        int gameplay = MIN_LANES;
        var policy = new LanePolicy(startup, gameplay);
        policy.state.put("lanes-from-property", override != null && !override.isBlank());
        policy.state.put("lanes-heap-capped", startup < clamp(Runtime.getRuntime().availableProcessors()));
        return policy;
    }

    /** Heap-scaled lane ceiling, mirroring RoadWeaver's native memory budget. */
    private static int memoryBudgetLanes() {
        long heapGiB = Math.max(1L,
                Math.max(512L, Runtime.getRuntime().maxMemory() / (1024L * 1024L)) / 1024L);
        return heapGiB <= 4L ? 4
                : heapGiB <= 8L ? 8
                : heapGiB <= 16L ? 12
                : MAX_LANES;
    }

    /**
     * Lanes this machine may create, before the {@code greatwall.lanes} override.
     *
     * <p>Exposed so a second scheduler cannot invent its own version of the same number: the
     * heavy-unit budget ({@link StageBudget}) must cover every lane this can create, or the extra lanes
     * exist only to wait for a permit. Same expression {@link #resolve()} uses with no override set.
     */
    public static int laneCeiling() {
        return clamp(Math.min(Runtime.getRuntime().availableProcessors(), memoryBudgetLanes()));
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
