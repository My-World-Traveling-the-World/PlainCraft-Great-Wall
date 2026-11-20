package local.plaincraft.greatwall.compat;

import java.util.Map;

/** 诊断只读提供方状态；旧版/缺席单列显示，不用“等不到”代替失败原因。 */
public final class RiverStartupQuery {
    private RiverStartupQuery() {}

    public static Map<String, Object> snapshot(Object world) {
        try {
            Class.forName("dev.streamsreflowing.api.RiverPlanApi");
            return Contract.snapshot(world);
        } catch (ClassNotFoundException absent) {
            return Map.of("availability", "ABSENT", "reason", "未安装公开河流状态契约");
        } catch (RuntimeException | LinkageError failure) {
            return Map.of("availability", "FAILED", "reason", failure.toString());
        }
    }

    /** 独立链接可选契约，主类在不装河流时仍能被正常加载。 */
    private static final class Contract {
        static Map<String, Object> snapshot(Object world) {
            if (!dev.streamsreflowing.api.RiverPlanApi.isInstalled())
                return Map.of("availability", "ABSENT", "reason", "河流提供方未安装");
            var status = dev.streamsreflowing.api.RiverPlanApi.startup(world);
            return Map.of("availability", "AVAILABLE", "session", status.session(),
                    "phase", status.phase().name(), "reason", status.reason());
        }
    }
}
