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
    public static final int HALF_WIDTH = 5;
    public static final int WALKWAY_HALF = 2;
    public static final int CLEARANCE = 14;
    public static final int PARAPET = 3;
    public static final int INNER_PARAPET = 3;
    public static final int MAX_FILL = 80;
    public static final int TOWER_HALF = 16;
    public static final int TOWER_HEIGHT = 32;
    public static final int GATE_HALF = 8;
    public static final int GATE_HEIGHT = 14;
    public static final int ROUTE_LENGTH = 600;
    /** Span of the detailed route the reference gate is stamped into. */
    public static final int GATE_STAMP_FROM = 0;
    /** Span of the detailed route the reference 敌台 is stamped into. */
    public static final int TOWER_STAMP_FROM = 0;
    public static final int TOWER_STAMP_TO = 0;
    public static final int GATE_STAMP_TO = 0;

    private static final BlockState FOUNDATION = Blocks.COBBLED_DEEPSLATE.defaultBlockState();
    private static final BlockState BODY = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState TRIM = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
    private static final BlockState WALK = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState WALK_ACCENT = Blocks.ANDESITE.defaultBlockState();
    private static final BlockState DRAIN = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState CAP = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState TERRACOTTA = Blocks.TERRACOTTA.defaultBlockState();
    private static final BlockState QUARTZ = Blocks.QUARTZ_BLOCK.defaultBlockState();

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
        // Two straight routes only: the diagonal read badly as voxels and was dropped on request.
        List<Route> routes = List.of(
                new Route(startX, startZ, 1.0D, 0.0D, ROUTE_LENGTH, true),
                new Route(startX, startZ, 0.0D, 1.0D, ROUTE_LENGTH, false));
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
        // Showcase models behind spawn (negative X), isolated from every route.
        // Absolute world coordinates: the models sit behind the world spawn (negative X), which is not
        // relative to the route start at all.
        for (int cx = Math.floorDiv(-100, 16); cx <= Math.floorDiv(-30, 16); cx++) {
            for (int cz = Math.floorDiv(-30, 16); cz <= Math.floorDiv(230, 16); cz++) {
                long key = (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
                if (seen.add(key)) next.chunks.add(new long[] {cx, cz});
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

    /** Stops the in-flight showcase build and releases its chunk tickets before the level closes. */
    public static void stop() {
        Job current = job;
        job = null;
        if (current != null) releaseAll(current);
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
                written += local.plaincraft.greatwall.generation.WonderShowcase.paint(job.level, chunk, x, z, ground);
                // 入海端台: the wall runs into a stepped terrace at the end of the detailed route,
                // the way the real wall ends in a fortified platform rather than a cut face.
                int endX = job.routes.get(0).x() + job.routes.get(0).length() - 10;
                int endZ = job.routes.get(0).z();
                if (Math.abs(x - endX) <= 24 && Math.abs(z - endZ) <= 24) {
                    // end platform: retired until it comes in as a template
                }
                // 烽火台: a beacon tower standing off the wall, square, solid, crenellated.
                int beaconX = job.routes.get(0).x() + 300;
                int beaconZ = job.routes.get(0).z() - 60;
                if (Math.abs(x - beaconX) <= 8 && Math.abs(z - beaconZ) <= 8) {
                    // beacon: retired until it comes in as a template
                }
                // 堡垒: a seven-by-seven outpost every four hundred blocks, off the outer face.
                Route main = job.routes.get(0);
                int span = (int) Math.floor(x - main.x());
                if (span % 400 == 0 && Math.abs(span) < 4000) {
                    int fz = main.z() - 24;
                    if (Math.abs(x - (main.x() + span)) <= 3 && Math.abs(z - fz) <= 3) {
                        // fort: retired until it comes in as a template
                    }
                }
                // 坡道: a stair from the walkway down to the ground every hundred and fifty blocks.

                for (Route route : job.routes) {
                    double rx = x - route.x();
                    double rz = z - route.z();
                    double s = rx * route.dx() + rz * route.dz();
                    double t = -rx * route.dz() + rz * route.dx();
                    if (s < 0 || s >= route.length() || Math.abs(t) > HALF_WIDTH) continue;
                    // The reference gate occupies this span: leave it empty so the stamped model and the
                    // rule generator never fight over the same blocks.
                    if (route.detailed() && s >= GATE_STAMP_FROM && s < GATE_STAMP_TO) continue;
                    if (route.detailed() && s >= TOWER_STAMP_FROM && s < TOWER_STAMP_TO) continue;
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

        boolean gate = detailed && s >= 200 && s < 232;
        // The procedural 敌台 is retired: it produced a hollow shell that read as a frame, and a real
        // 敌台 will arrive as a template instead.
        boolean tower = false;

        // Close both ends of every route: an open cross-section lets you see straight through the
        // hollow body, which reads as "the wall is floating".
        if (s <= 1 || s >= routeLength - 2) {
            for (int y = foot; y < walkY; y++) {
                written += put(level, chunk, x, y, z, band(walkY - 1 - y));
            }
            return written + put(level, chunk, x, walkY - 1, z, WALK);
        }

        if (tower) {
            boolean perimeter = at >= TOWER_HALF - 4 || Math.abs(s - 128) >= TOWER_HALF - 4;
            if (perimeter) {
                for (int y = ground - 1; y < walkY; y++) {
                    if (at >= TOWER_HALF || Math.abs(s - 128) >= TOWER_HALF) continue;
                    written += put(level, chunk, x, y, z, band(walkY - 1 - y));
                }
                // 拱门: one arched doorway per face at walkway level, the way a 敌台 is entered.
                boolean faceDoor = (Math.abs(s - 128) <= 3 && at >= TOWER_HALF - 1)
                        || (at >= TOWER_HALF - 1 && Math.abs(s - 128) <= 3);
                for (int y = walkY; y < walkY + TOWER_HEIGHT; y++) {
                    int dy = y - walkY;
                    boolean window = dy >= 20 && dy < 26 && Math.floorMod(x + z, 8) < 4;
                    if (dy == 19) { written += put(level, chunk, x, y, z, TRIM); }
                    boolean door = dy < 10 && Math.abs(t) <= 3 && Math.abs(s - 128) <= TOWER_HALF
                            && (Math.abs(s - 128) >= TOWER_HALF - 1 || at >= TOWER_HALF - 1);
                    written += put(level, chunk, x, y, z, (window || door) ? AIR : (dy % 12 == 0 ? TRIM : BODY));
                }
                written += put(level, chunk, x, walkY + TOWER_HEIGHT, z, CAP);
                // 垛口圈: the terrace edge is crenellated, alternating three and one blocks high.
                int ph = Math.floorMod(s + t, 4);
                for (int y = 1; y <= (ph < 2 ? 3 : 1); y++) {
                    written += put(level, chunk, x, walkY + TOWER_HEIGHT + y, z, y == 3 ? CAP : BODY);
                }
                // 铺房: the small guard house a real 敌台 carries on its terrace.
                if (Math.abs(s - 128) <= 4 && at <= 3) {
                    for (int y = walkY + TOWER_HEIGHT + 1; y <= walkY + TOWER_HEIGHT + 6; y++) {
                        boolean wall = at == 3 || Math.abs(s - 128) == 4;
                        boolean door = at <= 1 && Math.abs(s - 128) == 4 && y <= walkY + TOWER_HEIGHT + 3;
                        if (wall && !door) written += put(level, chunk, x, y, z, BODY);
                        else if (y == walkY + TOWER_HEIGHT + 6) written += put(level, chunk, x, y, z, CAP);
                    }
                    // 瓦顶: a stepped tile roof over the 铺房, the way the real ones are roofed.
                    int roofY = walkY + TOWER_HEIGHT + 7;
                    int ds = Math.abs(s - 128);
                    if (ds <= 4 && at <= 4 && (ds <= 2 || at <= 2)) {
                        written += put(level, chunk, x, roofY, z, Blocks.DEEPSLATE_TILES.defaultBlockState());
                        if (ds <= 1 && at <= 1) written += put(level, chunk, x, roofY + 1, z, CAP);
                    }
                }
            } else {
                // Interior: a stone-slab floor and a barrel-vaulted ceiling, as in the photographed
                // chambers inside a 敌台 - the vault rises towards the middle of the room.
                BlockState floor = Math.floorMod(x + z, 3) == 0 ? Blocks.SMOOTH_STONE.defaultBlockState() : WALK;
                written += put(level, chunk, x, walkY - 1, z, floor);
                int vault = walkY + 10 + (at <= 2 ? 2 : (at <= 5 ? 1 : 0));
                written += put(level, chunk, x, vault, z, BODY);
                // 二层楼板: a timber-storey floor above the vault, with a stair well left open.
                if (vault < walkY + 10 || at > 3) {
                    written += put(level, chunk, x, walkY + 11, z, WALK);
                }
                written += put(level, chunk, x, walkY + TOWER_HEIGHT - 1, z, CAP);

                // 壁龛: a lit niche every eight blocks, the way the chambers are fitted out.
                if (Math.floorMod(s, 8) == 0 && at == TOWER_HALF - 5) {
                    written += put(level, chunk, x, walkY + 3, z, TRIM);
                    written += put(level, chunk, x, walkY + 4, z, Blocks.LANTERN.defaultBlockState());
                }
                // 楼梯: a straight flight along the inner face, ground floor to the upper floor.
                if (at == TOWER_HALF - 6 && s >= 104 && s < 120) {
                    int step = (s - 104) / 2;
                    written += put(level, chunk, x, walkY + step, z, BODY);
                }
                // 陈设: barrels, a chest and a hanging lantern - a guard room, not an empty box.
                if (walkY > 0 && at <= 2) {
                    if (Math.abs(s - 112) <= 1 && Math.abs(t + 2) <= 1) {
                        written += put(level, chunk, x, walkY, z, Blocks.BARREL.defaultBlockState());
                    }
                    if (Math.abs(s - 144) <= 1 && t == 2) {
                        written += put(level, chunk, x, walkY, z, Blocks.CHEST.defaultBlockState());
                    }
                    if (Math.abs(s - 120) <= 1 && t == -2) {
                        written += put(level, chunk, x, walkY, z, Blocks.CAULDRON.defaultBlockState());
                    }
                    if (Math.abs(s - 136) <= 1 && t == 2) {
                        written += put(level, chunk, x, walkY, z, Blocks.SMOKER.defaultBlockState());
                    }
                    if (Math.abs(s - 128) <= 1 && t == 0) {
                        written += put(level, chunk, x, walkY + 9, z, Blocks.LANTERN.defaultBlockState());
                    }
                }
            }
            return written;
        }

        if (gate) {
            for (int y = foot; y < walkY; y++) {
                written += put(level, chunk, x, y, z, band(walkY - 1 - y));
            }
            // The gate is a tunnel at GROUND level, not a hole through the walkway: the wall's walkway
            // must stay continuous over the gate, otherwise the wall is cut in two at every gate.
            if (at <= GATE_HALF) {
                int springing = ground + 1;
                int crown = ground + GATE_HEIGHT - (at * at) / 24;
                for (int y = springing; y <= crown; y++) {
                    written += put(level, chunk, x, y, z, AIR);
                }
                if (at <= GATE_HALF) {
                    written += put(level, chunk, x, ground, z, Blocks.SMOOTH_STONE.defaultBlockState());
                }
                // 券脸：门洞边缘包一圈 chiseled
                if (at == GATE_HALF) {
                    for (int y = ground + 1; y <= ground + GATE_HEIGHT; y++) {
                        written += put(level, chunk, x, y, z, TRIM);
                    }
                }
                if (Math.abs(s - 316) <= 1) {
                    for (int y = ground + 1; y <= ground + GATE_HEIGHT - 2; y++) {
                        if (at <= GATE_HALF) written += put(level, chunk, x, y, z, Blocks.IRON_BARS.defaultBlockState());
                    }
                }
                return written;
            }
        } else {
            // Extrude the reference cross-section: hollow body, mid floor, quartz-edged walkway and
            // one-thick parapets. Below the body, pour a solid foundation down to the ground.
            int topY = walkY + WallProfile.ABOVE_WALK;
            // The module carries the reference merlon rhythm itself: three stair-topped merlons and one
            // gap per four blocks, so no phase hack is needed here.
            // Merlon rhythm is a four-block cycle that must start on a merlon run and end on one, so a
            // building's wall stub (which finishes on a gap) hands over cleanly. The last few columns of
            // a run drop their merlons instead of leaving a hole, reading as a low transition.
            int runStart;
            int runLength;
            if (s < GATE_STAMP_FROM) {
                runStart = 0;
                runLength = GATE_STAMP_FROM;
            } else if (s < TOWER_STAMP_FROM) {
                runStart = GATE_STAMP_TO;
                runLength = TOWER_STAMP_FROM - GATE_STAMP_TO;
            } else {
                runStart = TOWER_STAMP_TO;
                runLength = routeLength - TOWER_STAMP_TO;
            }
            int runPos = s - runStart;
            int trim = Math.floorMod(runLength - 3, 4);
            boolean tail = runPos >= runLength - trim;
            int phase = tail ? WallProfile.PERIOD - 1 : Math.floorMod(runPos, 4);
            for (int y = topY; y >= ground - 1; y--) {
                int depth = topY - y;
                BlockState st = WallProfile.at(phase, t, depth);
                if (st != null) {
                    written += put(level, chunk, x, y, z, st);
                } else if (depth >= WallProfile.DEPTH) {
                    written += put(level, chunk, x, y, z, FOUNDATION);
                }
            }
            return written;
        }

        if (at < HALF_WIDTH - 2) {
            BlockState deck = at == HALF_WIDTH - 4 ? DRAIN : (Math.floorMod(x + z, 7) == 0 ? WALK_ACCENT : WALK);
            written += put(level, chunk, x, walkY - 1, z, deck);
        }
        // 城墙上的 3x3 篝火阵: every two hundred blocks, unlit, for the same quest.
        if (Math.abs(t) <= 1 && Math.floorMod(s, 200) <= 2) {
            written += put(level, chunk, x, walkY, z,
                    Blocks.CAMPFIRE.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false));
        }
        // 吐水嘴: a stone spout every 48 blocks, so the wall reads as draining rather than as a trough.
        if (t == -(HALF_WIDTH - 1) && Math.floorMod(s, 48) == 0) {
            written += put(level, chunk, x, walkY - 2, z, CAP);
        }
        if (t <= -(HALF_WIDTH - 2)) {
            // Merlon three blocks wide, gap two: at this scale the old five-wide merlons read as one
            // long wall with the odd notch instead of as crenellation.
            int phase = Math.floorMod(s, 6);
            int crenel = phase < 3 ? 3 : PARAPET;
            for (int y = 0; y < crenel; y++) {
                written += put(level, chunk, x, walkY + y, z, y == crenel - 1 ? CAP : BODY);
            }
            if (phase == 2) {
                put(level, chunk, x, walkY + 1, z, AIR);
            }
        } else if (t >= HALF_WIDTH - 2) {
            for (int y = 0; y < INNER_PARAPET; y++) {
                written += put(level, chunk, x, walkY + y, z, y == INNER_PARAPET - 1 ? CAP : BODY);
            }
        }
        return written;
    }

    private static long fortColumn(ServerLevel level, LevelChunk chunk, int x, int z, int ground) {
        long written = 0L;
        int walkY = ground + 6;
        for (int y = ground - 1; y < walkY; y++) {
            int dy = walkY - 1 - y;
            written += put(level, chunk, x, y, z, dy <= 2 ? FOUNDATION : BODY);
        }
        for (int y = 0; y < 2; y++) {
            written += put(level, chunk, x, walkY + y, z, y == 1 ? CAP : BODY);
        }
        return written;
    }

    private static long platformColumn(ServerLevel level, LevelChunk chunk, int x, int z,
            int px, int pz, int ground) {
        long written = 0L;
        int at = Math.max(Math.abs(px), Math.abs(pz));
        int walkY = ground + CLEARANCE;
        for (int y = ground - 1; y < walkY + 12; y++) {
            int dy = walkY + 11 - y;
            int half = 24 - Math.min(16, dy / 4);
            if (at > half) continue;
            if (at < half - 3 && dy > 4) continue;
            written += put(level, chunk, x, y, z, dy <= 3 ? FOUNDATION : (dy % 6 == 0 ? TRIM : BODY));
        }
        if (at <= 24) written += put(level, chunk, x, walkY + 11, z, CAP);
        // 澄海楼: a small pavilion on the terrace, the way the wall's sea end carries a tower.
        if (at <= 8) {
            for (int y = walkY + 12; y < walkY + 20; y++) {
                boolean wallRing = at >= 6;
                boolean window = y >= walkY + 15 && y < walkY + 18 && Math.floorMod(x + z, 6) < 3;
                if (wallRing) written += put(level, chunk, x, y, z, window ? AIR : BODY);
            }
            written += put(level, chunk, x, walkY + 20, z, CAP);
            // 红柱: the crimson columns of the pavilion, the detail that reads first in the photograph.
            if ((Math.abs(px) == 7 || Math.abs(px) == 8) && (Math.abs(pz) == 7 || Math.abs(pz) == 8)) {
                for (int y = walkY + 12; y < walkY + 20; y++) {
                    written += put(level, chunk, x, y, z, Blocks.RED_TERRACOTTA.defaultBlockState());
                }
            }
        }
        return written;
    }

    private static long beaconColumn(ServerLevel level, LevelChunk chunk, int x, int z, int ground) {
        long written = 0L;
        int walkY = ground + CLEARANCE;
        for (int y = ground - 1; y < walkY + 24; y++) {
            int dy = walkY + 23 - y;
            boolean rim = Math.abs(x) >= 0;   // solid beacon: no hollow, it must read as a mass
            if (!rim) continue;
            written += put(level, chunk, x, y, z, dy <= 3 ? FOUNDATION : (dy % 6 == 0 ? TRIM : BODY));
        }
        // 3x3 篝火阵: left unlit on purpose - the player lights them for the beacon quest, and the
        // campfire-optimisation mod keeps the particle cost down when several burn at once.
        if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
            written += put(level, chunk, x, walkY + 24, z,
                    Blocks.CAMPFIRE.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false));
            return written;
        }
        int phase = Math.floorMod(x + z, 6);
        for (int y = 0; y < (phase < 3 ? 3 : 2); y++) {
            written += put(level, chunk, x, walkY + 24 + y, z, y == 2 ? CAP : BODY);
        }
        return written;
    }

    /** Seven-band wall section, copied from the reference gate: base, body, terracotta band, body, quartz belt, body. */
    private static BlockState band(int depth) {
        // The reference wall, bottom up: stone plinth, stone-brick body, terracotta band, quartz belt.
        if (depth >= 12) return FOUNDATION;    // 3-layer plinth
        if (depth == 1) return TERRACOTTA;     // band just under the belt
        if (depth == 0) return QUARTZ;         // white belt right under the walkway
        return BODY;
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
