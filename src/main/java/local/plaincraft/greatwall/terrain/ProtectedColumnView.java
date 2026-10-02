package local.plaincraft.greatwall.terrain;

import java.util.List;
import java.util.function.Function;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.Bounds;
import local.plaincraft.greatwall.model.WallTypes.Point3i;

/** XZ membership is constant for a column; only the certified Y interval changes. */
final class ProtectedColumnView {
    private ProtectedColumnView() {}

    static <T> T use(SurfaceProbe.ColumnView original, Point3i pos, List<Bounds> boxes,
            Function<SurfaceProbe.ColumnView, T> action) {
        long scratch = Math.addExact(256, Math.multiplyExact(64L, boxes.size()));
        CompiledAllocation.charge(scratch);
        try { return action.apply(wrap(original, pos, boxes)); }
        finally { CompiledAllocation.discard(scratch); }
    }

    static SurfaceProbe.ColumnView wrap(SurfaceProbe.ColumnView original, Point3i pos, List<Bounds> boxes) {
        List<Bounds> local = boxes.stream().filter(b -> pos.x() >= b.minX() && pos.x() < b.maxXExclusive()
                && pos.z() >= b.minZ() && pos.z() < b.maxZExclusive()).distinct().toList();
        return new SurfaceProbe.ColumnView() {
            public String state(int y) { return original.state(y); }
            public boolean solidSupport(int y) { return original.solidSupport(y); }
            public boolean water(int y) { return original.water(y); }
            public boolean airOrReplaceablePlant(int y) { return original.airOrReplaceablePlant(y); }
            public int highestNonPlantFeet() { return original.highestNonPlantFeet(); }
            public String hash() { return original.hash(); }
            public boolean naturalThinIce(int y) { return original.naturalThinIce(y) && !protectedAt(y); }
            public boolean protectedAt(int y) {
                if (original.protectedAt(y)) return true;
                for (Bounds box : local) if (y >= box.minY() && y < box.maxYExclusive()) return true;
                return false;
            }
        };
    }
}
