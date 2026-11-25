package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.generation.CoveragePlanner;
import local.plaincraft.greatwall.model.WallTypes.*;

/** 先冻结每个施工区块的完整输入，再允许按区块切窗，避免后一窗覆盖前一窗的半截墙。 */
final class PlanningOwnerIndex {
    record CellWork(CellKey cell, List<ChunkKey> captureOwners, SegmentPlan plan, PlanWindow segmentWindow,
            boolean startsInStationRange) { }

    record OwnerWork(ChunkKey owner, List<CellWork> cells) { }
    private record SourceCell(CellKey cell, long segment) { }

    static List<OwnerWork> prepare(PlanWindow plans, String recipeHash, String dimension, CoveragePlanner coverage) {
        return prepare(plans,recipeHash,dimension,coverage,new CompileCancellation());
    }

    static List<OwnerWork> prepare(PlanWindow plans,String recipeHash,String dimension,CoveragePlanner coverage,
            CompileCancellation cancellation) {
        cancellation.check();
        // 保留整窗原枚举次序：同一段的证据由多个区块共享，同一区块的各单元也按原序裁决。
        var byOwner = new LinkedHashMap<ChunkKey, LinkedHashMap<SourceCell, CellWork>>();
        for (var plan : plans.plans()) {
            cancellation.check();
            try {
                long from = plan.prefix().lengthBeforeQ8();
                long to = from + plan.geometry().localSQ8().getLast();
                // 已冻结的宽水域终点之后没有施工，不能为整条原始候选路线发布几百份空计划。
                if(coverage.imported()&&plan.geometry().terminal().isPresent())
                    to=Math.min(to,from+plan.geometry().terminal().orElseThrow().localSQ8());
                long cellSize = 64L * 256;
                long first = Math.floorDiv(from, cellSize);
                long last = Math.floorDiv(to + cellSize - 1, cellSize);
                var segmentWindow = new PlanWindow(List.of(plan));
                var lookup = coverage.prepareOwners(segmentWindow);
                var stations = plan.stations().stations();
                for (long cell = first; cell <= last; cell++) {
                    cancellation.check();
                    var key = new CellKey(recipeHash, dimension, cell);
                    var captureOwners = lookup.forCell(key);
                    if (captureOwners.isEmpty()) continue;
                    long start = cell * cellSize;
                    boolean inside = stations.size() >= 2 && start >= stations.getFirst().globalSQ8()
                            && start < stations.getLast().globalSQ8();
                    // 取证范围必须随单元完整保留；构件的地基和出口会读到写入区块的外面。
                    var work = new CellWork(key, captureOwners, plan, segmentWindow, inside);
                    for (var owner : captureOwners) {
                        cancellation.check();
                        byOwner.computeIfAbsent(owner, ignored -> new LinkedHashMap<>())
                                .merge(new SourceCell(key,coverage.imported()?plan.geometry().key().index():0),
                                        work, PlanningOwnerIndex::prefer);
                    }
                }
            } catch (RuntimeException | Error failure) {
                cancellation.rethrowCancellation(failure);
                // 尚未得到完整区块集合，无法给丢失段计入失败率；必须阻止残缺枚举冒充完整结案。
                GreatWallMod.LOGGER.error("[GreatWall:S1C] PLANNING_COMPILE_ENUMERATION_FAILED 段归属枚举失败 prefix={} failure={}",
                        plan.prefix().lengthBeforeQ8(), PlanningChunkCompiler.summarize(failure), failure);
                throw failure;
            }
        }
        var result = new ArrayList<OwnerWork>(byOwner.size());
        byOwner.forEach((owner, cells) -> result.add(new OwnerWork(owner, List.copyOf(cells.values()))));
        return List.copyOf(result);
    }

    private static CellWork prefer(CellWork previous, CellWork candidate) {
        // 上一段为空间取证纳入的尾单元不能挡住下一段在同弧长的真实墙体。
        return !previous.startsInStationRange() && candidate.startsInStationRange() ? candidate : previous;
    }
}
