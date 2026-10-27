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
    /**
     * 门楼端面处的起始相位。
     *
     * <p>墙模块的垛口是「垛垛垛缺」周期 4，缺口在 `phase % 4 == 3`；门楼自己那条压顶的缺口在世界
     * `x % 4 == 0`（实测：184/188/…/324/328 都是缺）。墙从 `centreX + 73` 起铺，所以
     * `phase = (offset + i) % 4` 的缺口落在世界 `x = 329 + i` 上：
     * <ul>
     *   <li>`offset = 3`（0.7.95 之前）：缺口落在 `x % 4 == 1`，拼缝处门楼 328 的缺 + 墙 329 的缺 = <b>连出两个缺</b>；</li>
     *   <li>`offset = 0`：缺口落在 `x % 4 == 0`，和门楼完全同相位 —— 拼缝处是「门楼缺 → 墙垛垛垛 → 缺」，两侧墙都接得上。</li>
     * </ul>
     */
    private static final int PHASE_OFFSET = 0;

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
        // Gate template is never clipped; the wall finds its real outer end. 门楼「直接落地」：
        // 模板最底层坐在该处地表（footprint 中位数）上，墙底也取同一口径，接口因此天然对齐。
        ShowcaseStamp.startGateOnGround("gate.csv", 145, 42, 32, 72, 19, centreX, centreZ);
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
                // 直接落地：墙底（depth=17）坐在该段 footprint 的地表上。取中位数而不是单点/最高点，
                // 门楼和城墙用的都是同一口径，因此边缘相接时不会出现一格台阶。
                int ground = GroundLevel.median(level, current.minX, current.minZ,
                        current.maxX - current.minX + 1, current.maxZ - current.minZ + 1);
                if (ground == GroundLevel.MISSING) continue;
                current.baseY = ground;
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
     * 直线段直接按模板逐 phase 铺；只有弯曲段才用中心线扫掠。
     *
     * <p>直线段本来不需要任何几何处理，扫掠反而会在两端「鼓」出一圈：每列都被投影到折线端点再取整，
     * 端点处会补出完整的横向截面，俯视就是墙端多出半个圆盘（真机表现为墙端多一截、石英檐露出来）。
     * 所以直线段严格一 phase 一格，端面永远是模板剖面本身。
     */
    private static void buildBlocks(Job job) {
        Bounds bounds = new Bounds();
        Set<Long> placed = new HashSet<>();
        if (job.bend) {
            buildSwept(job, placed, bounds);
        } else {
            buildModuleRun(job, placed, bounds);
        }
        mirrorLeft(job, placed, bounds);
        job.minX = bounds.minX;
        job.minZ = bounds.minZ;
        job.maxX = bounds.maxX;
        job.maxZ = bounds.maxZ;
    }

    /** 模板行按 phase 索引一次，两条路径共用。 */
    private static Map<Integer, List<Row>> rowsByPhase(Job job) {
        Map<Integer, List<Row>> byPhase = new HashMap<>();
        for (Row row : job.rows) {
            byPhase.computeIfAbsent(row.phase(), k -> new ArrayList<>()).add(row);
        }
        return byPhase;
    }

    /** 直线段：与模板逐格一致（x 沿路每格一个 phase，z = centreZ + t，y = baseY + 17 - depth）。 */
    private static void buildModuleRun(Job job, Set<Long> placed, Bounds bounds) {
        Map<Integer, List<Row>> byPhase = rowsByPhase(job);
        for (int step = 0; step < job.steps; step++) {
            List<Row> rows = byPhase.get(Math.floorMod(PHASE_OFFSET + step, 48));
            if (rows == null) continue;
            int wx = job.centreX + 73 + step;
            for (Row row : rows) {
                add(job, placed, bounds, wx, 17 - row.depth(), job.centreZ + row.t(), row.state());
            }
        }
    }

    /**
     * 弯曲段：把每列投影到中心线上，横向取 t、纵向取 phase，用扫掠体的并集覆盖斜接与过渡区。
     * 额外丢掉落在折线两端以外的格子——否则端点会长出半个圆盘。
     */
    private static void buildSwept(Job job, Set<Long> placed, Bounds bounds) {
        List<double[]> line = centreline(job);
        Map<Integer, List<Row>> byPhase = rowsByPhase(job);

        double minLineX = Double.MAX_VALUE, maxLineX = -Double.MAX_VALUE;
        double minLineZ = Double.MAX_VALUE, maxLineZ = -Double.MAX_VALUE;
        for (double[] p : line) {
            minLineX = Math.min(minLineX, p[0]); maxLineX = Math.max(maxLineX, p[0]);
            minLineZ = Math.min(minLineZ, p[1]); maxLineZ = Math.max(maxLineZ, p[1]);
        }

        int fromX = (int) Math.floor(minLineX) - 7;
        int toX = (int) Math.ceil(maxLineX) + 7;
        int fromZ = (int) Math.floor(minLineZ) - 7;
        int toZ = (int) Math.ceil(maxLineZ) + 7;

        for (int wx = fromX; wx <= toX; wx++) {
            for (int wz = fromZ; wz <= toZ; wz++) {
                double bestDist2 = Double.MAX_VALUE;
                double bestS = 0.0D;
                double bestLat = 0.0D;
                double bestRawU = 0.0D;
                int bestSeg = -1;
                double travelled = 0.0D;
                for (int i = 1; i < line.size(); i++) {
                    double ax = line.get(i - 1)[0], az = line.get(i - 1)[1];
                    double bx = line.get(i)[0], bz = line.get(i)[1];
                    double vx = bx - ax, vz = bz - az;
                    double len2 = vx * vx + vz * vz;
                    if (len2 < 1.0E-9D) continue;
                    double rawU = ((wx - ax) * vx + (wz - az) * vz) / len2;
                    double u = Math.max(0.0D, Math.min(1.0D, rawU));
                    double qx = ax + vx * u, qz = az + vz * u;
                    double dx = wx - qx, dz = wz - qz;
                    double d2 = dx * dx + dz * dz;
                    if (d2 < bestDist2) {
                        bestDist2 = d2;
                        double len = Math.sqrt(len2);
                        bestS = travelled + len * u;
                        bestLat = dx * (-vz / len) + dz * (vx / len);
                        bestRawU = rawU;
                        bestSeg = i;
                    }
                    travelled += Math.sqrt(len2);
                }
                if (bestSeg < 0 || bestDist2 > 5.55D * 5.55D) continue;
                // 端点之外不延伸：投影落在首段起点外 / 末段终点外，整列丢掉。
                if (bestSeg == 1 && bestRawU < 0.0D) continue;
                if (bestSeg == line.size() - 1 && bestRawU > 1.0D) continue;
                int t = (int) Math.round(bestLat);
                if (t < -5 || t > 5) continue;
                List<Row> rows = byPhase.get(Math.floorMod(PHASE_OFFSET + (int) Math.floor(bestS), 48));
                if (rows == null) continue;
                for (Row row : rows) {
                    if (row.t() != t) continue;
                    add(job, placed, bounds, wx, 17 - row.depth(), wz, row.state());
                }
            }
        }
    }

    /** 中心线：从门楼端面外一格起，先直走 STRAIGHT 格，再用两段过渡转到 45°。 */
    private static List<double[]> centreline(Job job) {
        List<double[]> line = new ArrayList<>();
        double x = job.centreX + 73.0D;
        double z = job.centreZ;
        line.add(new double[] {x, z});
        for (int step = 0; step < job.steps; step++) {
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
            x += Math.cos(angle);
            z += Math.sin(angle);
            line.add(new double[] {x, z});
        }
        return line;
    }

    private static void add(Job job, Set<Long> placed, Bounds bounds, int wx, int wy, int wz,
                            BlockState state) {
        long key = ((long) wx & 0x3FFFFFFL) << 38 | ((long) wz & 0x3FFFFFFL) << 12 | (wy & 0xFFFL);
        if (!placed.add(key)) return;
        job.blocks.add(new int[] {wx, wy, wz});
        job.states.add(state);
        bounds.add(wx, wz);
    }

    /** 镜像到门楼的另一侧；只翻 x，z 方向不动，所以 t 的正负与门楼端面的 +z 侧保持一致。 */
    private static void mirrorLeft(Job job, Set<Long> placed, Bounds bounds) {
        int rightCount = job.blocks.size();
        for (int i = 0; i < rightCount; i++) {
            int[] p = job.blocks.get(i);
            int leftX = 2 * job.centreX - p[0];
            long key = ((long) leftX & 0x3FFFFFFL) << 38
                    | ((long) p[2] & 0x3FFFFFFL) << 12 | (p[1] & 0xFFFL);
            if (!placed.add(key)) continue;
            job.blocks.add(new int[] {leftX, p[1], p[2]});
            job.states.add(job.states.get(i));
            bounds.add(leftX, p[2]);
        }
    }

    /** 已铺格子的包围盒。 */
    private static final class Bounds {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        void add(int x, int z) {
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
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
