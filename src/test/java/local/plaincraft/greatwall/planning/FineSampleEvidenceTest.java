package local.plaincraft.greatwall.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.Quality;
import local.plaincraft.greatwall.planning.WallFinePlanner.FineColumn;
import local.plaincraft.greatwall.terrain.FineSampleCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FineSampleEvidenceTest {
    @TempDir Path directory;

    @Test void finishKeepsBothRouteSectionsAndDoesNotRewriteSuccessfulCacheOnClose() throws Exception {
        var file = directory.resolve("columns.bin");
        var persistence = new FineSamplePersistence(FineSampleCache.load(file, "world"), Runnable::run);
        var evidence = new FineSampleEvidence(persistence);
        var earlier = column(4, 64, 256);
        var later = column(4, 58, 512);
        var probed = column(12, 72, 512);
        // 切片逆序完成，两个截面经过同列；取证择高而地基仍须看见两份原输入。
        assertTrue(evidence.accept(512, List.of(later), List.of(probed)));
        assertTrue(evidence.accept(256, List.of(earlier), List.of()));
        var result = evidence.finish();
        assertNotNull(result);
        assertEquals(List.of(earlier, later), result.orderedColumns());
        assertEquals(1, result.size());
        assertEquals(earlier.sample(), result.at(4, 0));
        assertFalse(Files.exists(file), "初采结案不单独写盘，仍待补采并集");
        assertTrue(persistence.finish().join());
        var reloaded = FineSampleCache.load(file, "world");
        assertEquals(probed.sample(), reloaded.get(12, 0));
        assertNull(reloaded.get(4, 0), "插值不可进入磁盘缓存");

        assertFalse(evidence.accept(768, List.of(earlier), List.of(earlier)), "结案后忽略迟到切片");
        // 删除成功写出的文件，第二次写盘就会将它重建，不依赖文件时间戳精度。
        Files.delete(file);
        evidence.close();
        persistence.close();
        assertFalse(Files.exists(file), "正常停服不能再写一次同批探测列");
    }

    @Test void closeRetriesActualIoFailureAndRetainsOnlyProbedColumns() throws Exception {
        var file = directory.resolve("columns.bin");
        var persistence = new FineSamplePersistence(FineSampleCache.load(file, "world"), Runnable::run);
        var evidence = new FineSampleEvidence(persistence);
        var temp = Files.createDirectory(directory.resolve("columns.bin.tmp"));
        var sampled = column(-7, 55, 256);
        assertTrue(evidence.accept(256, List.of(sampled), List.of(sampled)));
        assertEquals(sampled.sample(), evidence.finish().at(-7, 0));
        assertFalse(persistence.finish().join(), "真实IO故障必须可辨认");
        assertFalse(Files.exists(file), "临时路径是目录，第一次必须写失败");
        Files.delete(temp);
        evidence.close();
        persistence.close();
        assertEquals(sampled.sample(), FineSampleCache.load(file, "world").get(-7, 0));
    }

    @Test void closeBeforeFinishPersistsAcceptedEvidenceAndRejectsLateSlice() {
        var file = directory.resolve("columns.bin");
        var persistence = new FineSamplePersistence(FineSampleCache.load(file, "world"), Runnable::run);
        var evidence = new FineSampleEvidence(persistence);
        var accepted = column(3, 64, 256);
        var late = column(4, 60, 512);
        assertTrue(evidence.accept(256, List.of(accepted), List.of(accepted)));
        evidence.close();
        persistence.close();
        assertFalse(evidence.accept(512, List.of(late), List.of(late)));
        assertNull(evidence.finish());
        var reloaded = FineSampleCache.load(file, "world");
        assertEquals(accepted.sample(), reloaded.get(3, 0));
        assertNull(reloaded.get(4, 0));
    }

    private static FineColumn column(int x, int y, long arc) {
        return new FineColumn(x, 0, 0, new ColumnSample(y, OptionalInt.empty(), OptionalInt.empty(),
                "minecraft:plains", Quality.REFERENCE, false), arc, x, 0);
    }
}
