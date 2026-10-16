package local.plaincraft.greatwall.planning;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.Point3i;

/**
 * Planning-time column provider: turns the fine cross-section samples into the
 * {@code Map<Point3i, SurfaceProbe.ColumnView>} the realization stage consumes, with no per-block
 * terrain dependency. Each sampled column becomes a {@link PlanningColumnView}; structure protection
 * is layered on top by the caller from the already-resolved structure boxes.
 *
 * <h2>What the map has to contain</h2>
 * The realization stage does not read a column only where the wall is: {@code
 * RealizationPlanner.componentInput} builds the support table for the whole <em>axis-aligned world
 * bounds</em> of a component and refuses to place it when a single cell is missing, and {@code
 * naturalExit} walks the same box looking for a ground exit. Those bounds span several chunks around
 * the site (up to {@code WallFinePlanner.LATERAL} blocks sideways, plus the end apron), so
 * <ul>
 *   <li>the caller must pass every owner the realization may look at - the same set
 *       {@code CoveragePlanner.realizationOwners} returns - and not just the chunk being written,
 *       which is why {@code PlanningChunkCompiler} carries the owner list with each work item;</li>
 *   <li>the one-cell holes of the sampled ladder are filled, see {@link #fillLadderHoles}.</li>
 * </ul>
 */
public final class PlanningColumnProvider {
    /**
     * How far a hole may sit from a sampled column and still be filled. The sampled rungs are a block
     * apart, so the real pinholes are one cell wide; the margin only covers the staircase at the
     * route ends, where two rungs step past each other.
     */
    private static final int FILL_RADIUS = 3;

    /**
     * The fill radius a sampling grid needs.
     *
     * <p>With {@code fineSampleGridStep = N} the probes form a lattice of {@code N}-block lateral
     * spacing and about {@code N / 2} blocks along the route, and the probes of two adjacent rows are
     * offset by the route's bearing. A cell in the middle of such a lattice is at most {@code N}
     * blocks away from a probe in world coordinates (the worst bearing makes the offset the sum of
     * the two half-gaps), so the scan has to reach that far. {@code N = 1} - every column sampled -
     * keeps the historical {@link #FILL_RADIUS}, so a step of one fills byte for byte as it always
     * has.
     */
    private static int fillRadiusFor(int gridStep) {
        return Math.max(FILL_RADIUS, gridStep);
    }

    private final FineHeightIndex index;
    private final int fillRadius;
    private final Map<Long, List<WallFinePlanner.FineColumn>> byChunk = new HashMap<>();

    /** Fills holes at the radius the frozen parameters' sampling grid needs. */
    public PlanningColumnProvider(FineHeightIndex index) {
        this(index, fillRadiusFor(
                local.plaincraft.greatwall.config.GreatWallConfig.parameters().fineSampleGridStep()));
    }

    public PlanningColumnProvider(FineHeightIndex index, int fillRadius) {
        this.index = Objects.requireNonNull(index, "index");
        this.fillRadius = Math.max(FILL_RADIUS, fillRadius);
        for (var column : index.columns()) {
            byChunk.computeIfAbsent(chunkKey(column.x() >> 4, column.z() >> 4), key -> new ArrayList<>())
                    .add(column);
        }
    }

    /**
     * All sampled columns that fall inside any of the given owner chunks, with the ladder's holes
     * filled.
     *
     * @param owners every owner chunk the caller's realization may read, not only the one being
     *               written; see the class comment for why a narrower set loses the whole component
     */
    public Map<Point3i, SurfaceProbe.ColumnView> columnsFor(Collection<ChunkKey> owners) {
        Objects.requireNonNull(owners, "owners");
        // Real columns inside the requested owners. A column belongs to exactly one chunk, so the
        // union has no duplicates and needs no conflict resolution.
        var resolved = new HashMap<Long, WallFinePlanner.FineColumn>();
        for (var owner : owners) collect(resolved, owner);
        // Copies may also grow from just outside the requested set: a hole sitting on a chunk border
        // has neighbours on the far side of it.
        var sources = new HashMap<>(resolved);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (var owner : owners) {
                    collect(sources, new ChunkKey(owner.dimension(), owner.x() + dx, owner.z() + dz));
                }
            }
        }
        fillLadderHoles(resolved, sources, owners);
        var result = new HashMap<Point3i, SurfaceProbe.ColumnView>(resolved.size() * 2);
        for (var column : resolved.values()) {
            result.put(new Point3i(column.x(), 0, column.z()),
                    new PlanningColumnView(column.x(), column.z(), column.sample()));
        }
        // A read-only view, deliberately not Map.copyOf: the same quadratic table build that
        // FineHeightIndex.of documents (a 192 877-column index spent 53 s inside
        // ImmutableCollections$MapN.<init>) would otherwise run here once per realisation cell on a
        // 27k-entry map, and again in RealizationPlanner.Input's compact constructor. `result` is built
        // locally, never aliased and never mutated after this point, so the view keeps the contract.
        return java.util.Collections.unmodifiableMap(result);
    }

    public FineHeightIndex index() {
        return index;
    }

    private void collect(Map<Long, WallFinePlanner.FineColumn> into, ChunkKey owner) {
        var bucket = byChunk.get(chunkKey(owner.x(), owner.z()));
        if (bucket == null) return;
        for (var column : bucket) into.putIfAbsent(pack(column.x(), column.z()), column);
    }

    /**
     * Fills the pinholes of the sampled ladder with copies of the nearest sampled column.
     *
     * <p>Each route point contributes one perpendicular line of columns and consecutive lines sit a
     * block apart, so the raw set is a ladder of one-cell-wide rungs. Where the wall runs diagonally
     * the two rungs' staircases step in x and z at different laterals, which leaves single cells that
     * neither rung covers. They are invisible to the wall's own planners - those live well inside the
     * emitted rungs - but one of them inside a component's box is enough to drop that component, so
     * they are filled here, per owner, instead of materialising nine copies of the whole corridor
     * index (a 250k-column corridor times nine does not fit in the heap).</p>
     *
     * <p>A copy only ever lands where nothing was sampled, never over a real probe or a conservative
     * fill, and it is the nearest sampled column in a fixed scan order - never a copy of a copy - so
     * the result depends on nothing but the sampled columns and the requested owners. With a sampling
     * grid the raw set is a lattice rather than a ladder; the radius then follows the grid step
     * (see {@link #fillRadiusFor}) and the nearest copy still closes every cell.</p>
     */
    private void fillLadderHoles(Map<Long, WallFinePlanner.FineColumn> resolved,
            Map<Long, WallFinePlanner.FineColumn> sources, Collection<ChunkKey> owners) {
        for (var owner : owners) {
            int minX = owner.x() << 4, minZ = owner.z() << 4;
            for (int x = minX; x < minX + 16; x++) {
                for (int z = minZ; z < minZ + 16; z++) {
                    long key = pack(x, z);
                    if (resolved.containsKey(key)) continue;
                    var source = nearestSource(sources, x, z);
                    if (source == null) continue;
                    resolved.put(key, new WallFinePlanner.FineColumn(x, z, source.lateral(), source.sample()));
                }
            }
        }
    }

    private WallFinePlanner.FineColumn nearestSource(Map<Long, WallFinePlanner.FineColumn> sources,
            int x, int z) {
        WallFinePlanner.FineColumn best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int dx = -fillRadius; dx <= fillRadius; dx++) {
            for (int dz = -fillRadius; dz <= fillRadius; dz++) {
                if (dx == 0 && dz == 0) continue;
                var column = sources.get(pack(x + dx, z + dz));
                if (column == null) continue;
                int distance = dx * dx + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = column;
                }
            }
        }
        return best;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return pack(chunkX, chunkZ);
    }
}
