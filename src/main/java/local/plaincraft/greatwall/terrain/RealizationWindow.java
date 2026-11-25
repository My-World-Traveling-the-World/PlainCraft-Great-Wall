package local.plaincraft.greatwall.terrain;

import java.util.*;
import local.plaincraft.greatwall.component.WallSection;
import java.util.function.Supplier;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.diagnostics.CompileWindowMemory;

/** 独占窗口拥有缓存和实现器视图；同一源可开多窗，互不替换可变状态。 */
public final class RealizationWindow implements AutoCloseable {
    final ImportedBuildingMemo buildings=new ImportedBuildingMemo();
    final ImportedProjectionMemo projections=new ImportedProjectionMemo();
    private final Map<SegmentPlan,List<Station>> stations=new IdentityHashMap<>();
    private final Map<SegmentPlan,List<SiteRecord>> sites=new IdentityHashMap<>();
    private final Map<SegmentPlan,List<WaterEvidence>> waters=new IdentityHashMap<>();
    private final Map<SegmentPlan,Map<CellKey,String>> evidence=new IdentityHashMap<>();
    private final Map<SegmentPlan,Map<Long,WallSection.ColumnContract>> contracts=new IdentityHashMap<>();
    private final RealizationPlanner planner;
    private long derivedBytes;

    RealizationWindow(RealizationPlanner source){planner=source.forkForWindow(this);}
    public RealizationCell resolve(RealizationPlanner.Input input){return planner.resolve(input);}
    public GroundExitDecision.Slice inspect(RealizationPlanner.Input input){return planner.inspect(input);}
    /** 补采前不写地形摘要缓存，最终编译才能对新增列重新取证。 */
    public List<BreakInterval> inspectBreaks(RealizationPlanner.Input input){return planner.inspectBreaks(input);}
    public int templateVersion(){return planner.templateVersion();}
    public ImportedBuildingMemo.Stats buildingMemoStats(){return buildings.stats();}
    public ImportedProjectionMemo.Stats projectionMemoStats(){return projections.stats();}

    /** 写入时增量记账，检查点不再扫描随段数增长的五张派生表；估算口径保持原常数。 */
    public CompileWindowMemory.Retained windowMemoStats(){
        return new CompileWindowMemory.Retained(buildings.stats().bytes(),projections.stats().bytes(),derivedBytes);
    }
    List<Station> stations(SegmentPlan plan,Supplier<List<Station>> compute){return list(stations,plan,compute,64);}
    List<SiteRecord> sites(SegmentPlan plan,Supplier<List<SiteRecord>> compute){return list(sites,plan,compute,128);}
    List<WaterEvidence> waters(SegmentPlan plan,Supplier<List<WaterEvidence>> compute){return list(waters,plan,compute,256);}
    private <T> List<T> list(Map<SegmentPlan,List<T>> cache,SegmentPlan plan,Supplier<List<T>> compute,long weight){
        var cached=cache.get(plan);if(cached!=null)return cached;
        var computed=compute.get();cache.put(plan,computed);derivedBytes+=64+weight*computed.size();return computed;
    }
    WallSection.ColumnContract contract(SegmentPlan plan,long key,Supplier<WallSection.ColumnContract> compute){
        var columns=contracts.get(plan);
        if(columns==null){columns=new HashMap<>();contracts.put(plan,columns);derivedBytes+=64;}
        var cached=columns.get(key);if(cached!=null)return cached;
        var computed=compute.get();columns.put(key,computed);derivedBytes+=192;return computed;
    }
    String evidence(SegmentPlan plan,CellKey key,Supplier<String> compute){
        var cells=evidence.get(plan);
        if(cells==null){cells=new HashMap<>();evidence.put(plan,cells);derivedBytes+=64;}
        var cached=cells.get(key);if(cached!=null)return cached;
        var computed=compute.get();cells.put(key,computed);derivedBytes+=256;return computed;
    }
    /** 成功/失败都释放全部计划引用；清理后可在下一次同步调用重新计算，产物不依赖缓存。 */
    public void clearCompiledBuildings(){close();}
    @Override public void close(){
        buildings.clear();projections.clear();stations.clear();sites.clear();waters.clear();
        evidence.clear();contracts.clear();derivedBytes=0;
    }
}
