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
    private final String hash;

    public PlanningColumnView(int x, int z, ColumnSample sample) {
        this.x = x;
        this.z = z;
        this.sample = sample;
        this.hash = DeterministicHash.sha256((x + ":" + z + ":" + sample.groundTopY() + ":"
                + sample.waterTopY() + ":" + sample.bedTopY()).getBytes(StandardCharsets.UTF_8));
    }

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
        return hash;
    }
}
