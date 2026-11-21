package local.plaincraft.greatwall.lifecycle;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.*;

/** 主阶段并行发布与真实进度数据守卫，不启动Minecraft客户端。 */
class StartupProgressUiTest {
    @AfterEach void clear() { PrewarmProgress.clear(); }

    @Test void parallelPlanningPublishesOneStableSamplingStageWithIndependentCounts() {
        PrewarmProgress.clear(); PrewarmProgress.begin(4, 2);
        PrewarmProgress.setStage(STRUCTURE_SCAN);
        PrewarmProgress.beginParallelPlanning();
        assertEquals(FINE_SAMPLE, PrewarmProgress.snapshot().stage());
        PrewarmProgress.setSubProgress(FINE_SAMPLE, 7, 20);
        PrewarmProgress.setCompleted(1);
        var s = PrewarmProgress.snapshot();
        assertEquals(FINE_SAMPLE, s.stage());
        assertArrayEquals(new int[] {7, 20}, s.subFor(FINE_SAMPLE));
        assertEquals(1, s.completed());
        assertFalse(PrewarmUiModel.complete(PREWARM, s));
        assertTrue(PrewarmUiModel.running(PREWARM, s));
        assertFalse(PrewarmUiModel.stages().contains(BACKFILL));
        assertFalse(PrewarmUiModel.complete(BACKFILL, s));
        PrewarmProgress.setCompleted(4);
        assertTrue(PrewarmUiModel.complete(PREWARM, PrewarmProgress.snapshot()));
    }

    @Test void failureRetainsActualCompletionAndNeverChecksDoneOrBackfill() {
        PrewarmProgress.clear(); PrewarmProgress.begin(5, 2);
        PrewarmProgress.setStage(COMPILE); PrewarmProgress.setCompleted(2);
        PrewarmProgress.finish(false);
        var s = PrewarmProgress.snapshot();
        assertFalse(s.active()); assertTrue(s.failed());
        assertEquals(2, s.completed());
        assertFalse(PrewarmUiModel.complete(DONE, s));
        assertFalse(PrewarmUiModel.complete(BACKFILL, s));
    }

    @Test void rateClockBelongsToItsStageAndResetClearsPreviousWorld() throws Exception {
        PrewarmProgress.clear(); PrewarmProgress.begin(5, 2);
        PrewarmProgress.setStage(FINE_SAMPLE);
        // 设置已知阶段账本，避免以长时间sleep换取一个计时断言。
        var field = PrewarmProgress.class.getDeclaredField("STAGE_NANOS"); field.setAccessible(true);
        var nanos = (java.util.concurrent.atomic.AtomicLongArray) field.get(null);
        nanos.set(STRUCTURE_SCAN.ordinal(), 50_000_000_000L);
        var since = PrewarmProgress.class.getDeclaredField("stageSinceNanos"); since.setAccessible(true);
        since.setLong(null, System.nanoTime() - 100_000_000L);
        var s = PrewarmProgress.snapshot();
        assertEquals(50_000L, s.stageElapsedMillis(STRUCTURE_SCAN));
        assertTrue(s.stageElapsedMillis(FINE_SAMPLE) >= 100);
        assertTrue(s.stageElapsedMillis(FINE_SAMPLE) < 10_000);
        PrewarmProgress.clear();
        assertEquals(0, PrewarmProgress.snapshot().stageElapsedMillis(STRUCTURE_SCAN));
    }

    @Test void cardFitsEveryLogicalScreenWithoutDroppingRows() {
        // 从小窗口到4K逻辑尺寸遍历；先断言实际有内容，再核对四条屏幕边界。
        for (int width : new int[] {160, 240, 320, 426, 854, 1920}) {
            for (int height : new int[] {90, 180, 240, 360, 480, 1080}) {
                for (int rows : new int[] {1, 7, 8, 10, 11}) {
                    var layout = LoadingCardLayout.fit(width, height, 280, rows);
                    assertTrue(layout.width() > 0 && layout.height() > 0);
                    assertTrue(layout.scale() > 0 && layout.scale() <= 1);
                    assertTrue(layout.x() >= 0 && layout.y() >= 0);
                    assertTrue(layout.x() + layout.width() * layout.scale() <= width + 0.001);
                    assertTrue(layout.y() + layout.height() * layout.scale() <= height + 0.001);
                    assertEquals(82 + rows * 12 + layout.bandHeight(), layout.height());
                }
            }
        }
        assertEquals(1F, LoadingCardLayout.fit(854, 480, 280, 8).scale());
        assertEquals(120, LoadingCardLayout.fit(854, 480, 280, 8).bandHeight());
    }

    @Test void realSessionAndRendererUseStableStageAndStageOwnedClock() throws Exception {
        var production = Files.readString(Path.of("src/main/java/local/plaincraft/greatwall/lifecycle/ProductionSession.java"));
        int gate = production.indexOf("if (installed) {");
        int begin = production.indexOf("PrewarmProgress.beginParallelPlanning();", gate);
        int bulk = production.indexOf("startBulkPrewarm(planner, \"structures-installed\");", gate);
        assertTrue(gate >= 0 && begin > gate && bulk > begin);
        assertFalse(production.substring(gate, bulk).contains("Stage.PREWARM"));
        var renderer = Files.readString(Path.of("src/client/java/local/plaincraft/greatwall/client/GreatWallLoadingProgressRenderer.java"));
        assertTrue(renderer.contains("PrewarmUiModel.stages()"));
        assertTrue(renderer.contains("snapshot.stageElapsedMillis(stage)"));
        assertTrue(renderer.contains("LoadingCardLayout.fit("));
        assertTrue(renderer.contains("graphics.pose().scale(layout.scale(), layout.scale(), 1F)"));
        assertTrue(renderer.contains("if (!snapshot.active() && !snapshot.failed()) return;"));
        assertFalse(renderer.contains("PrewarmProgress.subTotalFor("));
    }
}
