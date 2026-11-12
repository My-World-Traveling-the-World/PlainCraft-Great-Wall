package local.plaincraft.greatwall.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.shiroha233.roadweaver.api.incoming.RiverReadinessApi;
import net.shiroha233.roadweaver.api.incoming.RiverReadinessListener;

/**
 * 通过 RoadWeaver 的<b>标准 API</b>接收「河流已就绪」通知。
 *
 * <h2>替代了两个东西</h2>
 *
 * <p>① 最早的 {@code RiversPrewarmedMixin} —— 一个 {@code @Pseudo @Mixin}，直接注入
 * RoadWeaver 的<b>私有实现方法</b>：</p>
 * <pre>
 * &#64;Mixin(targets = "net.shiroha233.roadweaver.generation.InitialGenManager", remap = false)
 * &#64;Inject(method = "prewarmInitialRiverPlates", at = &#64;At("RETURN"), require = 1)
 * </pre>
 * <p>那条 {@code require = 1} 是一把双刃剑：它确实修好了「{@code require = 0} 让签名
 * 不匹配静默失效」的老问题，代价是 RoadWeaver 一改这个方法的名字或签名，长城就在
 * 类加载期直接崩溃。对可选 peer 来说这是错的耦合方向。</p>
 *
 * <p>② 之后换成了 {@code PlanningLifecycleApi.onRiversPrewarmed}，但<b>触发时机是错的</b>：
 * 那个回调由道路在「结构扫描之后」的河流步骤里触发，而实测时间线显示长城在那之前
 * 就已经自己预热完了，事件被幂等吞掉；真正起作用的其实是河流 mod 里一条
 * {@code Class.forName} 硬编码直连到 {@link WorldServices#riversPrewarmed()}。</p>
 *
 * <h2>现在</h2>
 *
 * <p>河流就绪的<b>提供方是河流 mod，不是道路</b>。契约 {@link RiverReadinessApi} 由道路的
 * Jar-in-Jar 提供，但它是 api 模块内的静态注册表，<b>不经过 RoadWeaver 的后端</b> ——
 * 既没有「道路后端尚未注入时注册会丢」的问题，触发时机也由真正知道河流状态的一方决定。</p>
 *
 * <p>晚订阅也是安全的：若订阅时河流已经就绪，{@link RiverReadinessApi#addListener}
 * 会立即补发一次回调。</p>
 *
 * <p>三种情形都安全：</p>
 * <ul>
 *   <li><b>河流在、契约在</b>：河流板块全部落地后广播，回调触发，长城开始预热；</li>
 *   <li><b>河流不在</b>：注册成功但回调永不触发 —— 长城走它自己的 TAIL 兜底路线；</li>
 *   <li><b>契约不在</b>：{@code fabric.mod.json} 的 {@code depends} 会让 Fabric
 *       在加载期明确报告缺依赖，而不是运行期 {@code NoClassDefFoundError}。</li>
 * </ul>
 *
 * <p>回调体仍<b>整体包在 try/catch</b> 里：这是别人的启动路径，长城侧任何失败都不能
 * 连累广播方。</p>
 */
public final class RoadWeaverLifecycleBridge {

    private RoadWeaverLifecycleBridge() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("greatwall/api");

    /** 在 mod 初始化时调用一次。 */
    public static void register() {
        RiverReadinessApi.addListener(new RiverReadinessListener() {
            @Override
            public void onRiversReady(String providerId) {
                try {
                    LOGGER.info("river-readiness: rivers ready from provider={}.", providerId);
                    WorldServices.riversPrewarmed();
                } catch (Throwable ignored) {
                    // 从别人的启动路径回调进来：长城侧失败绝不能让河流生成失败。
                    // 长城自己的 TAIL 兜底路线依然会跑。
                }
            }
        });
        // 自证：长城单独发布、河流没装时，listeners 恒 0 且 alreadyReady=false ——
        // 一眼能看出"订阅上了但没人会通知我"，而不是误以为接线成功。
        LOGGER.info("river-readiness: subscribed (listeners={}, alreadyReady={}).",
                RiverReadinessApi.listenerCount(), RiverReadinessApi.isReady());
    }
}
