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
        // Group by owner, so a chunk that several cells admit is compiled ONCE with all of them.
        //
        // The old shape enumerated (cell, owner) pairs and stored each result with a plain put. A seam
        // chunk - one whose realization every neighbouring cell clips to its own half - was therefore
        // compiled once per cell, and the later put replaced the earlier plan, so the half the first cell
        // had built disappeared. A wall 8,190 blocks long has about 128 such chunks and each lost roughly
        // eight blocks of wall body: about twelve percent of the whole wall, with nothing in the log.
        // ChunkPlanCompiler.compile already takes a List<RealizationCell> - it was simply always handed
        // one - so the fix is to hand it every cell that admits the owner.
        var byOwner = new java.util.LinkedHashMap<ChunkKey, java.util.ArrayList<Owned>>();
        for (var plan : plans.plans()) {
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
                    byOwner.computeIfAbsent(owner, ignored -> new java.util.ArrayList<>())
                            .add(new Owned(cellKey, owner, owners, plan));
                }
            }
        }
        var result = new HashMap<ChunkKey, ChunkWritePlan>();
        var provider = new PlanningColumnProvider(index);
        int ownerCount = byOwner.size();
        if (progress != null) progress.accept(0, ownerCount);
        // Two per-window values the per-owner path used to recompute: the sampled vertical extent (one
        // pass over every indexed column, so a hundred-owner cell paid it a hundred times) and the
        // protection hash (a sort plus a SHA-256 over every structure box, once per owner). Both are
        // functions of this compiler's own fields, not of the owner.
        var windowBounds = recipeBounds();
        var protection = List.copyOf(structures);
        String protectionHash = local.plaincraft.greatwall.geometry.DeterministicHash.sha256(
                structures.stream().map(Bounds::toString).sorted().toList().toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Per-cell memo shared across owners: a cell's column map and its realization depend on the cell
        // and the plan window it was resolved with, never on which owner is asking. One resolution per
        // cell instead of one per (cell, owner) removes about ninety identical realizations per cell -
        // the wall component alone is ~400 ms of work.
        var cellColumns = new HashMap<CellKey, Map<Point3i, SurfaceProbe.ColumnView>>();
        var cellRealizations = new HashMap<CellKey, Object>();
        int done = 0;
        int failed = 0;
        long started = System.nanoTime();
        long lastReport = started;
        long reportInterval = java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        for (var entry : byOwner.entrySet()) {
            var owner = entry.getKey();
            var items = entry.getValue();
            var cells = new java.util.ArrayList<RealizationCell>();
            var ownerPlans = new java.util.ArrayList<SegmentPlan>();
            var seen = new java.util.LinkedHashSet<CellKey>();
            var before = new HashMap<Point3i, String>();
            Throwable cellFailure = null;
            for (var item : items) {
                if (!seen.add(item.cell())) continue;
                ownerPlans.add(item.plan());
                Map<Point3i, SurfaceProbe.ColumnView> columns = cellColumns.computeIfAbsent(
                        item.cell(), ignored -> provider.columnsFor(item.owners()));
                Object realization = cellRealizations.get(item.cell());
                if (realization == null) {
                    try {
                        realization = resolveCell(item.cell(), columns,
                                new PlanWindow(List.of(item.plan())), protectionHash);
                    } catch (Throwable failure) {
                        // Memoised so every owner of this cell fails the same way, instead of each paying
                        // the realization again to hit the same failure.
                        realization = failure;
                    }
                    cellRealizations.put(item.cell(), realization);
                }
                if (realization instanceof Throwable failure) {
                    cellFailure = failure;
                    break;
                }
                var cellResult = (RealizationCell) realization;
                cells.add(cellResult);
                // The pre-write state of this owner's own cells, gathered from every cell that admits it.
                // ChunkPlanCompiler needs it for T0_MISSING; with one cell it used to see only that cell's
                // voxels, which is the second half of why a seam chunk lost content.
                for (var component : cellResult.components()) {
                    for (var v : component.voxels()) {
                        if (Math.floorDiv(v.pos().x(), 16) != owner.x()
                                || Math.floorDiv(v.pos().z(), 16) != owner.z()) continue;
                        var column = columns.get(new Point3i(v.pos().x(), 0, v.pos().z()));
                        if (column != null) before.putIfAbsent(v.pos(), column.state(v.pos().y()));
                    }
                }
            }
            // One owner's failure must not cost the rest of the window its wall: that owner is skipped,
            // named in the log, and the remaining owners still publish.
            try {
                if (cellFailure != null) throw cellFailure;
                var planWindow = new PlanWindow(List.copyOf(ownerPlans));
                var plan = compiler.bindCoverage(
                        compiler.compile(owner, recipeHash, List.copyOf(cells), before),
                        coverage.forChunk(owner, planWindow, windowBounds).hash());
                if (result.putIfAbsent(owner, plan) != null) {
                    // Cannot happen with one entry per owner; loud if a future change reintroduces the
                    // silent overwrite this grouping exists to remove.
                    local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                            "[GreatWall:S1C] PLANNING_COMPILE_DUPLICATE_OWNER owner=[{},{}]",
                            owner.x(), owner.z());
                }
            } catch (Throwable failure) {
                failed++;
                local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                        "[GreatWall:S1C] PLANNING_COMPILE_OWNER_FAILED owner=[{},{}] failed={}/{}",
                        owner.x(), owner.z(), failed, ownerCount, failure);
            }
            done++;
            // Progress reporting never costs the window its result: the sink writes a loading-card
            // counter today, but this loop is the only place that publishes the compile at all.
            if (progress != null) {
                try { progress.accept(done, ownerCount); } catch (Throwable ignored) { }
            }
            // A 15 s heartbeat: (done, total) with the elapsed time yields a per-owner rate even from a
            // run that is interrupted, which is the only way this stage can be sized today.
            long now = System.nanoTime();
            if (now - lastReport >= reportInterval || done == ownerCount) {
                lastReport = now;
                double seconds = Math.max(0.001, (now - started) / 1_000_000_000.0);
                double perOwnerMs = 1000.0 * seconds / Math.max(1, done);
                local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                        "[GreatWall:S1C] PLANNING_COMPILE_PROGRESS owners={}/{} failed={} elapsedMs={} "
                                + "msPerOwner={} etaMs={}",
                        done, ownerCount, failed, Math.round(seconds * 1000.0),
                        String.format(java.util.Locale.ROOT, "%.1f", perOwnerMs),
                        Math.round(perOwnerMs * Math.max(0, ownerCount - done)));
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

    /** {@return the realization of one cell, as the compile input describes it} */
    private RealizationCell resolveCell(CellKey cell,
            Map<Point3i, SurfaceProbe.ColumnView> columns, PlanWindow plans, String protectionHash) {
        return realization.resolve(new RealizationPlanner.Input(cell, plans, Map.of(), columns,
                List.copyOf(structures), Map.of(), 0, protectionHash));
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
