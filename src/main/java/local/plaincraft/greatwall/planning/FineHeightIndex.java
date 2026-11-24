package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.Quality;

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
    /**
     * Order-independent conflict resolution for the same {@code (x, z)} reached by two plans.
     *
     * <p>Adjacent route points sit one block apart, so their cross-sections overlap by up to
     * {@code 2 * LATERAL} columns: a slice boundary, or - with the two-tier sampler - a probed column
     * in one point against a conservatively filled one in its neighbour, can publish two different
     * values for one {@code (x, z)}. Picking whichever arrived last made the published index, and
     * therefore the compiled {@code ChunkWritePlan}, depend on the order the slices finished in; that
     * is a thread-completion order, which the determinism red line forbids. This order is a pure
     * function of the two columns:
     *
     * <ol>
     *   <li>a sample that reports water over one that does not, then higher {@code waterTopY} and
     *       {@code bedTopY}: {@code FineSamplePlan.fill} never synthesises water (it emits a dry
     *       column with the maximum of its two probed neighbours), so a water report can only come
     *       from a real probe - it is the strictly better-informed of the two, and taking it also
     *       keeps a water column from being buried by a filled neighbour;</li>
     *   <li>higher {@code groundTopY}: for two dry samples the fill rule already takes the maximum of
     *       its neighbours, so preferring the higher surface can only add fill, never shave terrain;</li>
     *   <li>better {@code quality}: {@code REFERENCE} before {@code COARSE} before {@code CENSORED};</li>
     *   <li>smaller {@code |lateral|}, then smaller {@code lateral}: the centre line is the
     *       authoritative cross-section and {@code WallFoundationPlanner} reads {@code lateral};</li>
     *   <li>{@code biomeKey} and finally the censored flag, which makes the order total.</li>
     * </ol>
     */
    private static final Comparator<WallFinePlanner.FineColumn> CONFLICT_ORDER =
            Comparator.comparing((WallFinePlanner.FineColumn column) -> !column.sample().waterTopY().isPresent())
                    .thenComparingInt(column -> heightKey(column.sample().waterTopY()))
                    .thenComparingInt(column -> heightKey(column.sample().bedTopY()))
                    .thenComparingInt(column -> -column.sample().groundTopY())
                    .thenComparingInt(column -> qualityRank(column.sample().quality()))
                    .thenComparingInt(column -> Math.abs(column.lateral()))
                    .thenComparingInt(WallFinePlanner.FineColumn::lateral)
                    .thenComparing(column -> column.sample().biomeKey())
                    .thenComparing(column -> column.sample().upperCensored());

    // 新版实测列不允许用邻列代替，也不参与旧横截面的共用地基。
    private final boolean exact;
    boolean exact() { return exact; }
    private final Map<Long, WallFinePlanner.FineColumn> byXZ;
    /** Original sampler order, including duplicate cross-section columns; grouping depends on it. */
    private final List<WallFinePlanner.FineColumn> routeColumns;

    private FineHeightIndex(Map<Long, WallFinePlanner.FineColumn> byXZ,
                            List<WallFinePlanner.FineColumn> routeColumns, boolean exact) {
        this.exact = exact;
        this.byXZ = byXZ;
        this.routeColumns = List.copyOf(routeColumns);
    }

    public static FineHeightIndex of(List<WallFinePlanner.FineColumn> columns) {
        return of(columns, false);
    }

    static FineHeightIndex of(List<WallFinePlanner.FineColumn> columns, boolean exact) {
        Objects.requireNonNull(columns, "columns");
        var map = new HashMap<Long, WallFinePlanner.FineColumn>(columns.size() * 2);
        for (var column : columns) {
            map.merge(key(column.x(), column.z()), column, FineHeightIndex::prefer);
        }
        // A read-only view, deliberately not Map.copyOf: the immutable-map implementation sizes its
        // table to the entry count and probes with the raw hashCode(), and key() packs two block
        // coordinates into one long, so adjacent columns collide. Measured on a 192 877-column index:
        // 53 s inside ImmutableCollections$MapN.<init> against ~0.1 s here - single-threaded, on the
        // thread that completes the fine-sample stage and holds its aggregate future.
        // The map is built above, never aliased, and the view keeps the read-only contract.
        return new FineHeightIndex(java.util.Collections.unmodifiableMap(map), columns, exact);
    }

    /** {@return whichever of the two columns for one {@code (x, z)} {@link #CONFLICT_ORDER} ranks first} */
    private static WallFinePlanner.FineColumn prefer(WallFinePlanner.FineColumn a, WallFinePlanner.FineColumn b) {
        return CONFLICT_ORDER.compare(a, b) <= 0 ? a : b;
    }

    /**
     * {@return whether {@code column} beats {@code existing} for the same {@code (x, z)}}
     *
     * <p>Strictly better only: a tie keeps {@code existing}, which is what {@link #of}'s
     * {@code Map.merge} does with the value already in the map. {@link WallFinePlanner} uses this to
     * collapse the repeated columns its overlapping cross-sections emit, in the very order the merged
     * map would have seen them, so the collapsed list merges to the identical index.
     */
    static boolean prefers(WallFinePlanner.FineColumn column, WallFinePlanner.FineColumn existing) {
        return CONFLICT_ORDER.compare(column, existing) < 0;
    }

    /**
     * Negated so that "higher wins" survives an ascending chain. Block levels live in the dimension's
     * build range, so negation cannot overflow, and an absent height sorts last.
     */
    private static int heightKey(OptionalInt height) {
        return height.isPresent() ? -height.getAsInt() : Integer.MAX_VALUE;
    }

    private static int qualityRank(Quality quality) {
        return switch (quality) {
            case REFERENCE -> 0;
            case COARSE -> 1;
            case CENSORED -> 2;
        };
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

    /** All sampled columns, for callers that must enumerate the footprint. */
    public java.util.Collection<WallFinePlanner.FineColumn> columns() {
        return byXZ.values();
    }

    /** Original route-ordered sampler columns; the foundation planner's grouping depends on it. */
    public List<WallFinePlanner.FineColumn> orderedColumns() {
        return routeColumns;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
