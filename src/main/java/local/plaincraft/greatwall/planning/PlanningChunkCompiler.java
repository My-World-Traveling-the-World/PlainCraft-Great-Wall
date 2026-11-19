package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.CoveragePlanner;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.RealizationPlanner;
import local.plaincraft.greatwall.terrain.RealizationWindow;
import local.plaincraft.greatwall.diagnostics.CompileWindowMemory;

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
    private CoveragePlanner coverage = new CoveragePlanner();
    private final PlanningColumnProvider.ColumnSource columns;
    private final PlanningCompileContext context;
    private RealizationWindow realizationWindow;
    private CompileWindowMemory memory = new CompileWindowMemory();

    public PlanningChunkCompiler(RealizationPlanner realization, ChunkPlanCompiler compiler,
            FineHeightIndex index, List<Bounds> structures) {
        this(realization.openWindow(),compiler,index,structures);
    }

    PlanningChunkCompiler(RealizationWindow window,ChunkPlanCompiler compiler,FineHeightIndex index,List<Bounds> structures){
        this(window::resolve,compiler,index,structures);
        // 捕获区块也必须选择同一版模板，否则新楼的高屋顶和宽底板不在证据范围内。
        coverage = new CoveragePlanner(window.templateVersion());
        realizationWindow = window;
    }

    /** 同一会话的多个编译器共享观测器，避免把每窗口64MiB误读为整个会话的总额。 */
    public PlanningChunkCompiler(RealizationPlanner realization, ChunkPlanCompiler compiler,
            FineHeightIndex index, List<Bounds> structures, CompileWindowMemory memory) {
        this(realization,compiler,index,structures);
        this.memory=Objects.requireNonNull(memory);
    }

    PlanningChunkCompiler(RealizationWindow window,ChunkPlanCompiler compiler,FineHeightIndex index,
            List<Bounds> structures,CompileWindowMemory memory){
        this(window,compiler,index,structures);this.memory=Objects.requireNonNull(memory);
    }

    @FunctionalInterface
    interface CellResolver {
        RealizationCell resolve(RealizationPlanner.Input input);
    }

    PlanningChunkCompiler(CellResolver realization, ChunkPlanCompiler compiler,
            FineHeightIndex index, List<Bounds> structures) {
        this(realization, compiler, new PlanningColumnProvider(index).source(),
                new PlanningCompileContext(index, structures));
    }

    // 局部编译只接收所需采样桶；覆盖、保护和地基裁决仍共享同一份整窗上下文。
    PlanningChunkCompiler(CellResolver realization, ChunkPlanCompiler compiler,
            PlanningColumnProvider.ColumnSource columns, PlanningCompileContext context) {
        this.realization = Objects.requireNonNull(realization, "realization");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.columns = Objects.requireNonNull(columns, "columns");
        this.context = Objects.requireNonNull(context, "context");
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
        // 需要整窗快照的调用才收集整窗；启动发布直接消费每个已结案区块。
        var result = new HashMap<ChunkKey, ChunkWritePlan>();
        compileInto(plans, recipeHash, dimension, progress, batchSize, workers, plan -> result.put(plan.owner(), plan));
        return java.util.Collections.unmodifiableMap(result);
    }

    /** 同步交付编译计划，回调运行在调用线程；返回实际交付的计划数。 */
    public int compileInto(PlanWindow plans, String recipeHash, String dimension,
            OwnerProgress progress, Consumer<ChunkWritePlan> output) {
        return compileInto(plans, recipeHash, dimension, progress, OWNER_BATCH_SIZE, compileWorkers(), output);
    }

    int compileInto(PlanWindow plans, String recipeHash, String dimension, OwnerProgress progress,
            int batchSize, int workers, Consumer<ChunkWritePlan> output) {
        return compileInto(plans,recipeHash,dimension,progress,batchSize,workers,output,new CompileCancellation());
    }

    /** 正式会话关闭检查贯穿枚举、实现和工作项认领，不只停在发布边界。 */
    public int compileInto(PlanWindow plans,String recipeHash,String dimension,OwnerProgress progress,
            Consumer<ChunkWritePlan> output,Runnable active) {
        return compileInto(plans,recipeHash,dimension,progress,OWNER_BATCH_SIZE,compileWorkers(),output,
                new CompileCancellation(active));
    }

    private int compileInto(PlanWindow plans,String recipeHash,String dimension,OwnerProgress progress,
            int batchSize,int workers,Consumer<ChunkWritePlan> output,CompileCancellation cancellation) {
        List<PlanningOwnerIndex.OwnerWork> owners;
        try{owners=PlanningOwnerIndex.prepare(plans,recipeHash,dimension,coverage,cancellation);}
        catch(RuntimeException|Error failure){
            // 枚举尚未进入编译finally时也要归还调用方预先填充的窗口状态。
            if(realizationWindow!=null)realizationWindow.close();
            throw failure;
        }
        return compileOwners(owners,recipeHash,progress,batchSize,workers,output,cancellation);
    }

    // 只切分已冻结的区块工作项；不能先按段过滤再重算接缝归属。
    int compileOwners(List<PlanningOwnerIndex.OwnerWork> owners, String recipeHash, OwnerProgress progress,
            int batchSize, int workers, Consumer<ChunkWritePlan> output) {
        return compileOwners(owners,recipeHash,progress,batchSize,workers,output,new CompileCancellation());
    }

    private int compileOwners(List<PlanningOwnerIndex.OwnerWork> owners,String recipeHash,OwnerProgress progress,
            int batchSize,int workers,Consumer<ChunkWritePlan> output,CompileCancellation cancellation) {
        try(var retained=memory.open()) {
            try {
                sampleMemory(retained);
                return compileOwnersWindow(owners,recipeHash,progress,batchSize,workers,output,retained,cancellation);
            } finally {
                if(realizationWindow!=null) {
                    var stats=realizationWindow.buildingMemoStats();
                    realizationWindow.clearCompiledBuildings();
                    local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                            "[GreatWall:编译] 实物楼体复用 编译={} 命中={} 峰值保留字节={} 窗口结束已释放",
                            stats.loads(),stats.hits(),stats.peak());
                }
            }
        } finally {
            var stats=memory.snapshot();
            local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                    "[GreatWall:编译] 会话窗口缓存保留估算 活动窗口={} 峰值窗口={} 当前字节={} 检查点峰值字节={} 已结束窗口={} 不含输出及原生地形",
                    stats.activeWindows(),stats.peakWindows(),stats.totalBytes(),stats.peakBytes(),stats.closedWindows());
        }
    }

    private void sampleMemory(CompileWindowMemory.Window retained) {
        if(realizationWindow!=null)retained.sample(realizationWindow.windowMemoStats());
    }

    // 大楼体只在本次窗口循环中保留，发布回调抛错也通过外层finally归还。
    private int compileOwnersWindow(List<PlanningOwnerIndex.OwnerWork> owners, String recipeHash, OwnerProgress progress,
            int batchSize, int workers, Consumer<ChunkWritePlan> output, CompileWindowMemory.Window retained, CompileCancellation cancellation) {
        Objects.requireNonNull(output, "output");
        // 工作线程只写当前批；调用线程同步交付后清表，计划不会留在整窗编译器里。
        var batchPlans = new java.util.concurrent.ConcurrentHashMap<ChunkKey, ChunkWritePlan>();
        int ownerCount = owners.size();
        publishProgress(progress, 0, ownerCount);
        var windowBounds = context.bounds();
        // 同一弧长单元能被相邻段分别收录。按枚举时共享的工作项复用，区分两份段窗口和取证范围。
        record CellWork(Map<Point3i, SurfaceProbe.ColumnView> columns, RealizationCell cell, CellVoxelOwners voxels) { }
        var cellWork = new java.util.IdentityHashMap<PlanningOwnerIndex.CellWork, CellWork>();
        var cellFailures = new java.util.IdentityHashMap<PlanningOwnerIndex.CellWork, Throwable>();
        // 按完整归属表标记最后一次使用，批次边界和区块失败都不决定共享证据的寿命。
        var lastOwner = new java.util.IdentityHashMap<PlanningOwnerIndex.CellWork, Integer>();
        int ordinal = 0;
        for (var owner : owners) {
            cancellation.check();
            for (var item : owner.cells()) lastOwner.put(item, ordinal);
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
        int emitted = 0;
        long outputNanos = 0;
        int peakQueued;
        // 一个窗口共享完整构件的冲突解析，取证和覆盖哈希仍按区块独立计算。
        var windowCompiler = compiler.window();
        // 采集串行维护共享取证缓存；编译只读各自的输入，每批可并行裁切到对应区块。
        // 进度由采集线程在批次结案后发布，避免多个工作线程把较旧的计数覆盖在较新的计数上。
        int reported = 0;
        long started = System.nanoTime();
        ordinal = 0;
        try (var pending = new OwnerCompileBatch(batchSize, workers,cancellation)) {
            for (var entry : owners) {
                cancellation.check();
                var owner = entry.owner();
                var items = entry.cells();
                var cells = new java.util.ArrayList<RealizationCell>();
                // 覆盖哈希按区块计算，只传入实际参与这个区块编译的段。
                var ownerPlans = new java.util.ArrayList<SegmentPlan>();
                var before = new HashMap<Point3i, String>();
                Throwable cellFailure = null;
                for (var item : items) {
                    cancellation.check();
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
                            var evidence = columns.columnsFor(item.captureOwners());
                            RealizationCell cell;
                            try { cell = realization.resolve(context.input(item, evidence)); }
                            finally { sampleMemory(retained); }
                            cancellation.check();
                            work = new CellWork(evidence, cell, new CellVoxelOwners(cell));
                            cellWork.put(item, work);
                            indexedCells++;
                            peakCells = Math.max(peakCells, cellWork.size());
                            indexedVoxels += work.voxels().indexedVoxels();
                        } catch (Throwable unavailable) {
                            cancellation.rethrowCancellation(unavailable);
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
                    boolean drained = pending.add(() -> {
                        try {
                            var plan = compiler.bindCoverage(
                                    windowCompiler.compile(owner, recipeHash, cells, before),
                                    coverage.forChunk(owner, new PlanWindow(ownerPlans), windowBounds).hash());
                            if (batchPlans.putIfAbsent(owner, plan) != null) {
                                local.plaincraft.greatwall.GreatWallMod.LOGGER.error(
                                        "[GreatWall:S1C] PLANNING_COMPILE_DUPLICATE_OWNER owner=[{},{}]",
                                        owner.x(), owner.z());
                            }
                        } catch (Throwable failure) {
                            cancellation.rethrowCancellation(failure);
                            recordFailure(owner, failure, tally, failedCounter);
                        } finally {
                            doneCounter.incrementAndGet();
                        }
                    });
                    if (drained) {
                        long outputStart = System.nanoTime();
                        cancellation.check();
                        emitted += emitBatch(batchPlans, output);
                        outputNanos += System.nanoTime() - outputStart;
                    }
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
            long outputStart = System.nanoTime();
            cancellation.check();
                        emitted += emitBatch(batchPlans, output);
            outputNanos += System.nanoTime() - outputStart;
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
        long compileNanos = System.nanoTime() - started - outputNanos;
        double seconds = Math.max(0.001, compileNanos / 1_000_000_000.0);
        // 完成数包含失败，计划数单列；避免把所有区块已处理误读为全部成功。
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:S1C] PLANNING_COMPILE_PROGRESS owners={}/{} completed={} failed={} elapsedMs={} msPerOwner={}",
                done, ownerCount, done, failed, Math.round(seconds * 1000.0),
                String.format(java.util.Locale.ROOT, "%.1f", 1000.0 * seconds / Math.max(1, done)));
        if (failed > 0) {
            local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                    "[GreatWall:S1C] PLANNING_COMPILE_PARTIAL owners={} failed={}", emitted, failed);
            // The whole point of the tally: a hundred owners failing for one reason is one bug, and the
            // summary says which. A hundred owners failing for a hundred reasons is not, and the summary
            // says that too - without a hundred stack traces.
            local.plaincraft.greatwall.GreatWallMod.LOGGER.warn(
                    "[GreatWall:S1C] COMPILE_FAILURES failed={} distinct={} {}", failed, tally.distinct(),
                    tally.summary(FAILURE_SUMMARY_LIMIT));
        }
        local.plaincraft.greatwall.GreatWallMod.LOGGER.info(
                "[GreatWall:S1C] PLANNING_COMPILE_DONE owners={} plans={} failed={} elapsedMs={}",
                ownerCount, emitted, failed, Math.round(compileNanos / 1_000_000.0D));
        return emitted;
    }

    private static int emitBatch(Map<ChunkKey, ChunkWritePlan> plans, Consumer<ChunkWritePlan> output) {
        int count = plans.size();
        // 发布异常交给调用方结案；不能把部分发布悄悄记成编译成功。
        plans.values().forEach(output);
        plans.clear();
        return count;
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
    static String summarize(Throwable failure) {
        if (failure == null) return "null";
        String type = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return type;
        String line = type + ": " + message.replace('\n', ' ').replace('\r', ' ');
        return line.length() <= 240 ? line : line.substring(0, 240) + "...";
    }

}
