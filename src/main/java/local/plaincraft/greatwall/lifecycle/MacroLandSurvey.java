package local.plaincraft.greatwall.lifecycle;

import java.util.HashMap;
import java.util.Map;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;

/** 用有界稀疏圆域比较整条主轴，两端一起算；只为选线，不把粗列交给施工。 */
final class MacroLandSurvey {
    private record Cell(boolean wet, int height) {}
    private final Point3i center;
    private final DimensionBounds bounds;
    private final int radius, step, cells, firstDistance, lastDistance;
    private final Map<Long, Cell> samples = new HashMap<>();
    private int index;
    private NaturalTerrainOracle.ColumnCursor column;
    private final MacroRiverWater rivers;
    MacroLandSurvey(Point3i center, DimensionBounds bounds, long length) {
        this(center, bounds, length, MacroRiverWater.NONE);
    }
    MacroLandSurvey(Point3i center, DimensionBounds bounds, long length, MacroRiverWater rivers) {
        this(center, bounds, new RootAxisWindow(length, 0), rivers, 0);
    }
    MacroLandSurvey(Point3i center, DimensionBounds bounds, RootAxisWindow window, MacroRiverWater rivers, int outerRing) {
        this.rivers = rivers;
        this.center = center; this.bounds = bounds;
        if (window.wallLength() > 0) {
            radius = (int) Math.max(1, window.wallLength()/2);
            firstDistance = -radius; lastDistance = radius;
        } else {
            firstDistance = 0; lastDistance = window.forwardBlocks();
            // 从候选端口而非出生点出发；外加一格网格的取整余量，窗口末端也有地形证据。
            int reach = lastDistance + outerRing;
            radius = reach + Math.max(128, (reach + 22) / 23);
        }
        // 半径增大时同步放大步长，圆域始终不超过49×49个网格点，避免面积级精采。
        step = Math.max(128, (radius + 23) / 24);
        cells = radius / step;
    }
    // 圆外和世界边界外的网格也经过判定，分子与分母统一按网格统计。
    int visitedCells() { return index; }
    int gridCells() { return (2 * cells + 1) * (2 * cells + 1); }
    boolean done() { return index >= gridCells(); }
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
            // COARSE 已带冻结的原始群系；再次查同一列既重复，也可能读到不同的来源。
            column = oracle.begin((int) x, (int) z, SampleKind.COARSE);
        } else if (!column.done()) oracle.advance(column, 1);
        else {
            var sample = oracle.finish(column);
            samples.put(key(gx, gz), new Cell(sample.upperCensored() || sample.waterTopY().isPresent()
                    || waterBiome(sample.biomeKey()) || rivers.wet(center, (int)x, (int)z), sample.groundTopY()));
            column = null; index++;
        }
    }
    /** 河湖是选线水面，不是触发海洋收口的条件。仅按资源路径识别，避免命名空间误判。 */
    static boolean waterBiome(String key) {
        if (key == null) return false;
        String path = key.substring(key.indexOf(':') + 1);
        for (String word : path.split("[/_]"))
            if (word.equals("river") || word.equals("lake") || word.equals("ocean") || word.equals("sea")
                    || word.equals("rivers") || word.equals("lakes") || word.equals("seas")) return true;
        return false;
    }
    long score(Axis axis) {
        if (!done()) throw new IllegalStateException("宏观选线尚未完成");
        // 每个方向在入围时评分一次，排序直接读候选中保存的分数。
        int wet = 0, total = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        // 仅查已完成圆域网格，不为每个候选重新读地形；未知区域记入代价，不能伪装陆地。
        for (int d = firstDistance; ; d = Math.min(lastDistance, d + step)) {
            long x = axis.origin().x() + FixedMath.mulDiv(d, axis.exQ30(), 1L << 30) - center.x();
            long z = axis.origin().z() + FixedMath.mulDiv(d, axis.ezQ30(), 1L << 30) - center.z();
            var sample = samples.get(key(Math.toIntExact(FixedMath.roundDiv(x, step)),
                    Math.toIntExact(FixedMath.roundDiv(z, step))));
            total++;
            if (sample == null || sample.wet()) wet++;
            if (sample != null) { min = Math.min(min, sample.height()); max = Math.max(max, sample.height()); }
            if (d == lastDistance) break;
        }
        long relief = min == Integer.MAX_VALUE ? 0 : (long) max - min;
        return -(wet * 1_000_000L / total) * 1_000_000L - relief;
    }
    private static long key(int x, int z) { return ((long) x << 32) ^ Integer.toUnsignedLong(z); }
}
