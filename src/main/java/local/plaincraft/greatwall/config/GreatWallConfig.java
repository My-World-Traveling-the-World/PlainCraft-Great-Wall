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

    public long wallLengthBlocks() { return wallLengthBlocks; }

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

    public boolean bounded() { return wallLengthBlocks > UNLIMITED_LENGTH; }

    /** Segments the configured length spans; {@code 0} when unbounded. */
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

    /** Persists the current values. Called by the config screens after an edit. */
    public static void save() {
        write(get());
    }

    /** Test seam: drops the cached instance so a rewritten file is re-read. */
    public static void reload() {
        synchronized (GreatWallConfig.class) { instance = null; }
    }
}
