package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.RealizationCell;

/** 每个冻结单元只遍历一次体素，按区块保存取施工前状态所需的位置。 */
final class CellVoxelOwners {
    private final Map<Long, List<Point3i>> positions = new HashMap<>();
    private long indexedVoxels;

    CellVoxelOwners(RealizationCell cell) {
        for (var component : cell.components()) {
            for (var voxel : component.voxels()) {
                var pos = voxel.pos();
                // 保留原构件/体素顺序和重复项，先遇到的有效状态仍由 putIfAbsent 决定。
                positions.computeIfAbsent(key(Math.floorDiv(pos.x(), 16), Math.floorDiv(pos.z(), 16)),
                        ignored -> new ArrayList<>()).add(pos);
                indexedVoxels++;
            }
        }
    }

    long indexedVoxels() { return indexedVoxels; }

    void collectBefore(ChunkKey owner, Map<Point3i, SurfaceProbe.ColumnView> columns,
            Map<Point3i, String> before) {
        for (var pos : positions.getOrDefault(key(owner.x(), owner.z()), List.of())) {
            var column = columns.get(new Point3i(pos.x(), 0, pos.z()));
            if (column != null) before.putIfAbsent(pos, column.state(pos.y()));
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffff_ffffL);
    }
}
