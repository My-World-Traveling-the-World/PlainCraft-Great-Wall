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
    // 限制同时保留的区块取证快照；区块的完整输入仍从整个窗口确定。
    private static final int OWNER_BATCH_SIZE = 64;
    private final CellResolver realization;
    private final ChunkPlanCompiler compiler;
    private final CoveragePlanner coverage = new CoveragePlanner();
    private final FineHeightIndex index;
    private final List<Bounds> structures;
    /** Absolute ground floor per (x,z), produced by WallFoundationPlanner for this window. */
    private final Map<Long, Integer> foundationFloor;
    private final List<WallFoundationPlanner.ColumnFoundation> foundations;

    public PlanningChunkCompiler(RealizationPlanner realization, ChunkPlanCompiler compiler,
            FineHeightIndex index, List<Bounds> structures) {
        this(withWindowMemos(realization), compiler, index, structures);
    }

    @FunctionalInterface
    interface CellResolver {
        RealizationCell resolve(RealizationPlanner.Input input);
    }

    PlanningChunkCompiler(CellResolver realization, ChunkPlanCompiler compiler,
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
        // packed-XZ键易形成相同哈希，统一快照避免开放寻址建表及各cell重复复制。
        this.foundationFloor = local.plaincraft.greatwall.terrain.FoundationFloorSnapshot.copyOf(floor);
        long fillColumns = foundations.stream().mapToLong(f -> f.fills().size()).sum();
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:S1C] WALL_FOUNDATION_PLAN cores={} fillColumns={}", foundations.size(), fillColumns);
    }

    private static CellResolver withWindowMemos(RealizationPlanner realization) {
        // 生产实现仍复用同一段计划的只读派生数据；离线可注入实现器检验完整编译入口。
        realization.useWindowMemos(new RealizationPlanner.WindowMemos());
        return realization::resolve;
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
        return compileWindow(plans, recipeHash, dimension, progress, OWNER_BATCH_SIZE, compileWorkers());
    }

    Map<ChunkKey, ChunkWritePlan> compileWindow(PlanWindow plans, String recipeHash,
            String dimension, OwnerProgress progress, int batchSize, int workers) {
        record Owned(CellKey cell, List<ChunkKey> owners, SegmentPlan plan, PlanWindow segmentWindow) { }
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
                // 同段重复查询不同 cell；预先建立只读索引，仍保留原窗口和接缝归属。
                var segmentWindow = new PlanWindow(List.of(plan));
                var ownerLookup = coverage.prepareOwners(segmentWindow);
                for (long cell = firstCell; cell <= lastCell; cell++) {
                    var cellKey = new CellKey(recipeHash, dimension, cell);
                    var owners = ownerLookup.forCell(cellKey);
                    if (owners.isEmpty()) continue;
                    var admitted = new Owned(cellKey, owners, plan, segmentWindow);
                    for (var owner : owners) {
                        // The whole owner list travels with the item: a component's support table and its
                        // ground-exit walk read every cell of its axis-aligned bounds, which reaches into
                        // the neighbouring chunks, so the column map of one owner alone cannot serve it.
                        byOwner.computeIfAbsent(owner, ignored -> new java.util.ArrayList<>())
                                .add(admitted);
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
        publishProgress(progress, 0, ownerCount);
        // Two per-window values the per-owner path used to recompute: the sampled vertical extent (one
        // pass over every indexed column, so a hundred-owner cell paid it a hundred times) and the
        // protection hash (a sort plus a SHA-256 over every structure box, once per owner). Both are
        // functions of this compiler's own fields, not of the owner.
        var windowBounds = recipeBounds();
        String protectionHash = local.plaincraft.greatwall.geometry.DeterministicHash.sha256(
                structures.stream().map(Bounds::toString).sorted().toList().toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // 同一弧长单元能被相邻段分别收录。按枚举时共享的工作项复用，区分两份段窗口和取证范围。
        record CellWork(Map<Point3i, SurfaceProbe.ColumnView> columns, RealizationCell cell, CellVoxelOwners voxels) { }
        var cellWork = new java.util.IdentityHashMap<Owned, CellWork>();
        var cellFailures = new java.util.IdentityHashMap<Owned, Throwable>();
        // 按完整归属表标记最后一次使用，批次边界和区块失败都不决定共享证据的寿命。
        var lastOwner = new java.util.IdentityHashMap<Owned, Integer>();
        int ordinal = 0;
        for (var items : byOwner.values()) {
            for (var item : items) lastOwner.put(item, ordinal);
            ordinal++;
        }
        long indexedVoxels = 0;
        long previousVoxelChecks = 0;
        int indexedCells = 0;
        int peakCells = 0;
        var tally = new FailureTally(FAILURE_DETAIL_LIMIT);
        var failedCounter = new java.util.concurrent.atomic.AtomicInteger();
        var doneCounter = new java.util.concurrent.atomic.AtomicInteger();
        int ownerJobs = 0;
        int peakQueued;
        // 一个窗口共享完整构件的冲突解析，取证和覆盖哈希仍按区块独立计算。
        var windowCompiler = compiler.window();
        // 采集串行维护共享取证缓存；编译只读各自的输入，每批可并行裁切到对应区块。
        // 进度由采集线程在批次结案后发布，避免多个工作线程把较旧的计数覆盖在较新的计数上。
        int reported = 0;
        long started = System.nanoTime();
        ordinal = 0;
        try (var pending = new OwnerCompileBatch(batchSize, workers)) {
            for (var entry : byOwner.entrySet()) {
                var owner = entry.getKey();
                var items = entry.getValue();
                var cells = new java.util.ArrayList<RealizationCell>();
                // 覆盖哈希按区块计算，只传入实际参与这个区块编译的段。
                var ownerPlans = new java.util.ArrayList<SegmentPlan>();
                var seen = new java.util.LinkedHashSet<CellKey>();
                var before = new HashMap<Point3i, String>();
                Throwable cellFailure = null;
                for (var item : items) {
                    // 同一区块仍按原顺序为每个弧长单元选一个来源，否则会提交两个同名墙体构件。
                    if (!seen.add(item.cell())) continue;
                    ownerPlans.add(item.plan());
                    var failure = cellFailures.get(item);
                    if (failure != null) {
                        cellFailure = failure;
                        break;
                    }
                    var work = cellWork.get(item);
                    if (work == null) {
                        try {
                            // 单元只读取枚举它的段的列，不能拿整窗路线去这一小片证据中找出口。
                            var columns = provider.columnsFor(item.owners());
                            var cell = resolveCell(item.cell(), columns, item.segmentWindow(), protectionHash);
                            work = new CellWork(columns, cell, new CellVoxelOwners(cell));
                            cellWork.put(item, work);
                            indexedCells++;
                            peakCells = Math.max(peakCells, cellWork.size());
                            indexedVoxels += work.voxels().indexedVoxels();
                        } catch (Throwable unavailable) {
                            // 同一工作项共享失败，另一段即使同弧长也可以独立编译。
                            cellFailures.put(item, unavailable);
                            cellFailure = unavailable;
                            break;
                        }
                    }
                    cells.add(work.cell());
                    previousVoxelChecks += work.voxels().indexedVoxels();
                    work.voxels().collectBefore(owner, work.columns(), before);
                }
                // 单区块取证失败要计入进度和诊断，但不能让其他区块丢失计划。
                if (cellFailure != null) {
                    recordFailure(owner, cellFailure, tally, failedCounter);
                    doneCounter.incrementAndGet();
                } else {
                    // 快照只交给本区块任务，采集端不再改写，避免再复制一份证据表。
                    ownerJobs++;
                    pending.add(() -> {
                        try {
                            var plan = compiler.bindCoverage(
                                    windowCompiler.compile(owner, recipeHash, cells, before),
                                    coverage.forChunk(owner, new PlanWindow(ownerPlans), windowBounds).hash());
                            if (result.putIfAbsent(owner, plan) != null) {
                                local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                                        "[GreatWall:S1C] PLANNING_COMPILE_DUPLICATE_OWNER owner=[{},{}]",
                                        owner.x(), owner.z());
                            }
                        } catch (Throwable failure) {
                            recordFailure(owner, failure, tally, failedCounter);
                        } finally {
                            doneCounter.incrementAndGet();
                        }
                    });
                }
                for (var item : items) {
                    // 最后一次可能使用后即可归还取证列；已入队区块只持构件和自身的施工前状态。
                    // 即使本区块在前一个单元失败，后续单元也要按最后使用次序归还。
                    if (lastOwner.getOrDefault(item, -1) == ordinal) {
                        cellWork.remove(item);
                        cellFailures.remove(item);
                        lastOwner.remove(item);
                    }
                }
                ordinal++;
                int complete = doneCounter.get();
                if (complete < ownerCount && complete - reported >= 32) {
                    publishProgress(progress, complete, ownerCount);
                    reported = complete;
                }
            }
            // 尾批可能没凑满，必须显式结案后才能返回完整结果。
            pending.drain();
            peakQueued = pending.peak();
        }
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:编译] 施工前状态体素分桶 cells={} indexedVoxels={} previousVoxelChecks={} peakCells={} peakQueued={}",
                indexedCells, indexedVoxels, previousVoxelChecks, peakCells, peakQueued);
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:编译] 构件解析次数 resolutions={} ownerJobs={}", windowCompiler.resolutions(), ownerJobs);
        int done = doneCounter.get();
        int failed = failedCounter.get();
        publishProgress(progress, done, ownerCount);
        long now = System.nanoTime();
        double seconds = Math.max(0.001, (now - started) / 1_000_000_000.0);
        // 完成数包含失败，计划数单列；避免把所有区块已处理误读为全部成功。
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:S1C] PLANNING_COMPILE_PROGRESS owners={}/{} completed={} failed={} elapsedMs={} msPerOwner={}",
                done, ownerCount, done, failed, Math.round(seconds * 1000.0),
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
        // 使用只读视图，避免对窗口内的施工计划再复制一份整表。
        // The end of the compile used to be "the progress lines stopped". One line with the three numbers
        // that separate "published" from "published nothing".
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:S1C] PLANNING_COMPILE_DONE owners={} plans={} failed={} elapsedMs={}",
                ownerCount, result.size(), failed,
                Math.round((System.nanoTime() - started) / 1_000_000.0D));
        return java.util.Collections.unmodifiableMap(result);
    }

    /** 进度包含失败区块；显示端故障不能阻止其余施工计划结案。 */
    private static void publishProgress(OwnerProgress progress, int done, int total) {
        if (progress != null) {
            try { progress.accept(done, total); } catch (Throwable ignored) { }
        }
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
