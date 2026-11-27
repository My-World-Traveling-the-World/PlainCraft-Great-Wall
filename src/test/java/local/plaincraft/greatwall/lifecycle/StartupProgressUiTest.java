package local.plaincraft.greatwall.lifecycle;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.*;

/** 主阶段并行发布与真实进度数据守卫，不启动Minecraft客户端。 */
class StartupProgressUiTest {
    @Test void zeroWorkWithoutConfirmationCannotReportComplete() {
        PrewarmProgress.clear(); PrewarmProgress.begin(0, 2);
        PrewarmProgress.setStage(DONE);
        assertEquals(0, PrewarmProgress.snapshot().overallPercent());
        assertFalse(PrewarmUiModel.complete(DONE, PrewarmProgress.snapshot()));
    }

    @AfterEach void clear() { PrewarmProgress.clear(); }

    @Test void parallelPlanningPublishesOneStableSamplingStageWithIndependentCounts() {
        PrewarmProgress.clear(); PrewarmProgress.begin(4, 2);
        PrewarmProgress.setStage(STRUCTURE_SCAN);
        PrewarmProgress.beginParallelPlanning();
        var waiting = PrewarmProgress.snapshot();
        assertEquals(PREWARM, waiting.stage());
        assertTrue(PrewarmUiModel.running(PREWARM, waiting));
        assertFalse(PrewarmUiModel.running(FINE_SAMPLE, waiting));
        assertFalse(PrewarmUiModel.complete(FINE_SAMPLE, waiting));
        assertTrue(PrewarmUiModel.stages().indexOf(PREWARM) < PrewarmUiModel.stages().indexOf(FINE_SAMPLE));
        PrewarmProgress.fineSliceScheduled();
        PrewarmProgress.setSubProgress(FINE_SAMPLE, 1, 0);
        int priorCredit = PrewarmProgress.snapshot().overallPercent();
        var previousWork = PrewarmProgress.snapshot().workSignature();
        PrewarmProgress.fineSliceAccepted(48, 192);
        var acceptedWork = PrewarmProgress.snapshot().workSignature();
        assertNotEquals(previousWork, acceptedWork, "切片验收即使所有列去重，也是真实工作");
        PrewarmProgress.fineSliceAccepted(48, 200);
        assertEquals(acceptedWork, PrewarmProgress.snapshot().workSignature(), "只改估计分母不应让超时从头计时");
        assertTrue(PrewarmProgress.snapshot().overallPercent() > priorCredit, "其他段尚未给出列总数，已采切片也必须推动总条");
        assertArrayEquals(new int[]{1, 0}, PrewarmProgress.snapshot().subFor(FINE_SAMPLE));
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

    @Test void capFailureMarksCompileEvenAfterEverySegmentIsPlanned() {
        PrewarmProgress.clear(); PrewarmProgress.begin(5, 2);
        PrewarmProgress.setStage(COMPILE); PrewarmProgress.setCompleted(5);
        PrewarmProgress.setCompileStep(PrewarmProgress.CompileStep.CAP_EVIDENCE);
        PrewarmProgress.setSubProgress(COMPILE, 3, 5);
        PrewarmProgress.finish(false);
        // 复现门楼预检失败，保留已规划的分段以及真正失败的子步骤。
        PrewarmProgress.setCompileStep(PrewarmProgress.CompileStep.PUBLISH_CHUNKS);
        var s = PrewarmProgress.snapshot();
        assertFalse(s.active()); assertTrue(s.failed());
        assertEquals(5, s.completed());
        assertEquals(94, s.overallPercent());
        assertEquals(PrewarmProgress.CompileStep.CAP_EVIDENCE, s.compileStep());
        assertTrue(PrewarmUiModel.failed(COMPILE, s));
        assertFalse(PrewarmUiModel.failed(PREWARM, s));
        assertTrue(PrewarmUiModel.complete(PREWARM, s));
        assertFalse(PrewarmUiModel.complete(DONE, s));
    }

    @Test void macroColumnsDoNotCountAsCandidatesAndPublicationDoesNotMeanDone() {
        PrewarmProgress.clear();
        PrewarmProgress.begin(5, 2);
        // 网格、候选与分段的单位各异，各自过半都应当推动同一条总进度。
        PrewarmProgress.setRootDisplay(PrewarmProgress.ROOT_MACRO_SURVEY, 105, 210);
        var macro = PrewarmProgress.snapshot();
        assertEquals("macro", PrewarmUiModel.rootStep(macro.rootPhase()));
        assertEquals(105, macro.rootCandidate());
        assertEquals(50, macro.stagePercent());
        assertEquals(2, macro.overallPercent());
        PrewarmProgress.setRootDisplay(0, 27, 324);
        var candidates = PrewarmProgress.snapshot();
        assertEquals("candidates", PrewarmUiModel.rootStep(candidates.rootPhase()));
        assertEquals(8, candidates.stagePercent());
        assertTrue(candidates.overallPercent() > macro.overallPercent());
        PrewarmProgress.setRootDisplay(4, 0, 0);
        PrewarmProgress.setStructureProgress(16, 64);
        int screening = PrewarmProgress.snapshot().overallPercent();
        PrewarmProgress.setStructureProgress(48, 64);
        assertTrue(PrewarmProgress.snapshot().overallPercent() > screening);
        PrewarmProgress.setStage(STRUCTURE_SCAN);
        PrewarmProgress.setSubProgress(FINE_SAMPLE, 50, 100);
        PrewarmProgress.beginParallelPlanning();
        var overlap = PrewarmProgress.snapshot();
        assertEquals(50, overlap.subDone());
        int fineOnly = overlap.overallPercent();
        PrewarmProgress.setCompleted(3);
        PrewarmProgress.setCompleted(2);
        assertEquals(3, PrewarmProgress.snapshot().completed(), "迟到的较小计数不能让下栏和总条倒走");
        int segmentCredit = PrewarmProgress.snapshot().overallPercent();
        assertTrue(segmentCredit > fineOnly);
        PrewarmProgress.setSubProgress(FINE_SAMPLE, 90, 100);
        assertTrue(PrewarmProgress.snapshot().overallPercent() > segmentCredit);
        PrewarmProgress.setStage(COMPILE);
        PrewarmProgress.setCompileStep(PrewarmProgress.CompileStep.PUBLISH_CHUNKS);
        PrewarmProgress.setSubProgress(COMPILE, 236, 236);
        assertEquals(98, PrewarmProgress.snapshot().overallPercent());
        PrewarmProgress.setStage(DONE);
        PrewarmProgress.setSubProgress(DONE, 150, 200);
        assertEquals(99, PrewarmProgress.snapshot().overallPercent());
        assertFalse(PrewarmUiModel.complete(DONE, PrewarmProgress.snapshot()));
        PrewarmProgress.finish(true);
        assertTrue(PrewarmUiModel.complete(DONE, PrewarmProgress.snapshot()));
        assertEquals(100, PrewarmProgress.snapshot().overallPercent());
        PrewarmProgress.clear();
        assertEquals(0, PrewarmProgress.snapshot().overallPercent());
        // 新会话不应被迟到的旧快照抬高。
        assertEquals(fineOnly, overlap.overallPercent());
        PrewarmProgress.beginRootSearch(2);
        assertEquals(0, PrewarmProgress.snapshot().overallPercent());
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

    @Test void healthyLongStartupOutlivesItsStallWindow() {
        // 直接推进单调时钟，同一工作签名持续完整期限才算停滞。
        long minute = java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
        var clock = new StartupStallClock(0, 30 * minute);
        assertFalse(clock.stalled(0, () -> "选址"));
        assertFalse(clock.stalled(29 * minute, () -> "结构扫描"));
        assertFalse(clock.stalled(58 * minute, () -> "精采"));
        assertFalse(clock.stalled(87 * minute, () -> "精采"));
        assertTrue(clock.stalled(88 * minute, () -> "精采"));
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
