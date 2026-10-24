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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Stamps the player's reference gate - a complete gate complex with a wall band, arched gateway, stairs
 * and a two-tier gatehouse - into the world.
 *
 * <p>The site is levelled the way a real pass is: the gate is planted on the highest ground under its
 * footprint, and every column of the model is then carried down to the actual terrain with a solid stone
 * footing. Nothing floats, nothing is buried, and the wall that meets it only has to match one height.
 */
public final class GateStamp {
    private static final String RESOURCE = "/assets/plaincraft-great-wall/templates/gate.csv";
    /** Footprint in blocks, from the exported model. */
    public static final int WIDTH = 145;
    public static final int HEIGHT = 44;
    public static final int DEPTH = 32;
    /** Where the gate centre sits inside the model (wall band z 15..25 of 32, arch centre x=72). */
    private static final int CENTRE_X = 72;
    private static final int CENTRE_Z = 19;

    private static List<int[]> coords;
    private static List<BlockState> states;
    /** Lowest model layer per column, indexed [x * DEPTH + z]; -1 where the column is empty. */
    private static int[] columnBottom;
    private static boolean loaded;
    private static boolean finished;
    private static boolean autoGround = true;
    private static boolean filling;
    private static int originX, originY, originZ;
    private static int cursor;
    private static long[] ticket;
    private static int fillX, fillZ;
    private static int filled;

    private GateStamp() {
    }

    /** Centres the gate on ({@code centreX}, {@code centreZ}); the base settles on the highest ground. */
    public static void start(int centreX, int centreZ) {
        originX = centreX - CENTRE_X;
        originZ = centreZ - CENTRE_Z;
        local.plaincraft.greatwall.generation.ShowcaseGate.expect();
        local.plaincraft.greatwall.compat.road.WallRoadGuard.publishRect(originX, originZ, originX + WIDTH - 1, originZ + DEPTH - 1);
        originY = 0;
        autoGround = true;
        filling = false;
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
        if (autoGround && !settleOnHighestGround(level)) return;
        if (!filling) {
            int budget = 4000;
            while (cursor < coords.size() && budget-- > 0) {
                int[] p = coords.get(cursor);
                level.setBlock(new BlockPos(originX + p[0], originY + p[1], originZ + p[2]), states.get(cursor), 2);
                local.plaincraft.greatwall.compat.road.WallRoadGuard.markColumn(originX + p[0], originZ + p[2]);
                cursor++;
            }
            if (cursor >= coords.size()) {
                GreatWallMod.LOGGER.info("[GreatWall:wonder] GATE_STAMPED blocks={} origin=({},{},{})",
                        coords.size(), originX, originY, originZ);
                local.plaincraft.greatwall.generation.StampVerify.verify(level, "gate.csv", originX, originY, originZ, coords, states);
                filling = true;
            }
            return;
        }
        // Carry every column of the model down to the real surface with a solid footing.
        int budget = 2000;
        while (budget-- > 0) {
            if (fillX >= WIDTH) {
                finished = true;

                local.plaincraft.greatwall.generation.ShowcaseGate.done();
                ChunkHold.release(level, ticket);
                ticket = null;
                GreatWallMod.LOGGER.info("[GreatWall:wonder] GATE_FOOTED columns={} originY={}", filled, originY);
                return;
            }
            int bottom = columnBottom[fillX * DEPTH + fillZ];
            if (bottom > 0) {
                int x = originX + fillX;
                int z = originZ + fillZ;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return;                       // wait for the chunk, never guess
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

    /** Plants the base on the highest ground under the footprint, once every chunk involved is loaded. */
    private static boolean settleOnHighestGround(ServerLevel level) {
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
        originY = highest - 2;   // 城门比地面低 2 格（走道对齐需要）
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
        java.util.Arrays.fill(columnBottom, -1);
        try (InputStream in = GateStamp.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                // The block spec itself contains commas, so split only the three numeric fields off.
                String[] parts = line.split(",", 4);
                if (parts.length != 4) continue;
                int dx = Integer.parseInt(parts[0]);
                int dy = Integer.parseInt(parts[1]);
                int dz = Integer.parseInt(parts[2]);
                coords.add(new int[] {dx, dy, dz});
                states.add(BlockSpec.parse(parts[3]));
                if (dx >= 0 && dx < WIDTH && dz >= 0 && dz < DEPTH) {
                    int i = dx * DEPTH + dz;
                    int current = columnBottom[i];
                    if (current < 0 || dy < current) columnBottom[i] = dy;
                }
            }
        } catch (Exception ignored) {
            // A missing template simply means nothing is stamped.
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