package local.plaincraft.greatwall.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import local.plaincraft.greatwall.GreatWallMod;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Global client-side configuration.
 *
 * <h2>Why a file and not a system property</h2>
 * Every earlier knob on this mod was a {@code -D} switch, which meant the
 * player had to edit the launcher's JVM arguments to change anything. The
 * wall's own tunables (how far it runs, how much is prewarmed before the
 * world is handed over) are ordinary gameplay choices, so they belong in a
 * durable file that the Mod Menu and the world-creation screen can both
 * write, and that survives a launcher reinstall.
 *
 * <h2>What is deliberately NOT here</h2>
 * Anything that changes <em>generation semantics</em> is excluded on purpose:
 * the terrain hash, palette hash and template hash are frozen into the world
 * recipe at creation and a mismatch is refused with
 * {@code RECIPE_MISMATCH} rather than silently producing a different wall.
 * This file only carries choices the recipe tolerates.
 *
 * <h2>Failure policy</h2>
 * A missing or unreadable file is not an error: defaults are used and the
 * file is rewritten. A malformed value falls back to its default rather than
 * throwing, because a bad config must never stop the game from starting.
 */
public final class GreatWallConfig {
    /** {@code 0} means "no configured end": the wall runs to the frozen horizontal limit. */
    public static final long UNLIMITED_LENGTH = 0L;
    /** 保留旧无限墙开头四段的默认值。 */
    public static final int DEFAULT_PREWARM_SEGMENTS = 4;
    /** Segments are 2048 blocks; the value is repeated here so the UI can label lengths without a recipe. */
    public static final int SEGMENT_BLOCKS = 2048;
    /** 当前启动编译仍需保留整窗证据。超长有限墙继续由后台推进，直到支持跨窗口取证。 */
    public static final long MAX_WALL_LENGTH_BLOCKS = 100_000L;
    public static final int MAX_UNLIMITED_PREWARM_BLOCKS = (int) MAX_WALL_LENGTH_BLOCKS;
    private static final String FILE_NAME = "greatwall.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile GreatWallConfig instance;
    /**
     * The parameter snapshot an open world generates from; {@code null} until first captured.
     *
     * <p>Deliberately separate from {@link #instance}: the instance is "what the screen and the file
     * say right now", the snapshot is "what this world was built with". Only {@link #parameters()}
     * may be read by planning code.
     */
    private static volatile Semantic frozenParameters;

    private long wallLengthBlocks = UNLIMITED_LENGTH;
    // 只影响开局工作的范围，不写进配方摘要；会话启动时取一次。
    private int unlimitedPrewarmBlocks = DEFAULT_PREWARM_SEGMENTS * SEGMENT_BLOCKS;
    /** 只把连续的大海作为收口候选；河湖不能再被旧50格阈值截断。 */
    private int oceanBreakLengthBlocks = 512;

    // ── 0.1.0 山脊规划打分参数（最细粒度奖励惩罚体系，默认值 = RidgePlanner 原硬编码）──
    /** 刀锋山脊（中心凸起）权重：正值奖励凸起、负值惩罚凸起。默认 +4 保持原行为。 */
    private int ridgeWeight = 4;
    /** 突出度（中心相对周边）权重。 */
    private int ridgeProminenceWeight = 2;
    /** 单步坡度权重（负值惩罚）。 */
    private int ridgeSlopeWeight = -5;
    /** 转向权重（负值惩罚）。 */
    private int ridgeTurnWeight = -2;
    /** 水域权重（负值惩罚）。 */
    private int ridgeWetWeight = -8;
    /** 偏离中轴权重（负值惩罚）。 */
    private int ridgeDepartureWeight = -1;
    /** 平坦山顶阈值：中心与两侧平均落差 ≤ 此格数视为「平坦山顶」（可走，奖励）。 */
    private int summitFlatThreshold = 2;
    /** 平坦山顶奖励（正值）。 */
    private int summitFlatReward = 3;
    /** 窄刀锋惩罚（负值，中心远高于两侧时）。 */
    private int knifeRidgePenalty = -6;
    /** 两侧不对称（斜坡）惩罚（负值）。 */
    private int ridgeAsymmetryWeight = -3;
    /** 累计坡度（连续爬坡）惩罚（负值）。 */
    private int ridgeAccumulatedSlopeWeight = -6;
    /** 结构避让余量（格），中心线接近结构足迹此距离内视为障碍。 */
    private int obstacleMarginBlocks = 8;

    // ── 0.1.0 性能调度参数 ──
    // 这些值只影响「什么时候算、一次算多少、复用多少」，**不改变任何采样结果**，
    // 因此不参与配方语义哈希（WorldManifest 的规范字段只有 carrierAmplitude /
    // corridorHalfWidth / maxGeometryInflight 等冻结值），改动它们不会让已有世界 RECIPE_MISMATCH。
    /**
     * 细采样专用驱动器的间隔（毫秒）。默认 1ms（每秒 1000 次）。
     *
     * <p>真机教训：细采样此前没有驱动器——`startupTimer` 被首个 server tick 取消后只剩 tick 驱动
     * （约 20 次/秒），27 分钟没跑完一个切片。调大此值会线性降低吞吐，只在需要给别的 mod 让出
     * CPU 时才调。
     */
    private int fineSampleDriverIntervalMs = 1;

    /**
     * 每条 lane 每轮发放的原始操作数。默认 256。
     *
     * <p><b>4096 是硬上限，不是建议值</b>：`CpuTerrainOracle.advance`、`WallFinePlanner.advance`、
     * `HydrologyClassifier.advance`、`RidgePlanner.advance` 都强制 primitiveLimit ∈ [1,4096]，超过直接
     * 抛异常，所以访问器会把配置钳到该区间内（旧的 256 上限已在 7cc3124f 抬升）。
     */
    private int laneBatchOperations = 256;

    /**
     * 每条 lane 缓存多少个「已烘焙的区块」（按 chunk 构造原点区分）。默认 128。
     *
     * <p>横断面一次扫掠约命中 13 个 cell（cell 宽 4 时），所以低于 13 会明显开始抖动；
     * 调大能提高命中率但按倍数占用堆内存。真机实测 16→64 把命中率从 72% 提到 ~85%，
     * 细采样省约 70-100s。
     *
     * <p>默认由 64 提到 128：真机那一局的细采样里 **命中/未命中 = 1.49M/139k**，而走廊里
     * 不重复的 cell 只有约 1.7 万个 ⇒ 每个 cell 平均被重建约 8 次，说明 64 条的 LRU 在同一条
     * lane 上被交替的作业（分段预热 / 高度契约 / 水文章节 / 细采样切片）来回冲刷。一次未命中就是
     * 一次切片填充（整条 2112 格高的 corner 采样），是这一阶段最大的单项成本之一；翻倍到 128 让
     * 每个 cell 的重建次数下降，代价是每条 lane 多占几十 MB，堆内存吃紧时可从配置界面调回 64。
     */
    private int chunkCacheLimit = 128;

    // ── 0.1.0 细采样精度档位（两级采样的"精度"旋钮）──
    /**
     * 横断面侧翼的采样间距（格）。**1 = 关闭抽稀**，完全等于旧行为；2 表示隔一列采一列。
     *
     * <p>中心带（±{@link #coreHalfWidth}）与两端永远逐格精采。抽稀只作用于侧翼，因为填充规则
     * 看不见比间距更窄的特征：1 格宽的尖峰或水塘夹在两个已采列之间时，高差规则（两边同高）
     * 与水规则都发现不了。默认 4（真机提速档，把侧翼采样减半；窄于 4 格的水带可能漏检）。
     */
    private int fineStep = 4;
    /** 相邻已采列高差达到此值就把该区间整段精采（墙基正是在台阶处换层）。 */
    private int steepThreshold = 3;
    /** 任何已采列报水时是否把整条横断面升级为全分辨率（并传播到下一个路线点）。 */
    private boolean refineWater = true;
    /** 中心带半宽（格）：此范围内一律逐格精采，抽稀不进入墙主体。默认 8（真机提速档）。 */
    private int coreHalfWidth = 8;

    // ── 0.1.0 采样网格步长（玩家可填的"精度档位"）──────────────────────────────
    /**
     * 采样网格步长（格）。**1 = 关闭网格，完全等于旧行为**；N 表示沿路线每 N 个路点采一条断面、
     * 横向每 N 格采一列，中间的格子由最近的真实采样列复制（{@code PlanningColumnProvider}）。
     *
     * <p>它与 {@link #fineStep} 的区别：{@code fineStep} 是断面<em>内部</em>的自适应抽稀（稀疏基础集
     * ＋台阶细化＋遇水升级），{@code fineSampleGridStep} 是断面<em>之间</em>与横向的硬网格，决定
     * "哪些格子是真的探过"。两者相乘：网格决定采样密度，自适应抽稀决定网格内还要不要补探。
     *
     * <p>代价（由玩家的选择承担）：采样列数约按 N² 下降，走廊细采样随之约快 N² 倍；但窄于网格的
     * 地形尖峰与水体会被漏检——墙基可能切进山体，或把一条窄溪当成陆地跨过去。默认 1 时这一取舍
     * 完全不生效，与既有版本逐字节一致。
     *
     * <p>取值 1..4，由 {@link #fineSampleGridStep()} 夹取；它进入世界身份指纹（会改变生成结果）。
     */
    private int fineSampleGridStep = 1;
    /** 采样网格步长的合法上限：4 已把采样列数压到约 1/16，再粗就会让窄地形特征大面积漏检。 */
    public static final int MAX_FINE_SAMPLE_GRID_STEP = 4;

    // ── 世界身份：会改变生成结果的参数快照 ─────────────────────────────────────
    /**
     * The parameter values that decide what the wall actually looks like.
     *
     * <h2>Why this is a separate type</h2>
     * The knobs in this class split into two families. Performance knobs (driver interval, lane
     * grant, chunk cache size) only decide <em>when</em> work happens. Semantic knobs decide
     * <em>what</em> is generated: which ridge the wall follows, the height reported for every
     * column of a cross-section, where the wall stops. Only the second family may influence a
     * world, so only the second family is fingerprinted — and it is fingerprinted as one value
     * rather than as seventeen fields, so adding a knob later cannot silently escape the guard.
     *
     * <h2>Frozen, not live</h2>
     * {@link GreatWallConfig#parameters()} returns the snapshot taken while a world loads, and the
     * planners read that snapshot rather than the live config. An edit made while a world is open
     * must not change plans halfway through the wall: before this type existed, {@code ridgeWeight}
     * and friends were read live, so a config edit left already-planned segments on the old ridge
     * choice and every later segment on the new one, and nothing failed closed. The live values
     * remain available for the config screen and for {@link #parameterDigest()}.
     */
    public record Semantic(long wallLengthBlocks, int ridgeWeight, int ridgeProminenceWeight,
            int ridgeSlopeWeight, int ridgeTurnWeight, int ridgeWetWeight, int ridgeDepartureWeight,
            int summitFlatThreshold, int summitFlatReward, int knifeRidgePenalty,
            int ridgeAsymmetryWeight, int ridgeAccumulatedSlopeWeight, int obstacleMarginBlocks,
            int fineStep, int steepThreshold, boolean refineWater, int coreHalfWidth,
            int fineSampleGridStep, int oceanBreakLengthBlocks) {
        /** 海洋收口阈值参与冻结摘要，不能让同一世界后半段突然采用另一种水域规则。 */
        public static final int SCHEMA = 3;

        /** The stock parameter set: what a fresh install generates. */
        public static final Semantic DEFAULTS = new GreatWallConfig().semantic();

        public boolean bounded() { return wallLengthBlocks > UNLIMITED_LENGTH; }

        /** Segments the frozen length spans; {@code 0} when unbounded. */
        public long configuredSegments() {
            if (!bounded()) return 0L;
            return segmentsFor(wallLengthBlocks);
        }

        /**
         * Canonical SHA-256 over every semantic value, in a fixed order.
         *
         * <p>This is the value a world stores. Two configs with the same digest generate the same
         * wall for the same seed; any difference here is a different world recipe.
         */
        public String digest() {
            var bytes = new java.io.ByteArrayOutputStream(128);
            try (var out = new java.io.DataOutputStream(bytes)) {
                out.writeInt(SCHEMA);
                out.writeLong(wallLengthBlocks);
                out.writeInt(ridgeWeight);
                out.writeInt(ridgeProminenceWeight);
                out.writeInt(ridgeSlopeWeight);
                out.writeInt(ridgeTurnWeight);
                out.writeInt(ridgeWetWeight);
                out.writeInt(ridgeDepartureWeight);
                out.writeInt(summitFlatThreshold);
                out.writeInt(summitFlatReward);
                out.writeInt(knifeRidgePenalty);
                out.writeInt(ridgeAsymmetryWeight);
                out.writeInt(ridgeAccumulatedSlopeWeight);
                out.writeInt(obstacleMarginBlocks);
                out.writeInt(fineStep);
                out.writeInt(steepThreshold);
                out.writeBoolean(refineWater);
                out.writeInt(coreHalfWidth);
                out.writeInt(fineSampleGridStep);
                out.writeInt(oceanBreakLengthBlocks);
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            return local.plaincraft.greatwall.geometry.DeterministicHash.sha256(bytes.toByteArray());
        }

        /** 现行配方只冻结会改变成品的旋钮，旧抽稀等不再使新世界的计划失效。 */
        public String landFirstDigest() {
            // 端楼先验与楼址逐列重选会改变成品，新世界不能读回旧算法的分段缓存。
            var fields = java.nio.ByteBuffer.allocate(Long.BYTES + 7 * Integer.BYTES);
            fields.putInt(2).putLong(wallLengthBlocks).putInt(ridgeSlopeWeight).putInt(ridgeTurnWeight)
                    .putInt(ridgeDepartureWeight).putInt(ridgeAccumulatedSlopeWeight).putInt(obstacleMarginBlocks)
                    .putInt(oceanBreakLengthBlocks);
            return local.plaincraft.greatwall.geometry.DeterministicHash.sha256(fields.array());
        }
    }

    /** Snapshot of the values this instance holds right now, with accessor clamping applied. */
    public Semantic semantic() {
        return new Semantic(wallLengthBlocks, ridgeWeight, ridgeProminenceWeight, ridgeSlopeWeight,
                ridgeTurnWeight, ridgeWetWeight, ridgeDepartureWeight, summitFlatThreshold,
                summitFlatReward, knifeRidgePenalty, ridgeAsymmetryWeight, ridgeAccumulatedSlopeWeight,
                obstacleMarginBlocks(), fineStep(), steepThreshold(), refineWater(), coreHalfWidth(),
                fineSampleGridStep(), oceanBreakLengthBlocks());
    }

    /**
     * Captures the current values as this session's parameter snapshot.
     *
     * <p>Called before any planner runs (the first identity computation does it). Later edits change
     * the stored file and {@link #parameterDigest()}, never the snapshot a running world uses.
     */
    public static void freezeParameters() {
        synchronized (GreatWallConfig.class) { frozenParameters = get().semantic(); }
    }

    /** The frozen snapshot; captured on first use so no caller can observe a half-frozen state. */
    public static Semantic parameters() {
        var value = frozenParameters;
        if (value == null) { freezeParameters(); value = frozenParameters; }
        return value;
    }

    /**
     * Digest of the values currently configured — deliberately live.
     *
     * <p>This is the drift detector: compare it with {@code parameters().digest()} (the frozen
     * snapshot) to see whether the screen edited something the open world must refuse.
     */
    public static String parameterDigest() { return get().semantic().digest(); }

    public long wallLengthBlocks() { return wallLengthBlocks; }

    /** 与界面共用边界，避免手改JSON制造无法计量的负长度。 */
    // 水文证据目前只含相邻段；阈值不超过一段，任一过海段都能拿到完整阈值邻域。
    public int oceanBreakLengthBlocks() { return Math.clamp(oceanBreakLengthBlocks, 64, SEGMENT_BLOCKS); }
    public void setOceanBreakLengthBlocks(int value) { oceanBreakLengthBlocks = value; }

    /** Driver interval in ms, floored at 1 so a config typo cannot stop the stage from progressing. */
    public int fineSampleDriverIntervalMs() { return Math.max(1, fineSampleDriverIntervalMs); }

    /** Lane grant, clamped to the 1..4096 window both advance contracts enforce. The default stays
     *  256: a larger grant trades scheduling fairness for fewer per-batch fixed costs, which is worth
     *  measuring on a real session before it becomes the default. */
    public int laneBatchOperations() { return Math.max(1, Math.min(4096, laneBatchOperations)); }

    /** Baked-chunk cache size per lane, clamped to 4..256 so it cannot be configured to zero. Each
     *  entry holds a baked NoiseChunk slice, so this is a memory/fine-sample-speed trade. */
    public int chunkCacheLimit() { return Math.max(4, Math.min(256, chunkCacheLimit)); }

    // Setters store the raw value on purpose: the accessors above are the single place that clamps,
    // so a hand-edited file and the config screen cannot disagree about the legal range.
    public void setFineSampleDriverIntervalMs(int value) { this.fineSampleDriverIntervalMs = value; }
    public void setLaneBatchOperations(int value) { this.laneBatchOperations = value; }
    public void setChunkCacheLimit(int value) { this.chunkCacheLimit = value; }

    /** Flank sampling stride, clamped to 1..4 so a typo cannot thin the cross-section into noise. */
    public int fineStep() { return Math.max(1, Math.min(4, fineStep)); }
    /** Step threshold in blocks, clamped to 1..16. */
    public int steepThreshold() { return Math.max(1, Math.min(16, steepThreshold)); }
    public boolean refineWater() { return refineWater; }
    /** Core band half width, clamped to 0..52 (WallFinePlanner.LATERAL) so it can never exceed the
     *  widest cross-section a fortified arc samples. */
    public int coreHalfWidth() { return Math.max(0, Math.min(52, coreHalfWidth)); }

    /**
     * Sampling grid step in blocks, clamped to 1..{@value #MAX_FINE_SAMPLE_GRID_STEP}.
     *
     * <p>Semantic knob: it decides which columns are actually probed, so it is part of the frozen
     * snapshot and of the world identity digest. {@code 1} means no grid.
     */
    public int fineSampleGridStep() {
        return Math.max(1, Math.min(MAX_FINE_SAMPLE_GRID_STEP, fineSampleGridStep));
    }

    public void setFineStep(int value) { this.fineStep = value; }
    public void setSteepThreshold(int value) { this.steepThreshold = value; }
    public void setRefineWater(boolean value) { this.refineWater = value; }
    public void setCoreHalfWidth(int value) { this.coreHalfWidth = value; }
    public void setFineSampleGridStep(int value) { this.fineSampleGridStep = value; }

    public int ridgeWeight() { return ridgeWeight; }
    public int ridgeProminenceWeight() { return ridgeProminenceWeight; }
    public int ridgeSlopeWeight() { return ridgeSlopeWeight; }
    public int ridgeTurnWeight() { return ridgeTurnWeight; }
    public int ridgeWetWeight() { return ridgeWetWeight; }
    public int ridgeDepartureWeight() { return ridgeDepartureWeight; }
    public int summitFlatThreshold() { return summitFlatThreshold; }
    public int summitFlatReward() { return summitFlatReward; }
    public int knifeRidgePenalty() { return knifeRidgePenalty; }
    public int ridgeAsymmetryWeight() { return ridgeAsymmetryWeight; }
    public int ridgeAccumulatedSlopeWeight() { return ridgeAccumulatedSlopeWeight; }
    // 配置文件可能绕过界面的 0..32；选址和施工必须使用同一上限，避免恶意或误填的超大扫描域。
    public int obstacleMarginBlocks() { return Math.clamp(obstacleMarginBlocks,0,32); }

    public void setRidgeWeight(int v) { this.ridgeWeight = v; }
    public void setRidgeProminenceWeight(int v) { this.ridgeProminenceWeight = v; }
    public void setRidgeSlopeWeight(int v) { this.ridgeSlopeWeight = v; }
    public void setRidgeTurnWeight(int v) { this.ridgeTurnWeight = v; }
    public void setRidgeWetWeight(int v) { this.ridgeWetWeight = v; }
    public void setRidgeDepartureWeight(int v) { this.ridgeDepartureWeight = v; }
    public void setSummitFlatThreshold(int v) { this.summitFlatThreshold = v; }
    public void setSummitFlatReward(int v) { this.summitFlatReward = v; }
    public void setKnifeRidgePenalty(int v) { this.knifeRidgePenalty = v; }
    public void setRidgeAsymmetryWeight(int v) { this.ridgeAsymmetryWeight = v; }
    public void setRidgeAccumulatedSlopeWeight(int v) { this.ridgeAccumulatedSlopeWeight = v; }
    public void setObstacleMarginBlocks(int v) { this.obstacleMarginBlocks = v; }

    public void setWallLengthBlocks(long value) {
        this.wallLengthBlocks = clampWallLengthBlocks(value);
    }

    public static long clampWallLengthBlocks(long blocks) {
        // 界面与手改 JSON 的有限长度共用一个上限；0 仍是既有无限模式标记。
        return Math.clamp(blocks, UNLIMITED_LENGTH, MAX_WALL_LENGTH_BLOCKS);
    }

    public int unlimitedPrewarmBlocks() { return unlimitedPrewarmBlocks; }

    public void setUnlimitedPrewarmBlocks(int blocks) {
        // 至少请求一段；禁止配置直接制造超出单窗口工作集的无限墙启动任务。
        unlimitedPrewarmBlocks = Math.clamp(blocks, 1, MAX_UNLIMITED_PREWARM_BLOCKS);
    }

    public static long segmentsFor(long blocks) {
        // 先减后除，避免 Long.MAX_VALUE 向上取整时溢出变成一段。
        return blocks <= 0 ? 0L : 1L + (blocks - 1L) / SEGMENT_BLOCKS;
    }

    /**
     * Whether the configured length is bounded.
     *
     * <p>Reads the live values: this is the screen's and the file writer's view. Planning code must
     * ask {@link #parameters()} instead, so an edit made while a world is open cannot change what
     * that world generates.
     */
    public boolean bounded() { return wallLengthBlocks > UNLIMITED_LENGTH; }

    /**
     * Segments the configured length spans; {@code 0} when unbounded.
     *
     * <p>Live view, like {@link #bounded()}; planning code asks {@link #parameters()}.
     */
    public long configuredSegments() {
        return segmentsFor(wallLengthBlocks);
    }

    /** 配置页展示单窗口段数；具体世界还要按冻结边界再收紧。 */
    public long prewarmSegmentsTarget() {
        return startupSegments(wallLengthBlocks, unlimitedPrewarmBlocks);
    }

    public static long startupSegments(long wallLengthBlocks, int unlimitedPrewarmBlocks) {
        if (wallLengthBlocks <= 0) return segmentsFor(unlimitedPrewarmBlocks);
        return segmentsFor(clampWallLengthBlocks(wallLengthBlocks));
    }

    public GreatWallConfig copy() {
        var copy = new GreatWallConfig();
        copy.wallLengthBlocks = wallLengthBlocks;
        copy.unlimitedPrewarmBlocks = unlimitedPrewarmBlocks;
        copy.oceanBreakLengthBlocks = oceanBreakLengthBlocks;
        copy.ridgeWeight = ridgeWeight;
        copy.ridgeProminenceWeight = ridgeProminenceWeight;
        copy.ridgeSlopeWeight = ridgeSlopeWeight;
        copy.ridgeTurnWeight = ridgeTurnWeight;
        copy.ridgeWetWeight = ridgeWetWeight;
        copy.ridgeDepartureWeight = ridgeDepartureWeight;
        copy.summitFlatThreshold = summitFlatThreshold;
        copy.summitFlatReward = summitFlatReward;
        copy.knifeRidgePenalty = knifeRidgePenalty;
        copy.ridgeAsymmetryWeight = ridgeAsymmetryWeight;
        copy.ridgeAccumulatedSlopeWeight = ridgeAccumulatedSlopeWeight;
        copy.obstacleMarginBlocks = obstacleMarginBlocks;
        copy.fineSampleDriverIntervalMs = fineSampleDriverIntervalMs;
        copy.laneBatchOperations = laneBatchOperations;
        copy.chunkCacheLimit = chunkCacheLimit;
        copy.fineStep = fineStep;
        copy.steepThreshold = steepThreshold;
        copy.refineWater = refineWater;
        copy.coreHalfWidth = coreHalfWidth;
        copy.fineSampleGridStep = fineSampleGridStep;
        return copy;
    }

    /** Applies another configuration's values onto this one. */
    public void applyFrom(GreatWallConfig other) {
        Objects.requireNonNull(other);
        setWallLengthBlocks(other.wallLengthBlocks);
        setUnlimitedPrewarmBlocks(other.unlimitedPrewarmBlocks);
        oceanBreakLengthBlocks = other.oceanBreakLengthBlocks;
        ridgeWeight = other.ridgeWeight;
        ridgeProminenceWeight = other.ridgeProminenceWeight;
        ridgeSlopeWeight = other.ridgeSlopeWeight;
        ridgeTurnWeight = other.ridgeTurnWeight;
        ridgeWetWeight = other.ridgeWetWeight;
        ridgeDepartureWeight = other.ridgeDepartureWeight;
        summitFlatThreshold = other.summitFlatThreshold;
        summitFlatReward = other.summitFlatReward;
        knifeRidgePenalty = other.knifeRidgePenalty;
        ridgeAsymmetryWeight = other.ridgeAsymmetryWeight;
        ridgeAccumulatedSlopeWeight = other.ridgeAccumulatedSlopeWeight;
        obstacleMarginBlocks = other.obstacleMarginBlocks;
        // Raw assignment on purpose, like the ridge weights above: the accessors clamp on read, so the
        // value a screen staged and applied is what a reload reports.
        fineSampleDriverIntervalMs = other.fineSampleDriverIntervalMs;
        laneBatchOperations = other.laneBatchOperations;
        chunkCacheLimit = other.chunkCacheLimit;
        fineStep = other.fineStep;
        steepThreshold = other.steepThreshold;
        refineWater = other.refineWater;
        coreHalfWidth = other.coreHalfWidth;
        fineSampleGridStep = other.fineSampleGridStep;
    }

    public static GreatWallConfig get() {
        var current = instance;
        if (current != null) return current;
        synchronized (GreatWallConfig.class) {
            if (instance == null) instance = load();
            return instance;
        }
    }

    /**
     * Config file location.
     *
     * <p>{@code FabricLoader.getConfigDir()} throws in a bare JUnit JVM, where no
     * loader is initialised. Tests exercise planning paths that read this config,
     * so a missing loader must degrade to a working directory rather than fail
     * the test run.
     */
    public static Path file() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (RuntimeException | LinkageError noLoader) {
            return Path.of("config", FILE_NAME).toAbsolutePath();
        }
    }

    private static GreatWallConfig load() {
        var config = new GreatWallConfig();
        Path path = file();
        if (!Files.isRegularFile(path)) {
            write(config);
            return config;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            var json = GSON.fromJson(text, JsonObject.class);
            if (json == null) return config;
            if (json.has("wallLengthBlocks")) {
                try { config.setWallLengthBlocks(json.get("wallLengthBlocks").getAsLong()); }
                catch (RuntimeException malformed) { /* keep default */ }
            }
            readInt(json, "unlimitedPrewarmBlocks", config::setUnlimitedPrewarmBlocks);
            readInt(json, "oceanBreakLengthBlocks", config::setOceanBreakLengthBlocks);
            // 0.1.0 山脊打分参数：逐项读取，缺失或畸形回退默认值。
            readInt(json, "ridgeWeight", v -> config.ridgeWeight = v);
            readInt(json, "ridgeProminenceWeight", v -> config.ridgeProminenceWeight = v);
            readInt(json, "ridgeSlopeWeight", v -> config.ridgeSlopeWeight = v);
            readInt(json, "ridgeTurnWeight", v -> config.ridgeTurnWeight = v);
            readInt(json, "ridgeWetWeight", v -> config.ridgeWetWeight = v);
            readInt(json, "ridgeDepartureWeight", v -> config.ridgeDepartureWeight = v);
            readInt(json, "summitFlatThreshold", v -> config.summitFlatThreshold = v);
            readInt(json, "summitFlatReward", v -> config.summitFlatReward = v);
            readInt(json, "knifeRidgePenalty", v -> config.knifeRidgePenalty = v);
            readInt(json, "ridgeAsymmetryWeight", v -> config.ridgeAsymmetryWeight = v);
            readInt(json, "ridgeAccumulatedSlopeWeight", v -> config.ridgeAccumulatedSlopeWeight = v);
            readInt(json, "obstacleMarginBlocks", v -> config.obstacleMarginBlocks = v);
            // 0.1.0 性能调度与细采样精度旋钮。它们不参与配方语义哈希，但**必须**持久化：写进文件前
            // 这些旋钮只存在于内存里，Mod Menu 里调过 fineStep 之类，重启后静默回到默认值。
            readInt(json, "fineSampleDriverIntervalMs", v -> config.fineSampleDriverIntervalMs = v);
            readInt(json, "laneBatchOperations", v -> config.laneBatchOperations = v);
            readInt(json, "chunkCacheLimit", v -> config.chunkCacheLimit = v);
            readInt(json, "fineStep", v -> config.fineStep = v);
            readInt(json, "fineSampleGridStep", v -> config.fineSampleGridStep = v);
            readInt(json, "steepThreshold", v -> config.steepThreshold = v);
            readBoolean(json, "refineWater", v -> config.refineWater = v);
            readInt(json, "coreHalfWidth", v -> config.coreHalfWidth = v);
            // Older files also carry prewarmSegments and prewarmAllWhenBounded. They are
            // ignored rather than read: the prewarm count is derived from the length now,
            // and honouring a stored value would reintroduce the mismatch the derivation
            // exists to prevent. Leaving them in the file is harmless.
        } catch (IOException | RuntimeException failure) {
            GreatWallMod.LOGGER.warn("[GreatWall:config] {} is unreadable; defaults in use", path, failure);
        }
        return config;
    }

    private static void readInt(JsonObject json, String key, java.util.function.IntConsumer setter) {
        if (json.has(key)) {
            try { setter.accept(json.get(key).getAsInt()); }
            catch (RuntimeException malformed) { /* keep default */ }
        }
    }

    private static void readBoolean(JsonObject json, String key, java.util.function.Consumer<Boolean> setter) {
        if (json.has(key)) {
            try { setter.accept(json.get(key).getAsBoolean()); }
            catch (RuntimeException malformed) { /* keep default */ }
        }
    }

    /** Rewrites the file from the given values; a write failure is logged, never thrown. */
    public static void write(GreatWallConfig config) {
        Objects.requireNonNull(config);
        var json = new JsonObject();
        json.addProperty("wallLengthBlocks", config.wallLengthBlocks);
        json.addProperty("unlimitedPrewarmBlocks", config.unlimitedPrewarmBlocks);
        json.addProperty("oceanBreakLengthBlocks", config.oceanBreakLengthBlocks);
        // Written for a human reading the file: the prewarm count is derived, and
        // showing it here explains what the length implies without being read back.
        json.addProperty("prewarmSegmentsDerived", config.prewarmSegmentsTarget());
        json.addProperty("ridgeWeight", config.ridgeWeight);
        json.addProperty("ridgeProminenceWeight", config.ridgeProminenceWeight);
        json.addProperty("ridgeSlopeWeight", config.ridgeSlopeWeight);
        json.addProperty("ridgeTurnWeight", config.ridgeTurnWeight);
        json.addProperty("ridgeWetWeight", config.ridgeWetWeight);
        json.addProperty("ridgeDepartureWeight", config.ridgeDepartureWeight);
        json.addProperty("summitFlatThreshold", config.summitFlatThreshold);
        json.addProperty("summitFlatReward", config.summitFlatReward);
        json.addProperty("knifeRidgePenalty", config.knifeRidgePenalty);
        json.addProperty("ridgeAsymmetryWeight", config.ridgeAsymmetryWeight);
        json.addProperty("ridgeAccumulatedSlopeWeight", config.ridgeAccumulatedSlopeWeight);
        json.addProperty("obstacleMarginBlocks", config.obstacleMarginBlocks);
        // Raw values, not the clamped accessors: the file is the record of what was configured, and
        // clamping on write would silently rewrite a hand-edited out-of-range value instead of letting
        // the accessor's window decide at read time.
        json.addProperty("fineSampleDriverIntervalMs", config.fineSampleDriverIntervalMs);
        json.addProperty("laneBatchOperations", config.laneBatchOperations);
        json.addProperty("chunkCacheLimit", config.chunkCacheLimit);
        json.addProperty("fineStep", config.fineStep);
        json.addProperty("fineSampleGridStep", config.fineSampleGridStep);
        json.addProperty("steepThreshold", config.steepThreshold);
        json.addProperty("refineWater", config.refineWater);
        json.addProperty("coreHalfWidth", config.coreHalfWidth);
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            // Write-then-move so a crash mid-write cannot leave a half file behind.
            Path temporary = Files.createTempFile(path.getParent(), FILE_NAME, ".tmp");
            Files.writeString(temporary, GSON.toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            GreatWallMod.LOGGER.warn("[GreatWall:config] could not persist {}", path, failure);
        }
    }

    /**
     * Persists the current values. Called by the config screens after an edit.
     *
     * <p>When a world is already open, a change to a semantic value is reported: that world keeps
     * generating from its frozen snapshot, and reopening it will refuse with {@code RECIPE_MISMATCH}
     * because the saved parameter identity no longer matches. Saying so here is the only warning the
     * player gets before the refusal, so it must not be silent.
     */
    public static void save() {
        write(get());
        var frozen = frozenParameters;
        if (frozen == null) return;
        // 配置界面只服务现行正式配方，保存提示和新世界重开采用同一份身份。
        String live = get().semantic().landFirstDigest();
        String original = frozen.landFirstDigest();
        if (!original.equals(live)) {
            GreatWallMod.LOGGER.warn("[GreatWall:config] generation parameters changed ({} -> {}); this world "
                    + "keeps using the frozen set, and the next time it is opened it will require a new world",
                    original, live);
        }
    }

    /**
     * Test seam: drops the cached instance so a rewritten file is re-read.
     *
     * <p>Also drops the frozen snapshot, because the point of this seam is to observe a freshly read
     * file. Production never reloads a world's parameters mid-session.
     */
    public static void reload() {
        synchronized (GreatWallConfig.class) {
            instance = null;
            frozenParameters = null;
        }
    }
}
