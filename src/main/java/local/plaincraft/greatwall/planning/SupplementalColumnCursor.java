package local.plaincraft.greatwall.planning;

import java.util.*;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.terrain.FineSampleCache;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;

/** 游标始终留在既有原生通道，补列与初采共用REFERENCE口径和原语预算。 */
final class SupplementalColumnCursor {
    private final List<WallFinePlanner.Target> targets;
    private final FineSampleCache cache;
    private final List<WallFinePlanner.FineColumn> output=new ArrayList<>();
    private final List<WallFinePlanner.FineColumn> probed=new ArrayList<>();
    private int index;
    private NaturalTerrainOracle.ColumnCursor column;
    SupplementalColumnCursor(List<WallFinePlanner.Target> targets,FineSampleCache cache) {
        this.targets=targets;this.cache=cache;
    }
    boolean advance(NaturalTerrainOracle oracle,int limit) {
        for(int used=0;used<limit&&index<targets.size();used++) {
            var target=targets.get(index);
            if(column==null) {
                var cached=cache.get(target.x(),target.z());
                if(cached!=null){output.add(target.resolve(cached));index++;}
                else column=oracle.begin(target.x(),target.z(),SampleKind.REFERENCE);
            } else if(!column.done())oracle.advance(column,1);
            else {
                var resolved=target.resolve(oracle.finish(column));
                output.add(resolved);probed.add(resolved);column=null;index++;
            }
        }
        return index==targets.size();
    }
    List<WallFinePlanner.FineColumn> result(){return List.copyOf(output);}
    // 缓存命中不算本轮实测，否则重开时又把全部旧列复制进待写批次。
    List<WallFinePlanner.FineColumn> probed(){return List.copyOf(probed);}
}
