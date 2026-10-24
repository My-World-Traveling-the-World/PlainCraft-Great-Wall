package local.plaincraft.greatwall.compat.road;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.GreatWallMod;

/**
 * Read-only guard that answers one question for other mods: "may a road be laid down at this column?"
 *
 * <p>Storage is a 256-bit column mask per chunk (four longs). A clear is a world-generation boundary:
 * no lookup may retain a null answer or a mask belonging to the preceding map.
 */
public final class WallRoadGuard {
    /** Blocks kept clear outside the marked columns so a road ends with a clean cut. */
    private static final int MARGIN = 2;
    /** Vertical pad above/below is irrelevant for column tests but keeps the API explicit. */
    private static final int VERTICAL_TOLERANCE = 4;

    private static final Map<Long, long[]> MASKS = new ConcurrentHashMap<>();
    /** Footprints published from the templates BEFORE anything is stamped: {minX, minZ, maxX, maxZ}. */
    private static final java.util.List<int[]> RECTS = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Only a write-side lookup shortcut. It is valid only while it is still the array in MASKS. */
    private static final ThreadLocal<Cache> MARK = ThreadLocal.withInitial(Cache::new);
    private static volatile long lastMarkMillis;

    private static final class Cache {
        long key = Long.MIN_VALUE;
        long[] mask;
    }

    private WallRoadGuard() {
    }

    /** Publishes a footprint before it is stamped, so roads avoid it from the first tick. */
    public static void publishRect(int minX, int minZ, int maxX, int maxZ) {
        RECTS.add(new int[]{minX, minZ, maxX, maxZ});
        lastMarkMillis = System.currentTimeMillis();
        GreatWallMod.LOGGER.info("[GreatWall:road] ROAD_GUARD_RECT {} {} {} {} (total {})",
                minX, minZ, maxX, maxZ, RECTS.size());
    }

    /** Clears published footprints (world unload). Marks are cleared by {@link #clear()}. */
    public static void clearRects() {
        RECTS.clear();
    }

    private static boolean inRect(int x, int z) {
        return inRect(x, z, MARGIN);
    }

    private static boolean inRect(int x, int z, int pad) {
        for (int[] r : RECTS) {
            if (x >= r[0] - pad && x <= r[2] + pad && z >= r[1] - pad && z <= r[3] + pad) return true;
        }
        return false;
    }

    /** Marks one column as wall-owned. Called by the wall writers as they place blocks. */
    public static void markColumn(int x, int z) {
        long key = chunkKey(x, z);
        Cache markCache = MARK.get();
        long[] mask = markCache.key == key ? markCache.mask : null;
        // A cached null after a miss, or a detached array after clear(), must never suppress this write.
        if (mask == null || mask != MASKS.get(key)) {
            mask = MASKS.computeIfAbsent(key, unused -> new long[4]);
            markCache.key = key;
            markCache.mask = mask;
        }
        int bit = ((z & 15) << 4) | (x & 15);
        synchronized (mask) {
            mask[bit >> 6] |= 1L << (bit & 63);
        }
        lastMarkMillis = System.currentTimeMillis();
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
        RECTS.clear();
        lastMarkMillis = 0L;
        GreatWallMod.LOGGER.info("[GreatWall:road] ROAD_GUARD_CLEARED");
    }

    /** Number of chunks that carry at least one wall column (diagnostics). */
    public static int markedChunks() {
        return MASKS.size();
    }

    /** True in a marked column or within {@link #MARGIN} of one. Keep this reflective signature stable. */
    public static boolean blocks(int x, int y, int z) {
        return inRect(x, z) || markedNear(x, z, MARGIN);
    }

    /** How long since the last column was marked (milliseconds); used to detect "stamping settled". */
    public static long millisSinceLastMark() {
        long last = lastMarkMillis;
        return last == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - last;
    }

    /** Any column marked at all? */
    public static boolean hasMarks() {
        return !MASKS.isEmpty() || !RECTS.isEmpty();
    }

    /** Diagnostics: how many footprints were published. */
    public static int rectCount() {
        return RECTS.size();
    }

    /** Same as {@link #blocks(int, int, int)} but with a caller-chosen radius, for multi-block props. */
    public static boolean blocksWithin(int x, int y, int z, int radius) {
        int reach = Math.max(MARGIN, radius);
        return inRect(x, z, reach) || markedNear(x, z, reach);
    }

    /** Cheap variant without the margin, for callers that add their own padding. */
    public static boolean blocksExact(int x, int y, int z) {
        return isMarked(x, z);
    }

    /** True when this column is wall-owned. Never cache a miss: a writer may mark it immediately after. */
    public static boolean isMarked(int x, int z) {
        long[] mask = MASKS.get(chunkKey(x, z));
        if (mask == null) return false;
        int bit = ((z & 15) << 4) | (x & 15);
        synchronized (mask) {
            return (mask[bit >> 6] & (1L << (bit & 63))) != 0L;
        }
    }

    /**
     * Chunk-mask-backed coarse filter for the road density hot path. Each query is local to the marked
     * chunks near this column; there is deliberately no map-wide bounding box. The two-block margin is
     * included, making this a conservative superset of the exact guard rather than only the wall itself.
     * No result is cached, so a first false answer cannot hide a later mark in the same world generation.
     */
    public static boolean coarseMayAffect(int x, int z) {
        if (inRect(x, z)) return true;
        if (MASKS.isEmpty()) return false;
        int cx = x >> 4, cz = z >> 4;
        // Any mark in this chunk makes the whole chunk coarse-true. That is deliberately
        // over-inclusive but far cheaper than 25 exact probes per density sample.
        if (MASKS.containsKey(pack(cx, cz))) return true;
        int lx = x & 15, lz = z & 15;
        boolean west = lx < MARGIN, east = lx >= 16 - MARGIN;
        boolean north = lz < MARGIN, south = lz >= 16 - MARGIN;
        if (!west && !east && !north && !south) return false;
        int minDx = west ? -1 : 0, maxDx = east ? 1 : 0;
        int minDz = north ? -1 : 0, maxDz = south ? 1 : 0;
        for (int dx = minDx; dx <= maxDx; dx++) {
            for (int dz = minDz; dz <= maxDz; dz++) {
                if ((dx != 0 || dz != 0) && MASKS.containsKey(pack(cx + dx, cz + dz))) return true;
            }
        }
        return false;
    }

    private static boolean markedNear(int x, int z, int reach) {
        if (MASKS.isEmpty()) return false;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (isMarked(x + dx, z + dz)) return true;
            }
        }
        return false;
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
