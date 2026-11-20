package local.plaincraft.greatwall.compat;

import dev.streamsreflowing.api.RiverPlanApi;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 只测长城自己的消费与可选链接；提供方结果门守卫归河流仓库。 */
class RiverStartupQueryTest {
    @Test void failureAndSessionAreVisibleWithoutPublishingReady() {
        RiverPlanApi.register(new RiverPlanApi.Provider() {
            public RiverPlanApi.Session open(Object world) { throw new AssertionError("诊断禁止构建引擎"); }
            public RiverPlanApi.Startup startup(Object world) {
                return new RiverPlanApi.Startup(42, RiverPlanApi.Phase.FAILED, "采样器关闭失败");
            }
        });
        var status = RiverStartupQuery.snapshot(new Object());
        assertEquals("AVAILABLE", status.get("availability"));
        assertEquals("FAILED", status.get("phase"));
        assertEquals(42L, status.get("session"));
        assertEquals("采样器关闭失败", status.get("reason"));
        assertThrows(UnsupportedOperationException.class, () -> status.clear());
    }

    @Test void absentContractStillLoadsConsumer() throws Exception {
        var location = RiverStartupQuery.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new java.net.URL[] {location}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("dev.streamsreflowing.")) throw new ClassNotFoundException(name);
                if (name.startsWith(RiverStartupQuery.class.getName())) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            var type = loader.loadClass(RiverStartupQuery.class.getName());
            var result = (java.util.Map<?, ?>) type.getMethod("snapshot", Object.class).invoke(null, new Object());
            assertEquals("ABSENT", result.get("availability"));
            assertNotNull(result.get("reason"));
        }
    }
}
