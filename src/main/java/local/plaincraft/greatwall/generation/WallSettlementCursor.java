package local.plaincraft.greatwall.generation;

import java.util.Set;
import java.util.function.BooleanSupplier;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.model.WallTypes.BlockSpec;

/** 单块外层事务拥有体素、实体/标记、发布和释放；暂停期间不提前发布完成。 */
final class WallSettlementCursor<S> {
    enum Stage { CHECK, REUSE, PREPARE, PAINT, METADATA, PUBLISH, CLOSED, CANCELLED, FAILED }
    interface Boundary<S> {
        boolean preserveCompleted();
        void reuseCompleted();
        void prepare();
        PublishedPlanPainter.World<S> world();
        void publish();
        default void release() {}
        /** 后续跨tick所有者在此验证世界、已加载区块租约和计划hash。 */
        default boolean current() { return true; }
    }
    private final ChunkWritePlan plan;
    private final Boundary<S> boundary;
    private final Thread owner = Thread.currentThread();
    private Stage stage = Stage.CHECK;
    private PublishedPlanCursor<S> painter;
    private PublishedPlanPainter.World<S> world;
    private PublishedPlanPainter.Result result;
    private Set<Point3i> satisfied;
    private boolean complete, released;

    WallSettlementCursor(ChunkWritePlan plan, Boundary<S> boundary) {
        this.plan = java.util.Objects.requireNonNull(plan);
        this.boundary = java.util.Objects.requireNonNull(boundary);
    }
    Stage stage() { return stage; }
    boolean terminal() { return stage == Stage.CLOSED || stage == Stage.CANCELLED || stage == Stage.FAILED; }
    boolean advance(int maxVoxels) {
        return advance(maxVoxels, () -> false);
    }
    /** 体素之间也检查时间，避免少量昂贵的光照更新耗尽整 tick。 */
    boolean advance(int maxVoxels, BooleanSupplier shouldYield) {
        checkThread();
        if (maxVoxels <= 0) throw new IllegalArgumentException("体素预算必须为正数");
        if (terminal()) return true;
        try {
            if (!boundary.current()) { cancel(); return true; }
            switch (stage) {
                case CHECK -> stage = boundary.preserveCompleted() ? Stage.REUSE : Stage.PREPARE;
                case REUSE -> {
                    boundary.reuseCompleted();
                    result = new PublishedPlanPainter.Result(true, 0, 0);
                    close();
                }
                case PREPARE -> {
                    boundary.prepare();
                    world = boundary.world();
                    painter = new PublishedPlanCursor<>(plan, deferredWorld());
                    stage = Stage.PAINT;
                }
                case PAINT -> {
                    if (painter.advance(maxVoxels, shouldYield)) {
                        result = painter.result();
                        stage = Stage.METADATA;
                    }
                }
                case METADATA -> {
                    try { world.finish(satisfied, complete); }
                    catch (RuntimeException failure) {
                        result = new PublishedPlanPainter.Result(false, result.written(), result.failed() + 1);
                        world.failure(null, failure);
                    }
                    stage = result.complete() ? Stage.PUBLISH : Stage.CLOSED;
                    if (stage == Stage.CLOSED) release();
                }
                case PUBLISH -> { boundary.publish(); close(); }
                default -> throw new IllegalStateException("已结案阶段不能执行");
            }
            return terminal();
        } catch (RuntimeException | Error failure) {
            stage = Stage.FAILED;
            release();
            throw failure;
        }
    }
    private PublishedPlanPainter.World<S> deferredWorld() {
        return new PublishedPlanPainter.World<>() {
            public boolean validHeight(int y) { return world.validHeight(y); }
            public S resolve(BlockSpec spec) { return world.resolve(spec); }
            public S read(Point3i p) { return world.read(p); }
            public boolean write(Point3i p, S state) { return world.write(p, state); }
            public void written(Point3i p, S state, int count) { world.written(p, state, count); }
            // 暂停封装只延迟结案，保留正式施工所需的坐标范围。
            public boolean trackOnlyEntityPositions() { return world.trackOnlyEntityPositions(); }
            public void failure(Point3i p, RuntimeException failure) { world.failure(p, failure); }
            public void finish(Set<Point3i> points, boolean success) {
                // 只暂存真实满足集合；取消/换代不能把实体或完成章写入旧计划。
                satisfied = points;
                complete = success;
            }
        };
    }
    PublishedPlanPainter.Result runAll() {
        checkThread();
        while (!terminal()) advance(Integer.MAX_VALUE);
        return result();
    }
    PublishedPlanPainter.Result result() {
        if (stage != Stage.CLOSED) throw new IllegalStateException("未结案事务不能读取完成结果: " + stage);
        return result;
    }
    private void close() { stage = Stage.CLOSED; release(); }
    void cancel() {
        checkThread();
        if (terminal()) return;
        if (painter != null) painter.cancel();
        stage = Stage.CANCELLED;
        release();
    }
    private void release() {
        if (released) return;
        released = true;
        try { boundary.release(); }
        catch (RuntimeException | Error failure) { stage = Stage.FAILED; throw failure; }
        finally { painter = null; world = null; satisfied = null; }
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("长城事务只能在所属世界线程推进");
    }
}
