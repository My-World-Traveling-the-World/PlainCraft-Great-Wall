package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import local.plaincraft.greatwall.planning.WallFoundationPlanner.ColumnFoundation;

/** 按完整弧长索引地基截面，避免墙体单元重复遍历整窗。 */
final class FoundationSectionIndex {
    private static final long CELL_Q8 = 64L * 256;
    private final List<ColumnFoundation> sections;

    FoundationSectionIndex(List<ColumnFoundation> source) {
        var sorted = new ArrayList<ColumnFoundation>();
        for (var section : source) {
            // 空截面不影响填充或扫描下界；没有弧长标识的兼容夹具也不会命中合法单元。
            if (!section.fills().isEmpty() && section.globalSQ8() >= 0) sorted.add(section);
        }
        // 稳定排序保留相同弧长的多份输入；世界坐标表另按原顺序裁决，不能从这里反推。
        sorted.sort(Comparator.comparingLong(ColumnFoundation::globalSQ8));
        sections = List.copyOf(sorted);
    }

    List<ColumnFoundation> forCell(long cell) {
        long from = Math.multiplyExact(cell, CELL_Q8);
        // 半开边界让交接点只归下一单元；非整格截面仍参与扫描深度。
        int start = lowerBound(from), end = lowerBound(Math.addExact(from, CELL_Q8));
        return start == end ? List.of() : sections.subList(start, end);
    }

    private int lowerBound(long arc) {
        int lo = 0, hi = sections.size();
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (sections.get(mid).globalSQ8() < arc) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
