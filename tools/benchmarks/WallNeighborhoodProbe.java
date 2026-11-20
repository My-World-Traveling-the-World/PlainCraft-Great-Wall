package local.plaincraft.greatwall.compat.road;

import java.nio.file.*;
import java.util.Arrays;

/** 同一保护图上交替对照旧逐列读法；不启动游戏，不把查询耗时当整机收益。 */
public final class WallNeighborhoodProbe {
    private static volatile long retained;
    public static void main(String[] args) throws Exception {
        WallRoadGuard.clear();
        for (int x = -2048; x < 2048; x++) WallRoadGuard.markColumn(x, 0);
        StringBuilder json = new StringBuilder("{\"unit\":\"milliseconds_per_10000_queries\",\"warmups\":3,\"samples\":9,\"cases\":[");
        int[] offsets = {50, 3, 2};
        for (int c = 0; c < offsets.length; c++) {
            int z = offsets[c];
            for (int x = -2200; x < 2200; x++)
                if (old(x, z) != WallRoadGuard.blocks(x, 0, z)) throw new AssertionError("查询不等价");
            for (int i = 0; i < 3; i++) { measure(z, false); measure(z, true); }
            double[] before = new double[9], after = new double[9];
            for (int i = 0; i < 9; i++) {
                if ((i & 1) == 0) { before[i] = measure(z, false); after[i] = measure(z, true); }
                else { after[i] = measure(z, true); before[i] = measure(z, false); }
            }
            Arrays.sort(before); Arrays.sort(after);
            if (c > 0) json.append(',');
            json.append("{\"z\":").append(z).append(",\"old_ms_p50\":").append(before[4])
                    .append(",\"new_ms_p50\":").append(after[4]).append('}');
        }
        json.append("]}\n");
        Files.writeString(Path.of(args[0]), json);
        System.out.print(json);
        WallRoadGuard.clear();
    }
    private static double measure(int z, boolean current) {
        long hits = 0, start = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            int x = i % 4400 - 2200;
            if (current ? WallRoadGuard.blocks(x, 0, z) : old(x, z)) hits++;
        }
        retained = hits;
        return (System.nanoTime() - start) / 1e6;
    }
    private static boolean old(int x, int z) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            if (WallRoadGuard.isMarked(x + dx, z + dz)) return true;
        return false;
    }
}
