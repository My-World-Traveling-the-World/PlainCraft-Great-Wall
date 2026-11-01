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
    /** Absolute ground floor per (x,z), produced by WallFoundationPlanner for this window. */
    private final Map<Long, Integer> foundationFloor;
    private final List<WallFoundationPlanner.ColumnFoundation> foundations;

    public PlanningChunkCompiler(RealizationPlanner realization, ChunkPlanCompiler compiler,
            FineHeightIndex index, List<Bounds> structures) {
        this.realization = Objects.requireNonNull(realization, "realization");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.index = Objects.requireNonNull(index, "index");
        this.structures = List.copyOf(Objects.requireNonNull(structures, "structures"));
        // Production wiring for the per-column foundation decision. The WallSection consumes the
        // same support samples when it expands its foundation floor; this call keeps the planner's
        // base/fill contract exercised on every compile window and makes omissions visible.
        this.foundations = WallFoundationPlanner.plan(index.orderedColumns());
        var floor = new HashMap<Long, Integer>();
        for (var core : foundations) {
            for (var fill : core.fills()) {
                floor.put(columnKey(fill.x(), fill.z()), fill.surfaceY());
            }
        }
        this.foundationFloor = Map.copyOf(floor);
        long fillColumns = foundations.stream().mapToLong(f -> f.fills().size()).sum();
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:S1C] WALL_FOUNDATION_PLAN cores={} fillColumns={}", foundations.size(), fillColumns);
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
        // Concurrent: the compile tasks write one entry each from several threads at once. A plain
        // HashMap here is not merely racy - concurrent putIfAbsent corrupts its bucket structure and
        // the next read throws a NullPointerException, which is exactly how a measured session lost
        // the whole window (PLANNING_COMPILE_FAILED phase=publish reason=NullPointerException).
        var result = new java.util.concurrent.ConcurrentHashMap<ChunkKey, ChunkWritePlan>();
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
        var pending = new java.util.ArrayList<Runnable>();
        var failedCounter = new java.util.concurrent.atomic.AtomicInteger();
        // The per-owner compile is the only expensive part of this stage and every owner is
        // independent: its inputs are read-only (cells, before-states, coverage) and its output is
        // one entry of the result map. A measured window of 2188 owners took 57 s on one thread; the
        // same work on the machine's cores is a few seconds. The collection phase below stays
        // serial because it fills the shared cell caches.
        int workers = compileWorkers();
        long started = System.nanoTime();
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
            if (cellFailure != null) {
                recordFailure(owner, cellFailure, tally, failedCounter);
            } else {
                var compiledCells = List.copyOf(cells);
                var compiledBefore = java.util.Collections.unmodifiableMap(new HashMap<>(before));
                var compiledPlans = List.copyOf(ownerPlans);
                pending.add(() -> {
                    try {
                        var plan = compiler.bindCoverage(
                                compiler.compile(owner, recipeHash, compiledCells, compiledBefore),
                                coverage.forChunk(owner, new PlanWindow(compiledPlans), windowBounds).hash());
                        // A null plan is a legitimate "this owner produces nothing"; the map is a
                        // ConcurrentHashMap now, which rejects null values outright - that threw the
                        // NullPointerException that killed a whole window in PLANNING_COMPILE_FAILED.
                        if (plan == null) return;
                        if (result.putIfAbsent(owner, plan) != null) {
                            local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                                    "[GreatWall:S1C] PLANNING_COMPILE_DUPLICATE_OWNER owner=[{},{}]",
                                    owner.x(), owner.z());
                        }
                    } catch (Throwable failure) {
                        recordFailure(owner, failure, tally, failedCounter);
                    }
                });
            }
        }
        failed = failedCounter.get();
        int ownerCountDone = pending.size();
        // Progress for the compile stage, which used to report only once - at the end - so the bar jumped
        // from nothing to full and looked like it never ran.
        final int total = pending.size();
        var doneCounter = new java.util.concurrent.atomic.AtomicInteger();
        // Run the collected compiles. One worker runs them inline: the pool buys nothing on a
        // single-core machine and the ordering of the progress log stays unchanged.
        if (pending.size() > 1 && workers > 1) {
            var pool = java.util.concurrent.Executors.newFixedThreadPool(workers, task -> {
                Thread thread = new Thread(task, "pgw-compile");
                thread.setDaemon(true);
                return thread;
            });
            try {
                var jobs = pending.toArray(new Runnable[0]);
                // One future per crew, not one per job. The array used to be sized jobs.length while
                // only the first crew count entries were filled, and allOf walks every element: the
                // trailing nulls made it throw NullPointerException out of andTree, which surfaced as
                // PLANNING_COMPILE_FAILED phase=publish on every session that compiled more owners
                // than it had workers. That is the failure that kept the wall from painting.
                int crews = Math.min(workers, jobs.length);
                var futures = new java.util.concurrent.CompletableFuture[crews];
                var cursor = new java.util.concurrent.atomic.AtomicInteger();
                for (int p = 0; p < crews; p++) {
                    futures[p] = java.util.concurrent.CompletableFuture.runAsync(() -> {
                        int i;
                        while ((i = cursor.getAndIncrement()) < jobs.length) {
                            jobs[i].run();
                            reportCompileProgress(doneCounter.incrementAndGet(), total, null);
                        }
                    }, pool);
                }
                java.util.concurrent.CompletableFuture.allOf(futures).join();
            } finally {
                pool.shutdownNow();
            }
        } else {
            for (var job : pending) { job.run(); reportCompileProgress(doneCounter.incrementAndGet(), total, null); }
        }
        done = ownerCountDone;
        // Most failures are recorded inside the pool, which drains above this line: re-read the counter
        // or the summary reports the collection phase alone (measured 0.7.101 run: DONE said failed=181
        // while the tally already held 182, and the second cause never reached the summary's ledger).
        failed = failedCounter.get();
        if (progress != null) {
            try { progress.accept(done, ownerCount); } catch (Throwable ignored) { }
        }
        long now = System.nanoTime();
        double seconds = Math.max(0.001, (now - started) / 1_000_000_000.0);
        // owners= is how many owners the collection phase admitted, completed= is how many the pool actually
        // drained. Those are the same number by the time this line runs, but reading owners= alone made an
        // earlier audit treat a mid-run progress report as "280 owners are done" when it meant "280 were
        // queued"; both are printed so the distinction cannot be lost again.
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:S1C] PLANNING_COMPILE_PROGRESS owners={}/{} completed={} failed={} elapsedMs={} msPerOwner={}",
                done, ownerCount, doneCounter.get(), failed, Math.round(seconds * 1000.0),
                String.format(java.util.Locale.ROOT, "%.1f", 1000.0 * seconds / Math.max(1, done)));
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

    /** One owner's failure: counted, summarised by cause, and never allowed to stop the others. */
    private static void reportCompileProgress(int done, int total, Object unused) {
        if (total <= 0) return;
        if (done != total && (done & 31) != 0) return;
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.COMPILE, done, total);
    }

    private static void recordFailure(ChunkKey owner, Throwable failure, FailureTally tally,
                                      java.util.concurrent.atomic.AtomicInteger failed) {
        int n = failed.incrementAndGet();
        String kind = tally.record(failure);
        if (tally.shouldLogDetail(kind)) {
            local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                    "[GreatWall:S1C] PLANNING_COMPILE_OWNER_FAILED owner=[{},{}] failed={}/{} failure={}",
                    owner.x(), owner.z(), n, tally.total(), kind, failure);
        } else if (tally.suppressed(kind) == 1) {
            local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                    "[GreatWall:S1C] PLANNING_COMPILE_OWNER_FAILED owner=[{},{}] failed={}/{} failure={} "
                            + "(stack trace suppressed from here; see COMPILE_FAILURES at the end)",
                    owner.x(), owner.z(), n, tally.total(), kind);
        }
    }

    /**
     * How many owners to compile at once.
     *
     * <p>Capped at eight: the pool exists to use idle cores during startup, and the tally and the
     * result map are the only shared state, both of which are concurrent. Override with
     * {@code -Dgreatwall.compileWorkers}.
     */
    private static int compileWorkers() {
        int available = Runtime.getRuntime().availableProcessors();
        int requested = Integer.getInteger("greatwall.compileWorkers", Math.min(8, available));
        return Math.max(1, Math.min(requested, Math.max(1, available - 1)));
    }

    /** {@return the realization of one cell, as the compile input describes it} */

    private RealizationCell resolveCell(CellKey cell,
            Map<Point3i, SurfaceProbe.ColumnView> columns, PlanWindow plans, String protectionHash) {
        return realization.resolve(new RealizationPlanner.Input(cell, plans, Map.of(), columns,
                List.copyOf(structures), Map.of(), 0, protectionHash, foundationFloor, foundations));
    }


    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
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
    /**
     * Counts failures by cause.
     *
     * <p>Synchronized because the compile tasks record from several threads: the counters are a
     * LinkedHashMap, and concurrent merge() both loses counts and can throw.
     */
    static final class FailureTally {
        private final java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        private final int detailLimit;

        FailureTally(int detailLimit) {
            this.detailLimit = Math.max(0, detailLimit);
        }

        synchronized String record(Throwable failure) {
            String kind = summarize(failure);
            counts.merge(kind, 1, Integer::sum);
            return kind;
        }

        /** {@return whether this occurrence still gets its stack trace} */
        synchronized boolean shouldLogDetail(String kind) {
            return count(kind) <= detailLimit;
        }

        /** {@return how many occurrences of this cause were already suppressed} */
        synchronized int suppressed(String kind) {
            return Math.max(0, count(kind) - detailLimit);
        }

        synchronized int distinct() {
            return counts.size();
        }

        synchronized int total() {
            int total = 0;
            for (int value : counts.values()) total += value;
            return total;
        }

        /** {@return "219x Type: message | 178x Type: message | +13 more"} */
        synchronized String summary(int limit) {
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
