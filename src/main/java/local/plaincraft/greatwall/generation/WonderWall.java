package local.plaincraft.greatwall.generation;

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
 * Procedural "wonder" wall sample: the real wall's masonry at Minecraft scale, with every piece of
 * architecture the final line will use - a stepped ramp, a two-storey tower, an arched gate with its
 * gatehouse, a round corner bastion and the sea terminus platform.
 *
 * <p>Nothing here is planned, persisted or validated: the shape is a function of the position along
 * the route, so the wall cannot have a gap, and the walkway is an upper envelope of the terrain plus a
 * clearance, slope-limited by raising the low side only, so the wall always stands above the ground.
 */
public final class WonderWall {
    public static final int HALF_WIDTH = 20;
    public static final int WALKWAY_HALF = 12;
    public static final int CLEARANCE = 40;
    public static final int PARAPET = 12;
    public static final int INNER_PARAPET = 6;
    public static final int MAX_FILL = 160;
    public static final int TOWER_HALF = 32;
    public static final int TOWER_HEIGHT = 64;
    public static final int GATE_HALF = 16;
    public static final int GATE_HEIGHT = 36;
    public static final int BASTION_RADIUS = 32;
    public static final int PLATFORM_HALF = 48;
    public static final int PLATFORM_HEIGHT = 24;

    private static final BlockState FOUNDATION = Blocks.COBBLED_DEEPSLATE.defaultBlockState();
    private static final BlockState BODY = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState TRIM = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
    private static final BlockState COPING = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState WALK = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState WALK_ACCENT = Blocks.ANDESITE.defaultBlockState();
    private static final BlockState DRAIN = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState CAP = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private WonderWall() {
    }

    private static final class Job {
        final ServerLevel level;
        final int startX;
        final int startZ;
        final int length;
        final List<long[]> chunks = new ArrayList<>();
        final List<long[]> tickets = new ArrayList<>();
        int[] profile;
        int cursor;
        int phase;
        long startedAt = System.nanoTime();
        long written;

        Job(ServerLevel level, int startX, int startZ, int length) {
            this.level = level;
            this.startX = startX;
            this.startZ = startZ;
            this.length = length;
        }
    }

    private static Job job;

    /** Starts the demonstration: a 240 block wall, tower, gate, corner bastion and sea platform. */
    public static void startSample(ServerLevel level, int startX, int startZ, int length) {
        if (level == null) return;
        Job next = new Job(level, startX, startZ, length);
        int minX = startX - 48;
        int maxX = startX + length + PLATFORM_HALF + 16;
        int minZ = startZ - PLATFORM_HALF - 16;
        int maxZ = startZ + PLATFORM_HALF + 16;
        for (int cx = Math.floorDiv(minX, 16); cx <= Math.floorDiv(maxX, 16); cx++) {
            for (int cz = Math.floorDiv(minZ, 16); cz <= Math.floorDiv(maxZ, 16); cz++) {
                next.chunks.add(new long[] {cx, cz});
            }
        }
        job = next;
        GreatWallMod.LOGGER.info("[GreatWall:wonder] WONDER_SAMPLE_START from=({},{}) length={} chunks={}",
                startX, startZ, length, next.chunks.size());
    }

    public static void tick(ServerLevel level) {
        Job current = job;
        if (current == null || level == null || level != current.level) return;
        if (current.phase == 0) {
            if (current.cursor < current.chunks.size()) {
                long[] chunk = current.chunks.get(current.cursor++);
                if (level.getChunkSource().getChunkNow((int) chunk[0], (int) chunk[1]) == null) {
                    current.cursor--;
                    request(level, current, (int) chunk[0], (int) chunk[1]);
                }
                return;
            }
            current.phase = 1;
            current.cursor = 0;
        }
        if (current.phase == 1) {
            current.profile = buildProfile(current);
            current.phase = 2;
            current.cursor = 0;
        }
        if (current.phase == 2) {
            if (current.cursor >= current.chunks.size()) {
                GreatWallMod.LOGGER.info("[GreatWall:wonder] WONDER_SAMPLE_DONE blocks={} chunks={} elapsedMs={}",
                        current.written, current.chunks.size(),
                        (System.nanoTime() - current.startedAt) / 1_000_000L);
                releaseAll(current);
                job = null;
                return;
            }
            long[] chunk = current.chunks.get(current.cursor);
            LevelChunk loaded = level.getChunkSource().getChunkNow((int) chunk[0], (int) chunk[1]);
            if (loaded == null) {
                // Do not advance: a chunk that unloaded between requesting it and its turn must be
                // painted when it comes back, not skipped for ever. A measured run skipped two thirds
                // of the section this way, which is why the wall looked like "just a little bit".
                request(level, current, (int) chunk[0], (int) chunk[1]);
                return;
            }
            current.cursor++;
            current.written += paintChunk(current, loaded);
        }
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

    private static int[] buildProfile(Job job) {
        int[] raw = new int[job.length];
        for (int i = 0; i < job.length; i++) {
            int best = Integer.MIN_VALUE;
            for (int d = -48; d <= 48; d += 4) {
                int ground = groundAt(job.level, job.startX + i + d, job.startZ);
                if (ground > best) best = ground;
            }
            raw[i] = best + CLEARANCE;
        }
        // A ramp at the start so the stairs are part of the demonstration.
        int rampEnd = Math.min(40, raw.length - 1);
        int top = raw[rampEnd];
        for (int i = 0; i < rampEnd; i++) raw[i] = top - (rampEnd - i) / 5;
        for (int i = 1; i < raw.length; i++) raw[i] = Math.max(raw[i], raw[i - 1] - 1);
        for (int i = raw.length - 2; i >= 0; i--) raw[i] = Math.max(raw[i], raw[i + 1] - 1);
        return raw;
    }

    private static int groundAt(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return level.getMinBuildHeight();
        return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
    }

    private static long paintChunk(Job job, LevelChunk chunk) {
        long written = 0L;
        int baseX = chunk.getPos().x << 4;
        int baseZ = chunk.getPos().z << 4;
        int bastionX = job.startX + job.length;
        int platformX = bastionX + PLATFORM_HALF;
        for (int lx = 0; lx < 16; lx++) {
            int x = baseX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int z = baseZ + lz;
                int ground = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                int s = x - job.startX;
                int t = z - job.startZ;
                if (s >= 0 && s < job.length && Math.abs(t) <= HALF_WIDTH) {
                    written += wallColumn(job.level, chunk, x, z, t, ground, job.profile[s], s);
                }
                int bx = x - bastionX;
                int bz = t;
                if (dist2(bx, bz) <= BASTION_RADIUS * BASTION_RADIUS) {
                    written += bastionColumn(job.level, chunk, x, z, bx, bz, ground);
                }
                int px = x - platformX;
                if (Math.abs(px) <= PLATFORM_HALF && Math.abs(t) <= PLATFORM_HALF) {
                    written += platformColumn(job.level, chunk, x, z, px, t, ground);
                }
            }
        }
        return written;
    }

    private static long dist2(int dx, int dz) {
        return (long) dx * dx + (long) dz * dz;
    }

    // ---------------------------------------------------------------- wall

    private static long wallColumn(ServerLevel level, LevelChunk chunk, int x, int z, int t,
            int ground, int walkY, int s) {
        long written = 0L;
        int at = Math.abs(t);
        int foot = Math.max(ground - 1, walkY - MAX_FILL);
        boolean gate = s >= 196 && s < 228;
        boolean tower = s >= 96 && s < 160;

        if (tower) {
            boolean perimeter = at >= TOWER_HALF - 4 || Math.abs(s - 128) >= TOWER_HALF - 4;
            if (perimeter) {
                for (int y = ground - 1; y < walkY; y++) {
                    if (at >= TOWER_HALF || Math.abs(s - 128) >= TOWER_HALF) continue;
                    written += put(level, chunk, x, y, z, (walkY - 1 - y) <= 8 ? FOUNDATION : BODY, false);
                }
                for (int y = walkY; y < walkY + TOWER_HEIGHT; y++) {
                    int dy = y - walkY;
                    boolean window = dy >= 40 && dy < 52 && Math.floorMod(x + z, 16) < 8;
                    written += put(level, chunk, x, y, z, window ? AIR : (dy % 24 == 0 ? TRIM : BODY), true);
                }
                for (int y = 0; y < 4; y++) {
                    written += put(level, chunk, x, walkY + TOWER_HEIGHT + y, z, CAP, true);
                }
            } else {
                written += put(level, chunk, x, walkY - 1, z, WALK, true);
                written += put(level, chunk, x, walkY + TOWER_HEIGHT - 1, z, CAP, true);
            }
            return written;
        }

        if (gate) {
            // A gate has to be masonry on both sides of its passage, so this section is filled solid
            // before the arch is cut through it; the rest of the wall stays a hollow shell.
            for (int y = foot; y < walkY; y++) {
                written += put(level, chunk, x, y, z, (walkY - 1 - y) <= 8 ? FOUNDATION : BODY, false);
            }
            if (at <= GATE_HALF) {
                int arch = GATE_HEIGHT - (at > 8 ? 12 : 0);
                for (int y = 0; y < arch; y++) {
                    written += put(level, chunk, x, walkY - 1 - y, z, AIR, true);
                }
                return written;
            }
        } else {
            for (int y = foot; y < walkY; y++) {
                if (at < HALF_WIDTH - 4) continue;
                written += put(level, chunk, x, y, z, (walkY - 1 - y) <= 8 ? FOUNDATION : BODY, false);
            }
        }
        if (at < HALF_WIDTH - 4) {
            BlockState deck = at == HALF_WIDTH - 5 ? DRAIN : (Math.floorMod(x + z, 7) == 0 ? WALK_ACCENT : WALK);
            written += put(level, chunk, x, walkY - 1, z, deck, true);
        }
        if (t <= -(HALF_WIDTH - 4)) {
            int phase = Math.floorMod(s, 12);
            int crenel = phase < 4 ? 6 : PARAPET;
            for (int y = 0; y < crenel; y++) {
                written += put(level, chunk, x, walkY + y, z, y == crenel - 1 ? CAP : BODY, true);
            }
            if (phase >= 4 && phase < 6) {
                put(level, chunk, x, walkY + 2, z, AIR, true);
                put(level, chunk, x, walkY + 3, z, AIR, true);
            }
        } else if (t >= HALF_WIDTH - 4) {
            for (int y = 0; y < INNER_PARAPET; y++) {
                written += put(level, chunk, x, walkY + y, z, y == INNER_PARAPET - 1 ? CAP : BODY, true);
            }
        }
        return written;
    }

    // ------------------------------------------------------------- bastion

    private static long bastionColumn(ServerLevel level, LevelChunk chunk, int x, int z,
            int bx, int bz, int ground) {
        long written = 0L;
        int walkY = ground + CLEARANCE;
        int top = walkY + 24;
        long r2 = dist2(bx, bz);
        for (int y = ground - 1; y < top; y++) {
            int dy = top - 1 - y;
            int radius = BASTION_RADIUS - Math.min(8, dy / 6);
            if (r2 > (long) radius * radius) continue;
            if (r2 < (long) (radius - 4) * (radius - 4) && dy > 4) continue;
            written += put(level, chunk, x, y, z, dy <= 6 ? FOUNDATION : (dy % 12 == 0 ? TRIM : BODY), false);
        }
        for (int y = 0; y < 4; y++) {
            int radius = BASTION_RADIUS;
            if (r2 > (long) radius * radius || r2 < (long) (radius - 3) * (radius - 3)) continue;
            written += put(level, chunk, x, top + y, z, CAP, true);
        }
        return written;
    }

    // ------------------------------------------------------------ platform

    private static long platformColumn(ServerLevel level, LevelChunk chunk, int x, int z,
            int px, int pz, int ground) {
        long written = 0L;
        int at = Math.max(Math.abs(px), Math.abs(pz));
        int walkY = ground + CLEARANCE;
        for (int y = ground - 1; y < walkY + PLATFORM_HEIGHT; y++) {
            int dy = walkY + PLATFORM_HEIGHT - 1 - y;
            int half = PLATFORM_HALF - Math.min(24, dy / 8);
            if (at > half) continue;
            if (at < half - 4 && dy > 4) continue;
            written += put(level, chunk, x, y, z, dy <= 6 ? FOUNDATION : (dy % 12 == 0 ? TRIM : BODY), false);
        }
        if (at <= PLATFORM_HALF) {
            written += put(level, chunk, x, walkY + PLATFORM_HEIGHT - 1, z, COPING, true);
        }
        return written;
    }

    private static long put(ServerLevel level, LevelChunk chunk, int x, int y, int z, BlockState state, boolean lit) {
        BlockPos pos = new BlockPos(x, y, z);
        if (chunk.getBlockState(pos).equals(state)) return 0L;
        if (lit) level.setBlock(pos, state, 2);
        else chunk.setBlockState(pos, state, false);
        return 1L;
    }
}
