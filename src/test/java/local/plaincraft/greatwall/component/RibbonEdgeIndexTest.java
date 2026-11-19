package local.plaincraft.greatwall.component;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;

/** 扫描参考永远保留在测试侧；同距、折返、定点舍入和端外延长都逐字段比较。 */
class RibbonEdgeIndexTest {
    @Test void randomFoldedRoutesAndNegativeColumnsMatchFrozenScan() {
        var random = new Random(768902);
        for (int trial = 0; trial < 12; trial++) {
            var stations = new ArrayList<Station>();
            long x = -20000, z = -10000;
            for (int i = 0; i < 180; i++) {
                if (i % 11 != 0) { x += random.nextInt(2049)-1024; z += random.nextInt(2049)-1024; }
                stations.add(new Station(i*1024L,new Point2q(x,z),64+random.nextInt(20),0));
            }
            var frozen = new FrozenImportedRibbonProjection(stations,List.of());
            var indexed = new ImportedRibbonProjection(stations,List.of());
            for (int i = 0; i < 10000; i++) {
                int px = random.nextInt(300)-150, pz = random.nextInt(300)-150;
                assertEquals(frozen.contract(px,pz),indexed.contract(px,pz), "trial="+trial+" column="+px+","+pz);
            }
        }
    }

    @Test void exactCrossingTiesPreferOriginalOrderAndEndsExtrapolate() {
        var stations = List.of(
                new Station(0,new Point2q(-4992,128),64,0),
                new Station(10000,new Point2q(5248,128),84,0),
                new Station(20000,new Point2q(5248,-4992),50,0),
                new Station(30000,new Point2q(128,-4992),60,0),
                new Station(40000,new Point2q(128,5248),90,0));
        var frozen = new FrozenImportedRibbonProjection(stations,List.of());
        var indexed = new ImportedRibbonProjection(stations,List.of());
        for (int x=-25;x<=25;x++) for(int z=-25;z<=25;z++)
            assertEquals(frozen.contract(x,z), indexed.contract(x,z));
        assertEquals(5000,indexed.contract(0,0).globalSQ8());
        assertTrue(indexed.contract(-25,0).globalSQ8()<0);
    }

    @Test void emptyAndDegenerateEdgesReturnSameNull() {
        var stations=List.of(new Station(0,new Point2q(0,0),64,0),new Station(256,new Point2q(0,0),65,0));
        assertNull(new ImportedRibbonProjection(stations,List.of()).contract(0,0));
    }

    @Test void longRouteReducesActualLeafChecksIncludingNodeDiagnostics() {
        var stations = new ArrayList<Station>();
        for (int i=0;i<=1024;i++) stations.add(new Station(i*1024L,new Point2q(i*1024L+128,128),64,0));
        var frozen = new FrozenImportedRibbonProjection(stations,List.of());
        var indexed = new ImportedRibbonProjection(stations,List.of());
        long[] visits = new long[2];
        for (int x=-8;x<4104;x++) for(int z=-6;z<=6;z++)
            assertEquals(frozen.contract(x,z), indexed.contract(x,z,visits));
        long scan = 4112L*13*1024;
        assertTrue(visits[0]<scan/8, "计数是叶边检查，不能把树节点访问冒充免费");
        assertTrue(visits[1]>0);
        System.out.println("RIBBON_INDEX columns="+(4112*13)+" scanEdges="+scan+" leafEdges="+visits[0]+" nodes="+visits[1]);
    }
}
