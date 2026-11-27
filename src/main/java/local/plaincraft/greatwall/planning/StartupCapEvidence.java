package local.plaincraft.greatwall.planning;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.diagnostics.CompileWindowMemory;
import local.plaincraft.greatwall.generation.ChunkPlanCompiler;
import local.plaincraft.greatwall.generation.CoveragePlanner;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.GroundExitResolver;
import local.plaincraft.greatwall.terrain.RealizationPlanner;
import local.plaincraft.greatwall.terrain.RealizationWindow;

/** 先发现真实收口，再局部补列并重建归属；生产发布前不允许把缺证据当成空地。 */
public final class StartupCapEvidence {
    public record Prepared(FineHeightIndex terrain,CoveragePlanner coverage,RealizationWindow realization,
            int passes,int inspectedPlans,int supplementalColumns) implements AutoCloseable {
        public PlanningChunkCompiler compiler(ChunkPlanCompiler compiler,List<Bounds> structures,CompileWindowMemory memory) {
            return new PlanningChunkCompiler(realization,compiler,terrain,structures,memory,coverage);
        }
        @Override public void close(){realization.close();}
    }
    private final List<SegmentPlan> plans;
    private final List<Bounds> structures;
    private final PlannerScheduler scheduler;
    private final RealizationWindow realization;
    private final Runnable active;
    private final java.util.function.BiConsumer<Integer, Integer> progress;
    private final long started=System.nanoTime();
    private int passes,inspected,added;

    private StartupCapEvidence(List<SegmentPlan> plans,List<Bounds> structures,PlannerScheduler scheduler,
            RealizationWindow realization,Runnable active,java.util.function.BiConsumer<Integer,Integer> progress) {
        this.plans=List.copyOf(plans);this.structures=List.copyOf(structures);this.scheduler=scheduler;
        this.realization=realization;this.active=active;this.progress=progress;
    }

    public static CompletableFuture<Prepared> prepare(List<SegmentPlan> plans,FineHeightIndex terrain,List<Bounds> structures,
            RealizationPlanner realization,PlannerScheduler scheduler,Runnable active) {
        return prepare(plans,terrain,structures,realization,scheduler,active,(done,total)->{});
    }

    /** 补采可能触发复核，阶段计数只表示本轮真正检查过的分段。 */
    public static CompletableFuture<Prepared> prepare(List<SegmentPlan> plans,FineHeightIndex terrain,List<Bounds> structures,
            RealizationPlanner realization,PlannerScheduler scheduler,Runnable active,
            java.util.function.BiConsumer<Integer,Integer> progress) {
        var work=new StartupCapEvidence(plans,structures,scheduler,realization.openWindow(),active,progress);
        // 预检不能占住规划pump，否则后续补采正在等待的也是这条线程。
        var result=work.pass(terrain,Map.of());
        result.whenComplete((value,error)->{if(error!=null)work.realization.close();});
        return result;
    }

    private CompletableFuture<Prepared> pass(FineHeightIndex terrain,Map<Long,List<ImportedSamplingFootprint.Attachment>> extra) {
        return CompletableFuture.supplyAsync(()->inspect(terrain,extra)).thenComposeAsync(next->{
            active.run();
            var targets=SupplementalColumns.missing(next.boxes(),terrain);
            if(targets.isEmpty()) {
                // 已有列也可能首次进入这个单元的captureOwners；归属变化后仍须按最终输入复核一次。
                if(!next.extra().equals(extra))return pass(terrain,next.extra());
                GreatWallMod.LOGGER.info("[GreatWall:收口补采] 预检完成 passes={} plans={} addedColumns={} elapsedMs={}",
                        passes,inspected,added,(System.nanoTime()-started)/1_000_000);
                // 缓存失败只影响下次复用，不得丢弃本次已齐全的施工证据。
                return scheduler.finishFineSampleCache().thenApply(saved->new Prepared(terrain,next.coverage(),realization,passes,inspected,added));
            }
            GreatWallMod.LOGGER.info("[GreatWall:收口补采] 仅补缺失地形列 pass={} missing={} retained={}",passes,targets.size(),terrain.size());
            return scheduler.requireSupplementalColumns(targets,Priority.PLAYER_PREFETCH).thenComposeAsync(columns->{
                active.run();added+=columns.size();
                // 新附件可能把邻列带入保护判定；补采后再次检查，直到没有新的缺列，而非冻结首次缺口。
                return pass(terrain.withSupplemental(columns),next.extra());
            });
        });
    }

    private record Inspection(Map<Long,List<ImportedSamplingFootprint.Attachment>> extra,CoveragePlanner coverage,List<Bounds> boxes) {}

    private Inspection inspect(FineHeightIndex terrain,Map<Long,List<ImportedSamplingFootprint.Attachment>> previous) {
        active.run();passes++;
        var coverage=new CoveragePlanner(realization.templateVersion(),terrain.exact(),previous);
        // 老横截面已包含完整附件带；新版精确窄带才需要动态扩列。
        if(!terrain.exact())return new Inspection(previous,coverage,List.of());
        var first=plans.getFirst().geometry().key();
        var owners=PlanningOwnerIndex.prepare(new PlanWindow(plans),first.recipeHash(),first.dimension(),coverage);
        var provider=new PlanningColumnProvider(terrain);
        var context=new PlanningCompileContext(terrain,structures);
        var seen=Collections.newSetFromMap(new IdentityHashMap<PlanningOwnerIndex.CellWork,Boolean>());
        var captures=new IdentityHashMap<SegmentPlan,Set<ChunkKey>>();
        var firstCell=new IdentityHashMap<SegmentPlan,PlanningOwnerIndex.CellWork>();
        var ranges=new IdentityHashMap<SegmentPlan,ArcInterval>();
        var breaks=new ArrayList<BreakInterval>();
        for(var owner:owners)for(var cell:owner.cells()) {
            if(!seen.add(cell))continue;
            firstCell.putIfAbsent(cell.plan(),cell);
            captures.computeIfAbsent(cell.plan(),ignored->new LinkedHashSet<>()).addAll(cell.captureOwners());
            long from=cell.cell().arcCell()*64L*256,to=from+64L*256;
            ranges.merge(cell.plan(),new ArcInterval(from,to),(a,b)->new ArcInterval(Math.min(a.fromQ8(),b.fromQ8()),Math.max(a.toQ8(),b.toQ8())));
        }
        progress.accept(0,plans.size());
        for(int i=0;i<plans.size();i++) {
            var plan=plans.get(i);
            var cell=firstCell.get(plan);
            if(cell!=null) {
                active.run();inspected++;
                // 整段只投影一次；只有检查返回后才对外计为完成。
                breaks.addAll(realization.inspectPlanBreaks(context.input(cell,provider.columnsFor(captures.get(plan))),ranges.get(plan)));
            }
            progress.accept(i+1,plans.size());
        }
        // 先合并跨64格单元的片段，否则会把单元缝当成额外收口。
        var merged=GroundExitResolver.merge(breaks);
        var result=new HashMap<Long,List<ImportedSamplingFootprint.Attachment>>();
        var boxes=new ArrayList<Bounds>();
        for(var plan:plans) {
            var all=new LinkedHashSet<>(previous.getOrDefault(plan.geometry().key().index(),List.of()));
            all.addAll(ImportedSamplingFootprint.realizedCaps(plan,merged));
            var attachments=List.copyOf(all);
            if(!attachments.isEmpty())result.put(plan.geometry().key().index(),attachments);
            attachments.forEach(a->boxes.add(a.bounds()));
        }
        var frozen=Map.copyOf(result);
        return new Inspection(frozen,new CoveragePlanner(realization.templateVersion(),true,frozen),List.copyOf(boxes));
    }
}
