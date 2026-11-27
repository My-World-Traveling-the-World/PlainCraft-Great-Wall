package local.plaincraft.greatwall.lifecycle;

import java.util.function.Consumer;
import java.util.function.Supplier;
import local.plaincraft.greatwall.compat.RiverStartupQuery.Check;
import local.plaincraft.greatwall.compat.RiverStartupQuery.Action;

/** 每个服务端实例各有一个门，两个 prepareLevels 钩子只许冻结和启动长城一次。仅属主线程访问。 */
final class WallRiverStartupGate {
    private long session;
    private boolean settled;
    private boolean allowed;

    boolean enter(boolean afterRiverHook, Supplier<Check> read, Runnable warm, Consumer<String> reject) {
        if (settled) return allowed;
        var current = read.get();
        // 零仅代表该世界尚无会话；一旦见过真实编号，不得用另一个会话的成功通知放行。
        boolean changed = session != 0 && session != current.session();
        if (session == 0) session = current.session();
        if (!changed && current.action() == Action.WAIT && !afterRiverHook) return false;
        settled = true;
        if (changed || current.action() != Action.ALLOW) {
            String reason = changed ? "河流启动会话已更换，原会话=" + session
                    : current.action() == Action.WAIT ? "河流启动钩子已返回但未给出终态" : current.reason();
            reject.accept("phase=" + current.phase() + " session=" + current.session() + " reason=" + reason);
            return false;
        }
        allowed = true;
        warm.run();
        return true;
    }
}
