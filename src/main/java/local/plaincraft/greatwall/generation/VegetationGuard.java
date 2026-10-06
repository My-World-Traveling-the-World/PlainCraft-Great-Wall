package local.plaincraft.greatwall.generation;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Per-FEATURES-owner immutable snapshots. A missing lease is an error, never an empty corridor. */
public final class VegetationGuard {
    private final ConcurrentHashMap<ChunkKey, List<Bounds>> corridors = new ConcurrentHashMap<>();

    public void publish(ChunkKey featuresOwner, PlanWindow plans) {
        List<Bounds> snapshot = CorridorBoxes.hard(plans, featuresOwner.dimension());
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
