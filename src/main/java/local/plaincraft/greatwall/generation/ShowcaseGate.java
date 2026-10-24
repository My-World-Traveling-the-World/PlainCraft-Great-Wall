package local.plaincraft.greatwall.generation;

import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.compat.road.WallRoadGuard;

/**
 * Holds the road mod back until every registered showcase piece has actually finished stamping.
 *
 * <p>Each piece calls {@link #expect()} when it is registered and {@link #done()} when it completes, so the
 * gate is exact - no timing heuristic. (An earlier version guessed "settled" from how long ago the last column
 * was marked; it opened 2.6 s in while pieces were still stamping 30 s later.) A hard deadline keeps a broken
 * piece from blocking the road forever.
 */
public final class ShowcaseGate {
    private static final long DEADLINE_MILLIS = 300_000L;

    private static final AtomicInteger PENDING = new AtomicInteger();
    private static volatile boolean active;
    private static volatile long armedAt;

    private ShowcaseGate() {
    }

    /** Registers one piece that must finish before roads may start. */
    public static void expect() {
        active = true;
        if (armedAt == 0L) armedAt = System.currentTimeMillis();
        PENDING.incrementAndGet();
    }

    /** Reports one piece finished. */
    public static void done() {
        int left = PENDING.decrementAndGet();
        if (left < 0) {
            // 没有配对的 expect() 就报完成 —— 只会来自复位后的重复 done()。
            // 过去这里任由计数变负，下一次 expect() 把计数抬到 <=0，门就提前开了。
            PENDING.set(0);
            GreatWallMod.LOGGER.warn("[GreatWall:road] SHOWCASE_GATE_DONE_UNEXPECTED (no matching expect(); counter clamped to 0)");
            return;
        }
        if (left == 0) {
            GreatWallMod.LOGGER.info("[GreatWall:road] SHOWCASE_GATE_OPEN after {}ms (all pieces stamped)",
                    System.currentTimeMillis() - armedAt);
        }
    }

    /**
     * 重新武装：`/greatwall restamp` 重画前调用。
     *
     * <p>把上一轮的计数、armed 时间和 active 全部清掉，让本轮 {@link #expect()} 从零开始计。
     * 不 reset 就 restart 会让 {@link #done()} 把计数打成负数，随后门提前开。
     */
    public static void reset() {
        int before = PENDING.getAndSet(0);
        armedAt = 0L;
        active = false;
        GreatWallMod.LOGGER.info("[GreatWall:road] SHOWCASE_GATE_RESET pendingWas={}", before);
    }

    /** True while the road mod must keep waiting. */
    public static boolean pending() {
        if (!active) return false;
        int left = PENDING.get();
        if (left <= 0) return false;
        long elapsed = System.currentTimeMillis() - armedAt;
        if (elapsed > DEADLINE_MILLIS) {
            PENDING.set(0);
            GreatWallMod.LOGGER.warn("[GreatWall:road] SHOWCASE_GATE_OPEN forced after {}ms ({} pieces never finished)",
                    elapsed, left);
            return false;
        }
        return true;
    }

    /** Pieces still outstanding (diagnostics). */
    public static int remaining() {
        return PENDING.get();
    }
}
