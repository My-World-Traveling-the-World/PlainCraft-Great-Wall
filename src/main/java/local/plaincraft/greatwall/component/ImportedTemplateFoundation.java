package local.plaincraft.greatwall.component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.generation.FoundationMaterial;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.BlockSpec;
import local.plaincraft.greatwall.model.WallTypes.DimensionBounds;
import local.plaincraft.greatwall.model.WallTypes.FailureCode;
import local.plaincraft.greatwall.model.WallTypes.IntentKind;
import local.plaincraft.greatwall.model.WallTypes.Part;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.SiteId;
import local.plaincraft.greatwall.model.WallTypes.VoxelIntent;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** 从模板全局底层生成地基；只消费施工前的列证据，不在施工时读取或重算地形。 */
record ImportedTemplateFoundation(List<Point3i> bottom, BlockSpec fill) {
    static ImportedTemplateFoundation inspect(ImportedWallTemplate template) {
        int bottomY = template.cells().stream().mapToInt(c -> c.local().y()).min().orElseThrow();
        var counts = new HashMap<BlockSpec, Integer>();
        var columns = new ArrayList<Point3i>();
        for (var cell : template.cells()) {
            if (cell.local().y() != bottomY) continue;
            var state = template.state(cell, Rotation.NONE);
            // 灯光标记不承重。模板的空气已在导入时剔除，拱顶也不在全局底层。
            if (state.id().equals("minecraft:light")) continue;
            columns.add(cell.local());
            counts.merge(state, 1, Integer::sum);
        }
        var ranked = new LinkedHashMap<BlockState, Integer>();
        // 平票时以稳定顺序调用既有材质选择器，避免同一模板因哈希表次序得到不同地基。
        counts.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(BlockSpec::id)
                .thenComparing(s -> new TreeMap<>(s.properties()).toString())))
                .forEach(e -> ranked.put(BlockCatalog.resolveState(e.getKey()), e.getValue()));
        return new ImportedTemplateFoundation(List.copyOf(columns), BlockCatalog.specification(FoundationMaterial.pick(ranked)));
    }

    int append(List<VoxelIntent> output, SiteId site, UnaryOperator<Point3i> place, Rotation rotation,
            DimensionBounds limits, Function<Point3i, SurfaceProbe.ColumnView> columns) {
        var material = BlockCatalog.specification(BlockCatalog.resolveState(fill).rotate(rotation));
        int lowest = Integer.MAX_VALUE;
        for (var local : bottom) {
            var p = place.apply(local);
            var column = columns.apply(new Point3i(p.x(), 0, p.z()));
            if (column == null) throw new WallFailure(FailureCode.T0_MISSING, "模板地基缺少施工前地表: " + p);
            int surface = column.highestNonPlantFeet();
            if (surface >= p.y()) continue;
            if (surface <= limits.minY() || !column.solidSupport(surface - 1)) {
                throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT, "模板地基没有有效承重面: " + p);
            }
            for (int y = p.y() - 1; y >= surface; y--) {
                var target = new Point3i(p.x(), y, p.z());
                if (column.protectedAt(y)) {
                    throw new WallFailure(FailureCode.OWNERSHIP_CONFLICT, "模板地基碰到保护位置: " + target);
                }
                // 旧规则只补实际空气；低洼里的水或其他已有方块不由地基改写。
                if (!column.airOrReplaceablePlant(y) || !isAir(column.state(y))) continue;
                CompiledAllocation.charge(256);
                output.add(new VoxelIntent(target, IntentKind.SOLID, Part.FOUNDATION, material,
                        site, ComponentAssembly.priority(site.kind()), true, false));
                lowest = Math.min(lowest, y);
            }
        }
        return lowest;
    }

    private static boolean isAir(String token) {
        // 列视图有带流体后缀和不带后缀两种，方块名在首个花括号之前。
        int end = token.indexOf('{');
        return switch (end == -1 ? token : token.substring(0, end)) {
            case "minecraft:air", "minecraft:cave_air", "minecraft:void_air" -> true;
            default -> false;
        };
    }
}
