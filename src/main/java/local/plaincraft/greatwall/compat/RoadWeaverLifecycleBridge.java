package local.plaincraft.greatwall.compat;

import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.shiroha233.roadweaver.api.incoming.PlanningLifecycleApi;
import net.shiroha233.roadweaver.api.incoming.PlanningLifecycleListener;

/**
 * 通过 RoadWeaver 的<b>标准 API</b>接收「河流预热完成」通知。
 *
 * <h2>替代了什么</h2>
 * <p>改造前是 {@code RiversPrewarmedMixin} —— 一个 {@code @Pseudo @Mixin}，直接注入
 * RoadWeaver 的<b>私有实现方法</b>：</p>
 * <pre>
 * &#64;Mixin(targets = "net.shiroha233.roadweaver.generation.InitialGenManager", remap = false)
 * &#64;Inject(method = "prewarmInitialRiverPlates", at = &#64;At("RETURN"), require = 1)
 * </pre>
 *
 * <p>那条 {@code require = 1} 是一把双刃剑：它确实修好了「{@code require = 0} 让签名不匹配
 * 静默失效」的老问题（见 mixin 原注释），代价是<b>RoadWeaver 一改这个方法的名字或签名，
 * 长城就在类加载期直接崩溃</b>。对可选 peer 来说这是错的耦合方向 ——
 * 上游的内部方法不该有能力把下游打挂。</p>
 *
 * <h2>现在</h2>
 * <p>回调注册在 {@link GreatWallMod} 的初始化里。三种情形都安全：</p>
 * <ul>
 *   <li><b>契约在、道路在</b>：河流预热完成后回调触发，等价于原 mixin；</li>
 *   <li><b>契约在、道路不在</b>：注册成功但回调永不触发 —— 与 {@code @Pseudo} 的
 *       「跳过注入」语义一致，长城走它自己的 TAIL 兜底路线；</li>
 *   <li><b>契约不在</b>：{@code fabric.mod.json} 的 {@code depends} 会让 Fabric
 *       在加载期明确报告缺依赖，而不是运行期 {@code NoClassDefFoundError}。</li>
 * </ul>
 *
 * <p>回调体仍<b>整体包在 try/catch</b> 里：这是道路的启动路径，长城侧任何失败都不能
 * 让道路生成失败。</p>
 */
public final class RoadWeaverLifecycleBridge {

    private RoadWeaverLifecycleBridge() {
    }

    /** 在 mod 初始化时调用一次。 */
    public static void register() {
        PlanningLifecycleApi.addListener(new PlanningLifecycleListener() {
            @Override
            public void onRiversPrewarmed() {
                try {
                    WorldServices.riversPrewarmed();
                } catch (Throwable ignored) {
                    // 从道路的启动路径回调进来：长城侧失败绝不能让道路生成失败。
                    // 长城自己的 TAIL 兜底路线依然会跑。
                }
            }
        });
    }
}
