package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.Quality;
import org.junit.jupiter.api.Test;

/** 真实plan入口与独立旧树分组对拍，防止优化丢两翼、错站号或改变重复列次序。 */
class FoundationGroupsTest {
    @Test void shuffledSectionsAndDuplicateColumnsMatchFrozenPlanner() {
        Random random = new Random(0x95fa11);
        int foundations = 0, fills = 0;
        for (int trial = 0; trial < 180; trial++) {
            var samples = new ArrayList<WallFinePlanner.FineColumn>();
            int sections = 2 + random.nextInt(110);
            for (int s = 0; s < sections; s++) {
                long key = (s - sections / 2L) * 256;
                int x = s * 3, z = -s * 2;
                // 至少第一站有中心和非空填充，缺中心的站应和旧实现一样被跳过。
                if (s == 0 || s % 7 != 0) samples.add(col(key, x, z, 0, 100));
                for (int j = 0; j < 8; j++)
                    samples.add(col(key, x, z, random.nextInt(105) - 52, 30 + random.nextInt(60)));
                samples.add(col(key, x + 1, z + 2, -2, 20));
                samples.add(col(key, x + 2, z + 4, -2, 25));
            }
            Collections.shuffle(samples, random);
            var expected = FrozenWallFoundationPlanner.plan(samples);
            var actual = WallFoundationPlanner.plan(samples);
            assertFalse(expected.isEmpty(), "夹具必须真正产生地基");
            assertEquals(expected, actual, "trial=" + trial);
            foundations += actual.size();
            fills += actual.stream().mapToInt(f -> f.fills().size()).sum();
        }
        assertTrue(foundations > 5000);
        assertTrue(fills > foundations);
    }

    @Test void fullLongKeysRehashAndSignedOrderRemainExact() {
        var samples = new ArrayList<WallFinePlanner.FineColumn>();
        long[] extremes = {Long.MIN_VALUE + 1, Long.MIN_VALUE + 256, -1, 0, 1, Long.MAX_VALUE};
        for (long key : extremes) {
            samples.add(col(key, 7, -9, 0, 90));
            samples.add(col(key, 7, -9, -52, 40));
        }
        for (int i = 2; i < 1400; i++) {
            // 共用低位，迫使完整long键查找经历多次扩容及碰撞。
            long key = (long) i << 32;
            samples.add(col(key, i, -i, 0, 90));
            samples.add(col(key, i, -i, 52, 50));
        }
        Collections.reverse(samples);
        var actual = WallFoundationPlanner.plan(samples);
        assertEquals(1404, actual.size());
        assertEquals(FrozenWallFoundationPlanner.plan(samples), actual);
        assertEquals(Long.MIN_VALUE + 1, actual.getFirst().globalSQ8());
        assertEquals(Long.MAX_VALUE, actual.getLast().globalSQ8());
        assertEquals(1404, actual.stream().mapToInt(f -> f.fills().size()).sum());
    }

    @Test void legacyOverflowAndLegacyIdentityAliasingKeepOldContract() {
        var samples = List.of(
                legacy(-2, 4, 0, 90), legacy(-2, 99, -1, 20),
                legacy(0, 10, 0, 80), legacy(0, 11, 1, 30),
                legacy(3, 7, 0, 100), legacy(3, 8, -1, 40),
                col(Long.MIN_VALUE + 3, 999, 333, 0, 110));
        var actual = WallFoundationPlanner.plan(samples);
        assertEquals(3, actual.size(), "旧夹具键与显式站号重合时保留原合组行为");
        assertEquals(FrozenWallFoundationPlanner.plan(samples), actual);
        assertEquals(0, actual.getFirst().centreX());
        assertEquals(-2, actual.getLast().centreX(), "负centreX加MIN的有符号溢出顺序保留");
        assertEquals(3, actual.get(1).centreX(), "重复中心仍取原输入第一个");
        assertEquals(110, actual.get(1).baseY(), "合组后最高点不能被丢掉");
    }

    @Test void duplicateCentresAndFillTiesPreserveFirstOccurrence() {
        var samples = List.of(col(8, 10, 20, -2, 30), col(8, 10, 20, 0, 60),
                col(8, 99, 88, 0, 80), col(8, 30, 40, -2, 40), col(8, 10, 20, 2, 50));
        var actual = WallFoundationPlanner.plan(samples);
        assertEquals(FrozenWallFoundationPlanner.plan(samples), actual);
        assertEquals(1, actual.size());
        assertEquals(10, actual.getFirst().centreX());
        assertEquals(80, actual.getFirst().baseY());
        assertEquals(List.of(10, 30, 10, 10),
                actual.getFirst().fills().stream().map(WallFoundationPlanner.FillColumn::x).toList());
        assertThrows(UnsupportedOperationException.class, () -> actual.clear());
        assertThrows(UnsupportedOperationException.class, () -> actual.getFirst().fills().clear());
    }

    @Test void missingCentreAndExtremeGroundKeepBoundaryRules() {
        assertEquals(List.of(), WallFoundationPlanner.plan(List.of()));
        assertEquals(List.of(), WallFoundationPlanner.plan(List.of(col(7, 1, 2, -1, 100))));
        var samples = List.of(col(2, 0, 0, 0, Integer.MIN_VALUE),
                col(2, 0, 0, -1, Integer.MIN_VALUE), col(1, 1, 1, 0, Integer.MAX_VALUE),
                col(1, 1, 1, 1, 0));
        var actual = WallFoundationPlanner.plan(samples);
        assertEquals(FrozenWallFoundationPlanner.plan(samples), actual);
        assertEquals(2, actual.size());
        assertEquals(0, actual.getLast().baseY());
        assertEquals(2, actual.getLast().fills().size());
        assertThrows(NullPointerException.class, () -> WallFoundationPlanner.plan(null));
    }

    private static WallFinePlanner.FineColumn col(long key, int x, int z, int lateral, int ground) {
        return new WallFinePlanner.FineColumn(x, z, lateral, sample(ground), key, x, z);
    }

    private static WallFinePlanner.FineColumn legacy(int x, int z, int lateral, int ground) {
        return new WallFinePlanner.FineColumn(x, z, lateral, sample(ground));
    }

    private static ColumnSample sample(int ground) {
        return new ColumnSample(ground, OptionalInt.empty(), OptionalInt.empty(),
                "minecraft:plains", Quality.REFERENCE, false);
    }
}
