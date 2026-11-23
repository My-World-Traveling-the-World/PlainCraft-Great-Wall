package local.plaincraft.greatwall.generation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.model.WallTypes.BlockSpec;

/** 只拥有单份不可变计划的落块进度；计划修订、区块租约和队列仍由外层负责。 */
final class PublishedPlanCursor<S> {
    enum State { NEW, RUNNING, FINISHED, CANCELLED, FAILED }
    private final ChunkWritePlan plan;
    private final PublishedPlanPainter.World<S> world;
    private final Thread owner=Thread.currentThread();
    private final HashMap<BlockSpec,S> palette=new HashMap<>();
    private final HashSet<Point3i> satisfied=new HashSet<>();
    private final Set<Point3i> trackedPositions;
    private State state=State.NEW;
    private int next,written,failed;

    PublishedPlanCursor(ChunkWritePlan plan,PublishedPlanPainter.World<S> world) {
        this.plan=Objects.requireNonNull(plan);
        this.world=Objects.requireNonNull(world);
        // 普通墙段没有实体，不为成千上万的地基和墙砖分配集合节点。
        if (world.trackOnlyEntityPositions()) {
            var positions = new HashSet<Point3i>();
            for (var entity : plan.blockEntities()) positions.add(entity.pos());
            trackedPositions = positions.isEmpty() ? Set.of() : positions;
        } else {
            trackedPositions = null;
        }
    }

    State state() { return state; }
    int visited() { return next; }

    boolean advance(int maxVoxels,BooleanSupplier shouldYield) {
        checkThread();
        if(maxVoxels<=0)throw new IllegalArgumentException("体素预算必须为正数");
        Objects.requireNonNull(shouldYield);
        if(state==State.FINISHED)return true;
        if(state==State.CANCELLED||state==State.FAILED)
            throw new IllegalStateException("已取消或失败的施工不能重放: "+state);
        state=State.RUNNING;
        try {
            int count=0;
            while(next<plan.voxels().size()&&count<maxVoxels) {
                if(shouldYield.getAsBoolean())return false;
                // KEEP和越界项也占扫描预算，不能让长串跳过项绕过暂停上限。
                var voxel=plan.voxels().get(next++);
                count++;
                paint(voxel);
            }
            if(next<plan.voxels().size())return false;
            try {
                // 完整遍历后才运行实体/标记阶段；暂停不得被理解为本块已经完成。
                world.finish(Set.copyOf(satisfied),failed==0);
            } catch(RuntimeException failure) {
                failed++;
                world.failure(null,failure);
            }
            state=State.FINISHED;
            palette.clear();
            satisfied.clear();
            return true;
        } catch(RuntimeException|Error failure) {
            // 诊断回调自身抛异常时保持原传播，且禁止重新执行已经有副作用的半块。
            state=State.FAILED;
            throw failure;
        }
    }

    private void paint(VoxelIntent voxel) {
        var p=voxel.pos();
        if(Math.floorDiv(p.x(),16)!=plan.owner().x()||Math.floorDiv(p.z(),16)!=plan.owner().z()
                ||voxel.kind()==IntentKind.KEEP)return;
        try {
            if(!world.validHeight(p.y()))throw new IllegalArgumentException("计划落块超出世界高度: "+p);
            // 材料解析跨暂停保留，同一计划每种材料只解析一次。
            S target=palette.computeIfAbsent(voxel.state(),world::resolve);
            if(!Objects.equals(world.read(p),target)) {
                if(!world.write(p,target))throw new IllegalStateException("计划落块被世界拒绝: "+p);
                written++;
                world.written(p,target,written);
            }
            // 相同状态仍需实体payload补齐，不能只收集实际写过的点。
            if (trackedPositions == null || trackedPositions.contains(p)) satisfied.add(p);
        } catch(RuntimeException failure) {
            failed++;
            world.failure(p,failure);
        }
    }

    PublishedPlanPainter.Result runAll() {
        advance(Integer.MAX_VALUE,()->false);
        return result();
    }

    PublishedPlanPainter.Result result() {
        if(state!=State.FINISHED)throw new IllegalStateException("未遍历完毕不能读取结案结果: "+state);
        return new PublishedPlanPainter.Result(failed==0,written,failed);
    }

    void cancel() {
        checkThread();
        if(state==State.FINISHED||state==State.FAILED||state==State.CANCELLED)return;
        // 取消不运行finish；外层必须按未完工处理，之后另建新事务重试。
        state=State.CANCELLED;
        palette.clear();
        satisfied.clear();
    }

    private void checkThread() {
        if(Thread.currentThread()!=owner)throw new IllegalStateException("施工游标只能在所属世界线程推进");
    }
}
