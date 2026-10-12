package local.plaincraft.greatwall.generation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Session-scoped cache of immutable wall write plans, mirroring RoadWeaver's
 * {@code RoadWorldgenPlanCache}: the planning phase compiles each chunk's {@link ChunkWritePlan}
 * once, and the generation phase only reads the cached plan — no chunk access, no neighbour reads,
 * no futures on the hot path.
 *
 * <p>Separate LRU budgets keep Chunky's huge number of grazing (empty) chunks from evicting the few
 * chunks that actually carry wall. Access-order eviction keeps hot plans resident.
 */
public final class WallWorldgenPlanCache {
    private static final int MAX_NON_EMPTY_PER_LEVEL = 4096;
    private static final int MAX_EMPTY_PER_LEVEL = 16384;
    private static final ConcurrentHashMap<ServerLevel, Session> SESSIONS = new ConcurrentHashMap<>();

    private WallWorldgenPlanCache() {
    }

    public static ChunkWritePlan get(ServerLevel level, ChunkKey key) {
        if (level == null || key == null) return null;
        return session(level).get(key);
    }

    public static void put(ServerLevel level, ChunkWritePlan plan) {
        if (level == null || plan == null) return;
        session(level).put(plan.owner(), plan);
    }

    public static void invalidate(ServerLevel level) {
        if (level == null) return;
        SESSIONS.remove(level);
    }

    public static void clearAll() {
        SESSIONS.clear();
    }

    private static Session session(ServerLevel level) {
        return SESSIONS.computeIfAbsent(level, ignored -> new Session());
    }

    private static final class Session {
        private final Object lock = new Object();
        private final LinkedHashMap<ChunkKey, ChunkWritePlan> nonEmpty =
                new LinkedHashMap<>(256, 0.75F, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<ChunkKey, ChunkWritePlan> eldest) {
                        return nonEmpty.size() > MAX_NON_EMPTY_PER_LEVEL;
                    }
                };
        private final LinkedHashMap<ChunkKey, ChunkWritePlan> empty =
                new LinkedHashMap<>(256, 0.75F, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<ChunkKey, ChunkWritePlan> eldest) {
                        return empty.size() > MAX_EMPTY_PER_LEVEL;
                    }
                };

        ChunkWritePlan get(ChunkKey key) {
            synchronized (lock) {
                ChunkWritePlan plan = nonEmpty.get(key);
                return plan != null ? plan : empty.get(key);
            }
        }

        void put(ChunkKey key, ChunkWritePlan plan) {
            boolean isEmpty = plan.voxels().isEmpty();
            synchronized (lock) {
                LinkedHashMap<ChunkKey, ChunkWritePlan> own = isEmpty ? empty : nonEmpty;
                LinkedHashMap<ChunkKey, ChunkWritePlan> other = isEmpty ? nonEmpty : empty;
                other.remove(key);
                own.put(key, plan);
            }
        }
    }
}
