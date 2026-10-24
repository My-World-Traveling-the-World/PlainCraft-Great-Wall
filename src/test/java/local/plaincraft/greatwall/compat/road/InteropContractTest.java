package local.plaincraft.greatwall.compat.road;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.api.WallRoadApi;
import local.plaincraft.greatwall.lifecycle.PrewarmProgress;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import org.junit.jupiter.api.Test;

/**
 * 跨 mod 契约锁定 —— 两个 mod 之间的调用面一旦改名/改签名，必须在这里先红。
 *
 * <p>原因：道路侧对这层契约有两套消费方式，坏起来都是<b>运行期静默</b>：
 * <ul>
 *   <li>写块守卫（{@link WallRoadApi}）走编译期 {@code compileOnly} 的 interop jar ——
 *       签名变了道路编译会失败，这一半是安全的；</li>
 *   <li>预热门（{@code WorldServices.awaitPrewarm} / {@code prewarmSnapshot}）走
 *       <b>反射字符串</b> —— 长城改了名道路<b>不会</b>编译失败，只会在运行期探不到、
 *       然后 fail-closed 把路卡住。</li>
 * </ul>
 *
 * <p>这个测试只读类元数据，不加载世界、不碰方块。它替代不了真机，但能在 CI 里
 * 把「改签名静默失效」变成「构建红」。
 */
class InteropContractTest {

    private static Method staticMethod(Class<?> owner, String name, Class<?>... parameters) throws Exception {
        Method method = owner.getMethod(name, parameters);
        assertTrue(Modifier.isStatic(method.getModifiers()),
                owner.getSimpleName() + "." + name + " 必须是 static（道路侧按静态门面调用）");
        return method;
    }

    /** 道路侧 {@code WallRoadAdapter} 直接调用的五个静态方法，签名与返回类型都不许动。 */
    @Test
    void wallRoadApiSignaturesAreFrozen() throws Exception {
        assertEquals(boolean.class, staticMethod(WallRoadApi.class, "blocks",
                int.class, int.class, int.class).getReturnType());
        assertEquals(boolean.class, staticMethod(WallRoadApi.class, "blocksWithin",
                int.class, int.class, int.class, int.class).getReturnType());
        assertEquals(boolean.class, staticMethod(WallRoadApi.class, "coarseMayAffect",
                int.class, int.class).getReturnType());
        assertEquals(long.class, staticMethod(WallRoadApi.class, "millisSinceLastMark").getReturnType());
        assertEquals(boolean.class, staticMethod(WallRoadApi.class, "hasMarks").getReturnType());
    }

    /**
     * 预热门的反射契约：方法名、参数表、以及 {@code Snapshot} 上那九个进度访问器。
     *
     * <p>九个名字与道路侧 {@code GreatWallPrewarmCompat.PROGRESS_COMPONENTS} 一一对应；
     * 少了任何一个，道路的"停滞检测"就读不到进度字段，只会静默退化。
     */
    @Test
    void prewarmGateContractIsFrozen() throws Exception {
        staticMethod(WorldServices.class, "awaitPrewarm", long.class, TimeUnit.class);
        Method snapshotAccessor = staticMethod(WorldServices.class, "prewarmSnapshot");
        assertEquals(PrewarmProgress.Snapshot.class, snapshotAccessor.getReturnType());

        String[] progressComponents = {
                "active", "failed", "stage", "completed",
                "rootSearch", "rootCandidate", "rootPhase",
                "structureCandidate", "subDone"
        };
        for (String component : progressComponents) {
            Method accessor = PrewarmProgress.Snapshot.class.getMethod(component);
            assertTrue(Modifier.isPublic(accessor.getModifiers()), "Snapshot." + component + " 必须 public");
            assertTrue(accessor.getParameterCount() == 0, "Snapshot." + component + " 必须是 record 访问器");
        }
    }

    /**
     * interop jar 只打包 {@code api} 包，而 api 门面在<b>运行时</b>依赖 {@code compat.road.WallRoadGuard}
     * （同一版长城主 jar 内）。这里把这条反向依赖显式钉住：它仍然是"编译期 ABI 自洽、运行期同包"，
     * 若将来真要接口化，这个断言会提醒改动者同步调整道路侧的降级路径。
     */
    @Test
    void apiFacadeStillDelegatesToTheGuard() throws Exception {
        for (String name : new String[] {"blocks", "blocksWithin", "coarseMayAffect"}) {
            assertTrue(WallRoadApi.class.getDeclaredMethod(name,
                            name.equals("blocksWithin")
                                    ? new Class<?>[] {int.class, int.class, int.class, int.class}
                                    : name.equals("coarseMayAffect")
                                            ? new Class<?>[] {int.class, int.class}
                                            : new Class<?>[] {int.class, int.class, int.class})
                    != null);
        }
        // 实现类必须在同一 mod 里（道路侧没有它，present() 探到 api 却调不动时会 fail-open）。
        Class.forName("local.plaincraft.greatwall.compat.road.WallRoadGuard", false,
                InteropContractTest.class.getClassLoader());
    }
}
