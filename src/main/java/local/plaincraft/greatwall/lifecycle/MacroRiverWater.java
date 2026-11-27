package local.plaincraft.greatwall.lifecycle;

import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 已冻结的宏观查询预算；域外没有模组水文证据，仍由原始群系和地形投票。 */
record MacroRiverWater(int radius, String identity, Column column) {
    @FunctionalInterface interface Column { boolean wet(int x, int z); }
    static final MacroRiverWater NONE = new MacroRiverWater(0, "", (x,z) -> false);

    MacroRiverWater {
        if (radius < 0) throw new IllegalArgumentException("水网范围小于零");
        Objects.requireNonNull(identity); Objects.requireNonNull(column);
    }

    boolean wet(Point3i spawn, int x, int z) {
        if (radius == 0) return false;
        // 同一出生点和半径决定查询域，不因新建/重开的河流预热中心或 LRU 淘汰而改选轴。
        long dx = (long)x - spawn.x(), dz = (long)z - spawn.z();
        return Math.abs(dx) <= radius && Math.abs(dz) <= radius
                && dx * dx + dz * dz <= (long)radius * radius && column.wet(x, z);
    }

    String rootCacheKey(String prior) {
        if (radius == 0) return prior;
        return DeterministicHash.sha256((prior + ":macro-authoritative-water-v1:" + radius + ":" + identity)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** 单独装长城时不解析河流 API；禁用预热时甚至不打开引擎。 */
    static MacroRiverWater freeze(Object world, boolean installed) {
        if (!installed) return NONE;
        // 契约链接或会话失效是本世界水文不可用，不伪装成旱地，也不因此崩溃整个服务器。
        try { return Contract.freeze(world); }
        catch (RuntimeException | LinkageError failure) { throw new Unavailable("无法冻结本世界河网", failure); }
    }

    static final class Contract {
        static MacroRiverWater freeze(Object world) {
            var phase = dev.streamsreflowing.api.RiverPlanApi.startup(world);
            if (phase.phase() == dev.streamsreflowing.api.RiverPlanApi.Phase.DISABLED) return NONE;
            if (phase.phase() != dev.streamsreflowing.api.RiverPlanApi.Phase.COMPLETE)
                throw new IllegalStateException("河流未完成，不能冻结水网：" + phase);
            var opened = dev.streamsreflowing.api.RiverPlanApi.open(world);
            if (opened.availability() != dev.streamsreflowing.api.RiverPlanApi.Availability.AVAILABLE)
                throw new IllegalStateException("无法冻结河流计划：" + opened.reason());
            return from(opened.session());
        }

        static MacroRiverWater from(dev.streamsreflowing.api.RiverPlanApi.Session session) {
            var scope = session.macroWaterScope();
            if (scope.radiusBlocks() == 0) return NONE;
            return new MacroRiverWater(scope.radiusBlocks(), scope.waterRules(), (x,z) -> {
                try {
                    // 预览未执行最终河道高度限制，且任一邻板被淘汰就成未知，不能决定永久轴向。
                    return session.column(x,z).inChannel() || session.lake(x,z).present();
                } catch (Exception error) {
                    throw new Unavailable("水文查询失败，不能把未知列视作陆地：x=" + x + ", z=" + z, error);
                }
            });
        }
    }

    static final class Unavailable extends RuntimeException {
        Unavailable(String message, Throwable cause) { super(message, cause); }
    }
}
