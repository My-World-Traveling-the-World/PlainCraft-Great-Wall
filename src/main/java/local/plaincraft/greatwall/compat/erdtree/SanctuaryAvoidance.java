package local.plaincraft.greatwall.compat.erdtree;

import java.util.Objects;
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
    /** BASE_RADIUS of the sanctuary biome circle. */
    private static final long SANCTUARY_RADIUS = 2000L;

    public SanctuaryAvoidance {
        if (radiusBlocks < 0) throw new IllegalArgumentException("negative avoidance radius");
    }

    public static SanctuaryAvoidance none() { return new SanctuaryAvoidance(0L, 0L, 0L); }

    public boolean active() { return radiusBlocks > 0L; }

    /**
     * Reads the persisted sanctuary centre reflectively. Never throws: a missing
     * mod or an incompatible layout simply yields {@link #none()}.
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
            return none();
        }
    }
}
