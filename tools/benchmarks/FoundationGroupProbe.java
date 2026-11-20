package local.plaincraft.greatwall.planning;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.Quality;

/** 对真实地基规划完整产物计时并测线程分配；不把局部收益换算成整局预热时间。 */
public final class FoundationGroupProbe {
    private static volatile Object retained;
    private static final com.sun.management.ThreadMXBean ALLOC =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private record Measurement(double milliseconds, long bytes) {}

    public static void main(String[] args) throws Exception {
        StringBuilder json = new StringBuilder(
                "{\"unit\":\"per_plan\",\"warmups\":5,\"samples\":7,\"cases\":[");
        int[][] cases = {{8, 16, 0}, {64, 16, 0}, {2048, 16, 0}, {2048, 52, 0}, {2048, 16, 1}};
        for (int c = 0; c < cases.length; c++) {
            int sections = cases[c][0], lateral = cases[c][1];
            var columns = fixture(sections, lateral, cases[c][2] != 0);
            var expected = FrozenWallFoundationPlanner.plan(columns);
            var actual = WallFoundationPlanner.plan(columns);
            if (expected.size() != sections || expected.getFirst().fills().isEmpty() || !expected.equals(actual))
                throw new AssertionError("新旧地基完整产物不等价或夹具空转");
            for (int i = 0; i < 5; i++) { measure(columns, false); measure(columns, true); }
            double[] before = new double[7], after = new double[7];
            long[] oldBytes = new long[7], newBytes = new long[7];
            for (int i = 0; i < 7; i++) {
                Measurement old, current;
                if ((i & 1) == 0) { old = measure(columns, false); current = measure(columns, true); }
                else { current = measure(columns, true); old = measure(columns, false); }
                before[i] = old.milliseconds(); after[i] = current.milliseconds();
                oldBytes[i] = old.bytes(); newBytes[i] = current.bytes();
            }
            Arrays.sort(before); Arrays.sort(after); Arrays.sort(oldBytes); Arrays.sort(newBytes);
            if (c > 0) json.append(',');
            json.append("{\"sections\":").append(sections).append(",\"columns\":").append(columns.size())
                    .append(",\"shuffled\":").append(cases[c][2] != 0)
                    .append(",\"old_ms_p50\":").append(before[3]).append(",\"new_ms_p50\":").append(after[3])
                    .append(",\"old_alloc_bytes_p50\":").append(oldBytes[3])
                    .append(",\"new_alloc_bytes_p50\":").append(newBytes[3]).append('}');
        }
        json.append("]}\n");
        Files.writeString(Path.of(args[0]), json);
        System.out.print(json);
    }

    private static Measurement measure(List<WallFinePlanner.FineColumn> columns, boolean current) {
        // 固定次数并交替测量；夹具构建与完整产物核对均放在计时外。
        int repeats = columns.size() < 10000 ? 16 : 4;
        long bytes = ALLOC.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long start = System.nanoTime();
        for (int i = 0; i < repeats; i++)
            retained = current ? WallFoundationPlanner.plan(columns) : FrozenWallFoundationPlanner.plan(columns);
        double elapsed = (System.nanoTime() - start) / 1e6 / repeats;
        long allocated = ALLOC.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes;
        return new Measurement(elapsed, allocated / repeats);
    }

    private static List<WallFinePlanner.FineColumn> fixture(int sections, int lateral, boolean shuffled) {
        var columns = new ArrayList<WallFinePlanner.FineColumn>(sections * (2 * lateral + 1));
        for (int s = 0; s < sections; s++) for (int side = -lateral; side <= lateral; side++) {
            int ground = 80 + (s * 17 + side * side) % 23;
            var sample = new ColumnSample(ground, OptionalInt.empty(), OptionalInt.empty(),
                    "minecraft:plains", Quality.REFERENCE, false);
            columns.add(new WallFinePlanner.FineColumn(s, side, side, sample, (long) s * 256, s, 0));
        }
        if (shuffled) Collections.shuffle(columns, new Random(0x95beef));
        return columns;
    }
}
