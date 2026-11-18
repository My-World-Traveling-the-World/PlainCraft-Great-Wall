package local.plaincraft.greatwall.component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.world.level.block.Rotation;

/** 编译门楼本体及底板下填；站点负责冻结标高并验证道路和两侧墙的接引。 */
public final class ImportedGateBody {
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", java.util.Map.of());

    private ImportedGateBody() {}

    private static final class Foundation {
        // 全局底层只扫一次，后续门楼站点复用同一块底板和填料。
        private static final ImportedTemplateFoundation PROFILE =
                ImportedTemplateFoundation.inspect(ImportedWallTemplate.Asset.GATE.template());
    }

    public static ComponentVoxels compile(SiteId site, Point3i wallFeet, Rotation rotation, long worldSeed) {
        // 离线核对模板本体时没有地形，不假造平地证据把它当作施工结果。
        return compileInternal(site, wallFeet, rotation, worldSeed, null, null);
    }

    public static ComponentVoxels compileOnTerrain(SiteId site, Point3i wallFeet, Rotation rotation, long worldSeed,
            DimensionBounds limits, Function<Point3i, SurfaceProbe.ColumnView> columns) {
        return compileInternal(site, wallFeet, rotation, worldSeed,
                java.util.Objects.requireNonNull(limits), java.util.Objects.requireNonNull(columns));
    }

    private static ComponentVoxels compileInternal(SiteId site, Point3i wallFeet, Rotation rotation, long worldSeed,
            DimensionBounds limits, Function<Point3i, SurfaceProbe.ColumnView> columns) {
        if (site.kind() != SiteKind.GATE) throw new IllegalArgumentException("门楼本体只接受 GATE 站点");
        var geometry = ImportedGateGeometry.gate();
        var template = ImportedWallTemplate.Asset.GATE.template();
        var walk = geometry.walkRoute();
        var road = geometry.roadVoid();
        int roadVolume = (road.maxXExclusive() - road.minX()) * (road.maxYExclusive() - road.minY())
                * (road.maxZExclusive() - road.minZ());
        CompiledAllocation.charge(512L + 256L * (template.cells().size() + roadVolume + 2 * walk.size()));
        var voxels = new ArrayList<VoxelIntent>(template.cells().size() + roadVolume + 2 * walk.size());
        var entities = new ArrayList<BlockEntityIntent>();
        var floor = new HashSet<Point3i>();
        var clear = new HashSet<Point3i>();
        for (var feet : walk) {
            floor.add(new Point3i(feet.x(), feet.y() - 1, feet.z()));
            clear.add(feet);
            clear.add(new Point3i(feet.x(), feet.y() + 1, feet.z()));
        }
        int priority = ComponentAssembly.priority(site.kind());
        for (var cell : template.cells()) {
            var local = cell.local();
            var pos = geometry.place(local, wallFeet, rotation);
            boolean walkable = floor.contains(local);
            voxels.add(new VoxelIntent(pos, IntentKind.SOLID, walkable ? Part.FLOOR : Part.CORE,
                    template.state(cell, rotation), site, priority, true, walkable));
            // 保留已验证可以通行的装饰方块；净空只补模板里没有实物的位置。
            clear.remove(local);
            if (cell.entity() != null) {
                CompiledAllocation.charge(256);
                entities.add(template.entityIntent(cell, pos, worldSeed, site));
            }
        }
        for (int y = road.minY(); y < road.maxYExclusive(); y++) {
            for (int z = road.minZ(); z < road.maxZExclusive(); z++) {
                for (int x = road.minX(); x < road.maxXExclusive(); x++) clear.add(new Point3i(x, y, z));
            }
        }
        addClearance(voxels, clear, geometry, site, wallFeet, rotation);
        var size = template.size();
        var a = geometry.place(new Point3i(0, 0, 0), wallFeet, rotation);
        var b = geometry.place(new Point3i(size.x() - 1, size.y() - 1, size.z() - 1), wallFeet, rotation);
        int base = a.y();
        if (limits != null) {
            if (a.y() < limits.minY() || b.y() >= limits.maxYExclusive()) {
                throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "门楼超出世界施工高度: " + a.y() + ".." + b.y());
            }
            // 只延长真底板的列；把地下的实际落块也纳入构件包络及后续区块证明。
            base = Math.min(base, Foundation.PROFILE.append(voxels, site, p -> geometry.place(p, wallFeet, rotation),
                    rotation, limits, columns));
        }
        var bounds = new Bounds(Math.min(a.x(), b.x()), base, Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()) + 1, b.y() + 1, Math.max(a.z(), b.z()) + 1);
        var routes = List.of(walk.stream().map(p -> geometry.place(p, wallFeet, rotation)).toList());
        return ComponentAssembly.hashed(site, bounds, voxels, entities, List.of(), routes);
    }

    private static void addClearance(List<VoxelIntent> voxels, Set<Point3i> clear, ImportedGateGeometry geometry,
            SiteId site, Point3i wallFeet, Rotation rotation) {
        // 只认两条通道的明确净空；外观空白不能变成挖地指令。排序使构件摘要不依赖哈希表迭代。
        clear.stream().sorted(java.util.Comparator.comparingInt(Point3i::y).thenComparingInt(Point3i::z)
                .thenComparingInt(Point3i::x)).forEach(local -> voxels.add(new VoxelIntent(
                        geometry.place(local, wallFeet, rotation), IntentKind.OWNED_VOID, Part.AIR,
                        AIR, site, 900, true, false)));
    }
}
