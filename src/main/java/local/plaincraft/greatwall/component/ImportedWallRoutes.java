package local.plaincraft.greatwall.component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 从真实施工脚点证明贯通，不能用“没有声明路线”绕过马道完整性检查。 */
final class ImportedWallRoutes {
    private ImportedWallRoutes() {}

    static List<List<Point3i>> prove(List<VoxelIntent> voxels, List<Station> stations, List<ArcInterval> intervals) {
        var feet = new HashSet<Point3i>();
        for (var voxel : voxels) if (voxel.walkable())
            feet.add(new Point3i(voxel.pos().x(), voxel.pos().y() + 1, voxel.pos().z()));
        var result = new ArrayList<List<Point3i>>();
        for (var interval : intervals) {
            if (interval.toQ8() - interval.fromQ8() < 3L * 256) continue;
            var a = ImportedRibbonSection.at(stations, interval.fromQ8() + 256);
            var b = ImportedRibbonSection.at(stations, interval.toQ8() - 256);
            var from = nearest(feet, a); var to = nearest(feet, b);
            if (from == null || to == null) throw fail("马道端点缺少真实底板: " + interval);
            var parent = new HashMap<Point3i, Point3i>();
            var pending = new ArrayDeque<Point3i>();
            parent.put(from, from); pending.add(from);
            while (!pending.isEmpty() && !parent.containsKey(to)) {
                var p = pending.removeFirst();
                for (int[] dir : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) for (int dy = -1; dy <= 1; dy++) {
                    var next = new Point3i(p.x() + dir[0], p.y() + dy, p.z() + dir[1]);
                    if (feet.contains(next) && parent.putIfAbsent(next, p) == null) pending.addLast(next);
                }
            }
            if (!parent.containsKey(to)) throw fail("最终马道不贯通: " + interval);
            var route = new ArrayList<Point3i>();
            for (var p = to; !p.equals(from); p = parent.get(p)) route.add(p);
            route.add(from); Collections.reverse(route); result.add(List.copyOf(route));
        }
        return List.copyOf(result);
    }

    private static Point3i nearest(Set<Point3i> feet, Station station) {
        return feet.stream().filter(p -> Math.abs(p.y() - station.walkY()) <= 1 && distance(p, station.point()) <= 640L * 640)
                .min(java.util.Comparator.<Point3i>comparingLong(p -> distance(p, station.point()))
                        .thenComparingInt(Point3i::y).thenComparingInt(Point3i::z).thenComparingInt(Point3i::x)).orElse(null);
    }

    private static long distance(Point3i p, Point2q target) {
        long dx = p.x() * 256L + 128 - target.xQ8(), dz = p.z() * 256L + 128 - target.zQ8();
        return dx * dx + dz * dz;
    }

    private static WallFailure fail(String reason) { return new WallFailure(FailureCode.OWNERSHIP_CONFLICT, reason); }
}
