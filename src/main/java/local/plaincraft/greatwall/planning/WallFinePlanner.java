package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;

/**
 * Planning-time fine terrain sampler over the wall's final centre line.
 *
 * <p>The ridge planner already produced the polyline geometry; this planner walks that polyline and
 * samples each column with the accurate {@code REFERENCE} probe (the full per-block noise walk)
 * instead of the coarse density query used during routing. The result is the per-column
 * groundTopY / water / bed the fine generation stage needs to rasterize a terrain-hugging wall —
 * computed entirely in the planning phase, with no chunk access and no live-world reads.
 *
 * <p>Edge (revetment) columns are deliberately out of scope here: the wall's exact width comes from
 * the component template, so the edge probe belongs to the rasterizer once that width is fixed.
 * This class answers the orthogonal question "what is the accurate surface height under every
 * centre-line column", which is the prerequisite for both the deck and the foundation.
 */
public final class WallFinePlanner {
    private WallFinePlanner() {
    }

    /** One accurately-sampled column, keyed by its block coordinates. */
    public record FineColumn(int x, int z, ColumnSample sample) {
        public FineColumn {
            Objects.requireNonNull(sample, "sample");
        }
    }

    public static Cursor begin(RouteShape route, NaturalTerrainOracle oracle) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(oracle, "oracle");
        return new Cursor(route, oracle);
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
        if (c.index >= c.route.points().size()) {
            c.completed = true;
            return;
        }
        var point = c.route.points().get(c.index);
        int x = FixedMath.floorBlock(point.xQ8());
        int z = FixedMath.floorBlock(point.zQ8());
        if (c.column == null) {
            c.column = c.oracle.begin(x, z, SampleKind.REFERENCE);
            return;
        }
        if (!c.column.done()) {
            c.oracle.advance(c.column, 1);
            return;
        }
        var sample = c.oracle.finish(c.column);
        c.column = null;
        c.results.add(new FineColumn(x, z, sample));
        c.index++;
    }

    public static final class Cursor {
        private final RouteShape route;
        private final NaturalTerrainOracle oracle;
        private Thread owner;
        private final List<FineColumn> results = new ArrayList<>();
        private int index;
        private NaturalTerrainOracle.ColumnCursor column;
        private boolean completed;

        private Cursor(RouteShape route, NaturalTerrainOracle oracle) {
            this.route = route;
            this.oracle = oracle;
        }

        private void claimOwner() {
            if (owner == null) owner = Thread.currentThread();
            else if (owner != Thread.currentThread()) throw new IllegalStateException("fine cursor has a different owner");
        }

        public boolean done() { return completed; }
        public int sampledColumns() { return results.size(); }
    }
}
