/* 文件职责：规划租约 —— 共享 future 的独占可取消视图，关闭时释放。 */
package local.plaincraft.greatwall.model;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规划租约。多个申请者共享同一个计算 future，但各自持有独立的可取消视图。
 * 完成不会自动释放：消费者用完 plan 才 close；超时/错误由消费者 finally close。
 */
public final class PlanLease<T> implements AutoCloseable {

    private final UUID id;
    private final CompletableFuture<T> view;
    private final Runnable release;
    private final ConcurrentHashMap<String, Boolean> state;

    public PlanLease(UUID id, CompletionStage<T> shared, Runnable release) {
        if (id == null) throw new IllegalArgumentException("id required");
        if (shared == null) throw new IllegalArgumentException("shared required");
        if (release == null) throw new IllegalArgumentException("release required");
        this.id = id;
        this.release = release;
        this.state = new ConcurrentHashMap<>();
        // 转发 shared 的完成到 view，但 view 的取消绝不传回 shared
        this.view = new CompletableFuture<>();
        shared.whenComplete((value, err) -> {
            if (err != null) {
                view.completeExceptionally(err);
            } else {
                view.complete(value);
            }
        });
    }

    public UUID id() {
        return id;
    }

    public CompletableFuture<T> future() {
        return view;
    }

    @Override
    public void close() {
        // 只关闭一次
        if (state.putIfAbsent("closed", Boolean.TRUE) == null) {
            view.cancel(false);
            release.run();
        }
    }
}
