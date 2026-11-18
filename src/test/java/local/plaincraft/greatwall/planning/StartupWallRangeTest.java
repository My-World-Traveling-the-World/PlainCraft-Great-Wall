package local.plaincraft.greatwall.planning;

import local.plaincraft.greatwall.config.GreatWallConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StartupWallRangeTest {
    @Test void unlimitedRangeRoundsUpAndFrozenBoundaryClipsEveryStage() {
        var config = new GreatWallConfig();
        assertEquals(4, config.prewarmSegmentsTarget());
        config.setUnlimitedPrewarmBlocks(8193);
        assertEquals(5, config.prewarmSegmentsTarget());
        var recipe = PlanningFixtures.recipe(0, 1 << 30, 0, 7000);
        var range = StartupWallRange.resolve(0, config.unlimitedPrewarmBlocks(),
                i -> StartupWallRange.segmentFits(recipe, 0, i));
        assertEquals(new StartupWallRange(5, 2, 0, 4096), range);
        // 边界外请求必须拒绝，不能把这个夹具的“裁到两段”写成无条件常量。
        assertTrue(StartupWallRange.segmentFits(recipe, 0, 1));
        assertFalse(StartupWallRange.segmentFits(recipe, 0, 2));
    }

    @Test void finiteLengthOverridesUnlimitedSettingAndKeepsLongWallFallback() {
        var full = StartupWallRange.resolve(100_000, 1, i -> i < 100);
        assertEquals(new StartupWallRange(49, 49, 49, 100_000), full);
        var longWall = StartupWallRange.resolve(100_001, 1, i -> i < 100);
        // 跨窗编译接通以前仍索引超长有限墙，但不同时钉住所有计划。
        assertEquals(new StartupWallRange(4, 4, 49, 100_001), longWall);
    }

    @Test void hugeLengthDoesNotOverflowAndEmptyEnvelopeDoesNotStart() {
        assertEquals(4_503_599_627_370_496L, GreatWallConfig.segmentsFor(Long.MAX_VALUE));
        var recipe = PlanningFixtures.recipe(0, 1 << 30, 0, 7000);
        var range = StartupWallRange.resolve(Long.MAX_VALUE, 8192,
                i -> StartupWallRange.segmentFits(recipe, 0, i));
        assertEquals(2, range.segments());
        assertEquals(4096, range.structureScanBlocks());
        assertEquals(new StartupWallRange(4, 0, 0, 0), StartupWallRange.resolve(0, 8192, i -> false));
    }
}
