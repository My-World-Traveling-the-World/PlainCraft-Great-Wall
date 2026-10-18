package local.plaincraft.greatwall.terrain;

import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.model.WallTypes.Point2q;
import local.plaincraft.greatwall.model.WallTypes.RouteShape;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A coarse water map of the wall corridor, from the heightmaps alone.
 *
 * <h2>Why this exists</h2>
 * The hydrology classifier needs the width of a water span to apply the documented rule (rivers under
 * {@code HydrologyClassifier.RIVER_CROSS_MAX_BLOCKS} cross, wider ones terminate), and it can only measure
 * that width by walking reference columns - which fails wherever a column cannot certify ground or river
 * bed. The heightmaps answer the same question without walking anything: the world surface is above the
 * ocean floor exactly where there is water, so a column costs two heightmap reads and no density
 * evaluation at all.
 *
 * <p>It is a <em>measurement</em>. Nothing downstream is decided by it yet: generation is unchanged, and
 * the log line is what turns "how much of this corridor is water, and how wide" into a number.
 *
 * <p>Sampled every {@value #STEP_BLOCKS} blocks along the route. At quart resolution this is the same
 * tiering the World Preview mod uses to render a whole world, and it is roughly one sixteenth of the cost
 * of the fine sample over the same distance.
 */
public final class CorridorWaterScan {

    /** Blocks between sampled stations. Four matches the noise quart width. */
    public static final int STEP_BLOCKS = 4;

    /** At most this many runs are printed; the rest are summarised by count and total. */
    private static final int MAX_LOGGED_RUNS = 8;

    private CorridorWaterScan() {}

    /** One continuous water span, measured in blocks along the route. */
    public record WaterRun(int fromBlock, int toBlock) {
        public int widthBlocks() { return Math.max(0, toBlock - fromBlock); }
    }

    /**
     * {@return the water runs along {@code route}, sampled every {@value #STEP_BLOCKS} blocks}
     *
     * <p>Distances are along-route block counts, so a run width compares directly with the documented
     * crossing limit.
     */
    public static List<WaterRun> scan(ServerLevel level, RouteShape route) {
        List<WaterRun> runs = new ArrayList<>();
        if (level == null || route == null || route.points().size() < 2) return runs;
        List<Point2q> points = route.points();
        int start = -1;
        long travelled = 0L;
        int previousX = Integer.MIN_VALUE;
        int previousZ = Integer.MIN_VALUE;
        for (int index = 0; index < points.size(); index += STEP_BLOCKS) {
            Point2q point = points.get(index);
            int x = (int) Math.floor(point.xQ8() / 256.0D);
            int z = (int) Math.floor(point.zQ8() / 256.0D);
            if (previousX != Integer.MIN_VALUE && (x != previousX || z != previousZ)) {
                travelled += Math.abs(x - previousX) + Math.abs(z - previousZ);
            }
            previousX = x;
            previousZ = z;
            boolean wet = isWater(level, x, z);
            if (wet && start < 0) start = (int) travelled;
            if (!wet && start >= 0) {
                runs.add(new WaterRun(start, (int) travelled));
                start = -1;
            }
        }
        if (start >= 0) runs.add(new WaterRun(start, (int) travelled));
        return runs;
    }

    /**
     * Whether the column's surface stands above its solid floor - i.e. there is water in it.
     *
     * <p>{@code WORLD_SURFACE_WG} answers the top of the column including fluids, {@code OCEAN_FLOOR_WG}
     * the top of the solid part; a column where they differ has fluid on top of it. Both heightmaps are
     * primed by the mod's own chunk writer, so this costs two lookups and no generation.
     */
    public static boolean isWater(ServerLevel level, int x, int z) {
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
        return surface > floor;
    }

    /** Measures the corridor and logs it. Never throws: a diagnostic must not fail a startup. */
    public static void log(ServerLevel level, RouteShape route) {
        long started = System.nanoTime();
        List<WaterRun> runs;
        try {
            runs = scan(level, route);
        } catch (RuntimeException unavailable) {
            GreatWallMod.LOGGER.warn("[GreatWall:water] CORRIDOR_WATER skipped reason={}",
                    unavailable.toString());
            return;
        }
        long total = 0L;
        int widest = 0;
        int crossing = 0;
        for (WaterRun run : runs) {
            total += run.widthBlocks();
            widest = Math.max(widest, run.widthBlocks());
            if (run.widthBlocks() < HydrologyClassifier.RIVER_CROSS_MAX_BLOCKS) crossing++;
        }
        StringBuilder detail = new StringBuilder();
        for (int index = 0; index < Math.min(runs.size(), MAX_LOGGED_RUNS); index++) {
            WaterRun run = runs.get(index);
            if (index > 0) detail.append(' ');
            detail.append('(').append(run.fromBlock()).append("..").append(run.toBlock())
                    .append(" w=").append(run.widthBlocks()).append(')');
        }
        GreatWallMod.LOGGER.info("[GreatWall:water] CORRIDOR_WATER step={} stations={} runs={} "
                        + "totalWaterBlocks={} widest={} crossable={} threshold={} elapsedMs={} runs={}",
                STEP_BLOCKS, Math.max(1, route.points().size() / STEP_BLOCKS), runs.size(),
                total, widest, crossing, HydrologyClassifier.RIVER_CROSS_MAX_BLOCKS,
                Math.round((System.nanoTime() - started) / 1_000_000.0D), detail);
    }
}
