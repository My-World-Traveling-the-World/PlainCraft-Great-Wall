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
    /** Block state of each column's lowest template block. */
    private static BlockState[] columnBottomState;
    /** Global lowest template layer and its material; foundation is one slab at this level. */
    private static int bottomLayerY = Integer.MAX_VALUE;
    private static BlockState bottomLayerState;
    /** Natural WORLD_SURFACE sampled before the model is written, one value per footprint column. */
    private static int[] groundSurface;
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
        ShowcaseLayout.add("gate", originX, originZ, originX + WIDTH - 1, originZ + DEPTH - 1);
        local.plaincraft.greatwall.compat.road.WallRoadGuard.publishRect(originX, originZ, originX + WIDTH - 1, originZ + DEPTH - 1);
        originY = 0;
        autoGround = true;
        filling = false;
        cursor = 0;
        fillX = 0;
        fillZ = 0;
        filled = 0;
        finished = false;
        if (ticket != null) ChunkHold.releaseEverywhere(ticket);
        ticket = null;
        load();
    }

    /** Releases the old world's ticket and drops template/state references. */
    public static void reset(ServerLevel level) {
        if (ticket != null) {
            ChunkHold.release(level, ticket);
            ticket = null;
        }
        finished = true;
        filling = false;
        loaded = false;
        coords = null;
        states = null;
        columnBottom = null;
        columnBottomState = null;
        bottomLayerY = Integer.MAX_VALUE;
        bottomLayerState = null;
        groundSurface = null;
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
            // One global-bottom foundation slab; doorway band stays open. Do not stretch
            // individual decorative columns down from their own (higher) lowest block.
            boolean doorway = fillX >= 68 && fillX <= 76;
            if (bottom >= 0 && !doorway && bottomLayerY != Integer.MAX_VALUE) {
                BlockState fillState = bottomLayerState;
                if (fillState == null) fillState = Blocks.STONE.defaultBlockState();
                int x = originX + fillX;
                int z = originZ + fillZ;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return;                       // wait for the chunk, never guess
                int surface = groundSurface != null
                        ? groundSurface[fillX * DEPTH + fillZ]
                        : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                for (int y = originY + bottomLayerY - 1; y > surface - 1; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, fillState, 2);
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
        // Sample every footprint column. A 4-block grid can miss a narrow natural peak; that
        // would lower originY and bury the gate's high side even though the per-column fill is exact.
        int highest = Integer.MIN_VALUE;
        groundSurface = new int[WIDTH * DEPTH];
        for (int dx = 0; dx < WIDTH; dx++) {
            for (int dz = 0; dz < DEPTH; dz++) {
                int x = originX + dx, z = originZ + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return false;
                int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                groundSurface[dx * DEPTH + dz] = surface;
                highest = Math.max(highest, surface);
            }
        }
        if (highest == Integer.MIN_VALUE) return false;
        originY = highest - 5;   // 0.7.90：比 0.7.89 的 highest-8 抬高 3 格（门楼不再埋进地面）
        autoGround = false;
        return true;
    }

    /**
     * Lower the already-stamped standalone gate by {@code blocks} in the current world:
     * remove the old gate blocks, shift originY, and stamp/found again. Intended for the
     * showcase command because an existing world never re-samples its original height.
     */
    public static int lowerBy(ServerLevel level, int blocks) {
        if (level == null || coords == null || coords.isEmpty()) return 0;
        if (blocks <= 0) return 0;
        for (int i = 0; i < coords.size(); i++) {
            int[] p = coords.get(i);
            level.setBlock(new BlockPos(originX + p[0], originY + p[1], originZ + p[2]),
                    Blocks.AIR.defaultBlockState(), 2);
        }
        originY -= blocks;
        cursor = 0;
        finished = false;
        filling = false;
        fillX = 0;
        fillZ = 0;
        filled = 0;
        autoGround = false;
        local.plaincraft.greatwall.generation.ShowcaseGate.expect();
        GreatWallMod.LOGGER.info("[GreatWall:wonder] GATE_LOWERED blocks={} newOriginY={}", blocks, originY);
        return blocks;
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
        columnBottomState = new BlockState[WIDTH * DEPTH];
        bottomLayerY = Integer.MAX_VALUE;
        bottomLayerState = null;
        groundSurface = null;
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
                BlockState parsed = BlockSpec.parse(parts[3]);
                states.add(parsed);
                if (dx >= 0 && dx < WIDTH && dz >= 0 && dz < DEPTH) {
                    int i = dx * DEPTH + dz;
                    int current = columnBottom[i];
                    if (current < 0 || dy < current) {
                        columnBottom[i] = dy;
                        columnBottomState[i] = parsed;
                    }
                    if (dy < bottomLayerY) {
                        bottomLayerY = dy;
                        bottomLayerState = parsed;
                    }
                }
            }
        } catch (Exception broken) {
            // 过去静默吞掉：模板损坏/缺失会表现成「什么都没盖」，排查时毫无线索。
            GreatWallMod.LOGGER.warn("[GreatWall:wonder] TEMPLATE_LOAD_FAILED piece=GateStamp resource={} ({})",
                    RESOURCE, broken.toString(), broken);
        }
    }
    /** 重新盖一遍：进度全部复位，下次 tick 从头铺。用于补洞 / 重画（/greatwall restamp）。 */
    public static void restart() {
        // 绝对不能清 loaded：load() 只从 start() 调用，清了之后 tick() 会在 `!loaded`
        // 处永久 return —— 命令回执说「queued again」，实际一个方块都不会重盖。
        // 模板资源在运行期不会变化，已加载的数据继续有效。
        if (coords == null) {
            GreatWallMod.LOGGER.warn("[GreatWall:wonder] GATE_RESTAMP_SKIPPED (this piece was never started in this world)");
            return;
        }
        cursor = 0;
        finished = false;
        filling = false;
        fillX = 0;
        fillZ = 0;
        filled = 0;
        // 重画也是一轮新的「必须盖完」，重新占一个 expect() 名额，否则门记账会错。
        local.plaincraft.greatwall.generation.ShowcaseGate.expect();
        GreatWallMod.LOGGER.info("[GreatWall:wonder] GATE_RESTAMP blocks={}", coords.size());
    }

}