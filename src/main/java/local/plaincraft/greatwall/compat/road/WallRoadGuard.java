package local.plaincraft.greatwall.compat.road;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.GreatWallMod;

/**
 * Read-only guard that answers one question for other mods: "may a road be laid down at this column?"
 *
 * <p>The wall marks every column it builds; the road mod calls {@link #blocks(int, int, int)} reflectively
 * before writing a block, so roads stop at the wall foot instead of paving it over. Nothing here writes to
 * the world, and a missing mark list simply returns {@code false} (behaviour identical to before).
 *
 * <p>Storage is a 256-bit column mask per chunk (four longs), which keeps the query O(1) and the memory
 * tiny even for a 8000-block wall.
 */
public final class WallRoadGuard {
    /** Blocks kept clear outside the marked columns so a road ends with a clean cut. */
    private static final int MARGIN = 2;
    /** Vertical pad above/below is irrelevant for column tests but keeps the API explicit. */
    private static final int VERTICAL_TOLERANCE = 4;

    private static final Map<Long, long[]> MASKS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);
    private static final ThreadLocal<Cache> MARK = ThreadLocal.withInitial(Cache::new);
    private static volatile long lastMarkMillis;
    private static volatile int minCX = Integer.MAX_VALUE, maxCX = Integer.MIN_VALUE;
    private static volatile int minCZ = Integer.MAX_VALUE, maxCZ = Integer.MIN_VALUE;

    private static final class Cache {
        long key = Long.MIN_VALUE;
        long[] mask;
    }

    private WallRoadGuard() {
    }

    /** Marks one column as wall-owned. Called by the wall writers as they place blocks. */
    public static void markColumn(int x, int z) {
        long key = chunkKey(x, z);
        int bit = ((z & 15) << 4) | (x & 15);
        Cache markCache = MARK.get();
        long[] mask;
        if (markCache.key == key && markCache.mask != null) {
            mask = markCache.mask;
        } else {
            mask = MASKS.computeIfAbsent(key, unused -> new long[4]);
            markCache.key = key;
            markCache.mask = mask;
        }
        synchronized (mask) {
            mask[bit >> 6] |= 1L << (bit & 63);
        }
        lastMarkMillis = System.currentTimeMillis();
        int cx = x >> 4;
        int cz = z >> 4;
        if (cx < minCX) minCX = cx;
        if (cx > maxCX) maxCX = cx;
        if (cz < minCZ) minCZ = cz;
        if (cz > maxCZ) maxCZ = cz;
    }

    /** Marks every column of an inclusive rectangle. */
    public static void markBox(int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                markColumn(x, z);
            }
        }
    }

    /** Forgets everything; called when a session ends or the world unloads. */
    public static void clear() {
        MASKS.clear();
        minCX = Integer.MAX_VALUE; maxCX = Integer.MIN_VALUE;
        minCZ = Integer.MAX_VALUE; maxCZ = Integer.MIN_VALUE;
        GreatWallMod.LOGGER.info("[GreatWall:road] ROAD_GUARD_CLEARED");
    }

    /** Number of chunks that carry at least one wall column (diagnostics). */
    public static int markedChunks() {
        return MASKS.size();
    }

    /**
     * True when a road block must not be placed here: the column is wall-owned or within {@link #MARGIN}
     * of one. Called reflectively by the road mod - keep this signature stable.
     */
    public static boolean blocks(int x, int y, int z) {
        if (MASKS.isEmpty()) return false;
        for (int dx = -MARGIN; dx <= MARGIN; dx++) {
            for (int dz = -MARGIN; dz <= MARGIN; dz++) {
                if (isMarked(x + dx, z + dz)) return true;
            }
        }
        return false;
    }

    /** How long since the last column was marked (milliseconds); used to detect "stamping settled". */
    public static long millisSinceLastMark() {
        long last = lastMarkMillis;
        return last == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - last;
    }

    /** Any column marked at all? */
    public static boolean hasMarks() {
        return !MASKS.isEmpty();
    }

    /** Same as {@link #blocks(int, int, int)} but with a caller-chosen radius, for multi-block props. */
    public static boolean blocksWithin(int x, int y, int z, int radius) {
        if (MASKS.isEmpty()) return false;
        int reach = Math.max(MARGIN, radius);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (isMarked(x + dx, z + dz)) return true;
            }
        }
        return false;
    }

    /** Cheap variant without the margin, for callers that add their own padding. */
    public static boolean blocksExact(int x, int y, int z) {
        return isMarked(x, z);
    }

    /** True when this column is wall-owned. */
    public static boolean isMarked(int x, int z) {
        long key = chunkKey(x, z);
        Cache cache = CACHE.get();
        long[] mask;
        if (cache.key == key) {
            mask = cache.mask;
        } else {
            mask = MASKS.get(key);
            cache.key = key;
            cache.mask = mask;
        }
        if (mask == null) return false;
        int bit = ((z & 15) << 4) | (x & 15);
        synchronized (mask) {
            return (mask[bit >> 6] & (1L << (bit & 63))) != 0L;
        }
    }

    /**
     * Cheap bounding-box test for very hot callers (density functions run per block per chunk).
     * Returns true when the column is anywhere near the wall; callers that need precision use
     * {@link #blocks(int, int, int)} instead.
     */
    public static boolean coarseMayAffect(int x, int z) {
        if (MASKS.isEmpty()) return false;
        int cx = x >> 4;
        int cz = z >> 4;
        return cx >= minCX - 1 && cx <= maxCX + 1 && cz >= minCZ - 1 && cz <= maxCZ + 1;
    }

    /** Diagnostic snapshot: how many columns are marked in this chunk. */
    public static int markedColumnsIn(int chunkX, int chunkZ) {
        long[] mask = MASKS.get(pack(chunkX, chunkZ));
        if (mask == null) return 0;
        int total = 0;
        synchronized (mask) {
            for (long word : mask) {
                total += Long.bitCount(word);
            }
        }
        return total;
    }

    private static long chunkKey(int x, int z) {
        return pack(x >> 4, z >> 4);
    }

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    /** Vertical tolerance accessor kept so the road side can reason about it without knowing internals. */
    public static int verticalTolerance() {
        return VERTICAL_TOLERANCE;
    }

    /** Unused by the road side; exposed for a future coarse mode. */
    public static List<Long> chunkKeys() {
        return List.copyOf(MASKS.keySet());
    }
}