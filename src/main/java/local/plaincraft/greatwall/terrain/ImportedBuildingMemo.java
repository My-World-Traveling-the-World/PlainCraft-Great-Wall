package local.plaincraft.greatwall.terrain;

import java.util.*;
import java.util.function.Supplier;
import local.plaincraft.greatwall.component.ImportedSiteGeometry;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.planning.PlanningColumnView;
import local.plaincraft.greatwall.component.GroundEntranceContract.Source;

/** 仅在一个编译窗口内复用完整楼体；键不持有整窗列图，容量含楼体和证据引用。 */
public final class ImportedBuildingMemo {
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 8;
    // 实物楼只消费site、证据和种子；普通墙截面地基/衰败/材料不是它的输入，不能混进键导致每单元必失配。
    private record Key(String recipe, SiteRecord site, Source source, DimensionBounds bounds,
            List<ColumnSample> columns, List<Bounds> protection, Map<GateId,Integer> gates,
            long gateRevision, String protectionHash) {}
    private record Entry(ComponentVoxels body, long bytes) {}
    public record Stats(long loads, long hits, int entries, long bytes, long peak) {}
    private final LinkedHashMap<Key,Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final long limit;
    private long bytes, peak, loads, hits;

    public ImportedBuildingMemo() { this(MAX_BYTES); }
    ImportedBuildingMemo(long limit) {
        if (limit <= 0 || limit > MAX_BYTES) throw new IllegalArgumentException("楼体复用预算必须在1..64MiB");
        this.limit = limit;
    }

    synchronized ComponentVoxels get(ComponentInput candidate, RealizationPlanner.Input input,
            Supplier<ComponentVoxels> compiler) {
        var key = key(candidate, input);
        var cached = key == null ? null : entries.get(key);
        if (cached != null) {
            // 同一对象可被另一个输出arena拥有；该arena仍须登记保留字节，不能绕开分配守卫。
            CompiledAllocation.charge(CompiledAllocation.bytes(cached.body()));
            hits++;
            return cached.body();
        }
        loads++;
        var body = compiler.get();
        if (key == null || body == null) return body;
        long weight = weight(key, body);
        if (weight > limit) return body;
        while (!entries.isEmpty() && (bytes + weight > limit || entries.size() >= MAX_ENTRIES)) {
            var eldest = entries.entrySet().iterator(); var entry = eldest.next();
            bytes -= entry.getValue().bytes(); eldest.remove();
        }
        entries.put(key, new Entry(body, weight));
        bytes += weight; peak = Math.max(peak, bytes);
        return body;
    }

    private static Key key(ComponentInput c, RealizationPlanner.Input input) {
        var box = ImportedSiteGeometry.bounds(c.site());
        var samples = new ArrayList<ColumnSample>();
        for (int z=box.minZ();z<box.maxZExclusive();z++) for(int x=box.minX();x<box.maxXExclusive();x++) {
            var column=input.columns().get(new Point3i(x,0,z));
            // 任意ColumnView可以带逐方块保护或可变状态；只缓存语义完全由冻结样本决定的生产列。
            if (!(column instanceof PlanningColumnView frozen)) return null;
            samples.add(frozen.frozenSample());
        }
        return new Key(input.key().recipeHash(),c.site(),c.entranceEvidence().source(),
                c.entranceEvidence().bounds(),List.copyOf(samples),input.protectedBounds(),
                input.frozenGateY(),input.gateRevision(),input.protectionHash());
    }

    private static long weight(Key c, ComponentVoxels body) {
        // 保守计入完整列样本与保护引用；不保留ComponentInput、整窗列图或普通墙地基表。
        return Math.addExact(CompiledAllocation.bytes(body), 4096L + 128L*c.columns().size()
                + 128L*c.protection().size()+256L*c.gates().size());
    }

    public synchronized Stats stats() { return new Stats(loads,hits,entries.size(),bytes,peak); }
    public synchronized void clear() { entries.clear(); bytes=0; }
}
