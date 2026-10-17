package local.plaincraft.greatwall.terrain;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.function.Function;
import java.util.function.Supplier;
import local.plaincraft.greatwall.diagnostics.CompiledAllocation;
import local.plaincraft.greatwall.generation.SurfaceProbe;
import local.plaincraft.greatwall.model.WallTypes.Bounds;
import local.plaincraft.greatwall.model.WallTypes.Point3i;

/** XZ membership is constant for a column; only the certified Y interval changes. */
final class ProtectedColumnView {
    private record Index(List<Bounds> source, Map<Long,List<Bounds>> buckets) {}
    private static final ThreadLocal<Index> CURRENT = new ThreadLocal<>();
    private ProtectedColumnView() {}

    static <T> T indexed(List<Bounds> boxes, Collection<Point3i> columns, Supplier<T> action) {
        long uniqueScratch = Math.addExact(512,Math.addExact(Math.multiplyExact(192L,boxes.size()),
                Math.multiplyExact(64L,columns.size())));
        CompiledAllocation.charge(uniqueScratch);
        long bucketScratch = 0;
        var prior = CURRENT.get();
        try {
            var unique = List.copyOf(new LinkedHashSet<>(boxes));
            Set<Long> chunks = new HashSet<>();
            for (var pos : columns) chunks.add(chunk(pos.x(),pos.z()));
            long requested = Math.addExact(512,Math.multiplyExact(64L * chunks.size(),unique.size()+1L));
            CompiledAllocation.charge(requested);
            bucketScratch = requested;
            var buckets = new HashMap<Long,List<Bounds>>();
            for (long key : chunks) {
                long x0 = (long)(int)(key >> 32)*16, z0 = (long)(int)key*16;
                buckets.put(key,unique.stream().filter(b->b.minX()<x0+16&&b.maxXExclusive()>x0
                        &&b.minZ()<z0+16&&b.maxZExclusive()>z0).toList());
            }
            // A read-only view, not a copy: the bucket key packs two block coordinates into one long, the
            // same shape FineHeightIndex.of documents at 53 s inside ImmutableCollections$MapN.<init> for
            // a 192k-entry index. The map is built here, never aliased and never mutated afterwards.
            CURRENT.set(new Index(boxes, java.util.Collections.unmodifiableMap(buckets)));
            return action.get();
        } finally {
            if (prior==null) CURRENT.remove(); else CURRENT.set(prior);
            if (bucketScratch!=0) CompiledAllocation.discard(bucketScratch);
            CompiledAllocation.discard(uniqueScratch);
        }
    }

    static <T> T use(SurfaceProbe.ColumnView original, Point3i pos, List<Bounds> boxes,
            Function<SurfaceProbe.ColumnView, T> action) {
        long scratch = Math.addExact(256, Math.multiplyExact(64L, boxes.size()));
        CompiledAllocation.charge(scratch);
        try { return action.apply(wrap(original, pos, boxes)); }
        finally { CompiledAllocation.discard(scratch); }
    }

    static SurfaceProbe.ColumnView wrap(SurfaceProbe.ColumnView original, Point3i pos, List<Bounds> boxes) {
        var index=CURRENT.get();
        var candidates=index!=null&&index.source()==boxes
                ?index.buckets().getOrDefault(chunk(pos.x(),pos.z()),boxes):boxes;
        List<Bounds> local = candidates.stream().filter(b -> pos.x() >= b.minX() && pos.x() < b.maxXExclusive()
                && pos.z() >= b.minZ() && pos.z() < b.maxZExclusive()).distinct().toList();
        return new SurfaceProbe.ColumnView() {
            public String state(int y) { return original.state(y); }
            public boolean solidSupport(int y) { return original.solidSupport(y); }
            public boolean water(int y) { return original.water(y); }
            public boolean airOrReplaceablePlant(int y) { return original.airOrReplaceablePlant(y); }
            public int highestNonPlantFeet() { return original.highestNonPlantFeet(); }
            public String hash() { return original.hash(); }
            public boolean naturalThinIce(int y) { return original.naturalThinIce(y) && !protectedAt(y); }
            public boolean protectedAt(int y) {
                if (original.protectedAt(y)) return true;
                for (Bounds box : local) if (y >= box.minY() && y < box.maxYExclusive()) return true;
                return false;
            }
        };
    }

    private static long chunk(int x,int z) {
        return ((long)Math.floorDiv(x,16)<<32) ^ (Math.floorDiv(z,16)&0xffffffffL);
    }
}
