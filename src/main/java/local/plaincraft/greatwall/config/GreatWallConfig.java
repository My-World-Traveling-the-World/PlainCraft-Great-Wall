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
    /**
     * Lengths at or below this are prewarmed in full before the player is handed
     * the world. Above it the wall prewarms only the opening segments, because a
     * full prewarm of an unbounded wall would never finish.
     */
    public static final long FULL_PREWARM_MAX_LENGTH = 100_000L;
    /** Opening segments prewarmed when the wall is longer than {@link #FULL_PREWARM_MAX_LENGTH}. */
    public static final int DEFAULT_PREWARM_SEGMENTS = 4;
    /** Segments are 2048 blocks; the value is repeated here so the UI can label lengths without a recipe. */
    public static final int SEGMENT_BLOCKS = 2048;
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
     * <p><b>256 是硬上限，不是建议值</b>：`CpuTerrainOracle.advance` 与 `WallFinePlanner.advance`
     * 都强制 primitiveLimit ∈ [1,256]，超过直接抛异常，所以访问器会把配置钳到该区间内。
     */
    private int laneBatchOperations = 256;

    /**
     * 每条 lane 缓存多少个「已烘焙的区块」（按 chunk 构造原点区分）。默认 64。
     *
     * <p>横断面一次扫掠约命中 13 个 cell（cell 宽 4 时），所以低于 13 会明显开始抖动；
     * 调大能提高命中率但按倍数占用堆内存。真机实测 16→64 把命中率从 72% 提到 ~85%，
     * 细采样省约 70-100s。
     */
    private int chunkCacheLimit = 64;

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
            int fineStep, int steepThreshold, boolean refineWater, int coreHalfWidth) {
        /**
         * Digest schema. <b>Bump this when the field list changes.</b> A world frozen under the old
         * schema must refuse rather than reinterpret its stored numbers under the new one.
         */
        public static final int SCHEMA = 1;

        /** The stock parameter set: what a fresh install generates. */
        public static final Semantic DEFAULTS = new GreatWallConfig().semantic();

        public boolean bounded() { return wallLengthBlocks > UNLIMITED_LENGTH; }

        /** Segments the frozen length spans; {@code 0} when unbounded. */
        public long configuredSegments() {
            if (!bounded()) return 0L;
            return Math.max(1L, (wallLengthBlocks + SEGMENT_BLOCKS - 1L) / SEGMENT_BLOCKS);
        }

        /** Segments that must be ready before gameplay begins; see the accessor's notes. */
        public long prewarmSegmentsTarget() {
            if (!bounded()) return DEFAULT_PREWARM_SEGMENTS;
            long total = configuredSegments();
            if (wallLengthBlocks <= FULL_PREWARM_MAX_LENGTH) return total;
            return Math.min(total, DEFAULT_PREWARM_SEGMENTS);
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
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            return local.plaincraft.greatwall.geometry.DeterministicHash.sha256(bytes.toByteArray());
        }
    }

    /** Snapshot of the values this instance holds right now, with accessor clamping applied. */
    public Semantic semantic() {
        return new Semantic(wallLengthBlocks, ridgeWeight, ridgeProminenceWeight, ridgeSlopeWeight,
                ridgeTurnWeight, ridgeWetWeight, ridgeDepartureWeight, summitFlatThreshold,
                summitFlatReward, knifeRidgePenalty, ridgeAsymmetryWeight, ridgeAccumulatedSlopeWeight,
                obstacleMarginBlocks, fineStep(), steepThreshold(), refineWater(), coreHalfWidth());
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

    /** Driver interval in ms, floored at 1 so a config typo cannot stop the stage from progressing. */
    public int fineSampleDriverIntervalMs() { return Math.max(1, fineSampleDriverIntervalMs); }

    /** Lane grant, clamped to the 1..256 window both advance contracts enforce. */
    public int laneBatchOperations() { return Math.max(1, Math.min(256, laneBatchOperations)); }

    /** Baked-chunk cache size per lane, clamped to a sane 4..64 so it cannot be configured to zero. */
    public int chunkCacheLimit() { return Math.max(4, Math.min(64, chunkCacheLimit)); }

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
    /** Core band half width, clamped to 0..LATERAL so it can never exceed the cross-section. */
    public int coreHalfWidth() { return Math.max(0, Math.min(24, coreHalfWidth)); }

    public void setFineStep(int value) { this.fineStep = value; }
    public void setSteepThreshold(int value) { this.steepThreshold = value; }
    public void setRefineWater(boolean value) { this.refineWater = value; }
    public void setCoreHalfWidth(int value) { this.coreHalfWidth = value; }

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
    public int obstacleMarginBlocks() { return obstacleMarginBlocks; }

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
        this.wallLengthBlocks = Math.max(0L, value);
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
        if (!bounded()) return 0L;
        return Math.max(1L, (wallLengthBlocks + SEGMENT_BLOCKS - 1L) / SEGMENT_BLOCKS);
    }

    /**
     * Segments that must be ready before gameplay begins.
     *
     * <h2>Derived, never entered</h2>
     * This is a function of the wall's length alone. An earlier revision exposed it
     * as a number the player typed, which let the two disagree — a length of 8189
     * blocks with a prewarm count of 4 silently prewarmed only the first 4 of the 5
     * segments the wall would build.
     *
     * <p>A bounded wall at or below {@link #FULL_PREWARM_MAX_LENGTH} is prewarmed in
     * full: that is the whole point of setting a length, and it is achievable within
     * the budget. Anything longer, including an unbounded wall, prewarms only the
     * opening {@link #DEFAULT_PREWARM_SEGMENTS}: asking for all of it would never
     * terminate.
     */
    public long prewarmSegmentsTarget() {
        if (!bounded()) return DEFAULT_PREWARM_SEGMENTS;
        long total = configuredSegments();
        if (wallLengthBlocks <= FULL_PREWARM_MAX_LENGTH) return total;
        return Math.min(total, DEFAULT_PREWARM_SEGMENTS);
    }

    public GreatWallConfig copy() {
        var copy = new GreatWallConfig();
        copy.wallLengthBlocks = wallLengthBlocks;
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
        return copy;
    }

    /** Applies another configuration's values onto this one. */
    public void applyFrom(GreatWallConfig other) {
        Objects.requireNonNull(other);
        setWallLengthBlocks(other.wallLengthBlocks);
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
        String live = parameterDigest();
        if (!frozen.digest().equals(live)) {
            GreatWallMod.LOGGER.warn("[GreatWall:config] generation parameters changed ({} -> {}); this world "
                    + "keeps using the frozen set, and the next time it is opened it will require a new world",
                    frozen.digest(), live);
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
