package local.plaincraft.greatwall.component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import local.plaincraft.greatwall.component.ImportedWallTemplate.Asset;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.diagnostics.WalkwayCollisionCheck;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 编译原样塔楼及真正的底板，不把稀疏模板中未声明的空气当作挖掘指令。 */
public final class ImportedTowerBody {
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", Map.of());
    private static final List<Direction> STEPS = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    private ImportedTowerBody() {}

    private static final class Profiles {
        // 模板、地基和碰撞证据只取一次，避免每个区块重扫整座塔楼。
        static final Map<Asset, Profile> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    }

    private record Profile(ImportedTemplateFoundation foundation, List<Point3i> route) {}

    public static ComponentVoxels compile(Asset asset, SiteId site, Point3i localAnchor, Point3i worldAnchor,
            Rotation rotation, long seed, DimensionBounds limits, Function<Point3i, SurfaceProbe.ColumnView> columns) {
        if (asset == Asset.GATE) throw new IllegalArgumentException("门楼有独立穿行净空，应使用门楼本体编译器");
        var template = asset.template();
        var profile = Profiles.CACHE.computeIfAbsent(asset, ImportedTowerBody::inspect);
        Function<Point3i, Point3i> place = local -> ImportedWallPorts.place(local, localAnchor, worldAnchor, rotation);
        var size = template.size();
        var a = place.apply(new Point3i(0, 0, 0));
        var b = place.apply(new Point3i(size.x() - 1, size.y() - 1, size.z() - 1));
        if (limits != null && (a.y() < limits.minY() || b.y() >= limits.maxYExclusive())) {
            throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "实物塔楼超出世界施工高度: " + site);
        }
        int priority = ComponentAssembly.priority(site.kind());
        var floor = new HashSet<Point3i>();
        var clearance = new java.util.LinkedHashSet<Point3i>();
        for (var feet : profile.route()) {
            floor.add(atY(feet, -1));
            clearance.add(feet);
            clearance.add(atY(feet, 1));
        }
        CompiledAllocation.charge(512L + 256L * (template.cells().size() + clearance.size()));
        var voxels = new ArrayList<VoxelIntent>(template.cells().size() + clearance.size());
        var entities = new ArrayList<BlockEntityIntent>();
        for (var cell : template.cells()) {
            var p = place.apply(cell.local());
            var walkable = floor.contains(cell.local());
            voxels.add(new VoxelIntent(p, IntentKind.SOLID, walkable ? Part.FLOOR : Part.CORE,
                    template.state(cell, rotation), site, priority, true, walkable));
            // 不清模板自带装饰；同一局部 ID 继续携带确定性战利品与实体 NBT。
            clearance.remove(cell.local());
            if (cell.entity() != null) {
                CompiledAllocation.charge(256);
                entities.add(template.entityIntent(cell, p, seed, site));
            }
        }
        for (var local : clearance) {
            voxels.add(new VoxelIntent(place.apply(local), IntentKind.OWNED_VOID, Part.AIR, AIR, site, 900, true, false));
        }
        int lowest = limits == null ? a.y() : Math.min(a.y(), profile.foundation().append(voxels, site,
                place::apply, rotation, limits, columns));
        var bounds = new Bounds(Math.min(a.x(), b.x()), lowest, Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()) + 1, b.y() + 1, Math.max(a.z(), b.z()) + 1);
        return ComponentAssembly.hashed(site, bounds, voxels, entities, List.of(),
                List.of(profile.route().stream().map(place).toList()));
    }

    private static Profile inspect(Asset asset) {
        var template = asset.template();
        var ports = ImportedWallPorts.of(asset);
        var start = ports.getFirst().feet();
        var end = ports.getLast().feet();
        var shapes = new HashMap<BlockSpec, VoxelShape>();
        var solids = new HashMap<Point3i, VoxelShape>();
        for (var cell : template.cells()) if (Math.abs(cell.local().y() - start.y()) <= 1) {
            var shape = shapes.computeIfAbsent(template.state(cell, Rotation.NONE), spec -> BlockCatalog.resolveState(spec)
                    .getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
            if (!shape.isEmpty()) solids.put(cell.local(), shape);
        }
        if (!passable(start, solids) || !passable(end, solids)) {
            throw new IllegalStateException("模板缺少可走端面: " + asset);
        }
        var from = new HashMap<Point3i, Point3i>();
        var pending = new ArrayDeque<Point3i>();
        from.put(start, start);
        pending.add(start);
        while (!pending.isEmpty()) {
            var p = pending.removeFirst();
            if (p.equals(end)) {
                var route = new ArrayList<Point3i>();
                for (var cur = end; !cur.equals(start); cur = from.get(cur)) route.add(cur);
                route.add(start);
                Collections.reverse(route);
                return new Profile(ImportedTemplateFoundation.inspect(template), List.copyOf(route));
            }
            for (var direction : STEPS) {
                var next = new Point3i(p.x() + direction.getStepX(), p.y(), p.z() + direction.getStepZ());
                if (passable(next, solids) && from.putIfAbsent(next, p) == null) pending.add(next);
            }
        }
        throw new IllegalStateException("模板两端马道没有同层通路: " + asset);
    }

    private static Point3i atY(Point3i p, int delta) { return new Point3i(p.x(), p.y() + delta, p.z()); }

    private static boolean passable(Point3i feet, Map<Point3i, VoxelShape> solids) {
        // 用原版碰撞形状识别支撑与净空，不把半砖和楼梯当整砖。
        return WalkwayCollisionCheck.inspect(solids.getOrDefault(feet, Shapes.empty()),
                solids.getOrDefault(atY(feet, 1), Shapes.empty()), solids.getOrDefault(atY(feet, -1), Shapes.empty())).valid();
    }
}
