package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import local.plaincraft.greatwall.component.S1TemplateSet;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.model.WallTypes.*;
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
        assertEquals(witness.voxels().size(), witness.proofs().size());
        assertTrue(whole.values().stream().filter(plan -> !plan.voxels().isEmpty()).count() > 3, "确实跨越多个批次");
        assertEquals(whole.keySet(), batched.keySet());
        whole.forEach((key, expected) -> assertArrayEquals(CanonicalCodec.writePlan(expected),
                CanonicalCodec.writePlan(batched.get(key)), key.toString()));
    }

    private static Map<ChunkKey, ChunkWritePlan> compile(PlanWindow window, FineHeightIndex samples,
            int batchSize, int workers) {
        var spawn = new Point3i(SEAM - 128, 64, 0);
        var recipe = new WorldRecipe(new UUID(0, 1), "fixture", 3, DIM, spawn, new Axis(spawn, 1 << 30, 0),
                new DimensionBounds(0, 128, 29_999_000),
                new GenerationConfig(1, 1, 2048, 4, 150000, 256, 512, 8, 64L << 20, 64L << 20, 32L << 20, HASH),
                HASH, HASH, HASH, HASH);
        // 使用真实墙体模板、取证、构件冲突解析和编码，只有世界地形是离线夹具。
        var realization = new RealizationPlanner(recipe, new SurfaceProbe(), S1TemplateSet.create(recipe.seed()));
        return new PlanningChunkCompiler(realization, new ChunkPlanCompiler(), samples, List.of())
                .compileWindow(window, HASH, DIM, null, batchSize, workers);
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
        // 给墙体足迹两侧都留采样余量，避免把缺证据造成的空计划误当成接缝结果。
        for (int x = SEAM - 144; x <= SEAM + 144; x++) {
            for (int z = -12; z <= 12; z++) columns.add(new WallFinePlanner.FineColumn(x, z, z, flat));
        }
        return FineHeightIndex.of(columns);
    }
}
