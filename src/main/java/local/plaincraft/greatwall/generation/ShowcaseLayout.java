package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.GreatWallMod;

/**
 * Startup-only footprint registry for the superflat showcase.
 *
 * <p>The showcase places many independently stamped pieces; a layout change can silently bury one
 * piece inside another (the 784x421 kitset board did exactly that). Registering each footprint before
 * stamping turns that into a named startup warning instead of a mystery hole in the world.
 */
public final class ShowcaseLayout {
    public record Rect(String name, int minX, int minZ, int maxX, int maxZ) {
        public boolean intersects(Rect other) {
            return minX <= other.maxX && other.minX <= maxX
                    && minZ <= other.maxZ && other.minZ <= maxZ;
        }
        public long area() {
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }
    }

    private static final List<Rect> RECTS = new ArrayList<>();

    private ShowcaseLayout() {
    }

    public static void reset() {
        RECTS.clear();
    }

    public static void add(String name, int minX, int minZ, int maxX, int maxZ) {
        Rect candidate = new Rect(name, minX, minZ, maxX, maxZ);
        for (Rect existing : RECTS) {
            if (candidate.intersects(existing)) {
                GreatWallMod.LOGGER.warn(
                        "[GreatWall:wonder] SHOWCASE_OVERLAP {} {} and {} {}",
                        name, candidate, existing.name(), existing);
            }
        }
        RECTS.add(candidate);
    }

    public static List<Rect> rectangles() {
        return List.copyOf(RECTS);
    }
}
