package local.plaincraft.greatwall.planning;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.Point3i;

/**
 * Planning-time column provider: turns the fine cross-section samples into the
 * {@code Map<Point3i, SurfaceProbe.ColumnView>} the realization stage consumes, with no per-block
 * terrain dependency. Each sampled column becomes a {@link PlanningColumnView}; structure protection
 * is layered on top by the caller from the already-resolved structure boxes.
 */
public final class PlanningColumnProvider {
    private final FineHeightIndex index;

    public PlanningColumnProvider(FineHeightIndex index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    /** All sampled columns that fall inside any of the given owner chunks. */
    public Map<Point3i, SurfaceProbe.ColumnView> columnsFor(Collection<ChunkKey> owners) {
        var result = new HashMap<Point3i, SurfaceProbe.ColumnView>();
        for (var owner : owners) {
            int minX = owner.x() * 16, minZ = owner.z() * 16;
            int maxX = minX + 15, maxZ = minZ + 15;
            // FineHeightIndex is keyed by (x,z); iterate the sampled columns and filter by the
            // owner's block bounds. The index is small (a corridor, not the whole world), so this is
            // cheap and keeps the provider independent of the index's internal layout.
            for (var column : index.columns()) {
                int x = column.x(), z = column.z();
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                    result.putIfAbsent(new Point3i(x, 0, z), new PlanningColumnView(x, z, column.sample()));
                }
            }
        }
        return Map.copyOf(result);
    }

    public FineHeightIndex index() {
        return index;
    }
}
