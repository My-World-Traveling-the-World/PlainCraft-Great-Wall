package local.plaincraft.greatwall.generation;

import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.model.WallTypes.Bounds;

/** 按已编译的真实占地挡住后续结构；每个世界独立持有，不扩大精采范围。 */
public final class StructurePlacementGuard {
    public static final int MARGIN = 16;
    private final ConcurrentHashMap<Long, long[]> columns = new ConcurrentHashMap<>();

    public void mark(int chunkX, int chunkZ, long[] mask) {
        if ((mask[0] | mask[1] | mask[2] | mask[3]) == 0) return;
        // 发布后数组不可变，生成线程查询不会读到一半更新的四行位图。
        columns.compute(key(chunkX, chunkZ), (key, old) -> new long[] {
                mask[0] | (old == null ? 0 : old[0]), mask[1] | (old == null ? 0 : old[1]),
                mask[2] | (old == null ? 0 : old[2]), mask[3] | (old == null ? 0 : old[3])});
    }

    public boolean intersects(Bounds structure) {
        if (columns.isEmpty()) return false;
        // 将查询盒外扩等价于将每个占地列外扩；无需为16格边距存九倍重复列。
        int x0 = structure.minX() - MARGIN, z0 = structure.minZ() - MARGIN;
        int x1 = structure.maxXExclusive() - 1 + MARGIN, z1 = structure.maxZExclusive() - 1 + MARGIN;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            int left = Math.max(0, x0 - cx * 16), right = Math.min(15, x1 - cx * 16);
            long row = ((1L << (right - left + 1)) - 1) << left;
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                var mask = columns.get(key(cx, cz));
                if (mask == null) continue;
                int top = Math.max(0, z0 - cz * 16), bottom = Math.min(15, z1 - cz * 16);
                for (int z = top; z <= bottom; z++)
                    if ((mask[z >> 2] & (row << ((z & 3) * 16))) != 0) return true;
            }
        }
        return false;
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ Integer.toUnsignedLong(z); }
}
