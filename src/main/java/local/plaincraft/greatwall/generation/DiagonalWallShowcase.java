package local.plaincraft.greatwall.generation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.compat.road.WallRoadGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A separate showcase where an upright, axis-aligned gate is approached by a wall whose
 * centreline starts straight at the gate end and then bends away through two transition sectors
 * into a 45-degree run. The gate template is never rotated; only the wall's route changes.
 */
public final class DiagonalWallShowcase {
    private static final String WALL = "/assets/plaincraft-great-wall/templates/wall_module.csv";
    private static final int STRAIGHT = 24;
    private static final int TRANSITION = 8;
    private static final int STEPS = STRAIGHT + 2 * TRANSITION + 24;

    private static Job job;

    private DiagonalWallShowcase() {
    }

    private record Row(int phase, int t, int depth, BlockState state) {
    }

    private static final class Job {
        final ServerLevel level;
        final int centreX, centreZ;
        final List<Row> rows;
        final List<int[]> blocks = new ArrayList<>();
        final List<BlockState> states = new ArrayList<>();
        int minX, minZ, maxX, maxZ;
        int baseY;
        long[] ticket;
        int cursor;
        boolean loaded;
        boolean finished;

        Job(ServerLevel level, int centreX, int centreZ, List<Row> rows) {
            this.level = level;
            this.centreX = centreX;
            this.centreZ = centreZ;
            this.rows = rows;
        }
    }

    public static void start(ServerLevel level, int centreX, int centreZ) {
        if (level == null || job != null) return;
        List<Row> rows = load();
        if (rows.isEmpty()) {
            GreatWallMod.LOGGER.warn("[GreatWall:wonder] DIAGONAL_WALL_EMPTY");
            return;
        }
        job = new Job(level, centreX, centreZ, rows);
        // Gate stays upright and whole. Wall attach point is the gate's +x end.
        ShowcaseStamp.startGate("gate.csv", 145, 44, 32, 72, 19, centreX, centreZ, -2, 68, 76);
        ShowcaseLayout.add("diagonal-wall", centreX - 210, centreZ - 16, centreX + 210, centreZ + 110);
        ShowcaseGate.expect();
        GreatWallMod.LOGGER.info("[GreatWall:wonder] DIAGONAL_WALL_START gate=({},{}) straight={} transitions={} steps={}",
                centreX, centreZ, STRAIGHT, TRANSITION, STEPS);
    }

    public static void tick(ServerLevel level) {
        Job current = job;
        if (current == null || current.level != level || current.finished) return;
        if (!current.loaded) {
            buildBlocks(current);
            current.loaded = true;
            if (current.blocks.isEmpty()) {
                finish(current, "empty");
                return;
            }
        }
        if (current.ticket == null) {
            current.ticket = ChunkHold.hold(level, current.minX, current.minZ, current.maxX, current.maxZ);
        }
        if (!chunksReady(level, current)) return;
        if (current.baseY == 0) {
            current.baseY = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    current.centreX + 73, current.centreZ);
        }
        int budget = 4000;
        while (current.cursor < current.blocks.size() && budget-- > 0) {
            int[] p = current.blocks.get(current.cursor);
            int y = current.baseY - 1 + p[1];
            level.setBlock(new BlockPos(p[0], y, p[2]), current.states.get(current.cursor), 2);
            WallRoadGuard.markColumn(p[0], p[2]);
            current.cursor++;
        }
        if (current.cursor >= current.blocks.size()) {
            finish(current, "stamped");
        }
    }

    public static void reset(ServerLevel level) {
        if (job != null && job.ticket != null) ChunkHold.release(level, job.ticket);
        job = null;
    }

    private static void finish(Job job, String reason) {
        job.finished = true;
        if (job.ticket != null) ChunkHold.release(job.level, job.ticket);
        job.ticket = null;
        ShowcaseGate.done();
        GreatWallMod.LOGGER.info("[GreatWall:wonder] DIAGONAL_WALL_DONE reason={} blocks={} baseY={}",
                reason, job.blocks.size(), job.baseY);
    }

    private static boolean chunksReady(ServerLevel level, Job job) {
        for (int cx = job.minX >> 4; cx <= job.maxX >> 4; cx++) {
            for (int cz = job.minZ >> 4; cz <= job.maxZ >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }

    private static void buildBlocks(Job job) {
        double x = job.centreX + 73.0D;
        double z = job.centreZ;
        double nx = 0.0D, nz = 1.0D;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int step = 0; step < STEPS; step++) {
            double angle;
            if (step < STRAIGHT) {
                angle = 0.0D;
            } else if (step < STRAIGHT + TRANSITION) {
                angle = Math.toRadians(15.0D);
            } else if (step < STRAIGHT + 2 * TRANSITION) {
                angle = Math.toRadians(30.0D);
            } else {
                angle = Math.toRadians(45.0D);
            }
            double dx = Math.cos(angle), dz = Math.sin(angle);
            nx = -dz;
            nz = dx;
            int cx = (int) Math.round(x);
            int cz = (int) Math.round(z);
            int phase = Math.floorMod(3 + step, 48);
            for (Row row : job.rows) {
                if (row.phase() != phase) continue;
                int bx = (int) Math.round(cx + nx * row.t());
                int bz = (int) Math.round(cz + nz * row.t());
                int by = 17 - row.depth();
                job.blocks.add(new int[] {bx, by, bz});
                job.states.add(row.state());
                minX = Math.min(minX, bx); minZ = Math.min(minZ, bz);
                maxX = Math.max(maxX, bx); maxZ = Math.max(maxZ, bz);
            }
            x += dx;
            z += dz;
        }
        // Mirror the same wall onto the gate's other side; both ends get a wall, one straight
        // attachment step followed by the same two transition sectors.
        int rightCount = job.blocks.size();
        for (int i = 0; i < rightCount; i++) {
            int[] p = job.blocks.get(i);
            int leftX = 2 * job.centreX - p[0];
            job.blocks.add(new int[] {leftX, p[1], p[2]});
            job.states.add(job.states.get(i));
            minX = Math.min(minX, leftX);
            maxX = Math.max(maxX, leftX);
            minZ = Math.min(minZ, p[2]);
            maxZ = Math.max(maxZ, p[2]);
        }
        job.minX = minX;
        job.minZ = minZ;
        job.maxX = maxX;
        job.maxZ = maxZ;
    }

    private static List<Row> load() {
        var result = new ArrayList<Row>();
        try (InputStream in = DiagonalWallShowcase.class.getResourceAsStream(WALL)) {
            if (in == null) return result;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 4);
                if (parts.length != 4) continue;
                result.add(new Row(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), BlockSpec.parse(parts[3])));
            }
        } catch (Exception broken) {
            GreatWallMod.LOGGER.warn("[GreatWall:wonder] DIAGONAL_WALL_LOAD_FAILED reason={}", broken.toString(), broken);
        }
        return result;
    }
}
