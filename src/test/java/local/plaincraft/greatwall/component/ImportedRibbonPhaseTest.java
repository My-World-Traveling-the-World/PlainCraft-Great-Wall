package local.plaincraft.greatwall.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImportedRibbonPhaseTest {
    @Test
    void endpointsKeepTheirFirstFortyEightColumnsAndTransitionNeverAddsConsecutiveGaps() {
        var site = gate(512);
        var phase = new ImportedRibbonPhase(List.of(site), 300L * 256, 750L * 256);
        for (int p = 0; p < 2; p++) {
            long port = ImportedSiteGeometry.portArc(site, p);
            int sign = p == 0 ? -1 : 1;
            for (int step = 1; step <= 48; step++)
                assertEquals(step % 4 != 0, phase.at(port + sign * step * 256L) != 3, "端面 " + p + " 第 " + step + " 格");
            int previousGap = 0;
            var distances = new ArrayList<Integer>();
            for (int step = 1; step <= 120; step++) if (phase.at(port + sign * step * 256L) == 3) {
                distances.add(step - previousGap);
                previousGap = step;
            }
            assertTrue(distances.size() >= 28, "空产物不能让逐列断言空转");
            assertTrue(distances.contains(p == 0 ? 5 : 3), "必须真的经过处理余数的相位过渡");
            assertTrue(distances.stream().allMatch(d -> d >= 3 && d <= 6), "过渡也不能出现连续缺或无界长垛");
        }
    }

    @Test
    void cellAndWindowBoundariesDoNotChooseDifferentPhaseOrigins() {
        var site = gate(512);
        var full = new ImportedRibbonPhase(List.of(site), 200L * 256, 800L * 256);
        for (long from = 320; from <= 704; from += 64) {
            var cell = new ImportedRibbonPhase(List.of(site), from * 256, (from + 64) * 256);
            for (long s = from * 256; s < (from + 64) * 256; s += 53)
                assertEquals(full.at(s), cell.at(s), "相位不能随 cell 构造范围重置");
        }
    }

    @Test
    void distantSitesAndInputOrderDoNotChangeLocalContract() {
        var center = gate(512);
        var one = new ImportedRibbonPhase(List.of(center), 350L * 256, 450L * 256);
        var many = new ImportedRibbonPhase(List.of(gate(2000), center, gate(10000)), 350L * 256, 450L * 256);
        for (long s = 350L * 256; s < 450L * 256; s += 71) assertEquals(one.at(s), many.at(s));
    }

    @Test
    void allFourLengthRemaindersConnectBothPortsWithoutAdjacentGaps() {
        for (int remainder = 0; remainder < 4; remainder++) {
            var left = gate(512);
            var right = gate(512 + 112 + 180 + remainder);
            long from = ImportedSiteGeometry.portArc(left, 1), to = ImportedSiteGeometry.portArc(right, 0);
            var phase = new ImportedRibbonPhase(List.of(right, left), from, to);
            assertEquals(3, phase.at(from));
            assertEquals(3, phase.at(to));
            for (int i = 1; i <= 24; i++) {
                assertEquals(i % 4 != 0, phase.at(from + i * 256L) != 3);
                assertEquals(i % 4 != 0, phase.at(to - i * 256L) != 3);
            }
            boolean previous = false;
            int visited = 0;
            for (long s = from + 256; s < to; s += 256) {
                boolean gap = phase.at(s) == 3;
                assertFalse(previous && gap, "端点长度余数不得制造连续缺");
                previous = gap;
                visited++;
            }
            assertTrue(visited >= 179);
        }
    }

    private static SiteRecord gate(long station) {
        var id = new SiteId("phase-fixture", SiteKind.GATE, station);
        var frame = new Frame(new Point2q(station * 256 + 128, 128), 1 << 30, 0, 79, false);
        return new SiteRecord(id, station * 256, frame,
                ImportedSiteGeometry.layout(id, frame).boundsWithStraightWalls(0),
                SiteState.PLANNED, Optional.empty(), Optional.empty());
    }
}
