package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.CoveragePlanner;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.RealizationPlanner;

/**
 * Planning-time chunk compiler: runs the whole realization + chunk-plan compilation in the planning
 * phase, with no per-block T0 snapshot, no mailbox lock and no neighbour read. It produces the exact
 * {@link ChunkWritePlan} the landing {@code WallFeature} paints verbatim, so the generation stage is
 * pure painting.
 *
 * <p>Reuses {@link RealizationPlanner} (unchanged — its column source is a planning
 * {@code PlanningColumnView}) and {@link ChunkPlanCompiler} (a pure function), and derives the
 * {@code Coverage} from the plan window alone (also planning-only). No {@code GroundExitResolver} is
 * needed: the decision-free {@code RealizationPlanner.resolve(input)} path computes its own end caps.
 */
public final class PlanningChunkCompiler {
    private final RealizationPlanner realization;
    private final ChunkPlanCompiler compiler;
    private final CoveragePlanner coverage = new CoveragePlanner();
    private final FineHeightIndex index;
    private final List<Bounds> structures;

    public PlanningChunkCompiler(RealizationPlanner realization, ChunkPlanCompiler compiler,
            FineHeightIndex index, List<Bounds> structures) {
        this.realization = Objects.requireNonNull(realization, "realization");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.index = Objects.requireNonNull(index, "index");
        this.structures = List.copyOf(Objects.requireNonNull(structures, "structures"));
    }

    /** Compiles every wall-touched chunk of the given plan window into a {@link ChunkWritePlan}. */
    public Map<ChunkKey, ChunkWritePlan> compileWindow(PlanWindow plans, String recipeHash, String dimension) {
        var result = new HashMap<ChunkKey, ChunkWritePlan>();
        var provider = new PlanningColumnProvider(index);
        for (var plan : plans.plans()) {
            // For each segment, enumerate the chunks its realization owners cover, resolve the
            // realization and clip to each owner.
            long from = plan.prefix().lengthBeforeQ8();
            long to = from + plan.geometry().localSQ8().getLast();
            var firstCell = Math.floorDiv(from, 64L * 256);
            var lastCell = Math.floorDiv(to + 64L * 256 - 1, 64L * 256);
            for (long cell = firstCell; cell <= lastCell; cell++) {
                var cellKey = new CellKey(recipeHash, dimension, cell);
                var owners = coverage.realizationOwners(cellKey, new PlanWindow(List.of(plan)));
                for (var owner : owners) {
                    compileOwner(cellKey, owner, new PlanWindow(List.of(plan)), recipeHash, provider, result);
                }
            }
        }
        return Map.copyOf(result);
    }

    private void compileOwner(CellKey cell, ChunkKey owner, PlanWindow plans, String recipeHash,
            PlanningColumnProvider provider, Map<ChunkKey, ChunkWritePlan> into) {
        var columns = provider.columnsFor(List.of(owner));
        var protection = new ArrayList<>(structures);
        String protectionHash = local.plaincraft.greatwall.geometry.DeterministicHash.sha256(
                structures.stream().map(Bounds::toString).sorted().toList().toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var input = new RealizationPlanner.Input(cell, plans, Map.of(), columns, protection,
                Map.of(), 0, protectionHash);
        var cellResult = realization.resolve(input);
        var before = new HashMap<Point3i, String>();
        for (var component : cellResult.components()) {
            for (var v : component.voxels()) {
                if (Math.floorDiv(v.pos().x(), 16) != owner.x() || Math.floorDiv(v.pos().z(), 16) != owner.z()) continue;
                var column = columns.get(new Point3i(v.pos().x(), 0, v.pos().z()));
                if (column != null) before.putIfAbsent(v.pos(), column.state(v.pos().y()));
            }
        }
        var coverageForOwner = coverage.forChunk(owner, plans, recipeBounds());
        var plan = compiler.bindCoverage(
                compiler.compile(owner, recipeHash, List.of(cellResult), before),
                coverageForOwner.hash());
        into.put(owner, plan);
    }

    private DimensionBounds recipeBounds() {
        // The planning compiler does not hold a WorldRecipe; derive a conservative bound from the
        // sampled columns' vertical extent (the wall only writes where it sampled).
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (var column : index.columns()) {
            minY = Math.min(minY, column.sample().groundTopY() - 64);
            maxY = Math.max(maxY, column.sample().groundTopY() + 64);
        }
        if (minY == Integer.MAX_VALUE) { minY = -64; maxY = 320; }
        return new DimensionBounds(minY, maxY, 29_999_000);
    }
}
