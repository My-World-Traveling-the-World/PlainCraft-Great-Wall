package local.plaincraft.greatwall.diagnostics;

import java.io.IOException;

/**
 * Crash-consistency fault injection. Production services depend only on this
 * interface and always receive {@link #disabled()}; the release jar contains
 * no killing implementation and no command switch to enable one. A throwing
 * {@link #hit} must abort the operation: a caller may never swallow the
 * IOException and continue into APPLY.
 */
public interface FaultInjector {
    /** Every distinct point a persistence step can be interrupted at. */
    enum Point {
        CACHE_HALF_WRITE,
        MANIFEST_BEFORE_MOVE,
        MANIFEST_AFTER_MOVE,
        T0_BEFORE_FORCE,
        T0_AFTER_FORCE,
        HISTORY_BEFORE_APPEND,
        HISTORY_AFTER_APPEND,
        HISTORY_BEFORE_FORCE,
        HISTORY_AFTER_FORCE,
        HEAD_BEFORE_MOVE,
        HEAD_AFTER_MOVE,
        GATE_BEFORE_FIRST_WRITE,
        GATE_MID_CHUNK,
        GATE_BEFORE_ACK,
        GATE_AFTER_ACK,
        WORLD_AFTER_CLOSE,
        DISK_FULL,
        QOS_FEATURE_SKIP
    }

    /**
     * Called exactly at the named point. Returning normally means "no fault
     * here"; throwing means the operation must fail and be recovered.
     *
     * @param point the injection point reached
     * @param key   a stable identifier for the item being persisted
     */
    void hit(Point point, String key) throws IOException;

    /** The only implementation shipping in production: a stateless no-op. */
    static FaultInjector disabled() {
        return (point, key) -> { };
    }

    /** Counts hits without failing; used by tests and diagnostics only. */
    final class Counting implements FaultInjector {
        private final java.util.concurrent.ConcurrentHashMap<Point, java.util.concurrent.atomic.AtomicLong> counts =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override public void hit(Point point, String key) {
            counts.computeIfAbsent(point, ignored -> new java.util.concurrent.atomic.AtomicLong())
                    .incrementAndGet();
        }

        public long count(Point point) {
            var value = counts.get(point);
            return value == null ? 0L : value.get();
        }

        public java.util.Map<Point, Long> snapshot() {
            java.util.Map<Point, Long> result = new java.util.EnumMap<>(Point.class);
            counts.forEach((point, value) -> result.put(point, value.get()));
            return java.util.Map.copyOf(result);
        }
    }
}
