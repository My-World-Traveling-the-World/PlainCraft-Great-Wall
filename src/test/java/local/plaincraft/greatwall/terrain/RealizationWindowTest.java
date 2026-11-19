package local.plaincraft.greatwall.terrain;

import java.util.*;
import local.plaincraft.greatwall.component.WallSection;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 用有效计划和真实列合同核对旧五表估算公式，命中不能重复记账，清理不能残留隐形引用。 */
class RealizationWindowTest {
    static final String H="a".repeat(64);
    @Test void everyMemoInsertionAndIdentityKeyUsesTheOriginalEstimateWithoutCountingHitsAgain(){
        var p=plan();var source=new RealizationPlanner(new SurfaceProbe(),Map.of());var window=source.openWindow();
        var site=new SiteRecord(new SiteId("fixture",SiteKind.WALL,0),0,
                new Frame(new Point2q(0,0),1<<30,0,64,false),new Bounds(0,0,0,1,1,1),SiteState.PLANNED,Optional.empty(),Optional.empty());
        var water=new WaterEvidence(new ArcInterval(0,256),WaterClass.NARROW,256,256,63,List.of(),List.of(),H);
        assertEquals(2,window.stations(p,()->p.stations().stations()).size());
        assertEquals(1,window.sites(p,()->List.of(site)).size());assertEquals(1,window.waters(p,()->List.of(water)).size());
        var contract=WallSection.contract(p.stations().stations(),1,0);
        assertEquals(contract,window.contract(p,1,()->contract));
        var key=new CellKey(H,"minecraft:overworld",0);assertEquals(H,window.evidence(p,key,()->H));
        assertEquals(1280,window.windowMemoStats().derivedBytes());
        window.stations(p,()->fail("缓存命中不得重算"));window.sites(p,()->fail("缓存命中不得重算"));
        window.waters(p,()->fail("缓存命中不得重算"));window.contract(p,1,()->fail("缓存命中不得重算"));
        window.evidence(p,key,()->fail("缓存命中不得重算"));
        assertEquals(1280,window.windowMemoStats().derivedBytes());
        window.contract(p,2,()->WallSection.contract(p.stations().stations(),2,0));
        window.evidence(p,new CellKey(H,"minecraft:overworld",1),()->H);
        assertEquals(1280+192+256,window.windowMemoStats().derivedBytes());
        var sameValue=new SegmentPlan(p.geometry(),p.prefix(),p.stations(),p.sites(),p.hash());
        assertEquals(p,sameValue);assertNotSame(p,sameValue);
        window.stations(sameValue,()->sameValue.stations().stations());
        assertEquals(1280+192+256+192,window.windowMemoStats().derivedBytes());
        assertEquals(0,source.windowMemoStats().total());
        window.close();assertEquals(0,window.windowMemoStats().total());
        window.contract(p,1,()->contract);assertEquals(64+192,window.windowMemoStats().derivedBytes());
        window.evidence(p,key,()->H);assertEquals(64+192+64+256,window.windowMemoStats().derivedBytes());
        window.close();window.close();assertEquals(0,window.windowMemoStats().total());
    }
    @Test void failedComputationDoesNotChargeAValueOrDoubleChargeTheContainer(){
        var window=new RealizationPlanner(new SurfaceProbe(),Map.of()).openWindow();var p=plan();
        var key=new CellKey(H,"minecraft:overworld",0);var cause=new IllegalStateException("摘要计算失败");
        assertSame(cause,assertThrows(IllegalStateException.class,()->window.evidence(p,key,()->{throw cause;})));
        assertEquals(64,window.windowMemoStats().derivedBytes());
        window.evidence(p,key,()->H);assertEquals(320,window.windowMemoStats().derivedBytes());
        window.close();assertEquals(0,window.windowMemoStats().total());
    }
    static SegmentPlan plan(){
        var a=new Boundary(0,new Point2q(0,0),64,H);var b=new Boundary(1,new Point2q(64*256,0),64,H);
        var geometry=new SegmentGeometry(new SegmentKey(H,"minecraft:overworld",0),List.of(a.point(),b.point()),
                List.of(0L,64*256L),List.of(),Optional.empty(),a,b,0,0,H);
        return new SegmentPlan(geometry,new PrefixSummary(0,0,Optional.empty(),H),
                new StationTable(List.of(new Station(0,a.point(),64,0),new Station(64*256,b.point(),64,0)),List.of(),H),List.of(),H);
    }
}
