package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;
import local.plaincraft.greatwall.terrain.FineSampleCache;

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

    /** Half-width in blocks of the sampled band.
     *
     *  <p>The wall is 10 blocks wide, but the band must cover more than the widest footprint: a
     *  component's realization reads the columns of its <em>axis aligned</em> world bounds, not of
     *  its rotated footprint ({@code RealizationPlanner.componentInput} refuses to place a component
     *  when any column of that box is missing), and
     *  {@code RealizationPlanner.naturalExit} additionally walks the ground out to the same box.</p>
     *
     *  <p>For a footprint whose local half extents are {@code A} along the wall and {@code B} across
     *  it, turned by {@code theta}, the axis aligned box reaches {@code B + A*sin(2*theta)} blocks
     *  sideways — worst at 45 degrees. The widest templates are FORT (A=24, B=24 -> 48),
     *  GATE/RAMP/END_CAP (A=30, B=15 -> 45) and WallSection (A=32, B=5 -> 37); a numeric sweep of
     *  every integer theta gives 50.22 as the true worst case. With the one-cell padding
     *  {@code Rasterizer.worldBounds} adds, 52 covers it.</p>
     *
     *  <p>Cost: the emitted cross-section grows from 49 to 105 columns. The probe count grows much
     *  less (the sparse plan strides the wings), and the columns inside the old ±24 wall band keep
     *  their lateral parity because both bounds are even, so the wall's own planning values are
     *  unchanged.</p> */
    public static final int LATERAL = 52;

    /** Cross-section half width everywhere except the terminal components.
     *
     *  <p>The wall body reads {@code |lateral| <= 5}, the ribbon path {@code ±7}, a tower {@code ±6} and
     *  a beacon {@code ±5} (see RealizationPlanner's extents), so sampling the full {@link #LATERAL}
     *  along the whole route pays for columns nothing ever reads. Forts and gates are the only parts
     *  whose axis-aligned box reaches past this width - and the scheduler hands those arcs their full
     *  {@link #LATERAL} band, because it knows where they sit from the same deterministic station
     *  schedule the contract uses.</p> */
    public static final int BASE_LATERAL = 16;

    /** Blocks of extra cross-sections sampled past each end of the route.
     *
     *  <p>The terminal components sit <em>on</em> the route ends (FORT0 and GATE0 are placed at arc
     *  0) and lean outwards, so their axis aligned boxes reach {@code A + B} blocks beyond the end
     *  (48 worst case, see {@link #LATERAL}). The band is therefore carried past both ends along the
     *  terminal tangent, which also gives the end caps the apron they need to find a natural ground
     *  exit.</p> */
    public static final int APRON_BLOCKS = 64;

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
        return begin(route, oracle, fromIndex, toIndex, FineSampleCache.none());
    }

    /**
     * Same, but reusing the columns a previous session already sampled when {@code cache} holds
     * them. A hit emits exactly the sample the oracle would have produced, so the merged index is
     * identical whichever columns the cache answered - only the terrain walks are skipped.
     */
    public static Cursor begin(RouteShape route, NaturalTerrainOracle oracle, int fromIndex, int toIndex,
                               FineSampleCache cache) {
        return begin(route, oracle, fromIndex, toIndex, cache, null);
    }

    /**
     * Same, with the route points that must carry the full {@link #LATERAL} band. {@code wideAt}
     * answers for a point index; {@code null} means every point is wide, which is what a caller
     * without a station schedule gets. The scheduler computes it from the same deterministic station
     * schedule the contract uses, so the fort and gate AABBs are never sampled narrower than they
     * read.
     */
    public static Cursor begin(RouteShape route, NaturalTerrainOracle oracle, int fromIndex, int toIndex,
                               FineSampleCache cache, java.util.function.IntPredicate wideAt) {
        return begin(route, oracle, fromIndex, toIndex, cache, wideAt, null);
    }

    /**
     * Same, with a session-wide memo of the columns this run has already walked.
     *
     * <p>Slices of one route overlap at their boundaries and neighbouring cross-sections round to the
     * same integer columns, so the same world column is asked for more than once in a session. A
     * column's sample is a pure function of its {@code (x, z)} — the same property the on-disk cache
     * relies on — so answering the second ask from the memo produces the value the oracle would have
     * produced, and skips only the walk. The memo is deliberately not written by the conservative
     * fills: a fill is a function of the cross-section that emitted it, not of the column, so it can
     * never stand in for a real probe.</p>
     *
     * @param walks shared by every slice of one session, or {@code null} to walk every column again
     */
    public static Cursor begin(RouteShape route, NaturalTerrainOracle oracle, int fromIndex, int toIndex,
                               FineSampleCache cache, java.util.function.IntPredicate wideAt,
                               java.util.Map<Long, ColumnSample> walks) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(cache, "cache");
        return new Cursor(route, oracle, fromIndex, toIndex, cache, wideAt, walks);
    }

    /**
     * The columns this cursor resolved itself (probed now, or answered from the cache): exactly the
     * set worth persisting, since the conservative fills depend on the sampling rules instead.
     */
    public static List<FineColumn> probed(Cursor c) {
        return List.copyOf(c.probed);
    }

    /**
     * Columns this cursor emitted before repeats were collapsed.
     *
     * <p>Compare with {@code finish(c).size()}: the ratio is what the overlapping cross-sections (and
     * the sampling grid's absence) cost in merge candidates, which is the number that tells whether
     * the de-duplication is doing its job on this world.
     */
    public static int emittedRaw(Cursor c) {
        return c.rawEmissions;
    }

    /**
     * Cross-sections this cursor actually walked, and how many of them carried the full wide band.
     *
     * <p>The pair answers the question a real session cannot answer after the fact: whether the base
     * band is really narrower than the wide band. A measured run emitted 1.35M columns over a corridor
     * of about 8190 blocks, which is far more per section than {@link #BASE_LATERAL} implies - either
     * the wide arcs cover most route points, or the sampling plan's flank refinement degrades to full
     * resolution. {@code emittedRaw / sections} is the average width actually walked.
     */
    public static long sections(Cursor c) {
        return c.sections;
    }

    /** {@return how many of {@link #sections} carried the full {@link #LATERAL} band} */
    public static long wideSections(Cursor c) {
        return c.wideSections;
    }

    /**
     * {@return REFERENCE walks this cursor started, i.e. columns no cache or memo answered}
     *
     * <p>Read from {@link Cursor#probed}, the list every resolved probe is added to, because the field
     * this used to read was declared and never incremented: a real session printed {@code probesPerSection=0}
     * and {@code probesPerColumn=0} for eleven minutes, which reads as "the probe plan visits nothing"
     * when it actually means "nobody counted".
     */
    public static long probedColumns(Cursor c) {
        return c.probed.size();
    }

    public static WorkState advance(Cursor c, int primitiveLimit) {
        if (primitiveLimit < 1 || primitiveLimit > 4096) throw new IllegalArgumentException("primitive limit 1..4096");
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
        if (c.index >= c.end) {
            c.completed = true;
            return;
        }
        if (c.pending == null) {
            if (c.gridStep > 1 && Math.floorMod(c.index, c.gridStep) != 0) {
                // The sampling grid skips this route point entirely. Its columns are one route point
                // away from an emitted cross-section (a route point is under a block long), so every
                // one of them is inside the fill radius of a real probe. c.waterCarry is deliberately
                // left untouched, exactly as for a redundant section below: it still describes the
                // last cross-section actually walked, which is the closest available answer.
                c.index++;
                return;
            }
            // Emit the cross-section sample points for the current centre point.
            c.pending = c.section();
            if (redundant(c)) {
                // Every column of this section is already sampled by this cursor, so walking it would
                // add nothing but the walk. Dropping the repeat cannot lose a column (a section is only
                // redundant when each of its columns was already sampled) and cannot change a value (a
                // column's sample is a pure function of its (x, z)), so the merged index is identical -
                // it just costs a fraction of the probe work.
                // c.waterCarry is deliberately left untouched: it still describes the last section this
                // cursor actually walked, and a skipped section is built from columns that very section
                // already sampled, so that flag is the closest available answer to "did the previous
                // route point see water". Clearing it here could let a river be filled as land.
                c.pending = null;
                c.index++;
                return;
            }
            // The plan decides which of those columns the accurate probe must visit; the rest take a
            // conservative fill derived from their probed neighbours.
            c.plan = new FineSamplePlan(c.pending.size(), c.rules, c.waterCarry);
            c.slot = -1;
            c.emit = 0;
            c.sections++;
            if (c.pending.size() > 2 * BASE_LATERAL + 1) c.wideSections++;
            // Fall through: this same step starts the first probe, exactly like the fixed sweep did.
        }
        if (c.column != null) {
            if (!c.column.done()) {
                c.oracle.advance(c.column, 1);
                return;
            }
            var sample = c.oracle.finish(c.column);
            c.column = null;
            c.plan.record(c.slot, sample);
            var walked = c.pending.get(c.slot);
            // Published for the session's other slices before anything reads it: the value is a pure
            // function of the column, so a later ask may skip the walk. Censored samples are left out
            // for the same reason the disk cache refuses to store them: their value depends on where
            // the walk started, and that is a performance decision, not a terrain fact.
            if (c.walks != null && sample.quality() == Quality.REFERENCE && !sample.upperCensored()) {
                c.walks.putIfAbsent(key(walked.x(), walked.z()), sample);
            }
            // Remember the walk itself: this is what the session persists, because the conservative
            // fills depend on the sampling rules while a probed column does not. Collected only when a
            // session cache can write it - an unread list would cost peak heap for nothing.
            if (c.cache.persistent()) {
                c.probed.add(new FineColumn(walked.x(), walked.z(), walked.lateral(), sample));
            }
            return;
        }
        int next = c.plan.nextSlot();
        if (next >= 0) {
            c.slot = next;
            var target = c.pending.get(next);
            // A column this session already walked (or an earlier session already cached) answers with
            // the very sample the oracle would produce, so reusing it cannot change the merged index -
            // it only skips the walk. The memo is asked first so a session hit does not inflate the
            // disk cache's miss counter, which is the diagnostic that separates the two costs.
            var cached = c.walks == null ? null : c.walks.get(key(target.x(), target.z()));
            if (cached == null) cached = c.cache.get(target.x(), target.z());
            if (cached != null) {
                c.plan.record(next, cached);
                if (c.cache.persistent()) {
                    c.probed.add(new FineColumn(target.x(), target.z(), target.lateral(), cached));
                }
                return;
            }
            c.column = c.oracle.begin(target.x(), target.z(), SampleKind.REFERENCE);
            return;
        }
        // Probing is finished: emit every slot in order - probed columns carry their real sample,
        // thinned ones their conservative fill - so the compiler still sees a complete cross-section.
        if (c.emit >= c.pending.size()) {
            c.waterCarry = c.plan.escalated();
            c.pending = null;
            c.plan = null;
            c.index++;
            return;
        }
        int slot = c.emit++;
        c.rawEmissions++;
        var target = c.pending.get(slot);
        var column = new FineColumn(target.x(), target.z(), target.lateral(), c.plan.column(slot));
        long columnKey = key(target.x(), target.z());
        Integer previous = c.emitted.get(columnKey);
        if (previous == null) {
            c.emitted.put(columnKey, c.results.size());
            c.results.add(column);
        } else {
            // The merge would keep whichever of the two its conflict order ranks first, and it keeps
            // the first one on a tie - so replacing in place reproduces the merged index exactly while
            // the list, and every pass over it, stays one entry per column.
            var existing = c.results.get(previous);
            if (FineHeightIndex.prefers(column, existing)) c.results.set(previous, column);
        }
        c.sampled.add(columnKey);
    }

    /** True when every column of the cursor's pending section has already been sampled by that cursor. */
    private static boolean redundant(Cursor c) {
        if (c.sampled.isEmpty()) return false;
        for (var target : c.pending) {
            if (!c.sampled.contains(key(target.x(), target.z()))) return false;
        }
        return true;
    }

    /** Packs a column coordinate the way the merged index keys it. */
    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** A pending sample coordinate before the oracle resolves it.
     *  Package-private together with {@link #crossSection} so the redundant-section guard test can
     *  walk exactly the sections the planner walks. */
    record Target(int x, int z, int lateral) {}

    /** Emits the centre + lateral columns for route point {@code index}, deduplicated by (x,z).
     *  The lateral direction is the local frame of the wall: normal to the tangent, with lateral=0
     *  at the centre. We use the chord to the next point (or the previous, at the tail) as tangent. */
    static List<Target> crossSection(List<Point2q> points, int index, int cx, int cz) {
        return crossSection(points, index, cx, cz, LATERAL, 1);
    }

    /** Same, with an explicit half width: outside the fort and gate arcs the wall only reads
     *  {@link #BASE_LATERAL}. */
    static List<Target> crossSection(List<Point2q> points, int index, int cx, int cz, int halfWidth) {
        return crossSection(points, index, cx, cz, halfWidth, 1);
    }

    /** Same, with the sampling grid applied across the band. {@code gridStep = 1} keeps every
     *  column, which is what every caller gets unless the config asks for a coarser grid. */
    static List<Target> crossSection(List<Point2q> points, int index, int cx, int cz, int halfWidth,
                                     int gridStep) {
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
        double length = StrictMath.hypot(dx, dz);
        if (length < 1.0) {
            // Degenerate segment; fall back to a single centre column.
            return List.of(new Target(cx, cz, 0));
        }
        return crossSection(dx / length, dz / length, cx, cz, halfWidth, gridStep);
    }

    /** Emits the centre + lateral columns around {@code (cx, cz)} for unit tangent {@code (ux, uz)}.
     *  Keeping the tangent as the argument is what lets the piece past each route end reuse the
     *  terminal cross-section verbatim (see {@link #apron}). */
    private static List<Target> crossSection(double ux, double uz, int cx, int cz) {
        return crossSection(ux, uz, cx, cz, LATERAL, 1);
    }

    private static List<Target> crossSection(double ux, double uz, int cx, int cz, int halfWidth) {
        return crossSection(ux, uz, cx, cz, halfWidth, 1);
    }

    private static List<Target> crossSection(double ux, double uz, int cx, int cz, int halfWidth,
                                             int gridStep) {
        double nx = -uz, nz = ux; // unit normal: the unit tangent rotated 90°
        var result = new ArrayList<Target>(2 * halfWidth + 1);
        var seen = new java.util.HashSet<Long>();
        for (int lateral = -halfWidth; lateral <= halfWidth; lateral++) {
            // The sampling grid keeps every gridStep-th lateral, including the centre line, which is
            // the authoritative cross-section the foundation planner reads. The skipped laterals sit
            // at most gridStep / 2 blocks from a kept one - three blocks at the maximum accepted grid
            // step - so PlanningColumnProvider can always fill them from a real probe.
            if (gridStep > 1 && Math.floorMod(lateral, gridStep) != 0) continue;
            int x = (int) Math.floor(cx + nx * lateral);
            int z = (int) Math.floor(cz + nz * lateral);
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            if (seen.add(key)) result.add(new Target(x, z, lateral));
        }
        return result;
    }

    /** A cross-section {@code alongBlocks} beyond the route end at {@code endIndex}, along the
     *  terminal chord, keeping that end's normal — the same centre/normal rule as the route itself,
     *  so the apron is a pure function of the route too. {@code forward} selects the tail end. */
    private static List<Target> apron(List<Point2q> points, int endIndex, int alongBlocks, boolean forward,
                                      int gridStep) {
        var end = points.get(endIndex);
        var other = points.get(forward ? Math.max(0, endIndex - 1) : Math.min(points.size() - 1, endIndex + 1));
        // Tangent pointing forward along the route, whichever end we are extending.
        long dx = forward ? end.xQ8() - other.xQ8() : other.xQ8() - end.xQ8();
        long dz = forward ? end.zQ8() - other.zQ8() : other.zQ8() - end.zQ8();
        int cx = FixedMath.floorBlock(end.xQ8());
        int cz = FixedMath.floorBlock(end.zQ8());
        double length = StrictMath.hypot(dx, dz);
        if (length < 1.0) return List.of(new Target(cx, cz, 0));
        double ux = dx / length, uz = dz / length;
        double sign = forward ? 1.0 : -1.0;
        int x = (int) Math.floor(end.xQ8() / 256.0 + sign * ux * alongBlocks);
        int z = (int) Math.floor(end.zQ8() / 256.0 + sign * uz * alongBlocks);
        return crossSection(ux, uz, x, z, LATERAL, gridStep);
    }

    public static final class Cursor {
        private final RouteShape route;
        private final NaturalTerrainOracle oracle;
        /** Point range this cursor covers: {@code [fromIndex, toIndex)} plus any end apron. */
        private final int fromIndex, toIndex;
        /** One past the last index this cursor walks; negative indices are the leading apron. */
        private final int end;
        /** Extra cross-sections past each route end, owned by whichever cursor holds that end. */
        private final int apronBefore, apronAfter;
        private Thread owner;
        private final List<FineColumn> results = new ArrayList<>();
        /** Columns resolved by a walk or by the cache; the fills are deliberately not included. */
        private final List<FineColumn> probed = new ArrayList<>();
        /**
         * The (x, z) columns this cursor has already emitted.
         *
         * <p>Route points sit a fraction of a block apart, so consecutive cross-sections round to
         * nearly the same integer columns. The merged index is keyed by (x, z), so a repeated section
         * would be walked and then thrown away by the merge. See {@link #redundant}.
         */
        private final java.util.HashSet<Long> sampled = new java.util.HashSet<>();
        /**
         * Where each already-emitted {@code (x, z)} sits in {@link #results}.
         *
         * <p>One world column is emitted by several consecutive cross-sections. The merged index keeps
         * only the winner of {@code FineHeightIndex}'s conflict order for that column, so the losers
         * are objects, merge probes and cache entries spent on a value that is thrown away. Keeping
         * one entry per column - the current winner, replaced in place when a later cross-section
         * outranks it - cannot change the merged index: it is the same column set, and per column the
         * same winner, in a shorter list.
         */
        private final java.util.HashMap<Long, Integer> emitted = new java.util.HashMap<>();
        /**
         * The sampling grid, read from the frozen parameter snapshot once per cursor.
         *
         * <p>{@code 1} - the default - keeps every emitted column, which is what every earlier
         * version generated. A larger value keeps one route point in {@code gridStep} and one lateral
         * in {@code gridStep}, so the merged index becomes a grid of real probes; the cells between
         * them are filled by {@code PlanningColumnProvider} from their nearest sampled neighbour,
         * which only ever copies a value and never shaves terrain. The along-route spacing that
         * results is {@code gridStep} route points, which is under two blocks even at the maximum,
         * while the across spacing is {@code gridStep} blocks - so the grid's reach stays inside that
         * fill radius for every accepted value (see {@code GreatWallConfig#fineSampleGridStep()}).
         */
        private final int gridStep;
        /**
         * Columns this whole session has already walked, shared across its slices.
         *
         * <p>{@code null} when no session memo was handed in. Only real probes are ever put here, so a
         * hit stands in for a walk exactly; the disk cache's counters stay meaningful because a memo
         * hit never reaches them.
         */
        private final java.util.Map<Long, ColumnSample> walks;
        /** Reused samples from an earlier session; {@link FineSampleCache#none()} disables reuse. */
        private final FineSampleCache cache;
        /** Route points that need the full {@link #LATERAL} band; {@code null} means every point. */
        private final java.util.function.IntPredicate wideAt;
        /** Sampling rules captured once per cursor: a config edit must not change a run mid-corridor. */
        private final FineSamplePlan.Rules rules = FineSamplePlan.Rules.fromConfig();
        private FineSamplePlan plan;
        /** Slot being probed (probe phase) and slot being emitted (emit phase) are different cursors. */
        private int emit;
        /** Slots emitted before repeats were collapsed; see {@link #emittedRaw}. */
        private int rawEmissions;
        /** Sections walked, wide-band sections, and the REFERENCE walks they cost. */
        private long sections, wideSections, probedColumns;
        /** Water seen at the previous route point; the escalation carries one point forward. */
        private boolean waterCarry;
        private int index;
        private List<Target> pending;
        private int slot;
        private NaturalTerrainOracle.ColumnCursor column;
        private boolean completed;

        private Cursor(RouteShape route, NaturalTerrainOracle oracle, int fromIndex, int toIndex,
                       FineSampleCache cache, java.util.function.IntPredicate wideAt,
                       java.util.Map<Long, ColumnSample> walks) {
            this.route = route;
            this.oracle = oracle;
            this.cache = cache;
            this.wideAt = wideAt;
            this.walks = walks;
            // Frozen snapshot, not the live config: the grid decides which columns are probed, so a
            // mid-world edit would change the terrain the second half of the wall is built on.
            this.gridStep = local.plaincraft.greatwall.config.GreatWallConfig.parameters().fineSampleGridStep();
            this.fromIndex = Math.max(0, fromIndex);
            this.toIndex = Math.min(route.points().size(), Math.max(this.fromIndex, toIndex));
            // Only the cursor that holds a route end samples past it, so every apron column is
            // produced exactly once — PlannerScheduler predicts the same split for its progress bar.
            this.apronBefore = this.fromIndex == 0 ? APRON_BLOCKS : 0;
            this.apronAfter = this.toIndex == route.points().size() ? APRON_BLOCKS : 0;
            this.index = -this.apronBefore;
            this.end = this.toIndex + this.apronAfter;
        }

        /** The current cross-section: a route point, or the apron past one of the route's ends. */
        private List<Target> section() {
            var points = route.points();
            if (index < 0) return apron(points, 0, -index, false, gridStep);
            if (index >= points.size()) return apron(points, points.size() - 1, index - (points.size() - 1), true, gridStep);
            var centre = points.get(index);
            return crossSection(points, index, FixedMath.floorBlock(centre.xQ8()),
                    FixedMath.floorBlock(centre.zQ8()), halfWidth(), gridStep);
        }

        /**
         * The cross-section half width for the point this cursor is on.
         *
         * <p>Everything the wall itself reads - its body, its ribbon path, towers and beacons - is
         * inside {@link #BASE_LATERAL}. Forts and gates are the only components whose axis-aligned box
         * reaches past it once their frame is rotated, and {@code wideAt} marks the arcs they sit on,
         * so nothing is sampled narrower than it is read. The aprons stay wide: the terminal components
         * live on the route ends.</p>
         */
        private int halfWidth() {
            if (wideAt == null) return LATERAL;
            return wideAt.test(index) ? LATERAL : BASE_LATERAL;
        }

        private void claimOwner() {
            if (owner == null) owner = Thread.currentThread();
            else if (owner != Thread.currentThread()) throw new IllegalStateException("fine cursor has a different owner");
        }

        public boolean done() { return completed; }
        public int sampledColumns() { return results.size(); }
    }
}
