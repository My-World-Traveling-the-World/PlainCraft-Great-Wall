package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.planning.WallFoundationPlanner.ColumnFoundation;
import local.plaincraft.greatwall.planning.WallFoundationPlanner.FillColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FoundationSectionIndexTest {
    @Test void boundaryAndOverlappingSectionsKeepTheirOriginalEvidence() {
        long boundary = 64L * 256;
        var prior = section(boundary - 1, 60);
        var first = section(boundary, 61);
        var second = section(boundary, 62);
        var fractional = section(boundary + 128, 63);
        var empty = new ColumnFoundation(boundary + 1, 0, 0, 64, List.of());
        var unlocated = section(Long.MIN_VALUE, 60);
        var source = new ArrayList<>(List.of(fractional, first, unlocated, prior, empty, second));
        var index = new FoundationSectionIndex(source);
        // 发布后的输入不随原表变化；边界上的重叠截面不能被去重或调换次序。
        source.clear();
        assertEquals(List.of(prior), index.forCell(0));
        assertEquals(List.of(first, second, fractional), index.forCell(1));
        assertTrue(index.forCell(2).isEmpty());
    }

    private static ColumnFoundation section(long arc, int surface) {
        return new ColumnFoundation(arc, 0, 0, 64, List.of(new FillColumn(5, surface, 0, 5, 64)));
    }
}
