package local.plaincraft.greatwall.generation;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import local.plaincraft.greatwall.model.WallTypes.Bounds;
import local.plaincraft.greatwall.model.WallTypes.PlanWindow;

/** 一份冻结走廊供多个区块共享；树冠相交和群系距离都保留原来的 XZ 口径。 */
public final class CorridorIndex {
    // 小叶直接扫描，避免每个保护盒都分配一个额外节点。
    private static final int LEAF_SIZE = 8;
    private final String dimension;
    private final List<Bounds> boxes;
    private final Bounds[] spatialOrder;
    private final Node root;

    public static CorridorIndex prepare(PlanWindow plans, String dimension) {
        return new CorridorIndex(dimension, CorridorBoxes.hard(plans, dimension));
    }

    /** 启动只建一份走廊索引，容量按段限制；单区块旧入口仍保留原窗口上限。 */
    public static CorridorIndex prepareStartupWindow(PlanWindow plans, String dimension) {
        return new CorridorIndex(dimension, CorridorBoxes.hardForIndex(plans, dimension));
    }

    CorridorIndex(String dimension, List<Bounds> boxes) {
        this.dimension = Objects.requireNonNull(dimension);
        this.boxes = List.copyOf(boxes);
        spatialOrder = this.boxes.toArray(Bounds[]::new);
        root = spatialOrder.length == 0 ? null : build(0, spatialOrder.length);
    }

    public String dimension() { return dimension; }
    public List<Bounds> boxes() { return boxes; }
    Bounds envelope() { return root == null ? null : root.envelope(); }

    public boolean intersects(Bounds feature) {
        return root != null && intersects(root, feature);
    }

    private boolean intersects(Node node, Bounds feature) {
        if (!CorridorBoxes.intersects(node.envelope(), feature)) return false;
        if (node.left() != null) return intersects(node.left(), feature) || intersects(node.right(), feature);
        // 节点包围盒会跨过走廊空洞，命中节点还要核对真实盒。
        for (int i = node.from(); i < node.to(); i++) {
            if (CorridorBoxes.intersects(spatialOrder[i], feature)) return true;
        }
        return false;
    }

    public long distance(int x, int z) {
        return root == null ? Long.MAX_VALUE : distance(root, x, z, Long.MAX_VALUE);
    }

    private long distance(Node node, int x, int z, long best) {
        if (CorridorBoxes.distance(node.envelope(), x, z) >= best) return best;
        if (node.left() == null) {
            for (int i = node.from(); i < node.to() && best > 0; i++) {
                best = Math.min(best, CorridorBoxes.distance(spatialOrder[i], x, z));
            }
            return best;
        }
        Node near = node.left(), far = node.right();
        if (CorridorBoxes.distance(near.envelope(), x, z) > CorridorBoxes.distance(far.envelope(), x, z)) {
            near = node.right();
            far = node.left();
        }
        // 节点距离是子树的下界；先测近侧，尽早排除不可能改善答案的远侧。
        return distance(far, x, z, distance(near, x, z, best));
    }

    private Node build(int from, int to) {
        var first = spatialOrder[from];
        int minX = first.minX(), maxX = first.maxXExclusive();
        int minZ = first.minZ(), maxZ = first.maxZExclusive();
        for (int i = from + 1; i < to; i++) {
            var box = spatialOrder[i];
            minX = Math.min(minX, box.minX()); maxX = Math.max(maxX, box.maxXExclusive());
            minZ = Math.min(minZ, box.minZ()); maxZ = Math.max(maxZ, box.maxZExclusive());
        }
        var envelope = new Bounds(minX, Integer.MIN_VALUE, minZ, maxX, Integer.MAX_VALUE, maxZ);
        if (to - from <= LEAF_SIZE) return new Node(envelope, from, to, null, null);
        // 沿较长轴按盒中心分半，折返路线也能按空间剪枝；排序不会改变公开盒的顺序。
        boolean splitX = (long) maxX - minX >= (long) maxZ - minZ;
        Arrays.sort(spatialOrder, from, to, Comparator.comparingLong(box -> splitX
                ? (long) box.minX() + box.maxXExclusive() : (long) box.minZ() + box.maxZExclusive()));
        int mid = (from + to) >>> 1;
        return new Node(envelope, from, to, build(from, mid), build(mid, to));
    }

    private record Node(Bounds envelope, int from, int to, Node left, Node right) { }
}
