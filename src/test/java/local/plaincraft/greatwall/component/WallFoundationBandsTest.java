package local.plaincraft.greatwall.component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import local.plaincraft.greatwall.planning.WallFoundationPlanner.ColumnFoundation;
import local.plaincraft.greatwall.planning.WallFoundationPlanner.FillColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WallFoundationBandsTest {
    @Test void localFillPreservesGapsAndHalfOpenEdgesAndSamplesHeightOncePerRelevantSection() {
        long center = 96 * 256L;
        long from = center - 32 * 256L, to = center + 32 * 256L;
        var heightCalls = new AtomicInteger();
        var bands = new WallFoundationBands(List.of(
                section(center, 40, 50), section(center, 60, 70),
                section(center + 128, 10, 20), section(from, 65, 66),
                section(to, -10, 80), section(from - 256, -10, 80)), center, from, to, arc -> {
                    assertTrue(arc >= from && arc < to, "无关截面不用查询站点高度");
                    heightCalls.incrementAndGet();
                    return arc == center ? 84 : 88;
                });
        // 半格截面只扩展扫描深度，既不能映射到整格，也不能填满两条记录之间的空段。
        assertEquals(-79, bands.lowest());
        assertTrue(bands.fills(0, -44, 5));
        assertTrue(bands.fills(0, -35, 5));
        assertFalse(bands.fills(0, -34, 5));
        assertFalse(bands.fills(0, -30, 5));
        assertTrue(bands.fills(0, -24, 5));
        assertFalse(bands.fills(0, -14, 5));
        assertFalse(bands.fills(0, -78, 5));
        assertFalse(bands.fills(0, -44, 6));
        assertTrue(bands.fills(-32, -23, 5));
        assertFalse(bands.fills(32, -23, 5));
        assertEquals(4, heightCalls.get(), "查询体素时不再计算截面高度");
    }

    private static ColumnFoundation section(long arc, int surface, int base) {
        return new ColumnFoundation(arc, 0, 0, base, List.of(new FillColumn(5, surface, 0, 5, base)));
    }
}
