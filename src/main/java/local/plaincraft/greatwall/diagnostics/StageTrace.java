package local.plaincraft.greatwall.diagnostics;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Diagnostic clocks and ordering are never inputs to a plan. */
public final class StageTrace {
    public record Event(String event, String session, String dimension, int chunkX, int chunkZ,
                        String stage, long threadId, String threadName, String lockToken, long nanoTime) {}

    private final String session;
    private final ConcurrentHashMap<UUID, Event> events = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> counts = new ConcurrentHashMap<>();
    private static final ThreadLocal<Integer> LOCK_DEPTH = ThreadLocal.withInitial(() -> 0);

    public StageTrace(UUID session) {
        this.session = session.toString();
    }

    public void record(String event, String dimension, int x, int z, String stage, String token) {
        counts.merge(event, 1L, Long::sum);
        if (events.size() < 50_000) {
            var thread = Thread.currentThread();
            events.put(UUID.randomUUID(), new Event(event, session, dimension, x, z, stage,
                    thread.threadId(), thread.getName(), token, System.nanoTime()));
        } else {
            counts.merge("TRACE_TRUNCATED", 1L, Long::sum);
        }
    }

    public void assertOutsideLock() {
        if (LOCK_DEPTH.get() != 0) {
            counts.merge("WAIT_WHILE_LOCKED", 1L, Long::sum);
            throw new IllegalStateException("Planning requested inside a C2ME regional write action");
        }
    }

    public static void enterLockAction() { LOCK_DEPTH.set(LOCK_DEPTH.get() + 1); }
    public static void assertInsideLock() {
        if(LOCK_DEPTH.get()==0) throw new IllegalStateException("Owner mutation requires native TerrainEngine authorization");
    }
    public static void leaveLockAction() {
        int next = LOCK_DEPTH.get() - 1;
        if (next == 0) LOCK_DEPTH.remove(); else LOCK_DEPTH.set(next);
    }
    public Map<String, Long> counts() { return Map.copyOf(counts); }
    public List<Event> snapshot() {
        return events.values().stream().sorted(Comparator.comparingLong(Event::nanoTime)).toList();
    }
}
