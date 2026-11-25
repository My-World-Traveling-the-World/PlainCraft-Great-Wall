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
}
