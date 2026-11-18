package local.plaincraft.greatwall.terrain;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 地基坐标常有相同Long哈希，避免MapN开放寻址聚集；私有快照可安全跨cell复用。 */
public final class FoundationFloorSnapshot extends AbstractMap<Long,Integer> {
    // 大部分独立构件不传地基；共用空表也能避免为这些入口反复分配包装对象。
    private static final Map<Long, Integer> EMPTY = new FoundationFloorSnapshot(Map.of());
    private final Map<Long,Integer> values;

    private FoundationFloorSnapshot(Map<Long,Integer> source) {
        var copy=new HashMap<Long,Integer>();
        source.forEach((key,value)->copy.put(Objects.requireNonNull(key),Objects.requireNonNull(value)));
        values=Collections.unmodifiableMap(copy);
    }

    public static Map<Long,Integer> copyOf(Map<Long,Integer> source) {
        Objects.requireNonNull(source);
        // 仅复用本类拥有的只读存储，不能把调用者的unmodifiableMap误当独立快照。
        if (source instanceof FoundationFloorSnapshot) return source;
        return source.isEmpty() ? EMPTY : new FoundationFloorSnapshot(source);
    }

    @Override public Integer get(Object key) { return values.get(key); }
    @Override public boolean containsKey(Object key) { return values.containsKey(key); }
    @Override public int size() { return values.size(); }
    @Override public Set<Entry<Long,Integer>> entrySet() { return values.entrySet(); }
}
