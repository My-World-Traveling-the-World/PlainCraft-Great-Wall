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
            try {
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
            } catch (Throwable failure) {
                // Enumeration used to sit outside every try: one coverage failure (its CAPACITY guard, an
                // unknown footprint) threw out of compileWindow and the whole window published nothing.
                // Dropping this plan's cells costs the owners that only it admits, not the other plans.
                local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                        "[GreatWall:S1C] PLANNING_COMPILE_PLAN_SKIPPED prefix={} failure={} "
                                + "(only this plan's owners are dropped)",
                        plan.prefix().lengthBeforeQ8(), summarize(failure), failure);
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
        var tally = new FailureTally(FAILURE_DETAIL_LIMIT);
        long started = System.nanoTime();
        long lastReport = started;
        long reportInterval = java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        for (var entry : byOwner.entrySet()) {
            var owner = entry.getKey();
            var items = entry.getValue();
            var cells = new java.util.ArrayList<RealizationCell>();
            // The plans that enumerate the cells of this owner, for the coverage lookup below: coverage
            // is asked per owner, not per cell.
            var ownerPlans = new java.util.ArrayList<SegmentPlan>();
            var seen = new java.util.LinkedHashSet<CellKey>();
            var before = new HashMap<Point3i, String>();
            Throwable cellFailure = null;
            for (var item : items) {
                if (!seen.add(item.cell())) continue;
                ownerPlans.add(item.plan());
                Map<Point3i, SurfaceProbe.ColumnView> columns;
                try {
                    columns = cellColumns.computeIfAbsent(
                            item.cell(), ignored -> provider.columnsFor(item.owners()));
                } catch (Throwable failure) {
                    // Gathering a cell's columns is not "one owner's failure" but it is still a cell's
                    // failure: without this the exception escaped the whole window and took all 2131
                    // owners with it. Skipping this owner is the same contract as the compile below.
                    cellFailure = failure;
                    break;
                }
                Object realization = cellRealizations.get(item.cell());
                if (realization == null) {
                    try {
                        // Resolved against the plan that enumerates this cell, NOT the whole launch
                        // window: the realization walks the columns of the cell, and those columns cover
                        // only the owners of that cell. Resolving against all four segments makes the walk
                        // look for ground exits far outside the cell and fail - a measured run threw
                        // "no proven ground exit for break" for 1532 of 2131 owners that way.
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
                var plan = compiler.bindCoverage(
                        compiler.compile(owner, recipeHash, List.copyOf(cells), before),
                        coverage.forChunk(owner, new PlanWindow(List.copyOf(ownerPlans)), windowBounds).hash());
                if (result.putIfAbsent(owner, plan) != null) {
                    // Cannot happen with one entry per owner; loud if a future change reintroduces the
                    // silent overwrite this grouping exists to remove.
                    local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                            "[GreatWall:S1C] PLANNING_COMPILE_DUPLICATE_OWNER owner=[{},{}]",
                            owner.x(), owner.z());
                }
            } catch (Throwable failure) {
                failed++;
                String kind = tally.record(failure);
                // One stack trace per distinct cause, not one per owner: a real run failed 1783 owners
                // with the same exception and wrote about fifty thousand lines doing it. The first few
                // keep their trace (that is what a diagnosis needs), the rest are counted.
                if (tally.shouldLogDetail(kind)) {
                    local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                            "[GreatWall:S1C] PLANNING_COMPILE_OWNER_FAILED owner=[{},{}] failed={}/{} failure={}",
                            owner.x(), owner.z(), failed, ownerCount, kind, failure);
                } else if (tally.suppressed(kind) == 1) {
                    local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                            "[GreatWall:S1C] PLANNING_COMPILE_OWNER_FAILED owner=[{},{}] failed={}/{} failure={} "
                                    + "(stack trace suppressed from here; see COMPILE_FAILURES at the end)",
                            owner.x(), owner.z(), failed, ownerCount, kind);
                }
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
            // The whole point of the tally: a hundred owners failing for one reason is one bug, and the
            // summary says which. A hundred owners failing for a hundred reasons is not, and the summary
            // says that too - without a hundred stack traces.
            local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                    "[GreatWall:S1C] COMPILE_FAILURES failed={} distinct={} {}", failed, tally.distinct(),
                    tally.summary(FAILURE_SUMMARY_LIMIT));
        }
        // Map.copyOf rejects nulls outright, and a null here would take the whole window down with it,
        // so drop any owner the compiler refused to produce a plan for. A read-only view rather than a
        // second full table build: this map holds one plan per owner of the whole window.
        result.values().removeIf(java.util.Objects::isNull);
        // The end of the compile used to be "the progress lines stopped". One line with the three numbers
        // that separate "published" from "published nothing".
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:S1C] PLANNING_COMPILE_DONE owners={} plans={} failed={} elapsedMs={}",
                ownerCount, result.size(), failed,
                Math.round((System.nanoTime() - started) / 1_000_000.0D));
        return java.util.Collections.unmodifiableMap(result);
    }

    /** {@return the realization of one cell, as the compile input describes it} */
    private RealizationCell resolveCell(CellKey cell,
            Map<Point3i, SurfaceProbe.ColumnView> columns, PlanWindow plans, String protectionHash) {
        return realization.resolve(new RealizationPlanner.Input(cell, plans, Map.of(), columns,
                List.copyOf(structures), Map.of(), 0, protectionHash));
    }


    /** How many owner failures of one cause keep their stack trace; the rest are only counted. */
    static final int FAILURE_DETAIL_LIMIT = 5;
    /** How many distinct causes the end-of-window summary names. */
    static final int FAILURE_SUMMARY_LIMIT = 5;

    /**
     * Counts compile failures by their one-line cause, so a mass failure stays one screen of log.
     *
     * <p>Measured: a real run failed 1783 of 2131 owners with the same exception, and printing the
     * throwable for each wrote roughly fifty thousand lines. The first few traces are what a diagnosis
     * needs; after that the count is the information, and the order of magnitude is all that matters.
     */
    static final class FailureTally {
        private final java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        private final int detailLimit;

        FailureTally(int detailLimit) {
            this.detailLimit = Math.max(0, detailLimit);
        }

        String record(Throwable failure) {
            String kind = summarize(failure);
            counts.merge(kind, 1, Integer::sum);
            return kind;
        }

        /** {@return whether this occurrence still gets its stack trace} */
        boolean shouldLogDetail(String kind) {
            return count(kind) <= detailLimit;
        }

        /** {@return how many occurrences of this cause were already suppressed} */
        int suppressed(String kind) {
            return Math.max(0, count(kind) - detailLimit);
        }

        int distinct() {
            return counts.size();
        }

        int total() {
            int total = 0;
            for (int value : counts.values()) total += value;
            return total;
        }

        /** {@return "219x Type: message | 178x Type: message | +13 more"} */
        String summary(int limit) {
            var sorted = new java.util.ArrayList<>(counts.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < Math.min(Math.max(0, limit), sorted.size()); i++) {
                if (i > 0) text.append(" | ");
                text.append(sorted.get(i).getValue()).append('x').append(' ').append(sorted.get(i).getKey());
            }
            if (sorted.size() > limit) text.append(" | +").append(sorted.size() - Math.max(0, limit)).append(" more");
            return text.toString();
        }

        private int count(String kind) {
            return counts.getOrDefault(kind, 0);
        }
    }

    /**
     * {@return a one-line type-and-message for a failure}
     *
     * <p>The stack trace still goes to the log as the trailing throwable, but a tail-truncated or
     * rotated log loses it - and a run where every owner failed identically then reads as "no reason
     * given" for a thousand lines. This field keeps the reason greppable and countable.
     */
    private static String summarize(Throwable failure) {
        if (failure == null) return "null";
        String type = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return type;
        String line = type + ": " + message.replace('\n', ' ').replace('\r', ' ');
        return line.length() <= 240 ? line : line.substring(0, 240) + "...";
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
