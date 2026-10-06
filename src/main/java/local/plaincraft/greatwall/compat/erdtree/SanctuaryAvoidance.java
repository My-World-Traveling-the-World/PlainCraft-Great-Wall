package local.plaincraft.greatwall.compat.erdtree;

import java.util.Objects;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;

/**
 * Soft-dependency reader for the Golden Erdtree sanctuary centre.
 *
 * <p>The wall must not route through the sanctuary biome (a ~2000-block-radius
 * circle around a seed/climate-selected centre). The centre is already durable:
 * {@code SanctuarySavedData} persists it before generation. This reader only
 * reflects it out when the mod is present; when absent or unreadable it returns
 * {@link #none()} so the wall keeps its normal routing.</p>
 */
public record SanctuaryAvoidance(long centerX, long centerZ, long radiusBlocks) {
    private static final String MOD_ID = "plaincraft_erdtree_sanctuary";
    private static final String MOD_CLASS = "local.plaincraft.erdtreesanctuary.GoldenErdtreeSanctuaryMod";
    // ═══════════════════════════════════════════════════════════════════════════════
    // 【用户明确授权的保留项 — 2026-10-05 拍板：只绕主群系圆，不绕外延河流】
    // 授权内容：固定使用 Golden 工程的 BASE_RADIUS = 2000（主群系圆），
    //   不使用其 MAX_RADIUS = 2600（transition 过渡带 / 外延河流）。
    // 授权理由：用户明确指示「长城绕行不要进入黄金树群系」——群系本身即 2000 圆；
    //   外延部分是水系而非群系，且长城本身具备跨河水门/断口机制，不需要绕开。
    //   用户原话：「只绕主群系圆（半径2000）」。
    // 已知代价（用户知情）：长城可能进入 2000..2600 的过渡带（含外延河流区域）。
    //   这是用户主动接受的取舍，不是遗漏。
    // 红队报告 H3 指出固定 2000 会漏掉 transition 外圈 —— 已被用户明确否决。
    // 恢复条件：仅在用户重新授权后，改为读取真实 layout 的 transition 半径。
    // ═══════════════════════════════════════════════════════════════════════════════
    private static final long SANCTUARY_RADIUS = 2000L;

    public SanctuaryAvoidance {
        if (radiusBlocks < 0) throw new IllegalArgumentException("negative avoidance radius");
    }

    public static SanctuaryAvoidance none() { return new SanctuaryAvoidance(0L, 0L, 0L); }

    public boolean active() { return radiusBlocks > 0L; }

    /**
     * Reads the persisted sanctuary centre reflectively.
     *
     * <p>2026-10-06 修正：原先任何异常都静默返回 {@link #none()}，于是方法签名变化、
     * 未初始化或读取失败时长城会**悄悄不绕行**圣域——没有任何日志或失败信号，
     * 属于静默丢保护。现在区分三种情况：mod 不在场 → 正常的 {@link #none()}；
     * 在场但读取失败 → 抛 {@link WallFailure}（fail-closed，绝不假装没有禁区）；
     * 读取成功 → 返回真实圆心。</p>
     */
    public static SanctuaryAvoidance of(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        if (level.getServer() == null || !FabricLoader.getInstance().isModLoaded(MOD_ID)) return none();
        try {
            Class<?> mod = Class.forName(MOD_CLASS);
            var dataMethod = mod.getMethod("data", ServerLevel.class);
            Object data = dataMethod.invoke(null, level);
            Object layout = data.getClass().getMethod("layout").invoke(data);
            long cx = ((Number) layout.getClass().getMethod("centerX").invoke(layout)).longValue();
            long cz = ((Number) layout.getClass().getMethod("centerZ").invoke(layout)).longValue();
            return new SanctuaryAvoidance(cx, cz, SANCTUARY_RADIUS);
        } catch (Throwable failure) {
            throw new WallFailure(FailureCode.UNSUPPORTED_ADAPTER,
                    "golden erdtree sanctuary is present but its persisted centre could not be read;"
                            + " refusing to plan without sanctuary avoidance", failure);
        }
    }
}
