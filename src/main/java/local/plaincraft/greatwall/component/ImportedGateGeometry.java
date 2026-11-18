package local.plaincraft.greatwall.component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import local.plaincraft.greatwall.diagnostics.WalkwayCollisionCheck;
import local.plaincraft.greatwall.model.WallTypes.BlockSpec;
import local.plaincraft.greatwall.model.WallTypes.Bounds;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 门楼实物的两层通行契约；模板空气只有被明确声明的范围才能变成施工净空。 */
public final class ImportedGateGeometry {
    private static final Point3i SIZE = ImportedWallLayout.GATE_SIZE;
    private static final Point3i WALK_ANCHOR = ImportedWallLayout.GATE_ANCHOR;
    private static final Bounds ROAD_VOID = new Bounds(54, 0, 0, 59, 5, 32);
    private static final List<Point3i> STEPS = List.of(new Point3i(1, 0, 0), new Point3i(0, 0, 1),
            new Point3i(-1, 0, 0), new Point3i(0, 0, -1));

    private final List<Point3i> walkRoute;

    private ImportedGateGeometry(List<Point3i> walkRoute) {
        this.walkRoute = List.copyOf(walkRoute);
    }

    private static class Holder {
        // 同一模板只做一次碰撞取证，避免每个门楼站点或区块重复搜路。
        private static final ImportedGateGeometry INSTANCE = inspect(ImportedWallTemplate.Asset.GATE.template());
    }

    public static ImportedGateGeometry gate() { return Holder.INSTANCE; }

    public List<Point3i> walkRoute() { return walkRoute; }
    public Bounds roadVoid() { return ROAD_VOID; }

    public Point3i place(Point3i local, Point3i wallFeet, Rotation rotation) {
        // 对齐行走的脚底，不对齐石英带的方块层；平移和转向对净空、端点、实体统一生效。
        return ImportedWallPorts.place(local, WALK_ANCHOR, wallFeet, rotation);
    }

    private static ImportedGateGeometry inspect(ImportedWallTemplate template) {
        if (!template.size().equals(SIZE)) throw new IllegalStateException("门楼素材尺寸已变，须重验墙端和门洞");
        var solids = new HashMap<Point3i, BlockSpec>();
        for (var cell : template.cells()) {
            var p = cell.local();
            if (p.x() >= ROAD_VOID.minX() && p.x() < ROAD_VOID.maxXExclusive()
                    && p.y() >= ROAD_VOID.minY() && p.y() < ROAD_VOID.maxYExclusive()
                    && p.z() >= ROAD_VOID.minZ() && p.z() < ROAD_VOID.maxZExclusive()) {
                throw new IllegalStateException("门楼声明的道路净空撞入模板实心: " + p);
            }
            // 只索引马道脚底、脚和头三层；整个模板无需重复建立空间表。
            if (Math.abs(p.y() - WALK_ANCHOR.y()) <= 1) solids.put(p, template.state(cell, Rotation.NONE));
        }
        var west = new Point3i(0, WALK_ANCHOR.y(), WALK_ANCHOR.z());
        var east = new Point3i(SIZE.x() - 1, WALK_ANCHOR.y(), WALK_ANCHOR.z());
        var shapes = new HashMap<BlockSpec, VoxelShape>();
        var passable = new boolean[SIZE.x()][SIZE.z()];
        for (int x = 0; x < SIZE.x(); x++) for (int z = 0; z < SIZE.z(); z++) {
            int y = WALK_ANCHOR.y();
            passable[x][z] = WalkwayCollisionCheck.inspect(shape(solids.get(new Point3i(x, y, z)), shapes),
                    shape(solids.get(new Point3i(x, y + 1, z)), shapes),
                    shape(solids.get(new Point3i(x, y - 1, z)), shapes)).valid();
        }
        if (!passable[west.x()][west.z()] || !passable[east.x()][east.z()]) {
            throw new IllegalStateException("门楼两侧马道接口没有脚底支撑或头部净空");
        }
        var cameFrom = new HashMap<Point3i, Point3i>();
        cameFrom.put(west, west);
        var queue = new ArrayDeque<Point3i>();
        queue.add(west);
        while (!queue.isEmpty()) {
            var p = queue.removeFirst();
            if (p.equals(east)) {
                var route = new ArrayList<Point3i>();
                for (var at = east; !at.equals(west); at = cameFrom.get(at)) route.add(at);
                route.add(west);
                Collections.reverse(route);
                return new ImportedGateGeometry(route);
            }
            for (var step : STEPS) {
                var next = new Point3i(p.x() + step.x(), p.y(), p.z() + step.z());
                if (next.x() >= 0 && next.x() < SIZE.x() && next.z() >= 0 && next.z() < SIZE.z()
                        && passable[next.x()][next.z()] && cameFrom.putIfAbsent(next, p) == null) queue.add(next);
            }
        }
        throw new IllegalStateException("门楼马道东西端不连通");
    }

    private static VoxelShape shape(BlockSpec spec, HashMap<BlockSpec, VoxelShape> cache) {
        return spec == null ? Shapes.empty() : cache.computeIfAbsent(spec, key -> BlockCatalog.resolveState(key)
                .getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }
}
