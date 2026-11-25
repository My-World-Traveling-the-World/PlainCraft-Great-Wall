package local.plaincraft.greatwall.component;

import java.util.*;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.Point3i;

/** 证据只冻结一次，构件重试可复用；不能把调用方只读视图误当独立存储。 */
public final class EntranceColumns extends AbstractMap<Point3i,SurfaceProbe.ColumnView> {
    private final Map<Point3i,SurfaceProbe.ColumnView> columns;
    private EntranceColumns(Map<Point3i,SurfaceProbe.ColumnView> source) {
        var copy=new HashMap<Point3i,SurfaceProbe.ColumnView>();
        source.forEach((key,value)->copy.put(Objects.requireNonNull(key),Objects.requireNonNull(value)));
        columns=Collections.unmodifiableMap(copy);
    }
    public static Map<Point3i,SurfaceProbe.ColumnView> copyOf(Map<Point3i,SurfaceProbe.ColumnView> source) {
        return source instanceof EntranceColumns?source:new EntranceColumns(source);
    }
    @Override public SurfaceProbe.ColumnView get(Object key){return columns.get(key);}
    // AbstractMap的默认containsKey逐项遍历；墙带完整性逐列调用时会把哈希查询退化为平方级扫描。
    @Override public boolean containsKey(Object key){return columns.containsKey(key);}
    @Override public int size(){return columns.size();}
    @Override public Set<Entry<Point3i,SurfaceProbe.ColumnView>> entrySet(){return columns.entrySet();}
}
