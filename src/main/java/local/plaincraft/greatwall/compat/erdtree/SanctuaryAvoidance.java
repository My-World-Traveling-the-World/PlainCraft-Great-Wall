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
    // 【用户明确授权 — 2026-10-06 修订为完全绕行，禁止在未重新授权前回退】
    // 授权内容：绕行范围使用 Golden 工程的 MAX_RADIUS = 2600（含 transition 过渡带与
    //   外延河流），而不是只看 BASE_RADIUS = 2000 的主群系圆。
    // 授权演进：2026-10-05 用户起初指示「只绕主群系圆（半径2000）」；
    //   2026-10-06 用户改为「黄金树圣域改成完全绕行吧」，故取 2600。
    // 依据：golden-erdtree-sanctuary 的 SanctuaryLayout.MAX_RADIUS = 2600，
    //   且 ErdtreeSnapshotInstaller 校验 manifest 时同样要求
    //   core_radius == 2000 && transition_outer_radius == 2600。
    // 已知代价（用户知情）：长城不会进入圣域 2000..2600 的过渡带，可选路线略少。
    // 红队报告 H3 曾指出固定 2000 漏掉 transition 外圈 —— 已按用户决定采用 2600 解决。
    // ═══════════════════════════════════════════════════════════════════════════════
    private static final long SANCTUARY_RADIUS = 2600L;

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
