package local.plaincraft.greatwall.planning;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import local.plaincraft.greatwall.config.GreatWallConfig;
import local.plaincraft.greatwall.model.WallTypes.Priority;
import local.plaincraft.greatwall.terrain.FineSampleCache;

/** 初轮精采已停表，补列单独持有推进信号；实际工作仍由原调度器和原OracleLane执行。 */
final class SupplementalColumnStage {
    private SupplementalColumnStage() {}

    static CompletableFuture<List<WallFinePlanner.FineColumn>> sample(PlannerScheduler scheduler,PlannerPump pump,
            List<WallFinePlanner.Target> targets,FineSampleCache cache,Priority priority,int lanes) {
        if(targets.isEmpty())return CompletableFuture.completedFuture(List.of());
        var frozen=List.copyOf(targets);
        var result=new CompletableFuture<List<WallFinePlanner.FineColumn>>();
        var timer=new AtomicReference<ScheduledFuture<?>>();
        result.whenComplete((v,e)->{var task=timer.get();if(task!=null)task.cancel(false);});
        try {
            pump.execute(()->{
                if(scheduler.closed()){result.completeExceptionally(scheduler.closedFailure());return;}
                try {
                    timer.set(pump.scheduleWithFixedDelay(()->{
                        if(scheduler.closed())result.completeExceptionally(scheduler.closedFailure());
                        else scheduler.driveStartupSlice();
                    },0,GreatWallConfig.get().fineSampleDriverIntervalMs(),TimeUnit.MILLISECONDS));
                    var slices=new ArrayList<CompletableFuture<List<WallFinePlanner.FineColumn>>>();
                    int size=(frozen.size()+lanes-1)/lanes;
                    for(int from=0;from<frozen.size();from+=size)
                        slices.add(scheduler.enqueueSupplemental(frozen.subList(from,Math.min(frozen.size(),from+size)),cache,priority));
                    CompletableFuture.allOf(slices.toArray(CompletableFuture[]::new)).whenComplete((ignored,error)->{
                        if(error!=null){result.completeExceptionally(error);return;}
                        var columns=new ArrayList<WallFinePlanner.FineColumn>(frozen.size());
                        slices.forEach(slice->columns.addAll(slice.join()));
                        result.complete(List.copyOf(columns));
                    });
                } catch(Throwable failure){result.completeExceptionally(failure);}
                // 关闭可以和入队并发；完成先于timer赋值时也必须取消刚装上的表。
                if(result.isDone()){var task=timer.get();if(task!=null)task.cancel(false);}
            });
        } catch(RejectedExecutionException closed){result.completeExceptionally(closed);}
        return result;
    }
}
