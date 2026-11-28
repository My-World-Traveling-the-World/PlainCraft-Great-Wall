package local.plaincraft.greatwall.planning;

import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;

import java.nio.charset.StandardCharsets;

/**
 * A {@link SurfaceProbe.ColumnView} backed entirely by a planning-resolved {@link ColumnSample}
 * (accurate ground/water surface) instead of a per-block T0 snapshot.
 *
 * <p>This is the root-cause replacement for the per-block column the generation stage used to read:
 * the surface probe only ever needed the ground surface, the water surface and the protect bit, so a
 * column view derived from those three numbers is sufficient and removes the per-block terrain
 * dependency entirely.
 *
 * <p>Derived semantics:
 * <ul>
 *   <li>{@code state(y)} below ground reads as solid stone; at/above ground as air (or water inside
 *       the water column).</li>
 *   <li>{@code solidSupport(y)} is true for y strictly below the ground surface.</li>
 *   <li>{@code water(y)} is true inside [bedTopY, waterTopY).</li>
 *   <li>{@code highestNonPlantFeet()} is the ground surface.</li>
 * </ul>
 * The protect bit is applied by {@link ProtectedColumnView} from the planning structure boxes.
 */
public final class PlanningColumnView implements SurfaceProbe.ColumnView {
    private final int x, z;
    private final ColumnSample sample;
    /**
     * Lazily computed column digest.
     *
     * <p>The view wraps one already-sampled column - it cannot change - but the digest was computed in
     * the constructor, and a realisation cell builds roughly 27k of these per compile: 3.5M SHA-256
     * calls and 3.5M 64-character strings over a session, for a value that only the evidence hash (and
     * the surface probe that forwards it) ever reads. Lazy keeps every observable value identical.
     */
    private String hash;

    public PlanningColumnView(int x, int z, ColumnSample sample) {
        this.x = x;
        this.z = z;
        this.sample = sample;
    }

    /** 样本是不可变记录；跨单元复用比较完整证据，不能仅信任摘要或对象地址。 */
    public ColumnSample frozenSample() { return sample; }

    @Override
    public String state(int y) {
        if (y < sample.groundTopY()) return "minecraft:stone{}";
        if (sample.waterTopY().isPresent() && y >= sample.bedTopY().orElse(sample.groundTopY())
                && y < sample.waterTopY().getAsInt()) return "minecraft:water{}";
        return "minecraft:air{}";
    }

    @Override
    public boolean solidSupport(int y) {
        return y < sample.groundTopY();
    }

    @Override
    public boolean protectedAt(int y) {
        return false; // protection is layered by ProtectedColumnView from planning structure boxes
    }

    // 此视图只携带原始精采地形；真实结构区间由外层视图按盒查询。
    @Override public boolean protectedBetween(int from, int to) { return false; }

    @Override
    public boolean water(int y) {
        return sample.waterTopY().isPresent()
                && y >= sample.bedTopY().orElse(sample.groundTopY())
                && y < sample.waterTopY().getAsInt();
    }

    @Override
    public boolean airOrReplaceablePlant(int y) {
        return !solidSupport(y) && !water(y);
    }

    @Override
    public int highestNonPlantFeet() {
        return sample.groundTopY();
    }

    @Override
    public String hash() {
        var cached = hash;
        if (cached != null) return cached;
        return hash = DeterministicHash.sha256((x + ":" + z + ":" + sample.groundTopY() + ":"
                + sample.waterTopY() + ":" + sample.bedTopY()).getBytes(StandardCharsets.UTF_8));
    }
}
