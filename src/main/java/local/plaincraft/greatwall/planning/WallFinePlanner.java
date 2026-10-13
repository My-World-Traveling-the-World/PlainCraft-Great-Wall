package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;

/**
 * Planning-time fine terrain sampler over the wall's full width.
 *
 * <p>The ridge planner produces the centre-line polyline; this planner walks that polyline and, at
 * every centre point, samples the full wall cross-section (the centre column plus {@link #LATERAL}
 * columns on each side) with the accurate {@code REFERENCE} probe. The result is the per-column
 * groundTopY / water / bed across the whole wall footprint — computed entirely in the planning phase,
 * with no chunk access and no live-world reads.
 *
 * <p>Why full width: the wall hugs a ridge, so the centre line alone cannot tell the foundation how
 * far down each side must fill. The cross-section heights let the rasterizer set the wall base to
 * the highest column and fill the rest, exactly like RoadWeaver's fill/carve, but without carving
 * (a defensive wall never shaves a mountain).
 */
public final class WallFinePlanner {
    private WallFinePlanner() {
    }

    /** Half-width in blocks: the wall is 10 blocks wide, but the full footprint must also cover the
     *  widest fixed component (FORT, whose cross-wall footprint is z in [-24,24]), so the planner
     *  samples 24 columns on each side of centre. This lets every component's realization read a
     *  planning column without any per-block terrain dependency. */
    public static final int LATERAL = 24;

    /** One accurately-sampled column. {@code lateral} is the signed offset from the centre line,
     *  in the wall's local frame (positive = one side, negative = the other); 0 is the centre. */
    public record FineColumn(int x, int z, int lateral, ColumnSample sample) {
        public FineColumn {
            Objects.requireNonNull(sample, "sample");
            if (lateral < -LATERAL || lateral > LATERAL) throw new IllegalArgumentException("lateral out of wall width");
        }
    }

    public static Cursor begin(RouteShape route, NaturalTerrainOracle oracle) {
        return begin(route, oracle, 0, route.points().size());
    }

    /**
     * Begins a cursor over a point sub-range {@code [fromIndex, toIndex)} of the route.
     *
     * <p>This is what makes the fine sample parallel: the full segment is ~2048 points x 49 columns
     * (~100k REFERENCE samples), far too much for one lane. Callers split the point range into
     * slices, one cursor per slice, each running on its own lane with its own oracle; the sampled
     * columns are merged afterwards by (x, z). The route points and their cross-sections are pure
     * functions of the index, so slicing changes no sampled value — only which thread computes it.</p>
     */
    public static Cursor begin(RouteShape route, NaturalTerrainOracle oracle, int fromIndex, int toIndex) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(oracle, "oracle");
        return new Cursor(route, oracle, fromIndex, toIndex);
    }

    public static WorkState advance(Cursor c, int primitiveLimit) {
        if (primitiveLimit < 1 || primitiveLimit > 256) throw new IllegalArgumentException("primitive limit 1..256");
        c.claimOwner();
        for (int op = 0; op < primitiveLimit && !c.done(); op++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new WallFailure(FailureCode.CLOSED, "fine sample interrupted");
            }
            step(c);
        }
        return c.done() ? WorkState.DONE : WorkState.MORE;
    }

    public static List<FineColumn> finish(Cursor c) {
        if (!c.done()) throw new IllegalStateException("fine planner incomplete");
        return List.copyOf(c.results);
    }

    private static void step(Cursor c) {
        if (c.index >= c.toIndex) {
            c.completed = true;
            return;
        }
        if (c.pending == null) {
            // Emit the cross-section sample points for the current centre point.
            var centre = c.route.points().get(c.index);
            int cx = FixedMath.floorBlock(centre.xQ8());
            int cz = FixedMath.floorBlock(centre.zQ8());
            c.pending = crossSection(c.route.points(), c.index, cx, cz);
            c.slot = 0;
        }
        if (c.slot >= c.pending.size()) {
            c.pending = null;
            c.index++;
            return;
        }
        var target = c.pending.get(c.slot);
        if (c.column == null) {
            c.column = c.oracle.begin(target.x(), target.z(), SampleKind.REFERENCE);
            return;
        }
        if (!c.column.done()) {
            c.oracle.advance(c.column, 1);
            return;
        }
        var sample = c.oracle.finish(c.column);
        c.column = null;
        c.results.add(new FineColumn(target.x(), target.z(), target.lateral(), sample));
        c.slot++;
    }

    /** A pending sample coordinate before the oracle resolves it. */
    private record Target(int x, int z, int lateral) {}

    /** Emits the centre + lateral columns for route point {@code index}, deduplicated by (x,z).
     *  The lateral direction is the local frame of the wall: normal to the tangent, with lateral=0
     *  at the centre. We use the chord to the next point (or the previous, at the tail) as tangent. */
    private static List<Target> crossSection(List<Point2q> points, int index, int cx, int cz) {
        long dx, dz;
        if (index + 1 < points.size()) {
            var next = points.get(index + 1);
            dx = next.xQ8() - points.get(index).xQ8();
            dz = next.zQ8() - points.get(index).zQ8();
        } else {
            var prev = points.get(index - 1);
            dx = points.get(index).xQ8() - prev.xQ8();
            dz = points.get(index).zQ8() - prev.zQ8();
        }
        // Unit normal = rotate the unit tangent 90°: (-dz, dx). Scale to LATERAL blocks in Q8.
        double length = StrictMath.hypot(dx, dz);
        if (length < 1.0) {
            // Degenerate segment; fall back to a single centre column.
            return List.of(new Target(cx, cz, 0));
        }
        double ux = dx / length, uz = dz / length;
        double nx = -uz, nz = ux; // unit normal
        var result = new ArrayList<Target>(2 * LATERAL + 1);
        var seen = new java.util.HashSet<Long>();
        for (int lateral = -LATERAL; lateral <= LATERAL; lateral++) {
            int x = (int) Math.floor(cx + nx * lateral);
            int z = (int) Math.floor(cz + nz * lateral);
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            if (seen.add(key)) result.add(new Target(x, z, lateral));
        }
        return result;
    }

    public static final class Cursor {
        private final RouteShape route;
        private final NaturalTerrainOracle oracle;
        /** Point range this cursor covers: {@code [fromIndex, toIndex)}. */
        private final int fromIndex, toIndex;
        private Thread owner;
        private final List<FineColumn> results = new ArrayList<>();
        private int index;
        private List<Target> pending;
        private int slot;
        private NaturalTerrainOracle.ColumnCursor column;
        private boolean completed;

        private Cursor(RouteShape route, NaturalTerrainOracle oracle, int fromIndex, int toIndex) {
            this.route = route;
            this.oracle = oracle;
            this.fromIndex = Math.max(0, fromIndex);
            this.toIndex = Math.min(route.points().size(), Math.max(this.fromIndex, toIndex));
            this.index = this.fromIndex;
        }

        private void claimOwner() {
            if (owner == null) owner = Thread.currentThread();
            else if (owner != Thread.currentThread()) throw new IllegalStateException("fine cursor has a different owner");
        }

        public boolean done() { return completed; }
        public int sampledColumns() { return results.size(); }
    }
}
