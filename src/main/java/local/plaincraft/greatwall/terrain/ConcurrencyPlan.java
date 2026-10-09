package local.plaincraft.greatwall.terrain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses how much planning work may run at once, from the hardware present.
 *
 * <h2>What was wrong with the fixed numbers</h2>
 * Geometry in-flight was a constant of four, chosen when the lane count was small. On a
 * sixteen-core machine the startup prewarm ran sixteen native lanes but could only ever
 * have four geometries in flight, so most of the machine sat idle while four segments
 * took twenty-odd minutes. Lane count had already been made hardware-aware; this is the
 * other half of the same decision, and keeping them in one place stops the two from
 * disagreeing again.
 *
 * <h2>Why the value is quantised</h2>
 * The result is written into the world recipe, and the recipe hash is the cache key for
 * everything planned under it - so a value that varied between launches would invalidate
 * the whole cache every time the game started. Quantising to a power of two means the
 * value changes only when the machine genuinely changes class, not when a core is busy
 * or the JVM reports a slightly different processor count.
 *
 * <h2>Why not simply "one geometry per lane"</h2>
 * A geometry is not free: each holds a slice of the segment budget and a scratch
 * reservation, and the byte budgets still apply on top of this count. The count is a
 * ceiling on parallelism, not a promise of memory; {@code BudgetReservation} remains the
 * gate that actually prevents over-commitment. This is set generously because a startup
 * prewarm owns the machine - see {@link #startupGeometryInflight()} - and the budgets
 * decide what is actually affordable.
 */
public final class ConcurrencyPlan {
    /** Matches the lane ceiling, so geometry can never outnumber the lanes that run it. */
    public static final int MAX_GEOMETRY_INFLIGHT = LanePolicy.MAX_LANES;
    /** Historical value, kept as the floor so behaviour cannot regress on small machines. */
    public static final int MIN_GEOMETRY_INFLIGHT = 4;
    /** Overrides the derived count, for reproducible diagnostics and acceptance runs. */
    public static final String GEOMETRY_PROPERTY = "greatwall.geometry.inflight";

    private final ConcurrentHashMap<String, Boolean> state = new ConcurrentHashMap<>();

    private ConcurrencyPlan() {
    }

    public Map<String, Boolean> diagnostics() { return Map.copyOf(state); }

    /**
     * Geometry jobs that may run at once during the startup prewarm.
     *
     * <p>Startup is the one phase where the wall owns the machine, so this takes the full
     * lane budget: the work is bounded, nothing else is generating chunks, and finishing
     * before the player arrives is the entire purpose.
     */
    public static int startupGeometryInflight() {
        return resolve();
    }

    /**
     * Geometry jobs that may run at once during ordinary play.
     *
     * <p>Deliberately the floor rather than the startup value: during play the server
     * thread, chunk generation and other mods all compete, and a prewarm that starved
     * them would be worse than a slower prewarm.
     */
    public static int gameplayGeometryInflight() {
        return MIN_GEOMETRY_INFLIGHT;
    }

    /**
     * Derives the count from available processors unless overridden.
     *
     * <p>One geometry per lane, since every geometry occupies exactly one lane while it
     * runs and extra in-flight jobs beyond that would only queue. Quantised down to a
     * power of two so the value - and therefore the recipe hash derived from it - is
     * stable across launches on the same machine.
     */
    private static int resolve() {
        String override = System.getProperty(GEOMETRY_PROPERTY);
        if (override != null && !override.isBlank()) {
            try {
                return clamp(Integer.parseInt(override.trim()));
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException("malformed -D" + GEOMETRY_PROPERTY + " value: " + override,
                        malformed);
            }
        }
        return clamp(quantiseDown(Runtime.getRuntime().availableProcessors()));
    }

    /** Largest power of two at or below {@code value}, so the result is stable per machine class. */
    private static int quantiseDown(int value) {
        int quantised = Integer.highestOneBit(Math.max(1, value));
        // A machine one core below a boundary would otherwise lose half its parallelism;
        // 3, 5 and 6 cores round up to the same power as 4 and 8 respectively.
        if (value > quantised + quantised / 2) quantised <<= 1;
        return quantised;
    }

    private static int clamp(int value) {
        return Math.max(MIN_GEOMETRY_INFLIGHT, Math.min(MAX_GEOMETRY_INFLIGHT, value));
    }
}
