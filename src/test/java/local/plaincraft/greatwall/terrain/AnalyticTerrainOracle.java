package local.plaincraft.greatwall.terrain;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningFixtures;

/** Analytic, owner-local input only. Warm/cold modes vary oracle work, never returned samples. */
public final class AnalyticTerrainOracle implements NaturalTerrainOracle {
    private final BiFunction<Integer, Integer, ColumnSample> terrain;
    private final Map<Long, ColumnSample> cache = new HashMap<>();
    private final boolean warm;
    private boolean closed;
    private int begun;

    public AnalyticTerrainOracle(BiFunction<Integer, Integer, ColumnSample> terrain, boolean warm) {
        this.terrain = terrain; this.warm = warm;
    }

    public static AnalyticTerrainOracle ridge(boolean warm) {
        return new AnalyticTerrainOracle((x, z) -> dry(90 + (int) StrictMath.floor(
                12 * StrictMath.sin(x / 700.0) + 30 * StrictMath.exp(-StrictMath.pow((z - 70 * StrictMath.sin(x / 1800.0)) / 80, 2)))), warm);
    }

    public static ColumnSample dry(int height) {
        return new ColumnSample(height, OptionalInt.empty(), OptionalInt.empty(), "minecraft:plains", Quality.REFERENCE, false);
    }

    public static ColumnSample water(int top, int bed) {
        return new ColumnSample(bed, OptionalInt.of(top), OptionalInt.of(bed), "minecraft:river", Quality.REFERENCE, false);
    }

    @Override public ColumnCursor begin(int x, int z, SampleKind kind) {
        checkOpen(); begun++;
        long key = ((long) x << 32) ^ Integer.toUnsignedLong(z);
        var value = cache.get(key);
        if (value == null) { value = terrain.apply(x, z); if (warm) cache.put(key, value); }
        return new Cursor(value, warm ? 1 : 3);
    }

    @Override public WorkState advance(ColumnCursor cursor, int primitiveLimit) {
        checkOpen();
        if (primitiveLimit < 1 || primitiveLimit > 256) throw new IllegalArgumentException("limit");
        var c = (Cursor) cursor; c.remaining = Math.max(0, c.remaining - primitiveLimit);
        return c.done() ? WorkState.DONE : WorkState.MORE;
    }

    @Override public ColumnSample finish(ColumnCursor cursor) {
        checkOpen(); var c = (Cursor) cursor;
        if (!c.done()) throw new IllegalStateException("unfinished sample");
        return c.value;
    }

    @Override public String fingerprint() { return PlanningFixtures.HASH; }
    @Override public boolean supportsSynchronousDiagnostics() { return true; }
    @Override public void close() { closed = true; cache.clear(); }
    public int begun() { return begun; }
    private void checkOpen() { if (closed) throw new IllegalStateException("closed oracle"); }
    private static final class Cursor implements ColumnCursor {
        private final ColumnSample value;
        private int remaining;
        private Cursor(ColumnSample value, int remaining) { this.value = value; this.remaining = remaining; }
        @Override public boolean done() { return remaining == 0; }
    }
}
