package local.plaincraft.greatwall.planning;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.FoundationFloorSnapshot;
import local.plaincraft.greatwall.terrain.RealizationPlanner;

/** 冻结整条路线的裁决；局部施工不得从裁切后的采样重新推导这份上下文。 */
final class PlanningCompileContext {
    private final List<Bounds> structures;
    private final String protectionHash;
    private final DimensionBounds bounds;
    private final Map<Long, Integer> foundationFloor;
    private final FoundationSectionIndex foundationSections;

    PlanningCompileContext(FineHeightIndex index, List<Bounds> structures) {
        this.structures = List.copyOf(structures);
        protectionHash = DeterministicHash.sha256(this.structures.stream().map(Bounds::toString).sorted()
                .toList().toString().getBytes(StandardCharsets.UTF_8));
        // 覆盖范围参与计划哈希；按局部高度极值重算会改变同一区块的覆盖版本。
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (var column : index.columns()) {
            minY = Math.min(minY, column.sample().groundTopY() - 64);
            maxY = Math.max(maxY, column.sample().groundTopY() + 64);
        }
        bounds = minY == Integer.MAX_VALUE ? new DimensionBounds(-64, 320, 29_999_000)
                : new DimensionBounds(minY, maxY, 29_999_000);

        // 实物墙和楼按本列地表填基；附件列不能混入旧横截面的最高点裁决。
        var sections = index.exact() ? List.<WallFoundationPlanner.ColumnFoundation>of()
                : WallFoundationPlanner.plan(index.orderedColumns());
        foundationSections = new FoundationSectionIndex(sections);
        var floor = new HashMap<Long, Integer>();
        for (var section : sections) for (var fill : section.fills()) {
            // 弧长截面与世界坐标是两套索引；全局最后一次填方决定重叠列的承重值。
            floor.put(((long) fill.x() << 32) ^ (fill.z() & 0xffffffffL), fill.surfaceY());
        }
        foundationFloor = FoundationFloorSnapshot.copyOf(floor);
        GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_FOUNDATION_PLAN cores={} fillColumns={}",
                sections.size(), sections.stream().mapToLong(section -> section.fills().size()).sum());
    }

    DimensionBounds bounds() { return bounds; }

    RealizationPlanner.Input input(PlanningOwnerIndex.CellWork work,
            Map<Point3i, SurfaceProbe.ColumnView> columns) {
        // 地基模板只用本单元的截面，世界坐标承重仍查整窗的裁决。
        return new RealizationPlanner.Input(work.cell(), work.segmentWindow(), Map.of(), columns,
                structures, Map.of(), 0, protectionHash, foundationFloor, foundationSections.forCell(work.cell().arcCell()));
    }
}
