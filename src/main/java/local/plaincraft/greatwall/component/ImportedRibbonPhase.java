package local.plaincraft.greatwall.component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeMap;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallTypes.*;

/**
 * 实物端面与全局格网共同冻结节拍，避免接口特判结束时突然更换原点。
 * 两端距离不是四的倍数时，在明确的一轮过渡中多留一至两列垛或少一列垛，绝不连续留缺。
 */
final class ImportedRibbonPhase {
    private static final int GRID = 16;
    private static final int END_RUN = ImportedSiteGeometry.STRAIGHT * 2;
    private record Anchor(long station, boolean port) {}
    private final Anchor[] anchors;
    private final long[] positions;

    ImportedRibbonPhase(java.util.List<SiteRecord> sites, long fromQ8, long toQ8) {
        var ports = new ArrayList<Long>();
        var occupied = new ArrayList<long[]>();
        var ordered = new TreeMap<Long, Anchor>();
        for (var site : sites) if (site.state() == SiteState.PLANNED) {
            int count = ImportedSiteGeometry.layout(site).ports().size();
            long first = Math.floorDiv(ImportedSiteGeometry.portArc(site, 0), 256);
            long last = Math.floorDiv(ImportedSiteGeometry.portArc(site, count - 1), 256);
            occupied.add(new long[] {first, last});
            for (int p = 0; p < count; p++) {
                long station = Math.floorDiv(ImportedSiteGeometry.portArc(site, p), 256);
                ports.add(station);
                ordered.put(station, new Anchor(station, true));
            }
        }
        // 16 格检查点限制依赖范围：最远只需知道 68 格内的端面，现有 88 格接墙取证范围可覆盖。
        long begin = Math.floorDiv(Math.floorDiv(fromQ8, 256), GRID) - 8;
        long end = Math.floorDiv(Math.floorDiv(toQ8, 256), GRID) + 8;
        for (long grid = begin; grid <= end; grid++) {
            long station = grid * GRID + 3;
            boolean hidden = occupied.stream().anyMatch(v -> station >= v[0] && station <= v[1]);
            boolean close = ports.stream().anyMatch(p -> Math.abs(station - p) <= END_RUN + 4);
            if (!hidden && !close) ordered.putIfAbsent(station, new Anchor(station, false));
        }
        anchors = ordered.values().toArray(Anchor[]::new);
        positions = Arrays.stream(anchors).mapToLong(Anchor::station).toArray();
    }

    int at(long sQ8) {
        long station = FixedMath.roundDiv(sQ8, 256);
        int found = Arrays.binarySearch(positions, station);
        if (found >= 0) return 3;
        int upper = -found - 1;
        if (upper == 0 || upper == anchors.length)
            throw new IllegalArgumentException("墙体相位缺少相邻锚点: " + station);
        var left = anchors[upper - 1];
        var right = anchors[upper];
        long length = right.station - left.station;
        long step = station - left.station;
        int remainder = Math.floorMod(length, 4);
        int adjustment = remainder == 3 ? -1 : remainder;
        if (adjustment == 0) return Math.floorMod(step - 1, 4);

        int cycle = 4 + adjustment;
        long leftGuard = left.port ? right.port ? ImportedSiteGeometry.STRAIGHT : END_RUN : 0;
        long rightGuard = right.port ? left.port ? ImportedSiteGeometry.STRAIGHT : END_RUN : 0;
        long low = (leftGuard + 3) / 4 * 4;
        long high = Math.floorDiv(length - rightGuard - cycle, 4) * 4;
        // 短的双端走廊优先保住首四格；长走廊保留两头直出节拍，过渡只落在中间。
        if (high < low) {
            low = length >= 12 ? 4 : 0;
            high = Math.max(low, Math.floorDiv(length - cycle - (length >= 12 ? 4 : 0), 4) * 4);
        }
        long transition = Math.clamp(Math.floorDiv(length / 2, 4) * 4, low, high);
        long logical = step;
        if (adjustment > 0 && step >= transition + 4)
            logical -= Math.min(adjustment, step - transition - 3);
        else if (adjustment < 0 && step >= transition + 3) logical++;
        return Math.floorMod(logical - 1, 4);
    }
}
