package local.plaincraft.greatwall.terrain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 在完整模板变成区块计划之前查最终落点，屋顶和地基也不能逃过结构保护。 */
final class ImportedConstructionGuard {
    private ImportedConstructionGuard() {}

    static ComponentVoxels validate(ComponentVoxels value, RealizationPlanner.Input input, DimensionBounds limits) {
        var columns = new HashMap<Point3i, SurfaceProbe.ColumnView>();
        for (var voxel : value.voxels()) {
            var p = voxel.pos();
            if (p.y() < limits.minY() || p.y() >= limits.maxYExclusive()
                    || Math.abs((long) p.x()) > limits.horizontalLimit() || Math.abs((long) p.z()) > limits.horizontalLimit())
                throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "实物终态越界: " + value.id() + " " + p);
            var key = new Point3i(p.x(), 0, p.z());
            var column = columns.computeIfAbsent(key, ignored -> {
                var raw = input.columns().get(key);
                if (raw == null) throw new WallFailure(FailureCode.T0_MISSING, "实物终态缺列: " + value.id() + " " + key);
                return ProtectedColumnView.wrap(raw, key, input.protectedBounds());
            });
            if (column.protectedAt(p.y())) throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT,
                    "实物终态触碰保护: " + value.id() + " " + p);
            if (voxel.kind() == IntentKind.OWNED_VOID && !column.airOrReplaceablePlant(p.y()))
                throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT, "实物净空不能挖自然实心: " + value.id() + " " + p);
        }
        return value;
    }
}
