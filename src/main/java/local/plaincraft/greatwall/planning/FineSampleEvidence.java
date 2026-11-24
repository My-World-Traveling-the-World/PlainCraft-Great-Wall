package local.plaincraft.greatwall.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.planning.WallFinePlanner.FineColumn;
import local.plaincraft.greatwall.terrain.FineSampleCache;

/** 串行化切片交付、结案及停服保存，防止同一个缓存临时文件被两个线程同时写。 */
final class FineSampleEvidence {
    private final FineSampleCache cache;
    private final TreeMap<Long, List<FineColumn>> slices = new TreeMap<>();
    private List<FineColumn> pending = new ArrayList<>();
    private boolean sealed;

    FineSampleEvidence(FineSampleCache cache) {
        this.cache = cache;
    }

    synchronized boolean accept(long order, List<FineColumn> columns, List<FineColumn> probed) {
        // 强制结案或关闭之后到达的切片不能修改已选定的样本集合。
        if (sealed) return false;
        slices.computeIfAbsent(order, ignored -> new ArrayList<>()).addAll(columns);
        if (cache.persistent()) pending.addAll(probed);
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
            persist();
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
        try {
            // 正常写入已成功时 pending 为空；只重试失败或尚未结案的数据。
            if (!pending.isEmpty()) persist();
        } finally {
            pending = List.of();
        }
    }

    private void persist() {
        GreatWallMod.LOGGER.info("[GreatWall:S1C] FINE_SAMPLE_CACHE 精采样待保存 hits={} misses={} cached={} walked={} persistent={}",
                cache.hits(), cache.misses(), cache.size(), pending.size(), cache.persistent());
        // IO 失败要留下原样本；缓存不影响本次从内存生成的施工计划。
        if (pending.isEmpty()) return;
        var entries = new ArrayList<FineSampleCache.Entry>(pending.size());
        for (var column : pending) {
            entries.add(new FineSampleCache.Entry(column.x(), column.z(), column.sample()));
        }
        if (cache.tryStore(entries)) pending = List.of();
    }
}
