package local.plaincraft.greatwall.api;

/**
 * 长城对外开放的只读查询门面 —— 唯一稳定的跨 mod 契约（静态门面，不引入实例生命周期）。
 *
 * <p>消费方（道路 mod）只需 compileOnly 依赖本类所在的 interop jar，即可类型安全地调用；
 * 运行时若没装长城，调用方必须先用 {@code isModLoaded} 挡住（实现隔离在单独类里）。
 *
 * <p><b>加方法前先想清楚：</b>任何签名变化都会让消费方编译失败 —— 这是刻意的，
 * 比运行时静默失效好。
 */
public final class WallRoadApi {
    private WallRoadApi() {
    }

    /** 这一格是不是被长城占用（含 2 格余量）；true 表示道路不得在此落方块。 */
    public static boolean blocks(int x, int y, int z) {
        return local.plaincraft.greatwall.compat.road.WallRoadGuard.blocks(x, y, z);
    }

    /** 同上，但余量由调用方指定（灯柱/路牌这类横向有伸展的道具用 3）。 */
    public static boolean blocksWithin(int x, int y, int z, int radius) {
        return local.plaincraft.greatwall.compat.road.WallRoadGuard.blocksWithin(x, y, z, radius);
    }

    /** 高热点用的粗筛：只判断"这一列是否在长城影响范围"，无锁、无分配。 */
    public static boolean coarseMayAffect(int x, int z) {
        return local.plaincraft.greatwall.compat.road.WallRoadGuard.coarseMayAffect(x, z);
    }

    /** 距上一次打标记过了多少毫秒。 */
    public static long millisSinceLastMark() {
        return local.plaincraft.greatwall.compat.road.WallRoadGuard.millisSinceLastMark();
    }

    /** 是否已经标过任何一列。 */
    public static boolean hasMarks() {
        return local.plaincraft.greatwall.compat.road.WallRoadGuard.hasMarks();
    }
}
