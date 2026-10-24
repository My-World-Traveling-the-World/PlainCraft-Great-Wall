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

/**
 * Stamps the reference 敌台 (hollow watchtower) into the world.
 *
 * <p>Same rules as the gate: the base settles on the highest ground under the footprint, every column is
 * carried down to the real surface with a stone footing, and the wall corridor through the tower is
 * floored so the walkway stays continuous at the wall's walking level.
 */
public final class TowerStamp {
    private static final String RESOURCE = "/assets/plaincraft-great-wall/templates/tower.csv";
    public static final int WIDTH = 24;
    public static final int HEIGHT = 34;
    public static final int DEPTH = 24;
    /** Wall band centre inside the model, and the corridor half width. */
    private static final int CENTRE_X = 12;
    private static final int CENTRE_Z = 11;
    private static final int BAND_HALF = 5;
    /** Height of the walkway surface above the model base. */
    private static final int WALK_DY = 14;

    private static List<int[]> coords;
    private static List<BlockState> states;
    private static int[] columnBottom;
    private static boolean loaded;
    private static boolean finished;
    private static boolean autoGround = true;
    private static boolean filling;
    private static boolean flooring;
    private static int originX, originY, originZ;
    private static int cursor;
    private static long[] ticket;
    private static int fillX, fillZ;
    private static int filled;

    private TowerStamp() {
    }

    public static void start(int centreX, int centreZ) {
        originX = centreX - CENTRE_X;
        originZ = centreZ - CENTRE_Z;
        local.plaincraft.greatwall.compat.road.WallRoadGuard.publishRect(originX, originZ, originX + WIDTH - 1, originZ + DEPTH - 1);
        originY = 0;
        autoGround = true;
        filling = false;
        flooring = false;
        cursor = 0;
        fillX = 0;
        fillZ = 0;
        filled = 0;
        finished = false;
        ticket = null;
        load();
    }

    public static void tick(ServerLevel level) {
        if (finished || !loaded || coords == null || coords.isEmpty()) return;
        if (ticket == null) ticket = ChunkHold.hold(level, originX, originZ, originX + (WIDTH) - 1, originZ + (DEPTH) - 1);
        if (autoGround && !settle(level)) return;
        if (!filling && !flooring) {
            int budget = 4000;
            while (cursor < coords.size() && budget-- > 0) {
                int[] p = coords.get(cursor);
                level.setBlock(new BlockPos(originX + p[0], originY + p[1], originZ + p[2]), states.get(cursor), 2);
                local.plaincraft.greatwall.compat.road.WallRoadGuard.markColumn(originX + p[0], originZ + p[2]);
                cursor++;
            }
            if (cursor >= coords.size()) {
                GreatWallMod.LOGGER.info("[GreatWall:wonder] TOWER_STAMPED blocks={} origin=({},{},{})",
                        coords.size(), originX, originY, originZ);
                local.plaincraft.greatwall.generation.StampVerify.verify(level, "tower2.csv", originX, originY, originZ, coords, states);
                filling = true;
            }
            return;
        }
        if (flooring) {
            // Keep the wall corridor walkable: floor it at the wall's walking level.
            int budget = 256;
            while (budget-- > 0) {
                if (fillX > WIDTH) {
                    finished = true;
                    ChunkHold.release(level, ticket);
                    ticket = null;
                    return;
                }
                int dz = CENTRE_Z - BAND_HALF + fillZ;
                BlockPos pos = new BlockPos(originX + fillX, originY + WALK_DY, originZ + dz);
                if (level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, Blocks.STONE_BRICKS.defaultBlockState(), 2);
                    local.plaincraft.greatwall.compat.road.WallRoadGuard.markColumn(pos.getX(), pos.getZ());
                }
                if (++fillZ > BAND_HALF * 2) {
                    fillZ = 0;
                    fillX++;
                }
            }
            return;
        }
        int budget = 2000;
        while (budget-- > 0) {
            if (fillX >= WIDTH) {
                fillX = 0;
                fillZ = 0;
                flooring = true;
                GreatWallMod.LOGGER.info("[GreatWall:wonder] TOWER_FOOTED columns={} originY={}", filled, originY);
                return;
            }
            int bottom = columnBottom[fillX * DEPTH + fillZ];
            if (bottom > 0) {
                int x = originX + fillX;
                int z = originZ + fillZ;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return;
                int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                for (int y = originY + bottom - 1; y > surface - 1; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
                        local.plaincraft.greatwall.compat.road.WallRoadGuard.markColumn(pos.getX(), pos.getZ());
                        filled++;
                    }
                }
            }
            if (++fillZ >= DEPTH) {
                fillZ = 0;
                fillX++;
            }
        }
    }

    private static boolean settle(ServerLevel level) {
        int highest = Integer.MIN_VALUE;
        for (int dx = 0; dx < WIDTH; dx += 4) {
            for (int dz = 0; dz < DEPTH; dz += 4) {
                int x = originX + dx;
                int z = originZ + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return false;
                highest = Math.max(highest, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15));
            }
        }
        if (highest == Integer.MIN_VALUE) return false;
        originY = highest;
        autoGround = false;
        return true;
    }

    public static boolean finished() {
        return finished;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        coords = new ArrayList<>();
        states = new ArrayList<>();
        columnBottom = new int[WIDTH * DEPTH];
        Arrays.fill(columnBottom, -1);
        try (InputStream in = TowerStamp.class.getResourceAsStream(RESOURCE)) {
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
                if (dx >= 0 && dx < WIDTH && dz >= 0 && dz < DEPTH) {
                    int i = dx * DEPTH + dz;
                    if (columnBottom[i] < 0 || dy < columnBottom[i]) columnBottom[i] = dy;
                }
            }
        } catch (Exception broken) {
            // 过去静默吞掉：模板损坏/缺失会表现成「什么都没盖」，排查时毫无线索。
            GreatWallMod.LOGGER.warn("[GreatWall:wonder] TEMPLATE_LOAD_FAILED piece=TowerStamp resource={} ({})",
                    RESOURCE, broken.toString(), broken);
        }
    }
    /** 重新盖一遍：进度全部复位，下次 tick 从头铺。用于补洞 / 重画（/greatwall restamp）。 */
    public static void restart() {
        cursor = 0;
        finished = false;
        loaded = false;        filling = false;
        fillX = 0;
        fillZ = 0;
        filled = 0;
    }

}