package local.plaincraft.greatwall.component;

import java.util.List;
import java.util.function.LongToIntFunction;
import local.plaincraft.greatwall.planning.WallFinePlanner;
import local.plaincraft.greatwall.planning.WallFoundationPlanner.ColumnFoundation;

/** 把当前墙体单元的地基预投影到栅格坐标，避免每个体素遍历整条走廊。 */
final class WallFoundationBands {
    private static final int HALF_LENGTH = 32;
    private static final int LATERAL = WallFinePlanner.LATERAL;
    private final Band[][] byLocalX = new Band[2 * HALF_LENGTH][];
    private int lowest = -32;

    WallFoundationBands(List<ColumnFoundation> foundations, long center, long from, long to,
            LongToIntFunction walkHeight) {
        for (var core : foundations) {
            long arc = core.globalSQ8();
            if (arc < from || arc >= to || core.fills().isEmpty()) continue;
            int walkY = walkHeight.applyAsInt(arc);
            long delta = arc - center;
            int x = (int) (delta / 256) + HALF_LENGTH;
            boolean aligned = delta % 256 == 0;
            for (var fill : core.fills()) {
                int surface = fill.surfaceY() - walkY;
                // 非整格截面仍能压低扫描下界；唯有精确弧长相等才提供该栅格列的填充规则。
                lowest = Math.min(lowest, surface - 1);
                if (!aligned) continue;
                var lateral = byLocalX[x];
                if (lateral == null) byLocalX[x] = lateral = new Band[2 * LATERAL + 1];
                int z = fill.lateral() + LATERAL;
                // 重叠截面的填充取并集。保留间隔，不能用最浅和最深两个值包住全部区间。
                lateral[z] = new Band(surface, fill.baseY() - walkY, lateral[z]);
            }
        }
    }

    int lowest() { return lowest; }

    boolean fills(int x, int y, int z) {
        if (x < -HALF_LENGTH || x >= HALF_LENGTH || z < -LATERAL || z > LATERAL) return false;
        var lateral = byLocalX[x + HALF_LENGTH];
        if (lateral == null) return false;
        for (var band = lateral[z + LATERAL]; band != null; band = band.next()) {
            if (y >= band.surface() && y < band.base()) return true;
        }
        return false;
    }

    private record Band(int surface, int base, Band next) { }
}
