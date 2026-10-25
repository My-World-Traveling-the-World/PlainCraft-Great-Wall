package local.plaincraft.greatwall.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.compat.road.WallRoadGuard;
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
    /** Paths whose read-only guard rectangles were already restored this process. */
    private static final Set<Path> RESTORED = new HashSet<>();

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
            restoreGuardRects(path, spawn);
            GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_SKIP already complete file={} source={}",
                    path, properties.getProperty("source", "unknown"));
        }
        return same;
    }

    /** Restores the read-only road-guard footprints recorded with the completed workshop. */
    private static void restoreGuardRects(Path path, BlockPos spawn) {
        if (!RESTORED.add(path)) return;
        try {
            boolean any = false;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.startsWith("rect=")) continue;
                String[] p = line.substring(5).split(",");
                if (p.length != 4) continue;
                WallRoadGuard.publishRect(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                        Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
                any = true;
            }
            if (!any) {
                // Markers written before rect persistence still need their protection footprint.
                publishGuardLayout(spawn);
            }
        } catch (IOException | RuntimeException unreadable) {
            GreatWallMod.LOGGER.warn("[GreatWall:wonder] SHOWCASE_GUARD_RESTORE_FAILED file={} reason={}",
                    path, unreadable.toString());
        }
    }

    /** Exact showcase footprints, also used when adopting a pre-marker save. */
    public static void publishGuardLayout(BlockPos spawn) {
        if (spawn == null) return;
        int x = spawn.getX(), z = spawn.getZ();
        // gate: origin spawn-136, spawn-19; 145 x 32
        WallRoadGuard.publishRect(x - 136, z - 19, x + 8, z + 12);
        // wall: origin spawn-88, spawn-64; 48 x 11 (the module is 11 wide, not the 18-row height)
        WallRoadGuard.publishRect(x - 88, z - 64, x - 41, z - 54);
        // stair: origin spawn-80, spawn+123; 32 x 11
        WallRoadGuard.publishRect(x - 80, z + 123, x - 49, z + 133);
        // showcase pieces, matching startFlatShowcase()
        WallRoadGuard.publishRect(x - 72, z + 184, x - 57, z + 199); // beacon 16x16
        WallRoadGuard.publishRect(x - 88, z + 296, x - 41, z + 343); // corner 48x48
        WallRoadGuard.publishRect(x - 71, z + 441, x - 58, z + 454); // fort 14x14
        WallRoadGuard.publishRect(x - 76, z + 84, x - 53, z + 107);  // tower2 24x24
        WallRoadGuard.publishRect(x - 101, z + 617, x - 28, z + 662); // palace2 74x46
        WallRoadGuard.publishRect(x - 87, z + 698, x - 42, z + 710);  // watergate 46x13
        WallRoadGuard.publishRect(x + 508, z + 190, x + 1291, z + 610); // kitset 784x421, new area
        // joined gate + left/right wall showcase at spawn+256
        WallRoadGuard.publishRect(x + 184, z - 19, x + 328, z + 12);
        WallRoadGuard.publishRect(x + 136, z - 5, x + 183, z + 5);
        WallRoadGuard.publishRect(x + 329, z - 5, x + 376, z + 5);
        // production-equivalent terrain demo: 160x160 at spawn+1600,+300
        WallRoadGuard.publishRect(x + 1520, z + 220, x + 1679, z + 379);
        // diagonal-approach demo: upright gate + wall bending away at spawn+256,+700
        WallRoadGuard.publishRect(x + 184, z + 681, x + 328, z + 712);
        WallRoadGuard.publishRect(x + 329, z + 681, x + 430, z + 790);
    }

    /**
     * Adopts a world that was already stamped before this marker existed. We sample a couple of
     * unmistakable template blocks in the expected footprints; this only runs at a flat world load
     * and never requests a chunk.
     */
    public static boolean canInspect(ServerLevel level, BlockPos spawn) {
        return level != null && spawn != null
                && level.getChunkSource().getChunkNow((spawn.getX() + 8) >> 4, (spawn.getZ() - 5) >> 4) != null
                && level.getChunkSource().getChunkNow((spawn.getX() - 64) >> 4, (spawn.getZ() + 3) >> 4) != null;
    }

    public static boolean detectExisting(ServerLevel level, BlockPos spawn) {
        if (level == null || spawn == null) return false;
        if (!(level.getChunkSource().getGenerator() instanceof FlatLevelSource)) return false;
        // Both anchors are inside the same gate and near spawn; requiring both avoids adopting on
        // one stray block without depending on a far corner chunk being loaded.
        // Gate tail: local (144,17,14), origin = spawn-136, spawn-19.
        // Gate centre: local (72,10,22), origin = spawn-136, spawn-19.
        return hasBlockAtColumn(level, spawn.getX() + 8, spawn.getZ() - 5, Blocks.STONE_BRICKS)
                && hasBlockAtColumn(level, spawn.getX() - 64, spawn.getZ() + 3, Blocks.STONE_BRICKS);
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
        try {
            if (Files.isRegularFile(path) && alreadyComplete(level, spawn)) return;
            if (WallRoadGuard.rectangles().isEmpty()) publishGuardLayout(spawn);
            Files.createDirectories(path.getParent());
            StringBuilder text = new StringBuilder()
                    .append("layout=").append(LAYOUT).append('\n')
                    .append("seed=").append(level.getSeed()).append('\n')
                    .append("spawnX=").append(spawn.getX()).append('\n')
                    .append("spawnZ=").append(spawn.getZ()).append('\n')
                    .append("source=").append(source).append('\n')
                    .append("completedAt=").append(java.time.Instant.now()).append('\n');
            for (int[] rect : WallRoadGuard.rectangles()) {
                text.append("rect=").append(rect[0]).append(',').append(rect[1]).append(',')
                        .append(rect[2]).append(',').append(rect[3]).append('\n');
            }
            Files.writeString(path, text.toString(), StandardCharsets.UTF_8);
            RESTORED.remove(path);
            GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_MARKED_COMPLETE file={} source={} rects={}",
                    path, source, WallRoadGuard.rectangles().size());
        } catch (IOException | RuntimeException writeFailure) {
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
