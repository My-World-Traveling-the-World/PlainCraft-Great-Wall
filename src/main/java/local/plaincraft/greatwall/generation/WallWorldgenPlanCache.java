package local.plaincraft.greatwall.generation;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan;
import net.minecraft.server.level.ServerLevel;

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

    /** 远景查询不能为已卸载世界创建新会话，也不能从磁盘补读。 */
    public static ChunkWritePlan peek(ServerLevel level, ChunkKey key) {
        var current=SESSIONS.get(level);
        return current==null?null:current.get(key);
    }

    public static net.shiroha233.roadweaver.api.outgoing.WorldgenPreviewApi.Chunk peekPreview(ServerLevel level,ChunkKey key) {
        var current=SESSIONS.get(level);
        return current==null?net.shiroha233.roadweaver.api.outgoing.WorldgenPreviewApi.Chunk.unavailable():current.preview(key);
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

    // 读票据只存于在途表；新发布使同区块票据失效，即使新计划已被 LRU 淘汰。
    static final class ReadTicket {
        private boolean superseded;
    }

    static Session readSession(ServerLevel level) {
        return session(level);
    }

    static boolean isReadSessionCurrent(ServerLevel level, Session owner) {
        return SESSIONS.get(level) == owner;
    }

    static final class Session {
        private final Object lock = new Object();
        private record Projection(ChunkWritePlan source,net.shiroha233.roadweaver.api.outgoing.WorldgenPreviewApi.Chunk value) {}
        private final LinkedHashMap<ChunkKey,Projection> previews=new LinkedHashMap<>(32,.75f,true);
        private final Map<ChunkKey, ReadTicket> reads = new HashMap<>();
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

        net.shiroha233.roadweaver.api.outgoing.WorldgenPreviewApi.Chunk preview(ChunkKey key) {
            ChunkWritePlan plan;
            synchronized(lock) {
                plan=get(key);
                if(plan==null)return net.shiroha233.roadweaver.api.outgoing.WorldgenPreviewApi.Chunk.unavailable();
                var cached=previews.get(key);
                if(cached!=null&&cached.source()==plan)return cached.value();
            }
            // 大模板的状态序列化不占用施工缓存锁；同版本只为远景投影一次。
            var value=WallPreviewProvider.convert(plan);
            synchronized(lock) {
                if(get(key)!=plan)return net.shiroha233.roadweaver.api.outgoing.WorldgenPreviewApi.Chunk.unavailable();
                previews.put(key,new Projection(plan,value));
                while(previews.size()>64)previews.remove(previews.keySet().iterator().next());
            }
            return value;
        }

        void put(ChunkKey key, ChunkWritePlan plan) {
            boolean isEmpty = plan.voxels().isEmpty();
            synchronized (lock) {
                var read = reads.get(key);
                if (read != null) read.superseded = true;
                store(key, plan, isEmpty);
            }
        }

        ReadTicket beginRead(ChunkKey key) {
            synchronized (lock) {
                if (reads.containsKey(key) || nonEmpty.containsKey(key) || empty.containsKey(key)) return null;
                var ticket = new ReadTicket();
                reads.put(key, ticket);
                return ticket;
            }
        }

        boolean finishRead(ChunkKey key, ReadTicket ticket, ChunkWritePlan plan) {
            synchronized (lock) {
                if (!reads.remove(key, ticket) || ticket.superseded || plan == null || !key.equals(plan.owner())) return false;
                store(key, plan, plan.voxels().isEmpty());
                return true;
            }
        }

        private void store(ChunkKey key, ChunkWritePlan plan, boolean isEmpty) {
            LinkedHashMap<ChunkKey, ChunkWritePlan> own = isEmpty ? empty : nonEmpty;
            LinkedHashMap<ChunkKey, ChunkWritePlan> other = isEmpty ? nonEmpty : empty;
            other.remove(key);
            previews.remove(key);
            own.put(key, plan);
        }
    }
}
