package local.plaincraft.greatwall.generation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final List<Job> JOBS = new ArrayList<>();

    private DiagonalWallShowcase() {
    }

    private record Row(int phase, int t, int depth, BlockState state) {
    }

    private static final class Job {
        final ServerLevel level;
        final int centreX, centreZ;
        final List<Row> rows;
        final boolean bend;
        final int steps;
        final String accountingName;
        final List<int[]> blocks = new ArrayList<>();
        final List<BlockState> states = new ArrayList<>();
        int minX, minZ, maxX, maxZ;
        int baseY;
        long[] ticket;
        int cursor;
        boolean loaded;
        boolean finished;

        Job(ServerLevel level, int centreX, int centreZ, List<Row> rows, boolean bend) {
            this.level = level;
            this.centreX = centreX;
            this.centreZ = centreZ;
            this.rows = rows;
            this.bend = bend;
            this.steps = bend ? STEPS : 48;
            this.accountingName = "wall-path@" + centreX + "," + centreZ + (bend ? "/bend" : "/straight");
        }
    }

    public static void start(ServerLevel level, int centreX, int centreZ) {
        start(level, centreX, centreZ, true);
    }

    public static void start(ServerLevel level, int centreX, int centreZ, boolean bend) {
        if (level == null) return;
        List<Row> rows = load();
        if (rows.isEmpty()) {
            GreatWallMod.LOGGER.warn("[GreatWall:wonder] DIAGONAL_WALL_EMPTY");
            return;
        }
        Job added = new Job(level, centreX, centreZ, rows, bend);
        JOBS.add(added);
        // Gate template is never clipped; the wall finds its real outer end.
        ShowcaseStamp.startGate("gate.csv", 145, 44, 32, 72, 19, centreX, centreZ, -2, 68, 76);
        ShowcaseLayout.add(bend ? "diagonal-wall" : "straight-wall",
                centreX - 210, centreZ - 16, centreX + 210, centreZ + 110);
        ShowcaseGate.expect(added.accountingName);
        GreatWallMod.LOGGER.info("[GreatWall:wonder] WALL_PATH_START gate=({},{}) bend={} steps={}",
                centreX, centreZ, bend, added.steps);
    }

    public static void tick(ServerLevel level) {
        for (int i = JOBS.size() - 1; i >= 0; i--) {
            Job current = JOBS.get(i);
            if (current.level != level || current.finished) {
                if (current.finished) JOBS.remove(i);
                continue;
            }
            if (!current.loaded) {
                buildBlocks(current);
                current.loaded = true;
                if (current.blocks.isEmpty()) {
                    finish(current, "empty");
                    continue;
                }
            }
            if (current.ticket == null) {
                current.ticket = ChunkHold.hold(level, current.minX, current.minZ, current.maxX, current.maxZ);
            }
            if (!chunksReady(level, current)) continue;
            if (current.baseY == 0) {
                current.baseY = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                        current.centreX + 73, current.centreZ);
            }
            int budget = 4000;
            while (current.cursor < current.blocks.size() && budget-- > 0) {
                int[] p = current.blocks.get(current.cursor);
                // 0.7.90：墙抬高 1 格。baseY 是地表上方第一层空气，墙底放到 baseY 上，
                // 正好和门楼端面的墙接口（gate.csv 局部 y=3）对齐。
                int y = current.baseY + p[1];
                level.setBlock(new BlockPos(p[0], y, p[2]), current.states.get(current.cursor), 2);
                WallRoadGuard.markColumn(p[0], p[2]);
                current.cursor++;
            }
            if (current.cursor >= current.blocks.size()) {
                finish(current, "stamped");
            }
        }
    }

    public static void reset(ServerLevel level) {
        for (Job job : JOBS) {
            if (job.ticket != null) ChunkHold.release(level, job.ticket);
        }
        JOBS.clear();
    }

    private static void finish(Job job, String reason) {
        job.finished = true;
        if (job.ticket != null) ChunkHold.release(job.level, job.ticket);
        job.ticket = null;
        ShowcaseGate.done(job.accountingName);
        GreatWallMod.LOGGER.info("[GreatWall:wonder] WALL_PATH_DONE bend={} reason={} blocks={} baseY={}",
                job.bend, reason, job.blocks.size(), job.baseY);
    }

    private static boolean chunksReady(ServerLevel level, Job job) {
        for (int cx = job.minX >> 4; cx <= job.maxX >> 4; cx++) {
            for (int cz = job.minZ >> 4; cz <= job.maxZ >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }

    /**
     * Rasterise the wall as a swept volume along the centreline. Each world column is assigned
     * to the nearest point on the polyline; its lateral coordinate selects the CSV t row and
     * its longitudinal distance selects the phase. This covers miter/transition areas with the
     * union of the swept body instead of exposing the seams between adjacent cross-sections.
     */
    private static void buildBlocks(Job job) {
        // Build the exact centreline as a polyline, including the bend sectors.
        List<double[]> line = new ArrayList<>();
        double x = job.centreX + 73.0D;
        double z = job.centreZ;
        line.add(new double[] {x, z});
        for (int step = 0; step < job.steps; step++) {
            double angle;
            if (!job.bend || step < STRAIGHT) {
                angle = 0.0D;
            } else if (step < STRAIGHT + TRANSITION) {
                angle = Math.toRadians(15.0D);
            } else if (step < STRAIGHT + 2 * TRANSITION) {
                angle = Math.toRadians(30.0D);
            } else {
                angle = Math.toRadians(45.0D);
            }
            x += Math.cos(angle);
            z += Math.sin(angle);
            line.add(new double[] {x, z});
        }

        // Index the template rows by phase and lateral coordinate.
        Map<Integer, Map<Integer, List<Row>>> byPhase = new HashMap<>();
        for (Row row : job.rows) {
            byPhase.computeIfAbsent(row.phase(), k -> new HashMap<>())
                    .computeIfAbsent(row.t(), k -> new ArrayList<>())
                    .add(row);
        }

        double minLineX = Double.MAX_VALUE, maxLineX = -Double.MAX_VALUE;
        double minLineZ = Double.MAX_VALUE, maxLineZ = -Double.MAX_VALUE;
        for (double[] p : line) {
            minLineX = Math.min(minLineX, p[0]); maxLineX = Math.max(maxLineX, p[0]);
            minLineZ = Math.min(minLineZ, p[1]); maxLineZ = Math.max(maxLineZ, p[1]);
        }

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Set<Long> placed = new HashSet<>();
        int fromX = (int) Math.floor(minLineX) - 7;
        int toX = (int) Math.ceil(maxLineX) + 7;
        int fromZ = (int) Math.floor(minLineZ) - 7;
        int toZ = (int) Math.ceil(maxLineZ) + 7;

        for (int wx = fromX; wx <= toX; wx++) {
            for (int wz = fromZ; wz <= toZ; wz++) {
                double bestDist2 = Double.MAX_VALUE;
                double bestS = 0.0D;
                double bestLat = 0.0D;
                double travelled = 0.0D;
                for (int i = 1; i < line.size(); i++) {
                    double ax = line.get(i - 1)[0], az = line.get(i - 1)[1];
                    double bx = line.get(i)[0], bz = line.get(i)[1];
                    double vx = bx - ax, vz = bz - az;
                    double len2 = vx * vx + vz * vz;
                    if (len2 < 1.0E-9D) continue;
                    double u = ((wx - ax) * vx + (wz - az) * vz) / len2;
                    u = Math.max(0.0D, Math.min(1.0D, u));
                    double qx = ax + vx * u, qz = az + vz * u;
                    double dx = wx - qx, dz = wz - qz;
                    double d2 = dx * dx + dz * dz;
                    if (d2 < bestDist2) {
                        bestDist2 = d2;
                        double len = Math.sqrt(len2);
                        bestS = travelled + len * u;
                        bestLat = dx * (-vz / len) + dz * (vx / len);
                    }
                    travelled += Math.sqrt(len2);
                }
                if (bestDist2 > 5.55D * 5.55D) continue;
                int t = (int) Math.round(bestLat);
                if (t < -5 || t > 5) continue;
                int phase = Math.floorMod(3 + (int) Math.floor(bestS), 48);
                Map<Integer, List<Row>> lateral = byPhase.get(phase);
                if (lateral == null) continue;
                List<Row> rows = lateral.get(t);
                if (rows == null) continue;
                for (Row row : rows) {
                    int by = 17 - row.depth();
                    long key = ((long) wx & 0x3FFFFFFL) << 38
                            | ((long) wz & 0x3FFFFFFL) << 12 | (by & 0xFFFL);
                    if (!placed.add(key)) continue;
                    job.blocks.add(new int[] {wx, by, wz});
                    job.states.add(row.state());
                    minX = Math.min(minX, wx); minZ = Math.min(minZ, wz);
                    maxX = Math.max(maxX, wx); maxZ = Math.max(maxZ, wz);
                }
            }
        }

        // Mirror onto the gate's other side.
        int rightCount = job.blocks.size();
        for (int i = 0; i < rightCount; i++) {
            int[] p = job.blocks.get(i);
            int leftX = 2 * job.centreX - p[0];
            long key = ((long) leftX & 0x3FFFFFFL) << 38
                    | ((long) p[2] & 0x3FFFFFFL) << 12 | (p[1] & 0xFFFL);
            if (!placed.add(key)) continue;
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
