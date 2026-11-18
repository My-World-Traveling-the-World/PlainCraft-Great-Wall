package local.plaincraft.greatwall.lifecycle;

import java.util.Objects;
import java.util.function.Consumer;

/** 扫描终态由服务器线程写入，其他入口只读；失败后不能再用空障碍索引继续规划。 */
final class StartupStructureGate {
    private enum Status { WAITING, READY, FAILED }

    private volatile Status status = Status.WAITING;

    <T> boolean complete(T result, Throwable error, Consumer<T> install, Consumer<Throwable> failed) {
        if (status != Status.WAITING) return false;
        if (error != null) {
            status = Status.FAILED;
            failed.accept(error);
            return false;
        }
        try {
            install.accept(Objects.requireNonNull(result, "结构扫描缺少结果"));
        } catch (Throwable failure) {
            status = Status.FAILED;
            failed.accept(failure);
            return false;
        }
        // 先发布完整索引，再允许任何入口启动规划。
        status = Status.READY;
        return true;
    }

    boolean ready() {
        return status == Status.READY;
    }
}
