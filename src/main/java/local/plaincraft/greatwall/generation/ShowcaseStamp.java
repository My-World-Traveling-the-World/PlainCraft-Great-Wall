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
    private final String accountingName;
    private final int width;
    private final int depth;
    private final int heightOffset;
    /** true = 模板最底层直接坐在地表上（门楼走这条），不再用 heightOffset 微调。 */
    private final boolean onGround;
    private final boolean wallModule;
    /** Inclusive source-x clip; gatehouse-only pieces reject the template's old wall wings. */
    private final int clipMinX;
    private final int clipMaxX;
    /** Wall-module phase rotation; 3 turns the gate-side start from 垛 into 缺垛垛垛. */
    private final int wallPhaseOffset;
    /** Gate passage columns that must never receive foundation fill. */
    private final int noFillMinX;
    private final int noFillMaxX;
    private final int originX;
    private final int originZ;
    private List<int[]> coords;
    private List<BlockState> states;
    private int[] columnBottom;
    /** Block state of each column's lowest template block. */
    private BlockState[] columnBottomState;
    /** Global lowest template layer and its material; the foundation is a single slab at this level. */
    private int bottomLayerY = Integer.MAX_VALUE;
    private BlockState bottomLayerState;
    private boolean loaded;
    private boolean settled;
    private boolean filling;
    private boolean finished;
    private int originY;
    private int cursor;
    private int[] groundSurface;
    private int fillU;
    private int fillV;
    private int filled;
    private long[] ticket;

    private ShowcaseStamp(String csvName, int w, int d, int centreX, int centreZ, int atX, int atZ,
                          int heightOffset, boolean wallModule, int clipMinX, int clipMaxX,
                          int wallPhaseOffset, int noFillMinX, int noFillMaxX, boolean onGround) {
        this.resource = "/assets/plaincraft-great-wall/templates/" + csvName;
        this.accountingName = csvName + "@" + atX + "," + atZ;
        this.width = w;
        this.depth = d;
        this.heightOffset = heightOffset;
        this.onGround = onGround;
        this.wallModule = wallModule;
        this.clipMinX = clipMinX;
        this.clipMaxX = clipMaxX;
        this.wallPhaseOffset = wallPhaseOffset;
        // Store the aperture in clipped-local x, the same coordinate used by fillU.
        this.noFillMinX = noFillMinX - clipMinX;
        this.noFillMaxX = noFillMaxX - clipMinX;
        this.originX = atX - centreX;
        this.originZ = atZ - centreZ;
    }

    /** Registers one more piece to be stamped. */
    public static void start(String csvName, int w, int h, int d, int cx, int cz, int atX, int atZ) {
        start(csvName, w, h, d, cx, cz, atX, atZ, 0, false);
    }

    /**
     * Adds one piece with an explicit height offset and optional wall-module axis mapping.
     *
     * <p>Wall module rows are {@code phase,t,depth} with depth counted downward from the top; the
     * showcase converter maps them to x/z/y. Gate rows are already x/y/z and use the normal path.
     */
    public static void start(String csvName, int w, int h, int d, int cx, int cz, int atX, int atZ,
                             int heightOffset, boolean wallModule) {
        start(csvName, w, h, d, cx, cz, atX, atZ, heightOffset, wallModule, 0, w - 1);
    }

    /** Same, plus an inclusive source-x clip; used to remove the gate template's old wall wings. */
    public static void start(String csvName, int w, int h, int d, int cx, int cz, int atX, int atZ,
                             int heightOffset, boolean wallModule, int clipMinX, int clipMaxX) {
        start(csvName, w, h, d, cx, cz, atX, atZ, heightOffset, wallModule, clipMinX, clipMaxX, 0);
    }

    /** Wall module with an explicit 4-phase crenellation rotation at its source end. */
    public static void startWall(String csvName, int w, int h, int d, int cx, int cz, int atX, int atZ,
                                 int heightOffset, int phaseOffset) {
        start(csvName, w, h, d, cx, cz, atX, atZ, heightOffset, true, 0, w - 1, phaseOffset);
    }

    /**
     * 门楼「直接落地」：模板最底层（局部 y=0，玩家精修封的底板）坐在该处地表上。
     *
     * <p>不再传 heightOffset，也不用「最高点 + 偏移」那套：footprint 里的树/村屋/土包会把最高点抬高
     * 好几格，门楼就会被压进地面（0.7.89 真机埋了 4 格）。这里与城墙共用 {@link GroundLevel} 的中位数，
     * 两者落在同一平面，端面接口逐格对齐。
     */
    public static void startGateOnGround(String csvName, int w, int h, int d, int cx, int cz,
                                         int atX, int atZ) {
        start(csvName, w, h, d, cx, cz, atX, atZ, 0, false, 0, w - 1, 0, -1, -1, true);
    }

    /** Gate placement that keeps its passage columns free of foundation fill. */
    public static void startGate(String csvName, int w, int h, int d, int cx, int cz, int atX, int atZ,
                                 int heightOffset, int noFillMinX, int noFillMaxX) {
        start(csvName, w, h, d, cx, cz, atX, atZ, heightOffset, false, 0, w - 1, 0,
                noFillMinX, noFillMaxX, false);
    }

    /** Gatehouse-only gate: the template file stays whole, but its old wall wings are not shown. */
    public static void startGateClipped(String csvName, int w, int h, int d, int cx, int cz,
                                        int atX, int atZ, int heightOffset,
                                        int clipMinX, int clipMaxX,
                                        int noFillMinX, int noFillMaxX) {
        start(csvName, w, h, d, cx, cz, atX, atZ, heightOffset, false, clipMinX, clipMaxX, 0,
                noFillMinX, noFillMaxX, false);
    }

    private static void start(String csvName, int w, int h, int d, int cx, int cz, int atX, int atZ,
                              int heightOffset, boolean wallModule, int clipMinX, int clipMaxX,
                              int wallPhaseOffset) {
        start(csvName, w, h, d, cx, cz, atX, atZ, heightOffset, wallModule, clipMinX, clipMaxX,
                wallPhaseOffset, -1, -1, false);
    }

    private static void start(String csvName, int w, int h, int d, int cx, int cz, int atX, int atZ,
                              int heightOffset, boolean wallModule, int clipMinX, int clipMaxX,
                              int wallPhaseOffset, int noFillMinX, int noFillMaxX, boolean onGround) {
        ShowcaseStamp piece = new ShowcaseStamp(csvName, w, d, cx, cz, atX, atZ, heightOffset, wallModule,
                clipMinX, clipMaxX, wallPhaseOffset, noFillMinX, noFillMaxX, onGround);
        ALL.add(piece);
        local.plaincraft.greatwall.generation.ShowcaseGate.expect(piece.accountingName);
        int layoutMinX = atX - cx + clipMinX;
        int layoutMaxX = atX - cx + clipMaxX;
        ShowcaseLayout.add(csvName, layoutMinX, atZ - cz, layoutMaxX, atZ - cz + d - 1);
        local.plaincraft.greatwall.compat.road.WallRoadGuard.publishRect(layoutMinX, atZ - cz, layoutMaxX, atZ - cz + d - 1);
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

                local.plaincraft.greatwall.generation.ShowcaseGate.done(accountingName);
                GreatWallMod.LOGGER.warn("[GreatWall:wonder] SHOWCASE_EMPTY {}", resource);
                return;
            }
        }
        if (ticket == null) {
            ticket = ChunkHold.hold(level, originX, originZ, originX + width - 1, originZ + depth - 1);
        }
        if (!settled) {
            if (onGround) {
                // 直接落地：模板最底层坐在 footprint 的地表上（中位数），与城墙同一口径。
                if (groundSurface == null || groundSurface.length < width * depth) {
                    groundSurface = new int[width * depth];
                }
                int ground = GroundLevel.fill(level, originX, originZ, width, depth, groundSurface);
                if (ground == GroundLevel.MISSING) return;
                originY = ground;
                settled = true;
            } else {
                int highest = Integer.MIN_VALUE;
                for (int dx = 0; dx < width; dx += 4) {
                    for (int dz = 0; dz < depth; dz += 4) {
                        int x = originX + dx;
                        int z = originZ + dz;
                        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                        if (chunk == null) return;
                        highest = Math.max(highest,
                                chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15));
                    }
                }
                if (highest == Integer.MIN_VALUE) return;
                originY = highest + heightOffset;
                groundSurface = new int[width * depth];
                for (int dx = 0; dx < width; dx++) {
                    for (int dz = 0; dz < depth; dz++) {
                        int x = originX + dx, z = originZ + dz;
                        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                        if (chunk == null) return;
                        groundSurface[dx * depth + dz] =
                                chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                    }
                }
                settled = true;
            }
        }
        if (!filling) {
            int budget = 6000;
            while (cursor < coords.size() && budget-- > 0) {
                int[] p = coords.get(cursor);
                level.setBlock(new BlockPos(originX + p[0], originY + p[1], originZ + p[2]),
                        states.get(cursor), StampWrite.flags(states.get(cursor)));
                local.plaincraft.greatwall.compat.road.WallRoadGuard.markColumn(originX + p[0], originZ + p[2]);
                cursor++;
            }
            if (cursor >= coords.size()) {
                GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_STAMPED {} blocks={} origin=({},{},{})",
                        resource, coords.size(), originX, originY, originZ);
                local.plaincraft.greatwall.generation.StampVerify.verify(level, resource, originX, originY, originZ, coords, states);
                filling = true;
            }
            return;
        }
        int budget = 3000;
        while (budget-- > 0) {
            if (fillU >= width) {
                finished = true;

                local.plaincraft.greatwall.generation.ShowcaseGate.done(accountingName);
                ChunkHold.release(level, ticket);
                ticket = null;
                GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_FOOTED {} columns={}", resource, filled);
                return;
            }
            int bottom = columnBottom[fillU * depth + fillV];
            // 只给「模板最底层本来就有方块」的列往下补（全局最底层那一层）。
            // 这样既有底板的结构被整层托住，又不会把高处的装饰柱/拱券拉成竖井；
            // 玩家有意留空的口子（门洞等）本身没有最底层方块，自动保持贯通。
            boolean aperture = fillU >= noFillMinX && fillU <= noFillMaxX;
            boolean footed = bottom >= 0 && bottom == bottomLayerY;
            boolean foundationColumn = footed && !aperture && bottomLayerY != Integer.MAX_VALUE;
            if (foundationColumn) {
                BlockState fillState = bottomLayerState;
                if (fillState == null) fillState = Blocks.STONE.defaultBlockState();
                int x = originX + fillU;
                int z = originZ + fillV;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return;
                int surface = groundSurface != null
                        ? groundSurface[fillU * depth + fillV]
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
        columnBottomState = new BlockState[width * depth];
        bottomLayerY = Integer.MAX_VALUE;
        bottomLayerState = null;
        groundSurface = null;
        Arrays.fill(columnBottom, -1);
        try (InputStream in = ShowcaseStamp.class.getResourceAsStream(resource)) {
            if (in == null) return;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 4);
                if (parts.length != 4) continue;
                int rawX = Integer.parseInt(parts[0]);
                if (rawX < clipMinX || rawX > clipMaxX) continue;
                int rawY = Integer.parseInt(parts[1]);
                int rawZ = Integer.parseInt(parts[2]);
                int dx = wallModule
                        ? Math.floorMod(rawX - clipMinX - wallPhaseOffset, width)
                        : rawX - clipMinX;
                int dy = wallModule ? 17 - rawY : rawY;
                int dz = wallModule ? rawZ + 5 : rawZ;
                coords.add(new int[] {dx, dy, dz});
                BlockState parsed = BlockSpec.parse(parts[3]);
                states.add(parsed);
                if (dx >= 0 && dx < width && dz >= 0 && dz < depth) {
                    int i = dx * depth + dz;
                    if (columnBottom[i] < 0 || dy < columnBottom[i]) {
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
            GreatWallMod.LOGGER.warn("[GreatWall:wonder] TEMPLATE_LOAD_FAILED piece=ShowcaseStamp resource={} ({})",
                    resource, broken.toString(), broken);
        }
    }

    /** 重新盖一遍（/greatwall restamp）。 */
    public static void restartAll() {
        int queued = 0;
        for (ShowcaseStamp piece : ALL) {
            piece.cursor = 0;
            piece.filled = 0;
            piece.fillU = 0;
            piece.fillV = 0;
            piece.finished = false;
            piece.filling = false;
            // Keep the first originY. Re-sampling WORLD_SURFACE after the first pass reads the
            // newly built roof as "ground" and lifts the whole piece one storey every restamp.
            if (piece.loaded && piece.coords != null && !piece.coords.isEmpty()) {
                piece.settled = true;
            } else {
                piece.settled = false;
                piece.loaded = false; // tickOne will load it once coordinates are available.
            }
            // 重画是新的一轮「必须盖完」：重新占一个 expect() 名额。
            local.plaincraft.greatwall.generation.ShowcaseGate.expect(piece.accountingName);
            queued++;
        }
        GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_RESTAMP pieces={} originKept=true", queued);
    }

    /** Releases every showcase ticket and forgets the pieces before the level can close. */
    public static void resetAll(ServerLevel level) {
        for (ShowcaseStamp piece : ALL) {
            if (piece.ticket != null) {
                ChunkHold.release(level, piece.ticket);
                piece.ticket = null;
            }
            piece.finished = true;
        }
        int dropped = ALL.size();
        ALL.clear();
        local.plaincraft.greatwall.generation.ShowcaseGate.reset();
        if (dropped > 0) {
            GreatWallMod.LOGGER.info("[GreatWall:wonder] SHOWCASE_RESET pieces={}", dropped);
        }
    }

}