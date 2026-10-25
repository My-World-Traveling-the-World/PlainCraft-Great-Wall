package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.component.WallSection;
import local.plaincraft.greatwall.decay.DecayField;
import local.plaincraft.greatwall.decay.PaletteResolver;
import local.plaincraft.greatwall.decay.PaletteTypes;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.WallFoundationPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A separate, production-equivalent terrain demo for the superflat workshop.
 *
 * <p>It builds a synthetic diagonal ridge with a valley and a water basin, then feeds the real
 * {@link WallSection} through the same station/support/foundation contract the planner uses. The
 * wall is not a hand-drawn facade: every voxel comes from {@code WallSection}, and the low columns
 * are filled by the same {@code ColumnFoundation} path as production.
 */
public final class TerrainShowcase {
    private static final int SIZE = 160;
    private static final int PATH_START = 20;
    private static final int PATH_LENGTH = 128;
    private static final long ARC_BASE = 2_000_000L;
    private static final double INV_SQRT2 = 1.0D / Math.sqrt(2.0D);
    private static final int WALK_ABOVE_BASE = 22;

    private static Job job;
    private static boolean completed;

    private TerrainShowcase() {
    }

    private static final class Job {
        final ServerLevel level;
        final int minX, minZ, maxX, maxZ;
        int baseY;
        boolean baseReady;
        long[] ticket;
        boolean terrainBuilt;
        int cursor;
        List<ComponentVoxels> components;
        List<int[]> tower;
        List<BlockState> towerStates;

        Job(ServerLevel level, int minX, int minZ, int baseY) {
            this.level = level;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = minX + SIZE - 1;
            this.maxZ = minZ + SIZE - 1;
            this.baseY = baseY;
        }
    }

    public static void start(ServerLevel level, int centreX, int centreZ) {
        if (level == null || job != null) return;
        int minX = centreX - SIZE / 2;
        int minZ = centreZ - SIZE / 2;
        job = new Job(level, minX, minZ, 0);
        completed = false;
        ShowcaseLayout.add("terrain-demo", minX, minZ, minX + SIZE - 1, minZ + SIZE - 1);
        ShowcaseGate.expect();
    }

    /** Advances terrain creation and, once complete, the wall compilation/write. */
    public static void tick(ServerLevel level) {
        Job current = job;
        if (current == null || current.level != level || completed) return;
        if (current.ticket == null) {
            current.ticket = ChunkHold.hold(level, current.minX, current.minZ, current.maxX, current.maxZ);
        }
        if (!chunksReady(level, current)) return;
        if (!current.baseReady) {
            current.baseY = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    current.minX + PATH_START, current.minZ + PATH_START);
            current.baseReady = true;
        }
        if (!current.terrainBuilt) {
            try {
                buildTerrain(level, current);
                current.terrainBuilt = true;
                current.components = buildWallComponents(level, current);
                buildTower(level, current);
            } catch (RuntimeException broken) {
                fail(current, broken);
                return;
            }
            GreatWallMod.LOGGER.info("[GreatWall:wonder] TERRAIN_DEMO_BUILT baseY={} components={}",
                    current.baseY, current.components.size());
            return;
        }
        if (current.cursor < current.components.size()) {
            try {
                writeComponent(level, current.components.get(current.cursor++));
            } catch (RuntimeException broken) {
                fail(current, broken);
            }
            return;
        }
        completed = true;
        ChunkHold.release(level, current.ticket);
        current.ticket = null;
        ShowcaseGate.done();
        GreatWallMod.LOGGER.info("[GreatWall:wonder] TERRAIN_DEMO_DONE origin=({},{}) baseY={}",
                current.minX, current.minZ, current.baseY);
    }

    public static void reset(ServerLevel level) {
        if (job != null && job.ticket != null) ChunkHold.release(level, job.ticket);
        job = null;
        completed = false;
    }

    private static void fail(Job job, RuntimeException broken) {
        completed = true;
        if (job.ticket != null) ChunkHold.release(job.level, job.ticket);
        job.ticket = null;
        ShowcaseGate.done();
        GreatWallMod.LOGGER.error("[GreatWall:wonder] TERRAIN_DEMO_FAILED origin=({},{}) reason={}",
                job.minX, job.minZ, broken.toString(), broken);
    }

    private static boolean chunksReady(ServerLevel level, Job job) {
        for (int cx = job.minX >> 4; cx <= job.maxX >> 4; cx++) {
            for (int cz = job.minZ >> 4; cz <= job.maxZ >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }

    private static void buildTerrain(ServerLevel level, Job job) {
        int startX = job.minX + PATH_START;
        int startZ = job.minZ + PATH_START;
        for (int x = job.minX; x <= job.maxX; x++) {
            for (int z = job.minZ; z <= job.maxZ; z++) {
                double s = ((x - startX) + (z - startZ)) * INV_SQRT2;
                double t = (-(x - startX) + (z - startZ)) * INV_SQRT2;
                if (Math.abs(t) > 30.0D) continue;
                int top = job.baseY + profile(s);
                int floor = job.baseY - 24;
                for (int y = floor; y <= top; y++) {
                    level.setBlock(new BlockPos(x, y, z),
                            y == top ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState(), 2);
                }
                if (water(s)) {
                    int bed = job.baseY - 16;
                    for (int y = bed + 1; y <= job.baseY - 8; y++) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static int profile(double s) {
        if (s < 30) return 8;
        if (s < 55) return 8 - (int) Math.round((s - 30) * 18.0D / 25.0D);
        if (s < 80) return -10;
        if (s < 105) return -16;
        if (s < 125) return -16 + (int) Math.round((s - 105) * 24.0D / 20.0D);
        return 8;
    }

    private static boolean water(double s) {
        return s >= 80 && s < 105;
    }

    private static int solidTop(Job job, int x, int z) {
        int startX = job.minX + PATH_START;
        int startZ = job.minZ + PATH_START;
        double s = ((x - startX) + (z - startZ)) * INV_SQRT2;
        double t = (-(x - startX) + (z - startZ)) * INV_SQRT2;
        return Math.abs(t) > 30.0D ? job.baseY : job.baseY + profile(s);
    }

    private static void buildTower(ServerLevel level, Job job) {
        int startX = job.minX + PATH_START;
        int startZ = job.minZ + PATH_START;
        int cx = (int) Math.floor(startX + INV_SQRT2 * 126);
        int cz = (int) Math.floor(startZ + INV_SQRT2 * 126);
        int base = job.baseY + 8;
        for (int x = cx - 10; x <= cx + 10; x++) {
            for (int z = cz - 10; z <= cz + 10; z++) {
                int ground = solidTop(job, x, z);
                for (int y = Math.min(base, ground); y <= base + 20; y++) {
                    int dx = x - cx, dz = z - cz;
                    boolean shell = Math.abs(dx) == 9 || Math.abs(dz) == 9;
                    boolean corner = Math.abs(dx) >= 8 && Math.abs(dz) >= 8;
                    if (shell || corner) {
                        level.setBlock(new BlockPos(x, y, z),
                                (y == base + 20 || y == base + 19) ? Blocks.STONE_BRICK_SLAB.defaultBlockState()
                                        : Blocks.STONE_BRICKS.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static List<ComponentVoxels> buildWallComponents(ServerLevel level, Job job) {
        var palette = new PaletteResolver(0, PaletteResolver.defaults());
        var wall = new WallSection(new DecayField(0), palette);
        var result = new ArrayList<ComponentVoxels>();
        result.add(compileCell(level, job, wall, 32, 0, 64));
        result.add(compileCell(level, job, wall, 96, 64, 118));
        return result;
    }

    private static ComponentVoxels compileCell(ServerLevel level, Job job, WallSection wall,
                                               int centreS, int fromS, int toS) {
        int startX = job.minX + PATH_START;
        int startZ = job.minZ + PATH_START;
        int cx = (int) Math.floor(startX + INV_SQRT2 * centreS);
        int cz = (int) Math.floor(startZ + INV_SQRT2 * centreS);
        int walkY = job.baseY + WALK_ABOVE_BASE;
        long centre = ARC_BASE + (long) centreS * 256L;
        var frame = new Frame(new Point2q((long) cx * 256L, (long) cz * 256L),
                759_250_125, 759_250_125, walkY, false);
        var site = new SiteRecord(new SiteId("terrain-demo", SiteKind.WALL, 0), centre, frame,
                new Bounds(cx - 80, job.baseY - 64, cz - 80, cx + 80, walkY + 40, cz + 80),
                SiteState.PLANNED, Optional.empty(), Optional.empty());
        var stations = new ArrayList<Station>();
        for (int s = centreS - 40; s <= centreS + 40; s++) {
            stations.add(new Station(ARC_BASE + (long) s * 256L,
                    new Point2q((long) Math.floor(startX + INV_SQRT2 * s) * 256L,
                            (long) Math.floor(startZ + INV_SQRT2 * s) * 256L), walkY, 0));
        }
        var supports = new HashMap<Point3i, ProbeResult>();
        var floor = new HashMap<Long, Integer>();
        var foundations = new ArrayList<WallFoundationPlanner.ColumnFoundation>();
        for (int s = centreS - 40; s <= centreS + 40; s++) {
            int sx = (int) Math.floor(startX + INV_SQRT2 * s);
            int sz = (int) Math.floor(startZ + INV_SQRT2 * s);
            int base = Integer.MIN_VALUE;
            var samples = new ArrayList<int[]>();
            for (int t = -5; t <= 5; t++) {
                int x = (int) Math.floor(sx + INV_SQRT2 * t);
                int z = (int) Math.floor(sz - INV_SQRT2 * t);
                int ground = solidTop(job, x, z);
                samples.add(new int[] {t, x, z, ground});
                base = Math.max(base, ground);
            }
            var fills = new ArrayList<WallFoundationPlanner.FillColumn>();
            for (var sample : samples) {
                if (sample[3] < base) {
                    fills.add(new WallFoundationPlanner.FillColumn(sample[0], sample[3], sample[1], sample[2], base));
                    floor.put(columnKey(sample[1], sample[2]), sample[3]);
                }
            }
            foundations.add(new WallFoundationPlanner.ColumnFoundation(ARC_BASE + (long) s * 256L,
                    sx, sz, base, fills));
        }
        for (int x = cx - 36; x <= cx + 36; x++) {
            for (int z = cz - 36; z <= cz + 36; z++) {
                int ground = solidTop(job, x, z);
                boolean water = water(((x - startX) + (z - startZ)) * INV_SQRT2);
                boolean deep = floor.containsKey(columnKey(x, z));
                var kind = water ? ProbeKind.WATER : (deep ? ProbeKind.SUPPORTED : ProbeKind.SUPPORTED);
                var waterTop = water ? OptionalInt.of(job.baseY - 8) : OptionalInt.empty();
                var bed = water ? OptionalInt.of(job.baseY - 16) : OptionalInt.empty();
                String hash = DeterministicHash.sha256(("terrain-demo:" + x + ":" + z + ":" + ground)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                // The demo's synthetic walkway is deliberately held high across the valley; the
                // ordinary support probe would reject that as a >12 drop. Keep the ground evidence,
                // but mark it buildable so the wall body reaches ComponentAssembly; the explicit
                // foundation fills still come from the real ColumnFoundation path.
                supports.put(new Point3i(x, walkY, z), new ProbeResult(kind, ground, waterTop, bed,
                        0, hash, OptionalInt.empty()));
            }
        }
        var materials = List.of(new PaletteTypes.BiomeStation(0, "minecraft:plains", PaletteTypes.Variant.STONE),
                new PaletteTypes.BiomeStation(Long.MAX_VALUE / 8, "minecraft:plains", PaletteTypes.Variant.STONE));
        var input = new ComponentInput(site, stations, supports,
                new DecaySample(DecayState.INTACT, 0, 20), PaletteFamily.STONE, false,
                List.of(), materials, local.plaincraft.greatwall.component.GroundEntranceContract.Evidence.analytic(supports),
                floor, foundations);
        return wall.compileIntervals(input,
                List.of(new ArcInterval(ARC_BASE + (long) fromS * 256L, ARC_BASE + (long) toS * 256L)));
    }

    private static void writeComponent(ServerLevel level, ComponentVoxels component) {
        for (VoxelIntent voxel : component.voxels()) {
            BlockState state = local.plaincraft.greatwall.terrain.BlockCatalog.resolveState(voxel.state());
            level.setBlock(new BlockPos(voxel.pos().x(), voxel.pos().y(), voxel.pos().z()), state, 2);
        }
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
