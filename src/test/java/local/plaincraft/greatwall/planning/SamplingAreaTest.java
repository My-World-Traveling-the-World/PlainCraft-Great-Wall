package local.plaincraft.greatwall.planning;

import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 用逐列枚举与扫描线对拍，覆盖折返、附件重复和跨段重叠，避免计数函数自证。 */
class SamplingAreaTest {
    @Test void rectanglesMatchIndependentEnumeration() {
        var random = new Random(9282026);
        for (int trial=0; trial<100; trial++) {
            var boxes = new ArrayList<Bounds>();
            var cells = new HashSet<Long>();
            for (int i=0;i<30;i++) {
                int x=random.nextInt(50)-25,z=random.nextInt(50)-25;
                var b=new Bounds(x,0,z,x+random.nextInt(1,15),1,z+random.nextInt(1,15));
                boxes.add(b);
                for (int a=b.minX();a<b.maxXExclusive();a++) for (int c=b.minZ();c<b.maxZExclusive();c++)
                    cells.add(key(a,c));
            }
            assertEquals(cells.size(),SamplingArea.count(boxes));
            Collections.reverse(boxes);
            assertEquals(cells.size(),SamplingArea.count(boxes));
        }
        assertEquals(0,SamplingArea.count(List.of()));
        assertEquals(10_000_000_000L,SamplingArea.count(List.of(new Bounds(-50000,0,-50000,50000,1,50000))));
    }

    @Test void fullWindowMatchesActualSamplingSectionsAndDoesNotCountOverlapsTwice() {
        var points=List.of(new Point2q(-256,0),new Point2q(0,256),new Point2q(256,0),new Point2q(0,-256));
        var footprint=new ImportedSamplingFootprint(Map.of(1,List.of(new Bounds(-30,0,-16,32,1,16)),
                3,List.of(new Bounds(-30,0,-16,32,1,16))));
        var path=new ImportedSamplePath(null,points,List.of(0L,256L,512L,768L),footprint);
        var cells=new HashSet<Long>();
        for (int i=0;i<points.size();i++) for (var t:footprint.section(path,i,i*256L)) cells.add(key(t.x(),t.z()));
        var area=new SamplingArea();
        area.add(path); area.add(path);
        assertTrue(cells.size()>1000, "先证明实际有非空墙带和宽附件");
        assertEquals(cells.size(),area.finish());
        assertEquals(0,area.finish(), "结案释放矩形，不跨轮累计");
    }
    private static long key(int x,int z) { return (long)x<<32 | (z & 0xffffffffL); }
}
