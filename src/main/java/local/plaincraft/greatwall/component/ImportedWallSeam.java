package local.plaincraft.greatwall.component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import local.plaincraft.greatwall.component.ImportedWallPorts.Port;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 独立编译端面之外的直接墙列；完整建筑仍由同一构件所有者提交。 */
public final class ImportedWallSeam {
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", Map.of());

    private ImportedWallSeam() {}

    public static ComponentVoxels attach(ComponentVoxels body, List<Port> ports, int length) {
        if (ports.isEmpty() || length < 1 || length > 48) throw new IllegalArgumentException("接墙须有端面且直段在 1..48 格");
        // 不能覆盖建筑或另一个接口。以水平占列检查，连建筑装饰和地基一起保护。
        Set<Point3i> bodyColumns = new HashSet<>(), wallColumns = new HashSet<>();
        for (var v : body.voxels()) bodyColumns.add(new Point3i(v.pos().x(), 0, v.pos().z()));
        var voxels = new ArrayList<>(body.voxels());
        var routes = new ArrayList<>(body.requiredRoutes());
        int priority = ComponentAssembly.priority(body.id().kind());
        Bounds bounds = body.bounds();
        for (var port : ports) {
            var route = new ArrayList<Point3i>();
            route.add(port.feet());
            for (int step = 1; step <= length; step++) {
                route.add(port.cell(step, 0, 0));
                // 模板末列已经是缺，向外第一个新格才是循环的第 0 列。
                int phase = (step - 1) % PairedWallProfile.PERIOD;
                for (int lateral = -PairedWallProfile.HALF; lateral <= PairedWallProfile.HALF; lateral++) {
                    var feet = port.cell(step, lateral, 0);
                    var column = new Point3i(feet.x(), 0, feet.z());
                    if (bodyColumns.contains(column) || !wallColumns.add(column)) {
                        throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT, "配套接墙重复占列: " + column);
                    }
                    for (int y = 0; y < PairedWallProfile.HEIGHT; y++) {
                        var state = PairedWallProfile.at(phase, lateral, y, port.merlonSide());
                        var pos = new Point3i(feet.x(), feet.y() + y - PairedWallProfile.FEET, feet.z());
                        if (state == null) {
                            // 只有马道声明施工净空；垛孔及两侧的空气不变成挖掘指令。
                            if (Math.abs(lateral) < PairedWallProfile.HALF && y >= 15 && y <= 16) {
                                CompiledAllocation.charge(256);
                                voxels.add(new VoxelIntent(pos, IntentKind.OWNED_VOID, Part.AIR, AIR,
                                        body.id(), 900, true, false));
                            }
                            continue;
                        }
                        boolean floor = y == PairedWallProfile.FEET - 1 && Math.abs(lateral) < PairedWallProfile.HALF;
                        CompiledAllocation.charge(256);
                        voxels.add(new VoxelIntent(pos, IntentKind.SOLID, floor ? Part.FLOOR : Part.CORE,
                                state, body.id(), priority, true, floor));
                    }
                    bounds = encompass(bounds, feet, feet.y() - PairedWallProfile.FEET,
                            feet.y() + PairedWallProfile.HEIGHT - PairedWallProfile.FEET);
                }
            }
            routes.add(route);
        }
        return ComponentAssembly.hashed(body.id(), bounds, voxels, body.blockEntities(), body.spawnSlots(), routes);
    }

    private static Bounds encompass(Bounds box, Point3i p, int minY, int maxY) {
        return new Bounds(Math.min(box.minX(), p.x()), Math.min(box.minY(), minY), Math.min(box.minZ(), p.z()),
                Math.max(box.maxXExclusive(), p.x() + 1), Math.max(box.maxYExclusive(), maxY),
                Math.max(box.maxZExclusive(), p.z() + 1));
    }
}
