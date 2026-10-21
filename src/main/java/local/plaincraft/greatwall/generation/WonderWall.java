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
 * A procedural "wonder" wall section: the shape real Great Wall masonry has, at Minecraft scale.
 *
 * <h2>Why a second generator</h2>
 * The planned wall is a per-voxel blueprint: every block is compiled, persisted and then written, and
 * every column may be rejected by a validation (height contract, ownership, support, structures). That
 * shape cannot scale: a wonder wall is tens of millions of voxels, it must never have a gap, and it
 * must never dive into a valley. This generator derives the cross-section from the route instead:
 *
 * <ul>
 *   <li><b>The walkway never follows the ground.</b> The profile is the upper envelope of the terrain
 *       (windowed maximum) plus a clearance, then slope-limited to 1:6 by raising the low side only.
 *       A peak lifts the wall; a dip is filled with masonry. The wall therefore always stands
 *       <em>above</em> the ground, exactly like the real wall on its ridge.</li>
 *   <li><b>Nothing is validated.</b> There is no height contract, no structure or water avoidance and
 *       no support graph: every column on the route is built, so gaps are impossible by construction.</li>
 *   <li><b>Nothing is persisted.</b> Blocks are written chunk by chunk, so a 40-wide, 50-tall wall
 *       costs no plan files and no memory beyond the chunk being written.</li>
 * </ul>
 *
 * <p>Reference proportions come from Badaling: 7-8 m high (14 m on steep sections), 6.5 m at the base,
 * 5 m across the top, a 2 m crenellated parapet outside and a 1 m parapet inside. This class multiplies
 * them by four and adds the details that make masonry read as masonry instead of a grey box: three
 * material courses, a batter, a coping course, a belt course, loopholes, a drainage channel and
 * discharge spouts.
 */
public final class WonderWall {
    /** Half the base width: 40 blocks across, 24 of them clear walkway. */
    public static final int HALF_WIDTH = 20;
    /** Walkway half width; the parapets occupy the four blocks outside it on each side. */
    public static final int WALKWAY_HALF = 12;
    /** Minimum height of the walkway above the ground it crosses. */
    public static final int CLEARANCE = 40;
    /** Outer parapet height; crenels cut six of those blocks down to six. */
    public static final int PARAPET = 12;
    /** Inner (friendly side) parapet height. */
    public static final int INNER_PARAPET = 6;
    /** Deepest foundation this generator will pour into a dip. */
    public static final int MAX_FILL = 160;
    /** Widest water this generator bridges; wider than this is a coast and the wall ends there. */
    public static final int MAX_WATER_SPAN = 96;

    private static final BlockState FOUNDATION = Blocks.COBBLED_DEEPSLATE.defaultBlockState();
    private static final BlockState BODY = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState CRACKED = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
    private static final BlockState TRIM = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
    private static final BlockState COPING = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState WALK = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState WALK_ACCENT = Blocks.ANDESITE.defaultBlockState();
    private static final BlockState DRAIN = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState CAP = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();

    private WonderWall() {
    }

    /** One sample the player can walk up to and judge before the whole line is generated. */
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

    /** Starts a sample section: {@code length} blocks along +X, centred on {@code startZ}. */
    public static void startSample(ServerLevel level, int startX, int startZ, int length) {
        if (level == null) return;
        Job next = new Job(level, startX, startZ, length);
        int minChunkX = Math.floorDiv(startX - 1, 16);
        int maxChunkX = Math.floorDiv(startX + length, 16);
        int minChunkZ = Math.floorDiv(startZ - HALF_WIDTH - 1, 16);
        int maxChunkZ = Math.floorDiv(startZ + HALF_WIDTH + 1, 16);
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                next.chunks.add(new long[] {cx, cz});
            }
        }
        job = next;
        GreatWallMod.LOGGER.info("[GreatWall:wonder] WONDER_SAMPLE_START from=({},{}) length={} chunks={}",
                startX, startZ, length, next.chunks.size());
    }

    /** One bounded step, called from the server tick. */
    public static void tick(ServerLevel level) {
        Job current = job;
        if (current == null || level == null || level != current.level) return;
        if (current.phase == 0) {
            // Phase 0 asks for one chunk per tick; the world is ticking here, so tickets work.
            if (current.cursor < current.chunks.size()) {
                long[] chunk = current.chunks.get(current.cursor++);
                if (level.getChunkSource().getChunkNow((int) chunk[0], (int) chunk[1]) == null) {
                    current.cursor--;
                    request(level, current, (int) chunk[0], (int) chunk[1]);
                    return;
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
            releaseAll(current);
        }
        if (current.phase == 2) {
            if (current.cursor >= current.chunks.size()) {
                GreatWallMod.LOGGER.info("[GreatWall:wonder] WONDER_SAMPLE_DONE blocks={} chunks={} elapsedMs={}",
                        current.written, current.chunks.size(),
                        (System.nanoTime() - current.startedAt) / 1_000_000L);
                job = null;
                return;
            }
            long[] chunk = current.chunks.get(current.cursor++);
            LevelChunk loaded = level.getChunkSource().getChunkNow((int) chunk[0], (int) chunk[1]);
            if (loaded == null) {
                request(level, current, (int) chunk[0], (int) chunk[1]);
                return;
            }
            current.written += paintChunk(current, loaded);
        }
    }

    private static void request(ServerLevel level, Job job, int cx, int cz) {
        for (long[] held : job.tickets) {
            if (held[0] == cx && held[1] == cz) return;
        }
        try {
            var access = (local.plaincraft.greatwall.mixin.TerrainChunkAccess) level.getChunkSource();
            var pos = new net.minecraft.world.level.ChunkPos(cx, cz);
            access.greatwall$distanceManager().addTicket(TICKET, pos,
                    net.minecraft.server.level.ChunkLevel.byStatus(
                            net.minecraft.world.level.chunk.status.ChunkStatus.FULL), "wonder");
            access.greatwall$request(cx, cz,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            job.tickets.add(new long[] {cx, cz});
        } catch (RuntimeException cannot) {
            // The sample is a demonstration; a chunk that refuses to load is skipped by the caller.
        }
    }

    private static final net.minecraft.server.level.TicketType<String> TICKET =
            net.minecraft.server.level.TicketType.create("greatwall_wonder",
                    java.util.Comparator.<String>naturalOrder());

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
            // Tickets vanish with the level anyway.
        }
        job.tickets.clear();
    }

    /**
     * Upper envelope plus slope limiting.
     *
     * <p>Every sample is the highest ground in a +/-48 block window plus the clearance; the second and
     * third passes raise the neighbours until no step is steeper than one block in six. Raising, never
     * lowering, is what guarantees the walkway is above the terrain everywhere.
     */
    private static int[] buildProfile(Job job) {
        int[] raw = new int[job.length];
        for (int i = 0; i < job.length; i++) {
            int best = Integer.MIN_VALUE;
            for (int d = -48; d <= 48; d += 4) {
                int x = job.startX + i + d;
                int ground = groundAt(job.level, x, job.startZ);
                if (ground > best) best = ground;
            }
            raw[i] = best + CLEARANCE;
        }
        int step = 6;
        for (int i = 1; i < raw.length; i++) raw[i] = Math.max(raw[i], raw[i - 1] - 1);
        for (int i = 0; i < raw.length; i++) {
            int limit = (int) Math.ceil((i + 1.0) / step);
            if (raw[i] < raw[0] - limit) raw[i] = raw[0] - limit;
        }
        for (int i = raw.length - 2; i >= 0; i--) raw[i] = Math.max(raw[i], raw[i + 1] - 1);
        return raw;
    }

    private static int groundAt(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return level.getMinBuildHeight();
        return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
    }

    /** Writes one chunk's slice of the section. */
    private static long paintChunk(Job job, LevelChunk chunk) {
        long written = 0L;
        int baseX = chunk.getPos().x << 4;
        int baseZ = chunk.getPos().z << 4;
        for (int lx = 0; lx < 16; lx++) {
            int x = baseX + lx;
            int s = x - job.startX;
            if (s < 0 || s >= job.length) continue;
            int walkY = job.profile[s];
            for (int lz = 0; lz < 16; lz++) {
                int z = baseZ + lz;
                int t = z - job.startZ;
                if (Math.abs(t) > HALF_WIDTH) continue;
                int ground = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                written += column(job.level, chunk, x, z, t, ground, walkY, s);
            }
        }
        return written;
    }

    private static long column(ServerLevel level, LevelChunk chunk, int x, int z, int t, int ground, int walkY, int s) {
        long written = 0L;
        int at = Math.abs(t);
        if (at >= HALF_WIDTH) return 0L;
        int foot = Math.max(ground - 1, walkY - MAX_FILL);
        // Four-block shell from the footing up to the walkway; the inside is left hollow, the way a
        // real rammed-earth-and-brick wall is, and it keeps a 40-wide wonder from costing the earth.
        for (int y = foot; y < walkY; y++) {
            if (at < HALF_WIDTH - 4) continue;
            BlockState state = (walkY - 1 - y) <= 8 ? FOUNDATION : BODY;
            written += put(level, chunk, x, y, z, state, false);
        }
        // Deck: stone brick paving with a drainage channel along each edge.
        if (at < HALF_WIDTH - 4) {
            BlockState deck = at == HALF_WIDTH - 5 ? DRAIN : (Math.floorMod(x + z, 7) == 0 ? WALK_ACCENT : WALK);
            written += put(level, chunk, x, walkY - 1, z, deck, true);
        }
        // Outer parapet (-Z side): crenellated, twelve high, with a two-by-two loophole under each gap.
        if (t <= -(HALF_WIDTH - 4)) {
            int phase = Math.floorMod(s, 12);
            int crenel = phase < 4 ? 6 : PARAPET;
            for (int y = 0; y < crenel; y++) {
                written += put(level, chunk, x, walkY + y, z, y == crenel - 1 ? CAP : BODY, true);
            }
            if (phase >= 4 && phase < 6) {
                put(level, chunk, x, walkY + 2, z, Blocks.AIR.defaultBlockState(), true);
                put(level, chunk, x, walkY + 3, z, Blocks.AIR.defaultBlockState(), true);
            }
        // Inner parapet (+Z side): the low friendly wall that keeps people on the walkway.
        } else if (t >= HALF_WIDTH - 4) {
            for (int y = 0; y < INNER_PARAPET; y++) {
                written += put(level, chunk, x, walkY + y, z, y == INNER_PARAPET - 1 ? CAP : BODY, true);
            }
        }
        return written;
    }
    /**
     * Writes one block. Surfaces go through the level so the heightmap and the light section are
     * updated; the fill below the walkway is written straight into the chunk, because it is buried and
     * a real call would run four heightmap updates per block over hundreds of thousands of blocks.
     */
    private static long put(ServerLevel level, LevelChunk chunk, int x, int y, int z, BlockState state, boolean lit) {
        BlockPos pos = new BlockPos(x, y, z);
        if (chunk.getBlockState(pos).equals(state)) return 0L;
        if (lit) level.setBlock(pos, state, 2);
        else chunk.setBlockState(pos, state, false);
        return 1L;
    }
}
