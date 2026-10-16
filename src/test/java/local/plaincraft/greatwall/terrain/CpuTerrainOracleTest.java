package local.plaincraft.greatwall.terrain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * 参考列走查的「起走高度阶梯」。
 *
 * <p>背景：细采样里每列从「平滑地表估计 + 32 格」向下走，若第一格不是空气就说明估计偏低。旧实现
 * 直接跳到历史天花板边距（{@code seaLevel - minY + 32}）——在本包 2112 格高的维度里那是一千多格，
 * 于是每一个估计偏低的列都要把这一千多格当普通方块走一遍。真机实测 1.35M 列 / 12 lane 只有约
 * 1.25k 列/秒（9.6 ms 一列），与「相当一部分列走了整条空气柱」吻合。
 *
 * <p>阶梯把这次跳跃改成倍增（32、64、128……格），并且只有「该起点的第一格是空气」才接受——与旧的
 * 短余量接受判据完全同一条件，只是多试了几个候选高度。这条测试钉住两件事：阶梯确实倍增，且在维度
 * 高度这种量级下只用对数级次数就到达天花板，而不是线性爬升。
 */
class CpuTerrainOracleTest {

    @Test void theRetryLadderDoublesAndIsCappedByTheCeilingRung() {
        assertEquals(32, CpuTerrainOracle.nextLeadClimb(0), "第一级就是短余量本身");
        assertEquals(64, CpuTerrainOracle.nextLeadClimb(32));
        assertEquals(128, CpuTerrainOracle.nextLeadClimb(64));
        assertEquals(256, CpuTerrainOracle.nextLeadClimb(128));
        assertEquals(512, CpuTerrainOracle.nextLeadClimb(256));
        // 阶梯永远不小于一个短余量：climb 必须是正的倍增（0 也不能退化成 0）。
        for (int climb = 0; climb <= 4096; climb++) {
            assertTrue(CpuTerrainOracle.nextLeadClimb(climb) >= 32, "climb=" + climb);
        }
    }

    @Test void theLadderReachesTheHistoricalCeilingInLogarithmicRungs() {
        // 本包维度：世界高 2112 格，seaLevel=63；天花板边距 = seaLevel - minY + 32。
        int ceiling = 63 + 1024 + 32;
        int start = 32, climb = 0, rungs = 0;
        while (start < ceiling) {
            climb = CpuTerrainOracle.nextLeadClimb(climb);
            start = Math.min(start + climb, ceiling);
            rungs++;
            assertTrue(rungs <= 16, "阶梯必须对数收敛，实际 rungs=" + rungs);
        }
        assertEquals(ceiling, start, "最后一级必须落在历史天花板边距上，行为与旧实现收敛");
        assertTrue(rungs <= 6, "1119 格的天花板边距只该用几级（实际 rungs=" + rungs + "）");

        // 旧实现是「一次跳到天花板」，代价是 ceiling 格；阶梯的代价是 rungs 次探测。
        assertTrue(rungs * 4 < ceiling / 32, "阶梯的探测次数必须远小于天花板高度");
    }
}
