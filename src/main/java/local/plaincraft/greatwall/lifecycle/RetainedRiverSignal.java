package local.plaincraft.greatwall.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;

/** 原子地交接“河流先完成”和“长城会话先注册”两条路径，避免通知落在两次操作之间。 */
final class RetainedRiverSignal<K> {
    private final Map<K, Runnable> sessions = new LinkedHashMap<>();
    private boolean ready;

    synchronized void attach(K key, Runnable callback) {
        sessions.put(key, callback);
        // 先注册再重放，且与publish同锁；构造器中先检查后入表会留下漏信号窗口。
        if (ready) callback.run();
    }

    synchronized void publish() {
        ready = true;
        // 保留重复广播，幂等仍由各生产会话负责，不抑制重开时的新通知。
        for (Runnable callback : sessions.values()) callback.run();
    }

    synchronized void detach(K key) {
        sessions.remove(key);
    }

    synchronized void clear() {
        // 与发布同锁，退出完成后不会再回调已经注销的世界。
        sessions.clear();
        ready = false;
    }
}
