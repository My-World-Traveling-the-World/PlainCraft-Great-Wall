package local.plaincraft.greatwall.generation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.GreatWallMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Stamps one standalone run of the wall module, so the wall can be inspected on its own instead of only
 * as part of a generated route.
 */
public final class WallStamp {
    private static final String RESOURCE = "/assets/plaincraft-great-wall/templates/wall_module.csv";

    private static List<int[]> coords;      // {phase, t, depth}
    private static List<BlockState> states;
    private static boolean loaded;
    private static boolean finished;
    private static boolean autoGround = true;
    private static int originX, originY, originZ;
    private static int cursor;
    private static long[] ticket;

    private WallStamp() {
    }

    /** Stamps {@link WallProfile#PERIOD} blocks of wall centred on ({@code centreX}, {@code centreZ}). */
    public static void start(int centreX, int centreZ) {
        originX = centreX - WallProfile.PERIOD / 2;
        originZ = centreZ;
        originY = 0;
        autoGround = true;
        cursor = 0;
        finished = false;
        ticket = null;
        load();
    }

    public static void tick(ServerLevel level) {
        if (finished || !loaded || coords == null || coords.isEmpty()) return;
        if (ticket == null) ticket = ChunkHold.hold(level, originX, originZ, originX + (WallProfile.PERIOD) - 1, originZ + (11) - 1);
        if (autoGround) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(originX >> 4, originZ >> 4);
            if (chunk == null) return;
            originY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, originX & 15, originZ & 15);
            autoGround = false;
        }
        int budget = 4000;
        while (cursor < coords.size() && budget-- > 0) {
            int[] p = coords.get(cursor);
            // depth 0 is the top of the section, so it maps to the highest y.
            int y = originY + (WallProfile.DEPTH - 1) - p[2];
            level.setBlock(new BlockPos(originX + p[0], y, originZ + p[1]), states.get(cursor), 2);
            cursor++;
        }
        if (cursor >= coords.size()) {
            finished = true;
                ChunkHold.release(level, ticket);
                ticket = null;
            GreatWallMod.LOGGER.info("[GreatWall:wonder] WALL_STAMPED blocks={} origin=({},{},{})",
                    coords.size(), originX, originY, originZ);
        }
    }

    public static boolean finished() {
        return finished;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        coords = new ArrayList<>();
        states = new ArrayList<>();
        try (InputStream in = WallStamp.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 4);
                if (parts.length != 4) continue;
                coords.add(new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])});
                states.add(BlockSpec.parse(parts[3]));
            }
        } catch (Exception ignored) {
            // A missing template simply means nothing is stamped.
        }
    }
}
