package local.plaincraft.greatwall.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The performance and precision knobs have to survive a restart.
 *
 * <p>They were declared, read by their accessors and writable from the Mod Menu screen, but absent
 * from {@code load}/{@code write}/{@code copy}/{@code applyFrom}: a value edited in the screen was
 * live for the session and silently back to the default after a restart.
 */
class GreatWallConfigTest {

    /** Distinct, legal values for every knob the round trip has to carry. */
    private static void configure(GreatWallConfig config) {
        config.setFineSampleDriverIntervalMs(7);
        config.setLaneBatchOperations(64);
        config.setChunkCacheLimit(8);
        config.setFineStep(3);
        config.setFineSampleGridStep(GreatWallConfig.MAX_FINE_SAMPLE_GRID_STEP);
        config.setSteepThreshold(9);
        config.setRefineWater(false);
        config.setCoreHalfWidth(5);
        config.setUnlimitedPrewarmBlocks(8193);
    }

    /** Deliberately different values, so an omitted field cannot pass by coincidence. */
    private static void disturb(GreatWallConfig config) {
        config.setFineSampleDriverIntervalMs(2);
        config.setLaneBatchOperations(128);
        config.setChunkCacheLimit(32);
        config.setFineStep(1);
        config.setFineSampleGridStep(3);
        config.setSteepThreshold(4);
        config.setRefineWater(true);
        config.setCoreHalfWidth(20);
        config.setUnlimitedPrewarmBlocks(2048);
    }

    private static void assertConfigured(GreatWallConfig config) {
        assertEquals(7, config.fineSampleDriverIntervalMs());
        assertEquals(64, config.laneBatchOperations());
        assertEquals(8, config.chunkCacheLimit());
        assertEquals(3, config.fineStep());
        assertEquals(GreatWallConfig.MAX_FINE_SAMPLE_GRID_STEP, config.fineSampleGridStep());
        assertEquals(9, config.steepThreshold());
        assertFalse(config.refineWater());
        assertEquals(5, config.coreHalfWidth());
        assertEquals(8193, config.unlimitedPrewarmBlocks());
    }

    /**
     * One edit per semantic value, each landing on a legal value different from the default.
     *
     * <p>Every entry has to move the digest on its own: a value omitted from {@link
     * GreatWallConfig.Semantic#digest()} would keep the old world identity while silently changing the
     * wall that gets generated - the exact hole this guard exists to close.
     */
    private static final java.util.List<java.util.function.Consumer<GreatWallConfig>> SEMANTIC_EDITS =
            java.util.List.of(
                    config -> config.setWallLengthBlocks(40000L),
                    config -> config.setRidgeWeight(9),
                    config -> config.setRidgeProminenceWeight(7),
                    config -> config.setRidgeSlopeWeight(-11),
                    config -> config.setRidgeTurnWeight(-3),
                    config -> config.setRidgeWetWeight(-13),
                    config -> config.setRidgeDepartureWeight(-4),
                    config -> config.setSummitFlatThreshold(5),
                    config -> config.setSummitFlatReward(8),
                    config -> config.setKnifeRidgePenalty(-9),
                    config -> config.setRidgeAsymmetryWeight(-6),
                    config -> config.setRidgeAccumulatedSlopeWeight(-12),
                    config -> config.setObstacleMarginBlocks(16),
                    config -> config.setFineStep(1),
                    config -> config.setFineSampleGridStep(2),
                    config -> config.setSteepThreshold(8),
                    config -> config.setRefineWater(false),
                    config -> config.setCoreHalfWidth(20));

    @Test void everySemanticValueMovesTheDigest() {
        var config = new GreatWallConfig();
        String reference = config.semantic().digest();
        assertTrue(reference.matches("[0-9a-f]{64}"), reference);
        var seen = new java.util.HashSet<String>();
        seen.add(reference);
        for (var edit : SEMANTIC_EDITS) {
            var edited = new GreatWallConfig();
            edit.accept(edited);
            String digest = edited.semantic().digest();
            assertNotEquals(reference, digest, "digest did not move for " + edit);
            // A field left out of digest() collides with the default digest, and two fields swapped in
            // the writer would collide with each other.
            assertTrue(seen.add(digest), "digest collision for " + edit);
        }
    }

    @Test void performanceKnobsStayOutOfTheDigest() {
        var config = new GreatWallConfig();
        String reference = config.semantic().digest();
        config.setFineSampleDriverIntervalMs(9);
        config.setLaneBatchOperations(32);
        config.setChunkCacheLimit(48);
        config.setUnlimitedPrewarmBlocks(8193);
        assertEquals(reference, config.semantic().digest(),
                "scheduling knobs must not invalidate an existing world");
    }

    @Test void landFirstWorldIgnoresRetiredKnobsButRecordsGeometryChanges() {
        var config = new GreatWallConfig();
        var original = config.semantic();
        // 旧配置留下的字段不得让现行世界的施工计划失效，但历史夹具仍保留原口径。
        config.setRidgeWetWeight(original.ridgeWetWeight() + 1);
        config.setFineSampleGridStep(4);
        assertEquals(original.landFirstDigest(), config.semantic().landFirstDigest());
        assertNotEquals(original.digest(), config.semantic().digest());
        config.setWallLengthBlocks(original.wallLengthBlocks() == 8000 ? 9000 : 8000);
        assertNotEquals(original.landFirstDigest(), config.semantic().landFirstDigest());
    }

    @Test void aFrozenSnapshotOutlivesLiveEdits() throws IOException {
        Path path = GreatWallConfig.file();
        byte[] original = Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        try {
            var config = GreatWallConfig.get();
            config.setRidgeWeight(4);
            GreatWallConfig.freezeParameters();
            String frozen = GreatWallConfig.parameters().digest();
            config.setRidgeWeight(11);
            assertEquals(4, GreatWallConfig.parameters().ridgeWeight(),
                    "the wall keeps the parameters it was created with");
            assertEquals(frozen, GreatWallConfig.parameters().digest());
            assertNotEquals(frozen, GreatWallConfig.parameterDigest(),
                    "the live digest is the drift detector the reload check reads");
            GreatWallConfig.save();
            GreatWallConfig.reload();
            assertEquals(11, GreatWallConfig.parameters().ridgeWeight(),
                    "reload drops the snapshot, so the next world uses the edited values");
        } finally {
            if (original == null) Files.deleteIfExists(path);
            else Files.write(path, original);
            GreatWallConfig.reload();
        }
    }

    @Test void copyCarriesEveryKnob() {
        var config = new GreatWallConfig();
        configure(config);
        assertConfigured(config.copy());
    }

    /**
     * 采样网格的夹取窗口就是配置界面的 {@code setMin/setMax} 与手改文件的唯一共识，而且默认必须是
     * 1：{@code 1} 意味着网格关闭，走出与历史逐字节相同的采样集合。
     */
    @Test void samplingGridStepClampsIntoItsDocumentedWindow() {
        var config = new GreatWallConfig();
        assertEquals(1, config.fineSampleGridStep(), "默认必须是网格关闭（历史行为）");
        config.setFineSampleGridStep(0);
        assertEquals(1, config.fineSampleGridStep());
        config.setFineSampleGridStep(-7);
        assertEquals(1, config.fineSampleGridStep());
        config.setFineSampleGridStep(GreatWallConfig.MAX_FINE_SAMPLE_GRID_STEP + 1);
        assertEquals(GreatWallConfig.MAX_FINE_SAMPLE_GRID_STEP, config.fineSampleGridStep(),
                "访问器上限必须等于界面上的 setMax，否则手改文件能超出界面的窗口");
        config.setFineSampleGridStep(3);
        assertEquals(3, config.fineSampleGridStep());
    }

    @Test void applyFromCarriesEveryKnob() {
        var target = new GreatWallConfig();
        disturb(target);
        var source = new GreatWallConfig();
        configure(source);
        target.applyFrom(source);
        assertConfigured(target);
    }

    @Test void finiteLengthStaysEditableAndManualJsonUsesTheSameCap() throws IOException {
        var config = new GreatWallConfig();
        config.setWallLengthBlocks(12_345L);
        assertEquals(12_345L, config.semantic().wallLengthBlocks());
        config.setWallLengthBlocks(100_001L);
        assertEquals(100_000L, config.semantic().wallLengthBlocks());
        assertEquals(49L, config.prewarmSegmentsTarget());
        config.setWallLengthBlocks(0L);
        assertFalse(config.bounded(), "以前的无限模式仍有独立的预热配置");

        // 用真实配置读取入口，防止菜单限制了上限但手改文件能绕开。
        Path path = GreatWallConfig.file();
        byte[] original = Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "{\"wallLengthBlocks\":900000}");
            GreatWallConfig.reload();
            assertEquals(100_000L, GreatWallConfig.get().wallLengthBlocks());
            assertEquals(49L, GreatWallConfig.get().prewarmSegmentsTarget());
        } finally {
            if (original == null) Files.deleteIfExists(path);
            else Files.write(path, original);
            GreatWallConfig.reload();
        }
    }

    @Test void savedKnobsSurviveAReload() throws IOException {
        Path path = GreatWallConfig.file();
        byte[] original = Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        try {
            var config = GreatWallConfig.get();
            disturb(config);
            configure(config);
            GreatWallConfig.save();
            GreatWallConfig.reload();
            assertConfigured(GreatWallConfig.get());
        } finally {
            // Put the developer's file back exactly as it was, then drop the cached instance so the
            // values this test staged cannot leak into any other test in the same JVM.
            if (original == null) Files.deleteIfExists(path);
            else Files.write(path, original);
            GreatWallConfig.reload();
        }
    }
}
