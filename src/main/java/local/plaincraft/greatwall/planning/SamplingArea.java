package local.plaincraft.greatwall.planning;

import java.util.*;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.Bounds;

/** 按矩形并集统计唯一地形列，不探测地形，也不为每个方块建立第二份集合。 */
final class SamplingArea {
    private record Event(int x, int from, int to, int delta) {}
    private final List<Bounds> rectangles = new ArrayList<>();

    void add(ImportedSamplePath path) {
        for (var p : path.points()) {
            int x = FixedMath.floorBlock(p.xQ8()), z = FixedMath.floorBlock(p.zQ8());
            int r = ImportedSamplingFootprint.RADIUS;
            rectangles.add(new Bounds(x-r, 0, z-r, x+r+1, 1, z+r+1));
        }
        path.footprint().byStation().values().forEach(rectangles::addAll);
    }

    int finish() {
        int result = Math.toIntExact(count(rectangles));
        rectangles.clear();
        return result;
    }

    /** 扫描线配合区间覆盖树：计数成本只随占地矩形数量增长，不随采样列面积增长。 */
    static long count(List<Bounds> boxes) {
        if (boxes.isEmpty()) return 0;
        var events = new ArrayList<Event>(boxes.size()*2);
        int[] coordinates = new int[boxes.size()*2];
        int n = 0;
        for (var b : boxes) {
            events.add(new Event(b.minX(), b.minZ(), b.maxZExclusive(), 1));
            events.add(new Event(b.maxXExclusive(), b.minZ(), b.maxZExclusive(), -1));
            coordinates[n++] = b.minZ(); coordinates[n++] = b.maxZExclusive();
        }
        coordinates = Arrays.stream(coordinates).distinct().sorted().toArray();
        events.sort(Comparator.comparingInt(Event::x));
        var tree = new Coverage(coordinates);
        long area = 0;
        int previous = events.getFirst().x();
        for (var e : events) {
            area += ((long)e.x()-previous)*tree.length[1];
            tree.update(1, 0, coordinates.length-1, Arrays.binarySearch(coordinates,e.from()),
                    Arrays.binarySearch(coordinates,e.to()), e.delta());
            previous = e.x();
        }
        return area;
    }

    private static final class Coverage {
        final int[] coordinates, covers;
        final long[] length;
        Coverage(int[] coordinates) {
            this.coordinates = coordinates;
            covers = new int[coordinates.length*4]; length = new long[covers.length];
        }
        void update(int node, int left, int right, int from, int to, int delta) {
            if (from <= left && right <= to) covers[node] += delta;
            else {
                int mid = (left+right) >>> 1;
                if (from < mid) update(node*2,left,mid,from,to,delta);
                if (to > mid) update(node*2+1,mid,right,from,to,delta);
            }
            length[node] = covers[node] > 0 ? (long)coordinates[right]-coordinates[left]
                    : right-left == 1 ? 0 : length[node*2]+length[node*2+1];
        }
    }
}
