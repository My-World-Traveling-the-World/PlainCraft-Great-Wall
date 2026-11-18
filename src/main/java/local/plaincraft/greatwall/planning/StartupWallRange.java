package local.plaincraft.greatwall.planning;

import java.util.function.LongPredicate;
import local.plaincraft.greatwall.config.GreatWallConfig;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.WorldRecipe;

/** 会话启动窗口：计划、采样和编译读取同一个已校验段数。 */
public record StartupWallRange(long requestedSegments, long segments, long configuredSegments,
        long structureScanBlocks) {

    static StartupWallRange resolve(long wallLength, int unlimitedPrewarmBlocks, LongPredicate safeSegment) {
        long configured = GreatWallConfig.segmentsFor(wallLength);
        long requested = GreatWallConfig.startupSegments(wallLength, unlimitedPrewarmBlocks);
        long segments = safePrefix(requested, safeSegment);
        // 有限墙原来还为后续游戏内段扫描结构，保留这个能力；无限墙只索引开局窗口。
        long scanBlocks = configured == 0 ? segments * GreatWallConfig.SEGMENT_BLOCKS
                : Math.min(wallLength, safePrefix(configured, safeSegment) * GreatWallConfig.SEGMENT_BLOCKS);
        return new StartupWallRange(requested, segments, configured, scanBlocks);
    }

    private static long safePrefix(long requested, LongPredicate safeSegment) {
        if (requested == 0 || !safeSegment.test(0)) return 0;
        // 轴是直线，两个端点都在凸包络内时中间也在；查找首个不安全的段，不逐段试探长墙。
        long lo = 1, hi = requested;
        while (lo < hi) {
            long mid = lo + (hi - lo + 1) / 2;
            if (safeSegment.test(mid - 1)) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    static boolean segmentFits(WorldRecipe recipe, long configuredSegments, long segment) {
        if (segment < 0 || (configuredSegments > 0 && segment >= configuredSegments)) return false;
        try {
            long radius = 1168L * 256, limit = recipe.bounds().horizontalLimit() * 256L;
            return endpointFits(recipe, radius, limit, segment)
                    && endpointFits(recipe, radius, limit, Math.addExact(segment, 1));
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private static boolean endpointFits(WorldRecipe recipe, long radius, long limit, long index) {
        // 预留主廊、山脊、构件与侧列空间；与段实际使用的冻结包络保持一致。
        long u = Math.multiplyExact(index, GreatWallConfig.SEGMENT_BLOCKS * 256L);
        var axis = recipe.axis();
        long x = Math.addExact(axis.origin().x() * 256L, FixedMath.mulDiv(u, axis.exQ30(), 1L << 30));
        long z = Math.addExact(axis.origin().z() * 256L, FixedMath.mulDiv(u, axis.ezQ30(), 1L << 30));
        long max = limit - radius;
        return x >= -max && x <= max && z >= -max && z <= max;
    }
}
