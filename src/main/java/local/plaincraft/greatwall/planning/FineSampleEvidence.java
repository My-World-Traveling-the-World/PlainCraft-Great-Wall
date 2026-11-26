package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import local.plaincraft.greatwall.planning.WallFinePlanner.FineColumn;

/** 只汇总有序初采截面；磁盘提交由初采和补采共用的会话对象拥有。 */
final class FineSampleEvidence {
    private final FineSamplePersistence persistence;
    private final TreeMap<Long, List<FineColumn>> slices = new TreeMap<>();
    private boolean sealed;

    FineSampleEvidence(FineSamplePersistence persistence) {
        this.persistence = persistence;
    }

    synchronized boolean accept(long order, List<FineColumn> columns, List<FineColumn> probed) {
        // 强制结案或关闭之后到达的切片不能修改已选定的样本集合。
        if (sealed) return false;
        if (!persistence.accept(probed)) return false;
        slices.computeIfAbsent(order, ignored -> new ArrayList<>()).addAll(columns);
        return true;
    }

    FineHeightIndex finish() { return finish(false); }

    FineHeightIndex finish(boolean exact) {
        var ordered = new ArrayList<FineColumn>();
        synchronized (this) {
            if (sealed) return null;
            sealed = true;
            // 按路线次序保留重复列：地基按截面分组，不能从 XZ 去重后的结果重建。
            for (var slice : slices.values()) ordered.addAll(slice);
            slices.clear();
        }
        // 后续只读局部快照，停服不必等坐标索引构建完。
        return FineHeightIndex.of(ordered, exact);
    }

    synchronized void discardSlices() {
        // 异常结案不建索引，但仍保留能用于重开的实测列。
        sealed = true;
        slices.clear();
    }

    synchronized void close() {
        discardSlices();
    }
}
