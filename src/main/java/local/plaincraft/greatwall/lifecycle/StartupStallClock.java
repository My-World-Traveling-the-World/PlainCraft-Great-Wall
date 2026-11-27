package local.plaincraft.greatwall.lifecycle;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 启动线程频繁轮询区块队列，只需每秒取一次工作计数来辨别停滞。 */
final class StartupStallClock {
    private final long limit;
    private long lastChange;
    private long lastPoll;
    private String previous;

    StartupStallClock(long now, long limit) {
        this.limit = limit;
        lastChange = now;
        lastPoll = now - TimeUnit.SECONDS.toNanos(1);
    }

    boolean stalled(long now, Supplier<String> work) {
        if (now - lastPoll < TimeUnit.SECONDS.toNanos(1)) return false;
        lastPoll = now;
        var current = work.get();
        if (!Objects.equals(previous, current)) {
            previous = current;
            lastChange = now;
        }
        return now - lastChange >= limit;
    }
}
