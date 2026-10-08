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
    private int prewarmSegments = DEFAULT_PREWARM_SEGMENTS;
    private boolean prewarmAllWhenBounded = true;

    public long wallLengthBlocks() { return wallLengthBlocks; }
    public int prewarmSegments() { return prewarmSegments; }
    public boolean prewarmAllWhenBounded() { return prewarmAllWhenBounded; }

    public void setWallLengthBlocks(long value) {
        this.wallLengthBlocks = Math.max(0L, value);
    }

    public void setPrewarmSegments(int value) {
        this.prewarmSegments = Math.max(0, Math.min(4096, value));
    }

    public void setPrewarmAllWhenBounded(boolean value) { this.prewarmAllWhenBounded = value; }

    public boolean bounded() { return wallLengthBlocks > UNLIMITED_LENGTH; }

    /** Segments the configured length spans; {@code 0} when unbounded. */
    public long configuredSegments() {
        if (!bounded()) return 0L;
        return Math.max(1L, (wallLengthBlocks + SEGMENT_BLOCKS - 1L) / SEGMENT_BLOCKS);
    }

    /**
     * Segments that must be ready before gameplay begins.
     *
     * <p>An unbounded wall prewarms only the opening segments: asking for all of
     * them would never terminate. A bounded wall at or below
     * {@link #FULL_PREWARM_MAX_LENGTH} prewarms every segment it will ever build,
     * which is the point of the setting; a longer bounded wall falls back to the
     * opening segments for the same reason as the unbounded case.
     */
    public long prewarmSegmentsTarget() {
        if (!bounded()) return Math.max(0, prewarmSegments);
        long total = configuredSegments();
        if (prewarmAllWhenBounded && wallLengthBlocks <= FULL_PREWARM_MAX_LENGTH) return total;
        if (prewarmSegments <= 0) return total;
        return Math.min(total, prewarmSegments);
    }

    public GreatWallConfig copy() {
        var copy = new GreatWallConfig();
        copy.wallLengthBlocks = wallLengthBlocks;
        copy.prewarmSegments = prewarmSegments;
        copy.prewarmAllWhenBounded = prewarmAllWhenBounded;
        return copy;
    }

    /** Never throws: an unusable value keeps its default. */
    public void applyFrom(GreatWallConfig other) {
        Objects.requireNonNull(other);
        setWallLengthBlocks(other.wallLengthBlocks);
        setPrewarmSegments(other.prewarmSegments);
        setPrewarmAllWhenBounded(other.prewarmAllWhenBounded);
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
            if (json.has("prewarmSegments")) {
                try { config.setPrewarmSegments(json.get("prewarmSegments").getAsInt()); }
                catch (RuntimeException malformed) { /* keep default */ }
            }
            if (json.has("prewarmAllWhenBounded")) {
                try { config.setPrewarmAllWhenBounded(json.get("prewarmAllWhenBounded").getAsBoolean()); }
                catch (RuntimeException malformed) { /* keep default */ }
            }
        } catch (IOException | RuntimeException failure) {
            GreatWallMod.LOGGER.warn("[GreatWall:config] {} is unreadable; defaults in use", path, failure);
        }
        return config;
    }

    /** Rewrites the file from the given values; a write failure is logged, never thrown. */
    public static void write(GreatWallConfig config) {
        Objects.requireNonNull(config);
        var json = new JsonObject();
        json.addProperty("wallLengthBlocks", config.wallLengthBlocks);
        json.addProperty("prewarmSegments", config.prewarmSegments);
        json.addProperty("prewarmAllWhenBounded", config.prewarmAllWhenBounded);
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
