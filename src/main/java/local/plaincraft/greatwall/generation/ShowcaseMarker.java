package local.plaincraft.greatwall.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import local.plaincraft.greatwall.GreatWallMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.storage.LevelResource;

/**
 * One completion marker per save, written only after every showcase piece has finished.
 *
 * <p>The flat showcase world is a template workshop, not a regenerating feature. Without this
 * marker every server start registered all pieces again and stamped the same models on top of the
 * already-edited world; re-entering a save therefore lifted another copy onto the old roofline.
 * The marker is keyed by layout version, seed and spawn, so a world copied to another folder or a
 * genuinely different flat world is not mistaken for the same workshop.
 */
public final class ShowcaseMarker {
    private static final String FILE = "showcase-complete.properties";
    private static final String LAYOUT = "2026-10-25-showcase-v1";

    private ShowcaseMarker() {
    }

    private static Path file(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("greatwall").resolve(FILE);
    }

    /** True only for the same layout, seed and spawn point. */
    public static boolean alreadyComplete(ServerLevel level, BlockPos spawn) {
        if (level == null || spawn == null) return false;
        Path path = file(level);
        if (!Files.isRegularFile(path)) return false;
        Properties properties = new Properties();
        try (var in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(in);
        } catch (IOException | RuntimeException unreadable) {
            GreatWallMod.LOGGER.warn("[GreatWall:wonder] SHOWCASE_MARKER_UNREADABLE file={} reason={}",
                    path, unreadable.toString());
            return false;
        }
        boolean same = LAYOUT.equals(properties.getProperty("layout"))
                && Long.toString(level.getSeed()).equals(properties.getProperty("seed"))
                && Integer.toString(spawn.getX()).equals(properties.getProperty("spawnX"))
                && Integer.toString(spawn.getZ()).equals(properties.getProperty("spawnZ"));
        if (same) {
            GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_SKIP already complete file={} source={}",
                    path, properties.getProperty("source", "unknown"));
        }
        return same;
    }

    /**
     * Adopts a world that was already stamped before this marker existed. We sample a couple of
     * unmistakable template blocks in the expected footprints; this only runs at a flat world load
     * and never requests a chunk.
     */
    public static boolean canInspect(ServerLevel level, BlockPos spawn) {
        return level != null && spawn != null
                && level.getChunkSource().getChunkNow((spawn.getX() + 8) >> 4, (spawn.getZ() - 5) >> 4) != null
                && level.getChunkSource().getChunkNow((spawn.getX() - 110) >> 4, (spawn.getZ() - 16) >> 4) != null;
    }

    public static boolean detectExisting(ServerLevel level, BlockPos spawn) {
        if (level == null || spawn == null) return false;
        if (!(level.getChunkSource().getGenerator() instanceof FlatLevelSource)) return false;
        // Gate tail: local (144,17,14) stone_bricks, gate origin = spawn-64-72, spawn-19.
        if (hasBlockAtColumn(level, spawn.getX() + 8, spawn.getZ() - 5, Blocks.STONE_BRICKS)) return true;
        // Palace2 floor: local (0,0,7) smooth quartz, origin = spawn-64-46, spawn-23.
        return hasBlockAtColumn(level, spawn.getX() - 110, spawn.getZ() - 16, Blocks.SMOOTH_QUARTZ);
    }

    private static boolean hasBlockAtColumn(ServerLevel level, int x, int z, net.minecraft.world.level.block.Block block) {
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return false;
        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
            if (level.getBlockState(new BlockPos(x, y, z)).is(block)) return true;
        }
        return false;
    }

    /** Writes the marker once; safe to call every tick. */
    public static void markComplete(ServerLevel level, BlockPos spawn, String source) {
        if (level == null || spawn == null) return;
        Path path = file(level);
        if (Files.isRegularFile(path)) return;
        try {
            Files.createDirectories(path.getParent());
            String text = "layout=" + LAYOUT + "\n"
                    + "seed=" + level.getSeed() + "\n"
                    + "spawnX=" + spawn.getX() + "\n"
                    + "spawnZ=" + spawn.getZ() + "\n"
                    + "source=" + source + "\n"
                    + "completedAt=" + java.time.Instant.now() + "\n";
            Files.writeString(path, text, StandardCharsets.UTF_8);
            GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_MARKED_COMPLETE file={} source={}",
                    path, source);
        } catch (IOException writeFailure) {
            GreatWallMod.LOGGER.warn("[GreatWall:wonder] SHOWCASE_MARKER_WRITE_FAILED file={} reason={}",
                    path, writeFailure.toString());
        }
    }

    /** Remove the marker so the next load deliberately restamps the workshop from template. */
    public static boolean clear(ServerLevel level) {
        if (level == null) return false;
        try {
            return Files.deleteIfExists(file(level));
        } catch (IOException failure) {
            GreatWallMod.LOGGER.warn("[GreatWall:wonder] SHOWCASE_MARKER_CLEAR_FAILED reason={}",
                    failure.toString());
            return false;
        }
    }
}
