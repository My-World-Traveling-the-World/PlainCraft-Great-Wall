package local.plaincraft.greatwall.compat;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.plaincraft.greatwall.lifecycle.PrewarmProgress;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.shiroha233.roadweaver.api.outgoing.GreatWallReadiness;
import net.shiroha233.roadweaver.api.outgoing.GreatWallReadinessRegistry;

/**
 * 把长城的预热状态通过<b>标准 API</b> 暴露给道路。
 *
 * <p>原先道路是用 {@code Class.forName} 反射进 {@link WorldServices} 的
 * {@code awaitPrewarm} / {@code prewarmSnapshot}，再逐个反射读快照的九个 accessor
 * 拼进度签名。现在长城自己实现 {@link GreatWallReadiness} 并注册，方法改名会变成
 * <b>编译期</b>错误，快照结构也只在长城内部可见。</p>
 *
 * <p>签名同时反映阶段、工作计数与编译子步骤。签名不含墙钟，
 * 因为等待方需要区分工作仍在推进和仅仅时间流逝。</p>
 */
public final class GreatWallReadinessProvider implements GreatWallReadiness {

    /** 注册表里的 id，不要去改（道路的诊断日志按它认长城）。 */
    public static final String OWNER_ID = "great-wall";

    private static final Logger LOGGER = LoggerFactory.getLogger("greatwall/api");
    private final Awaiter awaiter;
    private final Supplier<PrewarmProgress.Snapshot> snapshot;

    private GreatWallReadinessProvider() {
        this(WorldServices::awaitPrewarm, WorldServices::prewarmSnapshot);
    }

    /** 只搬读状态边界，端口测试不需要构造真实服务端或改变预热判据。 */
    GreatWallReadinessProvider(Awaiter awaiter, Supplier<PrewarmProgress.Snapshot> snapshot) {
        this.awaiter = awaiter;
        this.snapshot = snapshot;
    }

    @FunctionalInterface
    interface Awaiter {
        boolean await(long timeout, TimeUnit unit);
    }

    /**
     * 在 mod 初始化时调用一次。
     *
     * <p>放在 {@link GreatWallMod} 里，与 {@code RoadWeaverLifecycleBridge.register()}
     * 相邻：一个是「河流就绪时通知我」，一个是「我公布自己的预热状态」，方向相反。</p>
     */
    public static void register() {
        GreatWallReadinessRegistry.register(new GreatWallReadinessProvider());
        // 自证：长城单独发布、道路没装时，这一行就是"我把就绪闸门挂上了"的唯一凭据。
        // 刻意不在这里报"谁订阅了我" —— 那是订阅方自己的事，长城不该知道。
        LOGGER.info("greatwall-readiness: provider registered (owner={}).", OWNER_ID);
    }

    @Override
    public String ownerId() {
        return OWNER_ID;
    }

    @Override
    public boolean isReady() {
        try {
            return awaiter.await(0L, TimeUnit.NANOSECONDS);
        } catch (Throwable failure) {
            // 契约要求：内部出错回「未就绪」，绝不能回「就绪」。
            return false;
        }
    }

    @Override
    public boolean awaitReady(long timeoutMillis) {
        if (timeoutMillis <= 0L) return isReady();
        try {
            return awaiter.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            return false;
        }
    }

    @Override
    public boolean isTerminalFailure() {
        // 轮询只读现有终态标志，不分配进度快照，也不把「尚未就绪」误报成失败。
        return PrewarmProgress.isFailed();
    }

    @Override
    public String progressSignature() {
        try {
            PrewarmProgress.Snapshot s = snapshot.get();
            if (s == null) return null;
            // 保留原有字段顺序，附上编译工序。等待者只把实际工作变化当作进展。
            return s.workSignature();
        } catch (Throwable failure) {
            // 契约要求：出错回 null（= 没有信号），不要回常量。
            return null;
        }
    }
}
