package local.plaincraft.greatwall.component;

import java.util.List;
import java.util.Objects;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;

/** 模板接口位于用户预留的墙节末列；沿接口外向的第 1 格才属于新城墙。 */
public final class ImportedWallPorts {
    private static final List<Port> GATE = opposing(112, 19);
    private static final List<Port> ENEMY = opposing(70, 13);
    private static final List<Port> BEACON = opposing(50, 5);
    private static final List<Port> CORNER = List.of(new Port(new Point3i(7, 15, 0), Direction.NORTH, Direction.WEST),
            new Port(new Point3i(31, 15, 22), Direction.EAST, Direction.SOUTH));
    private static final List<Port> CHENGHAI = List.of(new Port(new Point3i(46, 15, 0), Direction.NORTH, Direction.EAST));

    private ImportedWallPorts() {}

    public static List<Port> of(Asset asset) {
        return switch (asset) {
            case GATE -> GATE;
            case ENEMY_TOWER -> ENEMY;
            case BEACON -> BEACON;
            case CORNER_TOWER -> CORNER;
            case CHENGHAI_TOWER -> CHENGHAI;
        };
    }

    private static List<Port> opposing(int eastX, int z) {
        // 左右端都保留同一条外侧垛墙，所以横轴不能由出墙方向推导。
        return List.of(new Port(new Point3i(0, 15, z), Direction.WEST, Direction.SOUTH),
                new Port(new Point3i(eastX, 15, z), Direction.EAST, Direction.SOUTH));
    }

    public record Port(Point3i feet, Direction outward, Direction merlonSide) {
        public Port {
            Objects.requireNonNull(feet);
            // 拒绝歪接或竖接，否则墙身与接口断面不再正交。
            if (!outward.getAxis().isHorizontal() || !merlonSide.getAxis().isHorizontal()
                    || outward.getAxis() == merlonSide.getAxis()) {
                throw new IllegalArgumentException("墙端外向和垛口侧必须是互相垂直的水平轴");
            }
        }

        public Port place(Point3i localAnchor, Point3i worldAnchor, Rotation rotation) {
            return new Port(ImportedWallPorts.place(feet, localAnchor, worldAnchor, rotation),
                    rotation.rotate(outward), rotation.rotate(merlonSide));
        }

        public Point3i cell(int step, int lateral, int dy) {
            // step=0 是原模板末列；lateral>0 永远指向垛口侧。
            return new Point3i(Math.addExact(feet.x(), outward.getStepX() * step + merlonSide.getStepX() * lateral),
                    Math.addExact(feet.y(), dy),
                    Math.addExact(feet.z(), outward.getStepZ() * step + merlonSide.getStepZ() * lateral));
        }
    }

    static Point3i place(Point3i local, Point3i localAnchor, Point3i worldAnchor, Rotation rotation) {
        // 本体、端面与方块实体使用同一个整数变换，避免四分之一圈时分离。
        var p = ImportedWallTemplate.rotateOffset(new Point3i(local.x() - localAnchor.x(),
                local.y() - localAnchor.y(), local.z() - localAnchor.z()), rotation);
        return new Point3i(Math.addExact(worldAnchor.x(), p.x()), Math.addExact(worldAnchor.y(), p.y()),
                Math.addExact(worldAnchor.z(), p.z()));
    }
}
