package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import local.plaincraft.greatwall.GreatWallMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Procedural "wonder" wall: the real wall's masonry at Minecraft scale, generated as a function of the
 * distance along a route instead of a per-voxel blueprint.
 *
 * <p>Nothing is planned, persisted or validated: every column on the route is built, so a gap is
 * impossible by construction, and the walkway is an upper envelope of the terrain plus a clearance, so
 * the wall always stands above the ground.
 *
 * <p>The test world builds three 2000-block routes - east, north and diagonal - so the same section can
 * be judged from every orientation.
 */
public final class WonderWall {
    public static final int HALF_WIDTH = 10;
    public static final int WALKWAY_HALF = 6;
    public static final int CLEARANCE = 20;
    public static final int PARAPET = 6;
    public static final int INNER_PARAPET = 3;
    public static final int MAX_FILL = 80;
    public static final int TOWER_HALF = 16;
    public static final int TOWER_HEIGHT = 32;
    public static final int GATE_HALF = 8;
    public static final int GATE_HEIGHT = 18;
    public static final int ROUTE_LENGTH = 2000;

    private static final BlockState FOUNDATION = Blocks.COBBLED_DEEPSLATE.defaultBlockState();
    private static final BlockState BODY = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState TRIM = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
    private static final BlockState WALK = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState WALK_ACCENT = Blocks.ANDESITE.defaultBlockState();
    private static final BlockState DRAIN = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState CAP = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private WonderWall() {
    }

    private record Route(int x, int z, double dx, double dz, int length, boolean detailed) {
    }

    private static final class Job {
        final ServerLevel level;
        final List<Route> routes;
        final List<long[]> chunks = new ArrayList<>();
        final List<long[]> tickets = new ArrayList<>();
        int cursor;
        int phase;
        long startedAt = System.nanoTime();
        long written;

        Job(ServerLevel level, List<Route> routes) {
            this.level = level;
            this.routes = routes;
        }
    }

    private static Job job;

    /** Starts three 2000-block routes from the given point: east, north and diagonal. */
    public static void startSample(ServerLevel level, int startX, int startZ, int ignored) {
        if (level == null) return;
        List<Route> routes = List.of(
                new Route(startX, startZ, 1.0D, 0.0D, ROUTE_LENGTH, true),
                new Route(startX, startZ, 0.0D, 1.0D, ROUTE_LENGTH, false),
                new Route(startX, startZ, 0.70710678D, 0.70710678D, ROUTE_LENGTH, false));
        Job next = new Job(level, routes);
        Set<Long> seen = new LinkedHashSet<>();
        for (Route route : routes) {
            for (int s = 0; s <= route.length(); s += 8) {
                int cx = (int) Math.floor(route.x() + route.dx() * s) >> 4;
                int cz = (int) Math.floor(route.z() + route.dz() * s) >> 4;
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        long key = (((long) (cx + ox)) << 32) ^ ((cz + oz) & 0xFFFFFFFFL);
                        if (seen.add(key)) next.chunks.add(new long[] {cx + ox, cz + oz});
                    }
                }
            }
        }
        job = next;
        GreatWallMod.LOGGER.info("[GreatWall:wonder] WONDER_ROUTES_START from=({},{}) routes={} length={} chunks={}",
                startX, startZ, routes.size(), ROUTE_LENGTH, next.chunks.size());
    }

    public static void tick(ServerLevel level) {
        Job current = job;
        if (current == null || level == null || level != current.level) return;
        if (current.phase == 0) {
            if (current.cursor >= current.chunks.size()) {
                current.phase = 1;
                current.cursor = 0;
                GreatWallMod.LOGGER.info("[GreatWall:wonder] WONDER_ROUTES_LOADED chunks={}", current.chunks.size());
                return;
            }
            long[] chunk = current.chunks.get(current.cursor);
            if (level.getChunkSource().getChunkNow((int) chunk[0], (int) chunk[1]) == null) {
                request(level, current, (int) chunk[0], (int) chunk[1]);
                return;
            }
            current.cursor++;
            return;
        }
        if (current.cursor >= current.chunks.size()) {
            GreatWallMod.LOGGER.info("[GreatWall:wonder] WONDER_ROUTES_DONE blocks={} chunks={} elapsedMs={}",
                    current.written, current.chunks.size(),
                    (System.nanoTime() - current.startedAt) / 1_000_000L);
            releaseAll(current);
            job = null;
            return;
        }
        long[] chunk = current.chunks.get(current.cursor);
        LevelChunk loaded = level.getChunkSource().getChunkNow((int) chunk[0], (int) chunk[1]);
        if (loaded == null) {
            request(level, current, (int) chunk[0], (int) chunk[1]);
            return;
        }
        current.cursor++;
        current.written += paintChunk(current, loaded);
    }

    private static final net.minecraft.server.level.TicketType<String> TICKET =
            net.minecraft.server.level.TicketType.create("greatwall_wonder",
                    java.util.Comparator.<String>naturalOrder());

    private static void request(ServerLevel level, Job job, int cx, int cz) {
        for (long[] held : job.tickets) if (held[0] == cx && held[1] == cz) return;
        try {
            var access = (local.plaincraft.greatwall.mixin.TerrainChunkAccess) level.getChunkSource();
            access.greatwall$distanceManager().addTicket(TICKET, new net.minecraft.world.level.ChunkPos(cx, cz),
                    net.minecraft.server.level.ChunkLevel.byStatus(
                            net.minecraft.world.level.chunk.status.ChunkStatus.FULL), "wonder");
            access.greatwall$request(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            job.tickets.add(new long[] {cx, cz});
        } catch (RuntimeException cannot) {
            // Demonstration only: a chunk that refuses to load is simply skipped.
        }
    }

    private static void releaseAll(Job job) {
        try {
            var access = (local.plaincraft.greatwall.mixin.TerrainChunkAccess) job.level.getChunkSource();
            int level = net.minecraft.server.level.ChunkLevel.byStatus(
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
            for (long[] held : job.tickets) {
                access.greatwall$distanceManager().removeTicket(TICKET,
                        new net.minecraft.world.level.ChunkPos((int) held[0], (int) held[1]), level, "wonder");
            }
        } catch (RuntimeException ignored) {
            // Tickets die with the level.
        }
        job.tickets.clear();
    }

    private static long paintChunk(Job job, LevelChunk chunk) {
        long written = 0L;
        int baseX = chunk.getPos().x << 4;
        int baseZ = chunk.getPos().z << 4;
        for (int lx = 0; lx < 16; lx++) {
            int x = baseX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int z = baseZ + lz;
                int ground = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                for (Route route : job.routes) {
                    double rx = x - route.x();
                    double rz = z - route.z();
                    double s = rx * route.dx() + rz * route.dz();
                    double t = -rx * route.dz() + rz * route.dx();
                    if (s < 0 || s >= route.length() || Math.abs(t) > HALF_WIDTH) continue;
                    written += wallColumn(job.level, chunk, x, z, (int) Math.round(t), ground,
                            ground + CLEARANCE, (int) s, route.detailed(), route.length());
                }
            }
        }
        return written;
    }

    private static long wallColumn(ServerLevel level, LevelChunk chunk, int x, int z, int t,
            int ground, int walkY, int s, boolean detailed, int routeLength) {
        long written = 0L;
        int at = Math.abs(t);
        int foot = Math.max(ground - 1, walkY - MAX_FILL);
        boolean gate = detailed && s >= 300 && s < 332;
        boolean tower = detailed && s >= 96 && s < 160;

        // Close both ends of every route: an open cross-section lets you see straight through the
        // hollow body, which reads as "the wall is floating".
        if (s <= 1 || s >= routeLength - 2) {
            for (int y = foot; y < walkY; y++) {
                written += put(level, chunk, x, y, z, (walkY - 1 - y) <= 6 ? FOUNDATION : BODY);
            }
            return written + put(level, chunk, x, walkY - 1, z, WALK);
        }

        if (tower) {
            boolean perimeter = at >= TOWER_HALF - 4 || Math.abs(s - 128) >= TOWER_HALF - 4;
            if (perimeter) {
                for (int y = ground - 1; y < walkY; y++) {
                    if (at >= TOWER_HALF || Math.abs(s - 128) >= TOWER_HALF) continue;
                    written += put(level, chunk, x, y, z, (walkY - 1 - y) <= 6 ? FOUNDATION : BODY);
                }
                for (int y = walkY; y < walkY + TOWER_HEIGHT; y++) {
                    int dy = y - walkY;
                    boolean window = dy >= 20 && dy < 26 && Math.floorMod(x + z, 8) < 4;
                    written += put(level, chunk, x, y, z, window ? AIR : (dy % 12 == 0 ? TRIM : BODY));
                }
                written += put(level, chunk, x, walkY + TOWER_HEIGHT, z, CAP);
            } else {
                written += put(level, chunk, x, walkY - 1, z, WALK);
                written += put(level, chunk, x, walkY + TOWER_HEIGHT - 1, z, CAP);
            }
            return written;
        }

        if (gate) {
            for (int y = foot; y < walkY; y++) {
                written += put(level, chunk, x, y, z, (walkY - 1 - y) <= 6 ? FOUNDATION : BODY);
            }
            if (at <= GATE_HALF) {
                // A barrel vault: the opening loses height towards the jambs, so the top reads as an arch
                // instead of a square slot.
                int arch = GATE_HEIGHT - 1 - (at * at) / 12;
                for (int y = 0; y < arch; y++) {
                    written += put(level, chunk, x, walkY - 2 - y, z, AIR);
                }
                // Gatehouse above the vault: a window band and a crenellated top, the way a real 城楼
                // sits over the gate rather than leaving a bare bridge of masonry.
                for (int y = walkY; y < walkY + 14; y++) {
                    int dy = y - walkY;
                    boolean window = dy >= 5 && dy < 9 && Math.floorMod(s, 6) < 3;
                    written += put(level, chunk, x, y, z, window ? AIR : BODY);
                }
                written += put(level, chunk, x, walkY + 14, z, CAP);
                return written;
            }
        } else {
            for (int y = foot; y < walkY; y++) {
                boolean rim = at >= HALF_WIDTH - 4;
                boolean base = y < foot + 4;   // solid base course: the hollow body must not be see-through
                if (!rim && !base) continue;
                int dy = walkY - 1 - y;
                written += put(level, chunk, x, y, z, dy <= 6 ? FOUNDATION : (dy % 8 == 0 ? TRIM : BODY));
            }
        }

        if (at < HALF_WIDTH - 4) {
            BlockState deck = at == HALF_WIDTH - 5 ? DRAIN : (Math.floorMod(x + z, 7) == 0 ? WALK_ACCENT : WALK);
            written += put(level, chunk, x, walkY - 1, z, deck);
        }
        if (t <= -(HALF_WIDTH - 4)) {
            // Merlon three blocks wide, gap two: at this scale the old five-wide merlons read as one
            // long wall with the odd notch instead of as crenellation.
            int phase = Math.floorMod(s, 5);
            int crenel = phase < 2 ? 3 : PARAPET;
            for (int y = 0; y < crenel; y++) {
                written += put(level, chunk, x, walkY + y, z, y == crenel - 1 ? CAP : BODY);
            }
            if (phase == 2) {
                put(level, chunk, x, walkY + 1, z, AIR);
            }
        } else if (t >= HALF_WIDTH - 4) {
            for (int y = 0; y < INNER_PARAPET; y++) {
                written += put(level, chunk, x, walkY + y, z, y == INNER_PARAPET - 1 ? CAP : BODY);
            }
        }
        return written;
    }

    private static long put(ServerLevel level, LevelChunk chunk, int x, int y, int z, BlockState state) {
        BlockPos pos = new BlockPos(x, y, z);
        if (chunk.getBlockState(pos).equals(state)) return 0L;
        // Through the level, never straight into the chunk: a raw write leaves the light engine and the
        // heightmap stale, and the blocks render black or not at all.
        level.setBlock(pos, state, 2);
        return 1L;
    }
}
