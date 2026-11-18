package local.plaincraft.greatwall.component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.world.level.block.Rotation;

/** 编译门楼本体；站点接入方还需证明地基、引路和墙端的地形条件。 */
public final class ImportedGateBody {
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", java.util.Map.of());

    private ImportedGateBody() {}

    public static ComponentVoxels compile(SiteId site, Point3i wallFeet, Rotation rotation, long worldSeed) {
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
        var bounds = new Bounds(Math.min(a.x(), b.x()), a.y(), Math.min(a.z(), b.z()),
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
