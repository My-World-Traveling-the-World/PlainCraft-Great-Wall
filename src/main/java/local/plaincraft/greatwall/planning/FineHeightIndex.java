package local.plaincraft.greatwall.planning;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;

/**
 * Planning-time height lookup over the wall's centre line.
 *
 * <p>{@link WallFinePlanner} samples each centre-line column with the accurate {@code REFERENCE}
 * probe; this index makes that result addressable by {@code (x, z)} so the fine-generation stage can
 * answer "what is the ground surface / water under this column" without any chunk access. It is the
 * planning-side replacement for the live-column reads the generation stage used to do through the
 * 81-chunk FEATURES snapshot.
 */
public final class FineHeightIndex {
    private final Map<Long, WallFinePlanner.FineColumn> byXZ;

    private FineHeightIndex(Map<Long, WallFinePlanner.FineColumn> byXZ) {
        this.byXZ = byXZ;
    }

    public static FineHeightIndex of(List<WallFinePlanner.FineColumn> columns) {
        Objects.requireNonNull(columns, "columns");
        var map = new HashMap<Long, WallFinePlanner.FineColumn>(columns.size() * 2);
        for (var column : columns) {
            map.put(key(column.x(), column.z()), column);
        }
        return new FineHeightIndex(Map.copyOf(map));
    }

    /** The sampled column at {@code (x, z)}, or null when the centre line does not pass through it. */
    public ColumnSample at(int x, int z) {
        var column = byXZ.get(key(x, z));
        return column == null ? null : column.sample();
    }

    /** Whether a centre-line column was sampled at {@code (x, z)}. */
    public boolean contains(int x, int z) {
        return byXZ.containsKey(key(x, z));
    }

    public int size() {
        return byXZ.size();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
