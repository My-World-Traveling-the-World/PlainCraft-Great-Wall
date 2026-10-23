package local.plaincraft.greatwall.generation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import local.plaincraft.greatwall.GreatWallMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** Showcase templates. One instance per piece, all of them stamped in parallel. */
public final class ShowcaseStamp {
    private static final List<ShowcaseStamp> ALL = new ArrayList<>();

    private final String resource;
    private final int width;
    private final int depth;
    private final int originX;
    private final int originZ;
    private List<int[]> coords;
    private List<BlockState> states;
    private int[] columnBottom;
    private boolean loaded;
    private boolean settled;
    private boolean filling;
    private boolean finished;
    private int originY;
    private int cursor;
    private int fillU;
    private int fillV;
    private int filled;
    private long[] ticket;

    private ShowcaseStamp(String csvName, int w, int d, int centreX, int centreZ, int atX, int atZ) {
        this.resource = "/assets/plaincraft-great-wall/templates/" + csvName;
        this.width = w;
        this.depth = d;
        this.originX = atX - centreX;
        this.originZ = atZ - centreZ;
    }

    /** Registers one more piece to be stamped. */
    public static void start(String csvName, int w, int h, int d, int cx, int cz, int atX, int atZ) {
        ALL.add(new ShowcaseStamp(csvName, w, d, cx, cz, atX, atZ));
    }

    /** Advances every registered piece. */
    public static void tick(ServerLevel level) {
        for (int i = 0; i < ALL.size(); i++) {
            ALL.get(i).tickOne(level);
        }
    }

    private void tickOne(ServerLevel level) {
        if (finished) return;
        if (!loaded) {
            loaded = true;
            load();
            if (coords == null || coords.isEmpty()) {
                finished = true;
                GreatWallMod.LOGGER.warn("[GreatWall:wonder] SHOWCASE_EMPTY {}", resource);
                return;
            }
        }
        if (ticket == null) {
            ticket = ChunkHold.hold(level, originX, originZ, originX + width - 1, originZ + depth - 1);
        }
        if (!settled) {
            int highest = Integer.MIN_VALUE;
            for (int dx = 0; dx < width; dx += 4) {
                for (int dz = 0; dz < depth; dz += 4) {
                    int x = originX + dx;
                    int z = originZ + dz;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null) return;
                    highest = Math.max(highest, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15));
                }
            }
            if (highest == Integer.MIN_VALUE) return;
            originY = highest;
            settled = true;
        }
        if (!filling) {
            int budget = 6000;
            while (cursor < coords.size() && budget-- > 0) {
                int[] p = coords.get(cursor);
                level.setBlock(new BlockPos(originX + p[0], originY + p[1], originZ + p[2]), states.get(cursor), 2);
                cursor++;
            }
            if (cursor >= coords.size()) {
                GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_STAMPED {} blocks={} origin=({},{},{})",
                        resource, coords.size(), originX, originY, originZ);
                filling = true;
            }
            return;
        }
        int budget = 3000;
        while (budget-- > 0) {
            if (fillU >= width) {
                finished = true;
                ChunkHold.release(level, ticket);
                ticket = null;
                GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_FOOTED {} columns={}", resource, filled);
                return;
            }
            int bottom = columnBottom[fillU * depth + fillV];
            if (bottom > 0) {
                int x = originX + fillU;
                int z = originZ + fillV;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return;
                int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                for (int y = originY + bottom - 1; y > surface - 1; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
                        filled++;
                    }
                }
            }
            if (++fillV >= depth) {
                fillV = 0;
                fillU++;
            }
        }
    }

    private void load() {
        coords = new ArrayList<>();
        states = new ArrayList<>();
        columnBottom = new int[width * depth];
        Arrays.fill(columnBottom, -1);
        try (InputStream in = ShowcaseStamp.class.getResourceAsStream(resource)) {
            if (in == null) return;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 4);
                if (parts.length != 4) continue;
                int dx = Integer.parseInt(parts[0]);
                int dy = Integer.parseInt(parts[1]);
                int dz = Integer.parseInt(parts[2]);
                coords.add(new int[] {dx, dy, dz});
                states.add(BlockSpec.parse(parts[3]));
                if (dx >= 0 && dx < width && dz >= 0 && dz < depth) {
                    int i = dx * depth + dz;
                    if (columnBottom[i] < 0 || dy < columnBottom[i]) columnBottom[i] = dy;
                }
            }
        } catch (Exception ignored) {
            // A missing template simply means nothing is stamped.
        }
    }
}