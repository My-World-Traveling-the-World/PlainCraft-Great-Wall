package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Per-FEATURES-owner immutable snapshots. A missing lease is an error, never an empty corridor. */
public final class VegetationGuard {
    private final ConcurrentHashMap<ChunkKey, List<Bounds>> corridors = new ConcurrentHashMap<>();

    public void publish(ChunkKey featuresOwner, PlanWindow plans) {
        var boxes = new ArrayList<Bounds>();
        for (var plan : plans.plans()) {
            if (!plan.geometry().key().dimension().equals(featuresOwner.dimension())) {
                throw new IllegalArgumentException("foreign vegetation plan dimension");
            }
            var stations = plan.stations().stations();
            for (int index = 1; index < stations.size(); index++) {
                var a = stations.get(index - 1).point();
                var b = stations.get(index).point();
                int minX = Math.toIntExact(Math.floorDiv(Math.min(a.xQ8(), b.xQ8()), 256));
                int maxX = Math.toIntExact(Math.floorDiv(Math.max(a.xQ8(), b.xQ8()), 256));
                int minZ = Math.toIntExact(Math.floorDiv(Math.min(a.zQ8(), b.zQ8()), 256));
                int maxZ = Math.toIntExact(Math.floorDiv(Math.max(a.zQ8(), b.zQ8()), 256));
                // 10 half-wall +32 vegetation margin +2 raster rounding; conservative segment rectangle.
                boxes.add(new Bounds(Math.subtractExact(minX, 44), Integer.MIN_VALUE, Math.subtractExact(minZ, 44),
                        Math.addExact(maxX, 45), Integer.MAX_VALUE, Math.addExact(maxZ, 45)));
            }
            for (var site : plan.sites()) {
                if (site.state() == SiteState.SUPPRESSED) continue;
                var b = site.bounds();
                boxes.add(new Bounds(Math.subtractExact(b.minX(), 32), Integer.MIN_VALUE, Math.subtractExact(b.minZ(), 32),
                        Math.addExact(b.maxXExclusive(), 32), Integer.MAX_VALUE, Math.addExact(b.maxZExclusive(), 32)));
            }
        }
        if (boxes.size() > 8192) throw new WallFailure(FailureCode.CAPACITY, "vegetation snapshot box capacity");
        List<Bounds> snapshot = List.copyOf(boxes);
        if (corridors.putIfAbsent(featuresOwner, snapshot) != null) {
            throw new WallFailure(FailureCode.MISSING_FEATURES_PLAN, "FEATURES owner already holds a vegetation lease");
        }
    }

    public boolean intersects(ChunkKey featuresOwner, Bounds completeFeatureBounds) {
        var snapshot = corridors.get(featuresOwner);
        if (snapshot == null) throw new WallFailure(FailureCode.MISSING_FEATURES_PLAN, "missing lock-external vegetation snapshot " + featuresOwner);
        for (var box : snapshot) {
            if (box.minX() < completeFeatureBounds.maxXExclusive() && box.maxXExclusive() > completeFeatureBounds.minX()
                    && box.minZ() < completeFeatureBounds.maxZExclusive() && box.maxZExclusive() > completeFeatureBounds.minZ()) return true;
        }
        return false;
    }

    public void release(ChunkKey featuresOwner) { corridors.remove(featuresOwner); }
    public int leases() { return corridors.size(); }
}
