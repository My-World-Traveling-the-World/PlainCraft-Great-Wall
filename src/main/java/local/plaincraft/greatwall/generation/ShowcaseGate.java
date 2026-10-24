package local.plaincraft.greatwall.generation;

import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.compat.road.WallRoadGuard;

/**
 * Holds the road mod back until the showcase stamps have actually finished.
 *
 * <p>The template world stamps pieces from the tick loop while the road mod is already generating, so a
 * road chunk written in those first seconds sees no wall columns and paves straight through them. The gate
 * waits until the marked column set has stopped growing (or a hard deadline passes), which is exactly when
 * stamping is over - no per-piece bookkeeping needed.
 */
public final class ShowcaseGate {
    /** Stamping counts as settled once no new column has been marked for this long. */
    private static final long SETTLE_MILLIS = 2_500L;
    /** Hard cap so a broken piece can never block the road's initial generation forever. */
    private static final long DEADLINE_MILLIS = 300_000L;

    private static volatile boolean active;
    private static volatile long armedAt;
    private static volatile boolean opened;

    private ShowcaseGate() {
    }

    /** Called when the showcase pieces are registered. */
    public static void arm() {
        active = true;
        opened = false;
        armedAt = System.currentTimeMillis();
    }

    /** True while the road mod must keep waiting. */
    public static boolean pending() {
        if (!active || opened) return false;
        long elapsed = System.currentTimeMillis() - armedAt;
        if (elapsed > DEADLINE_MILLIS) {
            opened = true;
            GreatWallMod.LOGGER.warn("[GreatWall:road] SHOWCASE_GATE_OPEN forced after {}ms", elapsed);
            return false;
        }
        if (!WallRoadGuard.hasMarks()) return true;
        if (WallRoadGuard.millisSinceLastMark() < SETTLE_MILLIS) return true;
        opened = true;
        GreatWallMod.LOGGER.info("[GreatWall:road] SHOWCASE_GATE_OPEN after {}ms (columns settled)", elapsed);
        return false;
    }

    /** Diagnostics. */
    public static boolean isActive() {
        return active;
    }
}
