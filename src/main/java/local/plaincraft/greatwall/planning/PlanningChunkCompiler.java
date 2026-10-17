package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.CoveragePlanner;
import local.plaincraft.greatwall.generation.SurfaceProbe;
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
        // The realization planner recomputes the window's stations, sites and waters for every owner it
        // compiles - about five merges of the whole window each time, for values that cannot change while
        // the window is frozen. The memo is keyed by plan instance, so it can live for the compiler's
        // whole life without ever answering for a re-planned segment.
        this.realization.useWindowMemos(new RealizationPlanner.WindowMemos());
    }

    /**
     * Reports how many owners of the window have been compiled out of how many the window holds.
     *
     * <p>The total is a real count, not an estimate: it is the owner list discovered by the walk
     * below. A sink that only received the done count had to publish an indeterminate total, which
     * the loading card renders as a stage parked at 0 % - the compile then looked frozen for its
     * whole duration and the bar jumped at the end.
     */
    @FunctionalInterface
    public interface OwnerProgress {
        void accept(int done, int total);
    }

    /** Compiles every wall-touched chunk of the given plan window into a {@link ChunkWritePlan}. */
    public Map<ChunkKey, ChunkWritePlan> compileWindow(PlanWindow plans, String recipeHash, String dimension) {
        return compileWindow(plans, recipeHash, dimension, null);
    }

    /**
     * Compiles the window, reporting per-owner progress against a known total.
     *
     * <p>Two passes: the first enumerates the owners (a pure coverage lookup, no realization and no
     * chunk-plan work), the second compiles them. Splitting them is what makes an honest total
     * possible - the owner set is discovered cell by cell, so a single pass cannot know its size
     * before it has already done the expensive part.
     */
    public Map<ChunkKey, ChunkWritePlan> compileWindow(PlanWindow plans, String recipeHash,
                                                       String dimension, OwnerProgress progress) {
        record Owned(CellKey cell, ChunkKey owner, List<ChunkKey> owners, SegmentPlan plan) { }
        var work = new java.util.ArrayList<Owned>();
        for (var plan : plans.plans()) {
            // For each segment, enumerate the chunks its realization owners cover.
            long from = plan.prefix().lengthBeforeQ8();
            long to = from + plan.geometry().localSQ8().getLast();
            var firstCell = Math.floorDiv(from, 64L * 256);
            var lastCell = Math.floorDiv(to + 64L * 256 - 1, 64L * 256);
            for (long cell = firstCell; cell <= lastCell; cell++) {
                var cellKey = new CellKey(recipeHash, dimension, cell);
                var owners = coverage.realizationOwners(cellKey, new PlanWindow(List.of(plan)));
                for (var owner : owners) {
                    // The whole owner list travels with the item: a component's support table and its
                    // ground-exit walk read every cell of its axis-aligned bounds, which reaches into
                    // the neighbouring chunks, so the column map of one owner alone cannot serve it.
                    work.add(new Owned(cellKey, owner, owners, plan));
                }
            }
        }
        var result = new HashMap<ChunkKey, ChunkWritePlan>();
        var provider = new PlanningColumnProvider(index);
        if (progress != null) progress.accept(0, work.size());
        // Two per-window values the per-owner path used to recompute: the sampled vertical extent (one
        // pass over every indexed column, so a hundred-owner cell paid it a hundred times) and the
        // protection hash (a sort plus a SHA-256 over every structure box, once per owner). Both are
        // functions of this compiler's own fields, not of the owner.
        var windowBounds = recipeBounds();
        var protection = List.copyOf(structures);
        String protectionHash = local.plaincraft.greatwall.geometry.DeterministicHash.sha256(
                structures.stream().map(Bounds::toString).sorted().toList().toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CellKey cachedCell = null;
        Map<Point3i, SurfaceProbe.ColumnView> cachedColumns = null;
        int done = 0;
        int failed = 0;
        long started = System.nanoTime();
        long lastReport = started;
        long reportInterval = java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        for (var item : work) {
            // Every item of a cell carries the same owner list, so the column map is built once per
            // cell and reused for each of its owners (building it is a scan over those owners).
            if (!item.cell().equals(cachedCell)) {
                cachedCell = item.cell();
                cachedColumns = provider.columnsFor(item.owners());
            }
            // Resolve the realization and clip it to this owner; each owner gets its own window
            // instance, exactly as the single-pass shape did. One owner's failure must not cost the
            // rest of the window its wall: that owner is skipped, named in the log, and the remaining
            // owners still publish. Before this, a single failing chunk compile threw out of the whole
            // window, so a session could paint nothing at all with no line in the log to say which
            // owner failed - the exact shape the startup used to fail with.
            // Throwable, not RuntimeException: an Error raised inside a native or linkage path (for
            // example the road interop's NoClassDefFoundError) would otherwise abort the whole window
            // and cost the session its wall over one owner.
            try {
                compileOwner(item.cell(), item.owner(), cachedColumns, new PlanWindow(List.of(item.plan())),
                        recipeHash, windowBounds, protection, protectionHash, result);
            } catch (Throwable failure) {
                failed++;
                local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                        "[GreatWall:S1C] PLANNING_COMPILE_OWNER_FAILED owner=[{},{}] cell={} failed={}/{}",
                        item.owner().x(), item.owner().z(), item.cell(), failed, work.size(), failure);
            }
            done++;
            // Progress reporting never costs the window its result: the sink writes a loading-card
            // counter today, but this loop is the only place that publishes the compile at all.
            if (progress != null) {
                try { progress.accept(done, work.size()); } catch (Throwable ignored) { }
            }
            // A 15 s heartbeat. The window's own timing only exists once the window finishes, and on the
            // measured sessions the compile never finished at all, so there was no evidence of its cost at
            // any scale. (done, total) with the elapsed time yields a per-owner rate even from a run that
            // is interrupted - which is the only way this stage can be sized today.
            long now = System.nanoTime();
            if (now - lastReport >= reportInterval || done == work.size()) {
                lastReport = now;
                double seconds = Math.max(0.001, (now - started) / 1_000_000_000.0);
                double perOwnerMs = 1000.0 * seconds / Math.max(1, done);
                local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                        "[GreatWall:S1C] PLANNING_COMPILE_PROGRESS owners={}/{} failed={} elapsedMs={} "
                                + "msPerOwner={} etaMs={}",
                        done, work.size(), failed, Math.round(seconds * 1000.0),
                        String.format(java.util.Locale.ROOT, "%.1f", perOwnerMs),
                        Math.round(perOwnerMs * Math.max(0, work.size() - done)));
            }
        }
        if (failed > 0) {
            local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                    "[GreatWall:S1C] PLANNING_COMPILE_PARTIAL owners={} failed={}", result.size(), failed);
        }
        // Map.copyOf rejects nulls outright, and a null here would take the whole window down with it,
        // so drop any owner the compiler refused to produce a plan for. A read-only view rather than a
        // second full table build: this map holds one plan per owner of the whole window.
        result.values().removeIf(java.util.Objects::isNull);
        return java.util.Collections.unmodifiableMap(result);
    }

    private void compileOwner(CellKey cell, ChunkKey owner,
            Map<Point3i, SurfaceProbe.ColumnView> columns, PlanWindow plans, String recipeHash,
            DimensionBounds windowBounds, List<Bounds> protection, String protectionHash,
            Map<ChunkKey, ChunkWritePlan> into) {
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
        var coverageForOwner = coverage.forChunk(owner, plans, windowBounds);
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
