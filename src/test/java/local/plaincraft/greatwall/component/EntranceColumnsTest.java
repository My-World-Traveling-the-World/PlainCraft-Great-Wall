package local.plaincraft.greatwall.component;

import java.util.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 复用必须以真正独立快照为前提，外部只读包装仍可能被原持有方修改。 */
class EntranceColumnsTest {
    @Test void sourceViewMutationCannotChangeEvidenceAndRetryReusesFrozenStorage(){
        var source=new HashMap<Point3i,SurfaceProbe.ColumnView>();
        var position=new Point3i(1,0,2);
        var column=new GroundEntranceContract.ClearSkyColumn(new ProbeResult(ProbeKind.SUPPORTED,64,
                OptionalInt.empty(),OptionalInt.empty(),0,TemplateFixtures.HASH));
        source.put(position,column);
        var frozen=EntranceColumns.copyOf(Collections.unmodifiableMap(source));
        var bounds=new DimensionBounds(-64,320,30_000_000);
        var first=new GroundEntranceContract.Evidence(GroundEntranceContract.Source.DURABLE_T0,bounds,frozen);
        source.clear();assertEquals(1,first.columns().size());assertSame(column,first.columns().get(position));
        var next=new GroundEntranceContract.Evidence(first.source(),bounds,first.columns());
        assertSame(first.columns(),next.columns());
        assertThrows(UnsupportedOperationException.class,()->next.columns().clear());
        assertThrows(UnsupportedOperationException.class,()->next.columns().entrySet().iterator().next().setValue(column));
    }

    @Test void membershipAndKeySetMembershipUseTheHashIndexInsteadOfScanningEntries() throws Exception {
        var source=new HashMap<Point3i,SurfaceProbe.ColumnView>();
        var column=new GroundEntranceContract.ClearSkyColumn(new ProbeResult(ProbeKind.SUPPORTED,64,
                OptionalInt.empty(),OptionalInt.empty(),0,TemplateFixtures.HASH));
        for(int x=-512;x<512;x++)source.put(new Point3i(x,0,-17),column);
        var frozen=EntranceColumns.copyOf(source);
        var field=EntranceColumns.class.getDeclaredField("columns");field.setAccessible(true);
        @SuppressWarnings("unchecked") var backing=(Map<Point3i,SurfaceProbe.ColumnView>)field.get(frozen);
        var lookups=new java.util.concurrent.atomic.AtomicInteger();var scans=new java.util.concurrent.atomic.AtomicInteger();
        // 仅测试包装内部不可变表，保持真实Point3i的equals/hashCode；不用不对称假键或墙钟阈值背书。
        field.set(frozen,new AbstractMap<Point3i,SurfaceProbe.ColumnView>() {
            public SurfaceProbe.ColumnView get(Object key){lookups.incrementAndGet();return backing.get(key);}
            public boolean containsKey(Object key){lookups.incrementAndGet();return backing.containsKey(key);}
            public Set<Entry<Point3i,SurfaceProbe.ColumnView>> entrySet(){scans.incrementAndGet();return backing.entrySet();}
        });
        assertTrue(frozen.containsKey(new Point3i(-511,0,-17)));
        assertFalse(frozen.containsKey(new Point3i(4096,0,-17)));
        assertFalse(frozen.containsKey(null));
        assertTrue(frozen.keySet().contains(new Point3i(511,0,-17)));
        assertEquals(4,lookups.get(),"每次存在性查询必须进入实际哈希索引");
        assertEquals(0,scans.get(),"存在性查询不能遍历entrySet");
    }
}
