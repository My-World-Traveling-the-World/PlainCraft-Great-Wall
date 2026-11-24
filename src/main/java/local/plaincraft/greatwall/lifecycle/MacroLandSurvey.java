package local.plaincraft.greatwall.lifecycle;

import java.util.HashMap;
import java.util.Map;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.OceanRouteScan;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;

/** 用有界稀疏圆域比较整条主轴，两端一起算；只为选线，不把粗列交给施工。 */
final class MacroLandSurvey {
    private record Cell(boolean wet, int height) {}
    private final Point3i center;
    private final DimensionBounds bounds;
    private final int radius, step, cells;
    private final Map<Long, Cell> samples = new HashMap<>();
    private int index;
    private NaturalTerrainOracle.ColumnCursor column;
    private String biome;
    private final Map<Axis, Long> scores = new HashMap<>();
    MacroLandSurvey(Point3i center, DimensionBounds bounds, long length) {
        this.center = center; this.bounds = bounds;
        radius = (int) Math.min(50_000, Math.max(1, length / 2));
        // 半径增大时同步放大步长，圆域始终不超过49×49个网格点，避免面积级精采。
        step = Math.max(128, (radius + 23) / 24);
        cells = radius / step;
    }
    boolean done() { return index >= (2 * cells + 1) * (2 * cells + 1); }
    int sampleCount() { return samples.size(); }
    int radius() { return radius; }
    void advance(NaturalTerrainOracle oracle) {
        if (done()) return;
        int gx = index / (2 * cells + 1) - cells, gz = index % (2 * cells + 1) - cells;
        int dx = gx * step, dz = gz * step;
        long x = center.x() + (long) dx, z = center.z() + (long) dz;
        if ((long) dx * dx + (long) dz * dz > (long) radius * radius
                || Math.abs(x) > bounds.horizontalLimit() || Math.abs(z) > bounds.horizontalLimit()) { index++; return; }
        if (column == null) {
            // 原始群系来自冻结BiomeSource，粗密度不带水时也不会把海误当陆地。
            biome = oracle.biomeKeyAt((int) x, (int) z);
            column = oracle.begin((int) x, (int) z, SampleKind.COARSE);
        } else if (!column.done()) oracle.advance(column, 1);
        else {
            var sample = oracle.finish(column);
            samples.put(key(gx, gz), new Cell(sample.upperCensored() || sample.waterTopY().isPresent()
                    || OceanRouteScan.isOcean(biome == null ? sample.biomeKey() : biome), sample.groundTopY()));
            column = null; index++;
        }
    }
    long score(Axis axis) {
        if (!done()) throw new IllegalStateException("宏观选线尚未完成");
        // 候选排序会反复比较同一轴，完成后的网格不变，评分只计算一次。
        return scores.computeIfAbsent(axis, this::computeScore);
    }
    private long computeScore(Axis axis) {
        int wet = 0, total = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        // 仅查已完成圆域网格，不为每个候选重新读地形；未知区域记入代价，不能伪装陆地。
        for (int d = -radius; d <= radius; d += step) {
            long x = axis.origin().x() + FixedMath.mulDiv(d, axis.exQ30(), 1L << 30) - center.x();
            long z = axis.origin().z() + FixedMath.mulDiv(d, axis.ezQ30(), 1L << 30) - center.z();
            var sample = samples.get(key(Math.toIntExact(FixedMath.roundDiv(x, step)),
                    Math.toIntExact(FixedMath.roundDiv(z, step))));
            total++;
            if (sample == null || sample.wet()) wet++;
            if (sample != null) { min = Math.min(min, sample.height()); max = Math.max(max, sample.height()); }
        }
        long relief = min == Integer.MAX_VALUE ? 0 : (long) max - min;
        return -(wet * 1_000_000L / total) * 1_000_000L - relief;
    }
    private static long key(int x, int z) { return ((long) x << 32) ^ Integer.toUnsignedLong(z); }
}
