package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 用完整矩形作参考，直接驱动生产枚举；旧夹具只对比了同单位的两个几何函数。 */
class CorridorStructureScanBandTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void worldScaleDiagonalStillContainsItsMiddle() {
        // 中点的点积乘轴长已经超过 long；正反扫描都不能漏掉这排结构。
        var forward = new CorridorStructureScan.Corridor(-20_000_000, -20_000_000, 20_000_000, 20_000_000, 576);
        var reverse = new CorridorStructureScan.Corridor(20_000_000, 20_000_000, -20_000_000, -20_000_000, 576);
        for (var corridor : List.of(forward, reverse)) {
            assertTrue(corridor.within(0, 0));
            assertFalse(corridor.within(0, 2000));
        }
    }

    @Test void customPlacementUsesBlocksForCorridorAndPreservesStartChunk() {
        // 种子和结构生成仍应使用 (225,100)，虽然原版定位点被偏移到了另一个区块。
        var corridor = new CorridorStructureScan.Corridor(3200, 1600, 6400, 1600, 576);
        var placement = new TestPlacement(new Vec3i(17, 0, -17), false);
        var start = new ChunkPos(225, 100);
        assertTrue(TestPlacement.acceptsChunk(start.x, start.z));
        assertEquals(new BlockPos(3617, 0, 1583), placement.getLocatePos(start));
        assertFalse(start.equals(new ChunkPos(placement.getLocatePos(start))));
        var candidates = compareExhaustive(placement, 164, 64, 436, 136, corridor);
        var matching = candidates.stream().filter(candidate -> candidate.startChunk().equals(start)).findFirst().orElse(null);
        assertNotNull(matching, "区块坐标不能直接与方块走廊比较，也不能由定位点反推起点");
    }

    @Test void customPlacementMatchesExhaustiveAcrossOffsetsAxesAndScanModes() {
        var random = new Random(20261020L);
        for (int trial = 0; trial < 48; trial++) {
            int ox = random.nextInt(401) - 200;
            int oz = random.nextInt(401) - 200;
            int ex = random.nextInt(401) - 200;
            int ez = random.nextInt(401) - 200;
            if (trial % 4 < 2) ex = ox;
            if (trial % 4 == 0 || trial % 4 == 2) ez = oz;
            var corridor = new CorridorStructureScan.Corridor(ox, oz, ex, ez, 18 + random.nextInt(96));
            var placement = new TestPlacement(new Vec3i(-19, 0, 24), trial % 2 == 0);
            compareExhaustive(placement, -16, -16, 16, 16, corridor);
            compareExhaustive(placement, -16, -16, 16, 16,
                    new CorridorStructureScan.Corridor(ex, ez, ox, oz, corridor.halfWidth()));
            compareExhaustive(placement, -1, -16, -1, 16, corridor);
        }
        // 无走廊也保留整个区块矩形的候选及顺序。
        compareExhaustive(new TestPlacement(Vec3i.ZERO, true), -3, -4, -3, 8, null);
    }

    @Test void outsideLocatePointsNeverCallNativePlacementRules() {
        // 真实距离过滤必须挡在昂贵放置规则之前，不能只验证最终结果。
        var corridor = new CorridorStructureScan.Corridor(-100, -100, 100, 100, 33);
        var placement = new TestPlacement(new Vec3i(-20, 0, 17), true);
        int total = 161 * 161;
        var candidates = compareExhaustive(placement, -80, -80, 80, 80, corridor);
        assertFalse(candidates.isEmpty());
        assertTrue(placement.queries.get() < total / 4, "原版规则不应收到整矩形的探测");
    }

    @Test void interruptedScanCannotPublishPartialCandidates() {
        var placement = new TestPlacement(Vec3i.ZERO, false);
        var holder = Holder.direct(new StructureSet(List.of(), placement));
        for (int maxX : new int[]{0, 12}) {
            var actual = new ArrayList<CorridorStructureScan.Candidate>();
            // 单行和分带都接同一个会话取消契约，完整结果才有资格写入扫描缓存。
            Thread.currentThread().interrupt();
            try {
                assertThrows(CancellationException.class, () -> CorridorStructureScan.predictCustom(
                        null, holder, placement, 0, 0, maxX, 10, null, actual));
                assertTrue(Thread.currentThread().isInterrupted());
                assertTrue(actual.isEmpty());
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static List<CorridorStructureScan.Candidate> compareExhaustive(TestPlacement placement,
            int minX, int minZ, int maxX, int maxZ, CorridorStructureScan.Corridor corridor) {
        var holder = Holder.direct(new StructureSet(List.of(), placement));
        var expected = new ArrayList<CorridorStructureScan.Candidate>();
        int expectedQueries = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                var start = new ChunkPos(x, z);
                var locate = placement.getLocatePos(start);
                if (corridor != null && !corridor.within(locate.getX(), locate.getZ())) continue;
                expectedQueries++;
                if (TestPlacement.acceptsChunk(x, z)) expected.add(new CorridorStructureScan.Candidate(start, holder));
            }
        }
        int before = placement.queries.get();
        var actual = new ArrayList<CorridorStructureScan.Candidate>();
        CorridorStructureScan.predictCustom(null, holder, placement, minX, minZ, maxX, maxZ, corridor, actual);
        assertEquals(expected, actual, () -> "候选或顺序改变：" + corridor);
        assertEquals(expectedQueries, placement.queries.get() - before, "每个走廊内起点只查一次放置规则");
        return actual;
    }

    private static final class TestPlacement extends StructurePlacement {
        private final boolean variableOffset;
        private final AtomicInteger queries = new AtomicInteger();

        TestPlacement(Vec3i offset, boolean variableOffset) {
            super(offset, FrequencyReductionMethod.DEFAULT, 1, 0, Optional.empty());
            this.variableOffset = variableOffset;
        }

        @Override public BlockPos getLocatePos(ChunkPos chunk) {
            var point = super.getLocatePos(chunk);
            // 模组允许覆盖定位规则；奇偶区块向不同方向跨界，不能套统一原点平移。
            return variableOffset ? point.offset((chunk.z & 1) == 0 ? -64 : 64, 0, 31 * (chunk.x % 3)) : point;
        }

        @Override public boolean isStructureChunk(ChunkGeneratorStructureState state, int x, int z) {
            queries.incrementAndGet();
            return isPlacementChunk(state, x, z);
        }

        @Override protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int x, int z) {
            return acceptsChunk(x, z);
        }

        static boolean acceptsChunk(int x, int z) {
            return Math.floorMod(x * 3 + z, 5) == 0;
        }

        @Override public StructurePlacementType<?> type() { return StructurePlacementType.RANDOM_SPREAD; }
    }
}
