package local.plaincraft.greatwall.generation;

import java.util.Arrays;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 展台（以及后续生产线的引入段）统一使用的「落地平面」取样器。
 *
 * <p><b>落地平面 = 地面上方的第一层空气</b>：模板最底层落在这一层，就等于整件坐在草方块顶面上。
 * ⚠️ 这里踩过一次坑（0.7.91 真机）：{@code LevelChunk.getHeight} 返回的是「最高实心方块那一层」
 * （草方块在 -61 时给 -61），而 {@code Level.getHeight} 返回「上方第一层空气」（给 -60）。
 * 用前者当落地点，门楼和城墙就整件沉进地面 1 格。所以本类统一取 {@code chunk.getHeight(...) + 1}，
 * 语义固定为「第一层空气」，调用方不要再自行 ±1。
 *
 * <p>取 <b>中位数</b> 而不是最高点：footprint 里有一棵树、一座村屋或一个土包就会把最高点抬高好几格，
 * 0.7.89 就因此把整座门楼压到地面以下 4 格。中位数对这类离群点不敏感，门楼和城墙因此能共用同一个落地平面。
 */
public final class GroundLevel {
    /** 还有区块没加载完（调用方应当下一 tick 重试，绝不猜高度）。 */
    public static final int MISSING = Integer.MIN_VALUE;

    private GroundLevel() {
    }

    /** Footprint 的落脚层中位数（地面上方的第一层空气）；任一区块缺失返回 {@link #MISSING}。 */
    public static int median(ServerLevel level, int x0, int z0, int width, int depth) {
        if (width <= 0 || depth <= 0) return MISSING;
        return fill(level, x0, z0, width, depth, new int[width * depth]);
    }

    /**
     * 采样 footprint 每一列的落脚层（地面上方的第一层空气）写入 {@code out}（下标 {@code dx * depth + dz}），
     * 返回中位数。
     *
     * <p>{@code out} 后续被地基填充用来判断「这一列地面在哪儿」，所以必须和落位用同一次采样，
     * 否则模型写下去之后再量地表会读到模型自己。
     */
    public static int fill(ServerLevel level, int x0, int z0, int width, int depth, int[] out) {
        if (level == null || out == null || width <= 0 || depth <= 0) return MISSING;
        if (out.length < width * depth) return MISSING;
        int[] sorted = new int[width * depth];
        int n = 0;
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                int x = x0 + dx;
                int z = z0 + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return MISSING;
                // LevelChunk.getHeight 给的是「最高实心方块那一层」，+1 才是可以落脚的第一层空气。
                int landing = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 1;
                out[dx * depth + dz] = landing;
                sorted[n++] = landing;
            }
        }
        if (n == 0) return MISSING;
        Arrays.sort(sorted, 0, n);
        return sorted[(n - 1) / 2];
    }
}
