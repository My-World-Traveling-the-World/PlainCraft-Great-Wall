package local.plaincraft.greatwall.planning;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.terrain.AnalyticTerrainOracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 驱动正式调度器，复现首段遇海、后续空段把整轮精采样打断的客户端故障。 */
class ImportedTerminalSamplingTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ImportedProductionPlanTest.bootstrap(); }

    @Test void terminalFollowedByEmptyPlansStillPublishesTheActualFirstWall() throws Exception {
        var recipe = recipe();
        try (var io = Executors.newSingleThreadExecutor();
             var scheduler = new PlannerScheduler(recipe, () -> new AnalyticTerrainOracle(
                     (x, z) -> x >= 1600 ? AnalyticTerrainOracle.water(64, 40)
                             : AnalyticTerrainOracle.dry(64), true),
                     new SegmentCacheStore(directory.resolve("cache"), recipe), io);
             var first = scheduler.requirePlan(0, Priority.PLAYER);
             var dead = scheduler.requirePlan(1, Priority.PLAYER)) {
            scheduler.startupPump();
            var live = first.future().get(30, TimeUnit.SECONDS);
            var after = dead.future().get(30, TimeUnit.SECONDS);
            assertEquals(0, live.geometry().terminal().orElseThrow().segment());
            assertTrue(live.stations().stations().size() > 2, "先证明首段确有施工墙线");
            assertEquals(0, after.prefix().terminal().orElseThrow().segment());
            assertTrue(after.stations().stations().isEmpty(), "必须走到客户端真实的终止空段");
            var index = scheduler.requireFineSamples(Priority.PLAYER).get(30, TimeUnit.SECONDS);
            assertTrue(index.size() > 100, "不能以空索引冒充采样成功");
            assertTrue(index.orderedColumns().stream().anyMatch(c -> c.x() < 1600),
                    "合法首段的实际地形证据必须交给正式编译");
        }
    }

    @Test void anEmptyActivePlanStillFailsInsteadOfBeingSilentlySkipped() {
        var recipe = recipe();
        var geometry = PlanningFixtures.geometry(recipe, 0, Optional.empty());
        var plan = new SegmentPlan(geometry, PrefixSummaryIndex.origin(),
                new StationTable(List.of(), List.of(), PlanningFixtures.HASH), List.of(), PlanningFixtures.HASH);
        assertThrows(IllegalArgumentException.class, () -> ImportedSamplePath.forSampling(plan));
    }

    /** 构造真实模板配方并重新盖语义哈希；不能拿非法配方测不到采样入口。 */
    private static WorldRecipe recipe() {
        var old = PlanningFixtures.recipe(0, 1 << 30, 0, 7000);
        var c = old.config();
        var config = new GenerationConfig(c.algorithmVersion(), 2, c.segmentBlocks(), c.stationBlocks(),
                c.columnBudget(), c.carrierAmplitude(), c.corridorHalfWidth(), c.maxGeometryInflight(),
                c.segmentBytes(), c.terrainBytes(), c.compiledBytes(), c.parametersHash());
        var draft = new WorldRecipe(old.instance(), old.wallId(), old.seed(), old.dimension(),
                old.frozenSpawn(), old.axis(), old.bounds(), config, old.terrainHash(),
                old.paletteHash(), old.templatesHash(), old.recipeHash());
        var hash = DeterministicHash.sha256(CanonicalCodec.recipe(draft));
        return new WorldRecipe(draft.instance(), draft.wallId(), draft.seed(), draft.dimension(),
                draft.frozenSpawn(), draft.axis(), draft.bounds(), config, draft.terrainHash(),
                draft.paletteHash(), draft.templatesHash(), hash);
    }
}
