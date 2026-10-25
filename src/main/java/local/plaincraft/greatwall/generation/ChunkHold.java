package local.plaincraft.greatwall.generation;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;

/**
 * Temporarily force-loads the chunks a stamp writes into.
 *
 * <p>The ownership is per {@link ServerLevel}: the same chunk coordinate in a second save is a
 * different chunk, and a set keyed by coordinates alone let a stale ticket suppress the new world's
 * force-load or release a chunk the new world still needed.
 */
public final class ChunkHold {
    /** Per-chunk reference counts: overlapping pieces may hold the same chunk at once. */
    private static final Map<ServerLevel, Map<Long, Integer>> HELD = new HashMap<>();

    private ChunkHold() {
    }

    /** Forces the chunks covering the given block box and returns a ticket for {@link #release}. */
    public static long[] hold(ServerLevel level, int x0, int z0, int x1, int z1) {
        if (level == null) return null;
        int cx0 = x0 >> 4;
        int cz0 = z0 >> 4;
        int cx1 = x1 >> 4;
        int cz1 = z1 >> 4;
        Map<Long, Integer> held = HELD.computeIfAbsent(level, ignored -> new HashMap<>());
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                long key = key(cx, cz);
                int count = held.getOrDefault(key, 0);
                if (count == 0) level.setChunkForced(cx, cz, true);
                held.put(key, count + 1);
            }
        }
        return new long[] {cx0, cz0, cx1, cz1};
    }

    /** Releases the chunks of one ticket. */
    public static void release(ServerLevel level, long[] ticket) {
        if (level == null || ticket == null || ticket.length != 4) return;
        Map<Long, Integer> held = HELD.get(level);
        if (held == null) return;
        for (int cx = (int) ticket[0]; cx <= (int) ticket[2]; cx++) {
            for (int cz = (int) ticket[1]; cz <= (int) ticket[3]; cz++) {
                long key = key(cx, cz);
                int count = held.getOrDefault(key, 0);
                if (count <= 1) {
                    held.remove(key);
                    if (count == 1) level.setChunkForced(cx, cz, false);
                } else {
                    held.put(key, count - 1);
                }
            }
        }
        if (held.isEmpty()) HELD.remove(level);
    }

    /** Releases every chunk this level still holds. Called while the level is stopping. */
    public static void releaseAll(ServerLevel level) {
        if (level == null) return;
        Map<Long, Integer> held = HELD.remove(level);
        if (held == null) return;
        for (long key : held.keySet()) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            level.setChunkForced(cx, cz, false);
        }
    }

    /** Releases the chunks of one ticket on every level; used when a stamp restarts in place. */
    public static void releaseEverywhere(long[] ticket) {
        if (ticket == null) return;
        for (ServerLevel level : Set.copyOf(HELD.keySet())) release(level, ticket);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
