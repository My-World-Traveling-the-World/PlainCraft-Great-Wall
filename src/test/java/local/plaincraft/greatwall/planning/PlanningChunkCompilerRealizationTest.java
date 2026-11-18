package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import local.plaincraft.greatwall.component.S1TemplateSet;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.CoveragePlanner;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.terrain.RealizationPlanner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanningChunkCompilerRealizationTest {
    private static final String DIM = "minecraft:overworld";
    private static final String HASH = "a".repeat(64);
    private static final int SEAM = 8;
    private static final int WALK_Y = 84;

    @Test void completeOwnerKeepsBothRealWallHalvesAcrossCompileBatches() {
        // 段界落在区块中间：若编译只保留一个段，区块仍存在，但另一半墙会消失。
        var window = new PlanWindow(List.of(segment(0), segment(1)));
        var samples = samples();
        var whole = compile(window, samples, Integer.MAX_VALUE, 1);
        var batched = compile(window, samples, 3, 2);
        var seam = new ChunkKey(DIM, 0, 0);
        var witness = whole.get(seam);
        assertNotNull(witness, "接缝区块必须确实产出施工计划");
        assertTrue(witness.voxels().stream().anyMatch(v -> v.pos().x() == SEAM - 1 && v.component().kind() == SiteKind.WALL));
        assertTrue(witness.voxels().stream().anyMatch(v -> v.pos().x() == SEAM && v.component().kind() == SiteKind.WALL));
        assertTrue(witness.voxels().stream().map(VoxelIntent::component).distinct().count() >= 2, "两侧必须来自不同墙体单元");
        assertTrue(witness.voxels().stream().anyMatch(v -> v.pos().equals(new Point3i(SEAM, 54, 5))
                && v.part() == Part.FOUNDATION), "接缝下一单元必须保留弧长边界上的深填土");
        // 用旧整窗截面输入生成的完整计划锁住地基、墙体、覆盖和施工前证明。
        assertEquals("136663182adf32f52642d84127d1cbc0523de11aab237a3c819fdb4affd0193f", witness.hash());
        assertEquals(witness.voxels().size(), witness.proofs().size());
        assertTrue(whole.values().stream().filter(plan -> !plan.voxels().isEmpty()).count() > 3, "确实跨越多个批次");
        assertEquals(whole.keySet(), batched.keySet());
        whole.forEach((key, expected) -> assertArrayEquals(CanonicalCodec.writePlan(expected),
                CanonicalCodec.writePlan(batched.get(key)), key.toString()));
    }

    @Test void independentOwnerWindowsPreserveTheRealSeamAndIsolateFailedOwners() {
        var plans = new PlanWindow(List.of(segment(0), segment(1)));
        var heights = samples();
        var healthy = compile(plans, heights, 64, 2);
        var seam = new ChunkKey(DIM, 0, 0);
        var work = PlanningOwnerIndex.prepare(plans, HASH, DIM, new CoveragePlanner());
        var seamWork = work.stream().filter(owner -> owner.owner().equals(seam)).findFirst().orElseThrow();
        assertEquals(List.of(0L, 1L), seamWork.cells().stream().map(cell -> cell.plan().geometry().key().index())
                .distinct().toList(), "区块工作项必须含接缝两侧的段");
        assertEquals(healthy.keySet(), new HashSet<>(work.stream().map(PlanningOwnerIndex.OwnerWork::owner).toList()));

        var split = compilePartitions(work, heights, false);
        assertEquivalent(healthy, split);
        var witness = split.get(seam);
        assertNotNull(witness);
        assertTrue(witness.voxels().stream().anyMatch(v -> v.pos().x() == SEAM - 1 && v.component().kind() == SiteKind.WALL));
        assertTrue(witness.voxels().stream().anyMatch(v -> v.pos().x() == SEAM && v.component().kind() == SiteKind.WALL));

        var wholeWithFailure = compiler(heights, true).compileWindow(plans, HASH, DIM, null, 64, 2);
        var splitWithFailure = compilePartitions(work, heights, true);
        var expectedFailures = new HashSet<>(healthy.keySet());
        expectedFailures.removeAll(wholeWithFailure.keySet());
        // 取证跨度超过一个单元，左端缺证据可让接缝拒绝，但不能影响远端的完整墙体。
        assertTrue(expectedFailures.contains(seam));
        var surviving = wholeWithFailure.get(new ChunkKey(DIM, 6, 0));
        assertNotNull(surviving);
        assertTrue(surviving.voxels().stream().anyMatch(v -> v.component().kind() == SiteKind.WALL));
        var failed = new HashSet<>(healthy.keySet());
        failed.removeAll(splitWithFailure.keySet());
        assertEquals(expectedFailures, failed);
        assertEquivalent(wholeWithFailure, splitWithFailure);
    }

    private static Map<ChunkKey, ChunkWritePlan> compilePartitions(List<PlanningOwnerIndex.OwnerWork> work,
            FineHeightIndex heights, boolean failLeft) {
        var split = new HashMap<ChunkKey, ChunkWritePlan>();
        int windowSize = 7;
        assertTrue(work.size() > windowSize * 2 && work.size() % windowSize != 0, "须跨窗且经过不满尾窗");
        for (int start = 0; start < work.size(); start += windowSize) {
            var window = work.subList(start, Math.min(start + windowSize, work.size()));
            var allowed = new HashSet<>(window.stream().map(PlanningOwnerIndex.OwnerWork::owner).toList());
            // 每窗新建真实构件实现器，不能靠上一窗缓存命中凑出相同结果。
            compiler(heights, failLeft).compileOwners(window, HASH, null, 3, 2, plan -> {
                assertTrue(allowed.remove(plan.owner()), "只能交付当前窗的区块一次");
                assertNull(split.put(plan.owner(), plan), "每个区块跨窗只能发布一次");
            });
        }
        return split;
    }

    private static void assertEquivalent(Map<ChunkKey, ChunkWritePlan> whole, Map<ChunkKey, ChunkWritePlan> split) {
        assertEquals(whole.keySet(), split.keySet());
        whole.forEach((owner, expected) -> {
            var actual = split.get(owner);
            assertEquals(expected.metadata().coverageHash(), actual.metadata().coverageHash(), owner.toString());
            assertArrayEquals(CanonicalCodec.writePlan(expected), CanonicalCodec.writePlan(actual), owner.toString());
        });
    }

    @Test void overlappingSamplesKeepDifferentRulesForEvidenceAndFoundation() {
        var work = new PlanWindow(List.of(segment(0), segment(1)));
        var original = samples().orderedColumns();
        var columns = new ArrayList<>(original);
        // 后一截面折回同一 XZ；坐标择高保留 54，地基仍按后一个弧长采用 53。
        var flat = original.getFirst().sample();
        var low = new ColumnSample(53, OptionalInt.empty(), OptionalInt.empty(), "minecraft:plains", Quality.REFERENCE, false);
        long arc = 192L * 256;
        columns.add(new WallFinePlanner.FineColumn(SEAM, 0, 0, flat, arc, SEAM, 0));
        columns.add(new WallFinePlanner.FineColumn(SEAM, 5, 5, low, arc, SEAM, 0));
        var heights = FineHeightIndex.of(columns);
        assertEquals(54, heights.at(SEAM, 5).groundTopY());
        var resolver = realization();
        int[] visited = {0};
        var compiler = new PlanningChunkCompiler(input -> {
            // 编译器会捕获断言异常并结案为失败区块，因此外部还要比对发布数量和实墙。
            assertEquals(53, input.foundationFloor().get(((long) SEAM << 32) | 5));
            if (input.key().arcCell() == 2 && input.plans().plans().getFirst().geometry().key().index() == 1) {
                assertTrue(input.foundations().stream().flatMap(section -> section.fills().stream())
                        .anyMatch(fill -> fill.x() == SEAM && fill.z() == 5 && fill.surfaceY() == 54));
                visited[0]++;
            }
            return resolver.resolve(input);
        }, new ChunkPlanCompiler(), heights, List.of());
        var result = compiler.compileWindow(work, HASH, DIM, null, 7, 1);
        assertEquals(compile(work, heights, 64, 1).keySet(), result.keySet());
        assertTrue(visited[0] > 0);
        assertTrue(result.get(new ChunkKey(DIM, 0, 0)).voxels().stream().anyMatch(v -> v.part() == Part.FOUNDATION));
    }

    private static PlanningChunkCompiler compiler(FineHeightIndex heights, boolean failLeft) {
        var real = realization();
        return new PlanningChunkCompiler(input -> {
            if (failLeft && input.key().arcCell() == 0) throw new WallFailure(FailureCode.T0_MISSING, "左端缺证据");
            return real.resolve(input);
        }, new ChunkPlanCompiler(), heights, List.of());
    }

    private static Map<ChunkKey, ChunkWritePlan> compile(PlanWindow window, FineHeightIndex samples,
            int batchSize, int workers) {
        return new PlanningChunkCompiler(realization(), new ChunkPlanCompiler(), samples, List.of())
                .compileWindow(window, HASH, DIM, null, batchSize, workers);
    }

    private static RealizationPlanner realization() {
        var spawn = new Point3i(SEAM - 128, 64, 0);
        var recipe = new WorldRecipe(new UUID(0, 1), "fixture", 3, DIM, spawn, new Axis(spawn, 1 << 30, 0),
                new DimensionBounds(0, 128, 29_999_000),
                new GenerationConfig(1, 1, 2048, 4, 150000, 256, 512, 8, 64L << 20, 64L << 20, 32L << 20, HASH),
                HASH, HASH, HASH, HASH);
        // 使用真实墙体模板、取证、构件冲突解析和编码，只有世界地形是离线夹具。
        return new RealizationPlanner(recipe, new SurfaceProbe(), S1TemplateSet.create(recipe.seed()));
    }

    private static SegmentPlan segment(int index) {
        int start = SEAM + (index - 1) * 128;
        long prefix = index * 128L * 256;
        var stations = new ArrayList<Station>();
        for (int x = 0; x <= 128; x += 4) {
            stations.add(new Station(prefix + x * 256L, new Point2q((start + x) * 256L, 0), WALK_Y, 0));
        }
        var a = new Boundary(index, stations.getFirst().point(), WALK_Y, HASH);
        var b = new Boundary(index + 1, stations.getLast().point(), WALK_Y, HASH);
        var geometry = new SegmentGeometry(new SegmentKey(HASH, DIM, index), List.of(a.point(), b.point()),
                List.of(0L, 128L * 256), List.of(), Optional.empty(), a, b, 0, 0, HASH);
        return new SegmentPlan(geometry, new PrefixSummary(index, prefix, Optional.empty(), HASH),
                new StationTable(stations, List.of(), HASH), List.of(), HASH);
    }

    private static FineHeightIndex samples() {
        var columns = new ArrayList<WallFinePlanner.FineColumn>();
        var flat = new ColumnSample(64, OptionalInt.empty(), OptionalInt.empty(), "minecraft:plains", Quality.REFERENCE, false);
        // 比自然承重基准低 10 格，仍在真实探针的寻底范围内，不能把无承重导致的拒绝混入测试。
        var low = new ColumnSample(54, OptionalInt.empty(), OptionalInt.empty(), "minecraft:plains", Quality.REFERENCE, false);
        // 给墙体足迹两侧都留采样余量，避免把缺证据造成的空计划误当成接缝结果。
        for (int x = SEAM - 144; x <= SEAM + 144; x++) {
            for (int z = -12; z <= 12; z++) {
                // 只降低一列，让地基来自有弧长标识的真实截面，整段地形保持可走。
                var height = x == SEAM && z == 5 ? low : flat;
                columns.add(new WallFinePlanner.FineColumn(x, z, z, height, (x - SEAM + 128) * 256L, x, 0));
            }
        }
        return FineHeightIndex.of(columns);
    }
}
