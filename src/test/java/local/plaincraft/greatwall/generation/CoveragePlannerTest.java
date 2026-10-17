package local.plaincraft.greatwall.generation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.geometry.Rasterizer;
import org.junit.jupiter.api.Test;

class CoveragePlannerTest {
    private static final String H="a".repeat(64);
    @Test void rotatedFortReadDomainIncludesTheActualRasterGuardAndExternalApron(){
        var frame=new Frame(new Point2q(52736,243968),247565951,1044812234,87,false);
        var site=new SiteRecord(new SiteId("fixture",SiteKind.FORT,0),0,frame,
                new Bounds(177,54,924,235,95,982),SiteState.PLANNED,Optional.empty(),Optional.empty());
        var start=new Boundary(0,frame.center(),87,H);
        var end=new Boundary(1,new Point2q(269*256L,1202*256L),87,H);
        var geometry=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",0),
                List.of(start.point(),end.point()),List.of(0L,256L*256),List.of(),Optional.empty(),start,end,0,0,H);
        var stations=new StationTable(List.of(new Station(0,start.point(),87,0),new Station(256L*256,end.point(),87,0)),List.of(),H);
        var plan=new SegmentPlan(geometry,new PrefixSummary(0,0,Optional.empty(),H),stations,List.of(site),H);
        var owners=new HashSet<>(new CoveragePlanner().realizationOwners(new CellKey(H,"minecraft:overworld",0),
                new PlanWindow(List.of(plan))));
        var actual=Rasterizer.worldBounds(frame,new Bounds(-24,-33,-24,24,8,24));
        var apron=new Bounds(actual.minX()-1,actual.minY(),actual.minZ()-1,
                actual.maxXExclusive()+1,actual.maxYExclusive(),actual.maxZExclusive()+1);
        assertTrue(owners.containsAll(Rasterizer.owners("minecraft:overworld",apron)),
                "all potential exit proof columns must be captured before realization");
        assertTrue(owners.contains(new ChunkKey("minecraft:overworld",10,59)),
                "retained seed17 witness: x=175 falls in owner10, not the old owner11 floor");
        assertTrue(owners.size()<=256,"the existing finite capture owner budget is not increased");
    }
    @Test void canonicalCoverageIncludesLowerGuardUpperSentinelAndNegativePages() {
        var stations=new StationTable(List.of(new Station(0,new Point2q(-256,0),-20,0),
                new Station(64L*256,new Point2q(63L*256,0),-20,0)),List.of(),H);
        var boundary=new Boundary(0,new Point2q(-256,0),-20,H);
        var geometry=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",0),
                stations.stations().stream().map(Station::point).toList(),List.of(0L,64L*256),List.of(),Optional.empty(),boundary,boundary,0,0,H);
        var plan=new SegmentPlan(geometry,new PrefixSummary(0,0,Optional.empty(),H),stations,List.of(),H);
        var window=new PlanWindow(List.of(plan));var compiler=new CoveragePlanner();var key=new ChunkKey("minecraft:overworld",-1,0);
        var c=compiler.forChunk(key,window,new DimensionBounds(-64,8192,29_999_000));
        assertEquals(-55,c.readBounds().minY());assertEquals(-12,c.readBounds().maxYExclusive());
        assertEquals(List.of(-128),c.tileBases());
        assertEquals(c,compiler.forChunk(key,new PlanWindow(List.of(plan,plan)),new DimensionBounds(-64,8192,29_999_000)));
    }

    @Test void triggerDomainUsesTheSameOwnerFunctionAsDurableCapture(){
        var window=window(128);
        var planner=new CoveragePlanner();
        var potentialOwners=new TreeSet<>(Comparator.comparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z));
        for(long cell=0;cell<=2;cell++) {
            var key=new CellKey(H,"minecraft:overworld",cell);
            for(var owner:planner.realizationOwners(key,window)) {
                potentialOwners.add(owner);
                assertTrue(planner.cellsForChunk(owner,window).contains(key),
                        "every captured station/site owner remains dispatchable: "+owner+" cell="+cell);
            }
        }
        for(var owner:potentialOwners) {
            for(var cell:planner.cellsForChunk(owner,window))
                assertTrue(planner.realizationOwners(cell,window).contains(owner),
                        "dispatch must be a subset of durable capture owners");
        }
        var fringe=new ChunkKey("minecraft:overworld",2,6);
        assertTrue(planner.cellsForChunk(fringe,window).isEmpty(),
                "the old lateral-halo fringe is explicitly a non-owner after A+C");
    }

    @Test void everyTemplateWaterGateAndExitEnvelopeOwnerRemainsDispatchableInFiveOrientations() {
        var templates=local.plaincraft.greatwall.component.S1TemplateSet.create(0);
        for(int[] direction:List.of(new int[]{1<<30,0},new int[]{0,1<<30},
                new int[]{-1<<30,0},new int[]{759250125,759250125},
                new int[]{247565951,1044812234})) {
            for(var entry:templates.entrySet()) {
                if(entry.getKey()==SiteKind.WATER_GATE)continue;
                var base=window(256).plans().getFirst();
                long s=128L*256;
                var frame=new Frame(new Point2q(s,0),direction[0],direction[1],84,false);
                var site=new SiteRecord(new SiteId("fixture",entry.getKey(),0),s,frame,
                        new Bounds(0,49,-64,256,92,64),SiteState.PLANNED,Optional.empty(),Optional.empty());
                var input=new ComponentInput(site,List.of(),Map.of(),new DecaySample(DecayState.INTACT,0,20),
                        PaletteFamily.STONE,false,List.of(),List.of());
                var actual=Rasterizer.worldBounds(frame,entry.getValue().localBounds(input));
                var plan=new SegmentPlan(base.geometry(),base.prefix(),base.stations(),List.of(site),H);
                var plans=new PlanWindow(List.of(plan));
                var planner=new CoveragePlanner();
                var cell=new CellKey(H,"minecraft:overworld",2);
                for(var owner:Rasterizer.owners("minecraft:overworld",actual)) {
                    assertTrue(planner.realizationOwners(cell,plans).contains(owner),
                            entry.getKey()+" potential write owner is captured: "+owner);
                    assertTrue(planner.cellsForChunk(owner,plans).contains(cell),
                            entry.getKey()+" potential write owner is dispatched: "+owner);
                }
            }
        }
    }

    @Test void dynamicWaterGateAndBothExitDirectionsAreCoveredWithoutSyntheticPlannedSites() {
        var templates=local.plaincraft.greatwall.component.S1TemplateSet.create(0);
        for(int[] direction:List.of(new int[]{1<<30,0},new int[]{0,1<<30},
                new int[]{-1<<30,0},new int[]{759250125,759250125},
                new int[]{247565951,1044812234})) {
            var stations=new ArrayList<Station>();
            for(int s=0;s<=256;s+=4)stations.add(new Station(s*256L,
                    new Point2q(local.plaincraft.greatwall.geometry.FixedMath.mulDiv(s*256L,direction[0],1L<<30),
                            local.plaincraft.greatwall.geometry.FixedMath.mulDiv(s*256L,direction[1],1L<<30)),84,0));
            var water=new WaterEvidence(new ArcInterval(104*256L,152*256L),WaterClass.NARROW,
                    48*256L,48*256L,66,List.of(),List.of(),H);
            var start=new Boundary(0,stations.getFirst().point(),84,H);
            var end=new Boundary(1,stations.getLast().point(),84,H);
            var geometry=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",0),
                    List.of(start.point(),end.point()),List.of(0L,256*256L),List.of(water),
                    Optional.of(new Terminal(0,256*256L,TerminalKind.WORLD_LIMIT)),start,end,0,0,H);
            var plans=new PlanWindow(List.of(new SegmentPlan(geometry,new PrefixSummary(0,0,Optional.empty(),H),
                    new StationTable(stations,List.of(),H),List.of(),H)));
            var planner=new CoveragePlanner();
            var cell=new CellKey(H,"minecraft:overworld",2);
            for(SiteKind kind:List.of(SiteKind.WATER_GATE,SiteKind.END_CAP))for(boolean reverse:List.of(false,true)) {
                var frame=new Frame(stations.get(32).point(),reverse?-direction[0]:direction[0],
                        reverse?-direction[1]:direction[1],84,false);
                var site=new SiteRecord(new SiteId("fixture",kind,0),128*256L,frame,
                        new Bounds(-256,49,-256,256,92,256),SiteState.PLANNED,Optional.empty(),Optional.empty());
                var input=new ComponentInput(site,stations,Map.of(),new DecaySample(DecayState.INTACT,0,20),
                        PaletteFamily.STONE,false,kind==SiteKind.WATER_GATE?List.of(water):List.of(),List.of());
                var local=templates.get(kind).localBounds(input);
                var envelope=Rasterizer.worldBounds(frame,local);
                var writes=new HashSet<ChunkKey>();
                for(int x=envelope.minX();x<envelope.maxXExclusive();x++)
                    for(int z=envelope.minZ();z<envelope.maxZExclusive();z++) {
                        var p=Rasterizer.localCell(frame,new Point3i(x,84,z));
                        if(p.x()>=local.minX()&&p.x()<local.maxXExclusive()
                                &&p.z()>=local.minZ()&&p.z()<local.maxZExclusive())
                            writes.add(new ChunkKey("minecraft:overworld",Math.floorDiv(x,16),Math.floorDiv(z,16)));
                    }
                assertFalse(writes.isEmpty(),"the full inverse-rasterized template domain is exercised");
                for(var owner:writes) {
                    assertTrue(planner.realizationOwners(cell,plans).contains(owner),kind+" captured "+owner);
                    assertTrue(planner.cellsForChunk(owner,plans).contains(cell),kind+" dispatched "+owner);
                }
            }
        }
    }

    /**
     * 站点中心落在本 cell 的 owner 窗口之外、但**会被本 cell realize** 时，它的足迹 chunk 必须也是本
     * cell 的 owner。
     *
     * <p>{@code RealizationPlanner.resolveAccounted} 的站点准入窗口比 cell 宽 64 格
     * （{@code from-64*256 .. to+64*256}），而 owner 枚举过去只按中心是否落在 {@code [from,to]} 判断：
     * 于是存在一条 64 格宽的带——站点会被 realize，但它的足迹 chunk 从不进 owner 集，编译到该组件时
     * 那些 chunk 的列缺失，组件被 T0_MISSING 丢弃、墙上留洞，**而编译仍然报成功**。这条钉住
     * 「准入 ⊆ 覆盖」，并且约束 owner 预算不被提高。
     */
    @Test void aSiteRealizedPastTheCellWindowStillOwnsItsFootprintChunks(){
        var templates=local.plaincraft.greatwall.component.S1TemplateSet.create(0);
        // cell 0 的 owner 窗口是 [-192, 256] 格；准入窗口到 320 格。取 300 格：旧规则必漏、准入必中。
        long centre=300L*256;
        var base=window(1024).plans().getFirst();
        var frame=new Frame(new Point2q(centre,0),1<<30,0,84,false);
        var site=new SiteRecord(new SiteId("fixture",SiteKind.FORT,0),centre,frame,
                new Bounds(0,49,-64,320,92,64),SiteState.PLANNED,Optional.empty(),Optional.empty());
        var input=new ComponentInput(site,List.of(),Map.of(),new DecaySample(DecayState.INTACT,0,20),
                PaletteFamily.STONE,false,List.of(),List.of());
        var envelope=Rasterizer.worldBounds(frame,templates.get(SiteKind.FORT).localBounds(input));
        var plans=new PlanWindow(List.of(new SegmentPlan(base.geometry(),base.prefix(),base.stations(),
                List.of(site),H)));
        var owners=new HashSet<>(new CoveragePlanner().realizationOwners(
                new CellKey(H,"minecraft:overworld",0),plans));
        assertTrue(owners.containsAll(Rasterizer.owners("minecraft:overworld",envelope)),
                "中心在 owner 窗口外、足迹入窗的站点必须带来它的足迹 chunk（否则组件静默掉洞）");
        assertTrue(owners.size()<=256,"owner 预算不被提高");
    }

    private static PlanWindow window(int length) {
        var stations=new ArrayList<Station>();
        for(int x=0;x<=length;x+=4) stations.add(new Station(x*256L,new Point2q(x*256L,0),84,0));
        var start=new Boundary(0,new Point2q(0,0),84,H);
        var end=new Boundary(1,new Point2q(length*256L,0),84,H);
        var geometry=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",0),
                List.of(start.point(),end.point()),List.of(0L,length*256L),List.of(),
                Optional.of(new Terminal(0,length*256L,TerminalKind.WORLD_LIMIT)),start,end,0,0,H);
        var fort=new SiteRecord(new SiteId("fixture",SiteKind.FORT,0),0,
                new Frame(new Point2q(0,0),1<<30,0,84,false),
                new Bounds(-24,49,-24,24,92,24),SiteState.PLANNED,Optional.empty(),Optional.empty());
        return new PlanWindow(List.of(new SegmentPlan(geometry,
                new PrefixSummary(0,0,Optional.empty(),H),
                new StationTable(stations,List.of(),H),List.of(fort),H)));
    }
}
