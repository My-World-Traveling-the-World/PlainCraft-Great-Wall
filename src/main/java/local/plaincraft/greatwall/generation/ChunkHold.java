package local.plaincraft.greatwall.generation;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;

/**
 * Temporarily force-loads the chunks a stamp writes into.
 *
 * <p>Writing blocks into an unloaded chunk silently waits forever, so a stamp placed far from spawn would
 * stall half finished. Each stamp holds the chunks under its footprint for the few seconds it runs and
 * releases them as soon as it is done, which keeps the world tidy.
 */
public final class ChunkHold {
    private static final Set<Long> HELD = new HashSet<>();

    private ChunkHold() {
    }

    /** Forces the chunks covering the given block box and returns a ticket for {@link #release}. */
    public static long[] hold(ServerLevel level, int x0, int z0, int x1, int z1) {
        int cx0 = x0 >> 4;
        int cz0 = z0 >> 4;
        int cx1 = x1 >> 4;
        int cz1 = z1 >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (HELD.add(key(cx, cz))) level.setChunkForced(cx, cz, true);
            }
        }
        return new long[] {cx0, cz0, cx1, cz1};
    }

    /** Releases the chunks of one ticket. */
    public static void release(ServerLevel level, long[] ticket) {
        if (ticket == null || ticket.length != 4) return;
        for (int cx = (int) ticket[0]; cx <= (int) ticket[2]; cx++) {
            for (int cz = (int) ticket[1]; cz <= (int) ticket[3]; cz++) {
                if (HELD.remove(key(cx, cz))) level.setChunkForced(cx, cz, false);
            }
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
