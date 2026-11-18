package local.plaincraft.greatwall.generation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import local.plaincraft.greatwall.model.WallTypes.*;

/**
 * Per-FEATURES-owner immutable snapshots of the wall corridor.
 *
 * <h2>A missing snapshot is not an error</h2>
 * This guard is consulted from inside vanilla's tree placement, on the chunk-generation thread.
 * Throwing there aborts the chunk's FEATURES stage, and the chunk then fails to load: a measured
 * session produced 51 {@code Failed to load chunk} entries around spawn, each one caused by
 * {@code missing lock-external vegetation snapshot} thrown from {@link #intersects}.
 *
 * <p>Snapshots are published at planning time, and only for the prewarmed window's chunks. Every
 * chunk outside that window therefore has no snapshot - and a chunk outside the corridor can never
 * need suppression, so "no snapshot" means "nothing to suppress", never "the wall is unprotected".
 * The wall's own corridor chunks do get their snapshot before the world generates them, which is
 * what makes suppression work where it matters.
 *
 * <h2>Spatial envelope</h2>
 * {@link #mayAffect} answers "could this chunk possibly touch a published corridor band" from a
 * single cached box, so the worldgen thread can reject the whole world outside the wall's band
 * without a map lookup or a miss.
 */
public final class VegetationGuard {
    private final ConcurrentHashMap<ChunkKey, CorridorIndex> corridors = new ConcurrentHashMap<>();
    /** Union of every published corridor box: minX, minZ, maxXExclusive, maxZExclusive. */
    private volatile int[] envelope;
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong suppresses = new AtomicLong();

    /**
     * Publishes the corridor boxes for one FEATURES owner.
     *
     * <p>Idempotent: re-planning the same window republishes the same boxes for the same owner, and
     * that must not abort the compile - it overwrites instead of failing.
     */
    public void publish(ChunkKey featuresOwner, PlanWindow plans) {
        publishPrepared(featuresOwner, CorridorIndex.prepare(plans, featuresOwner.dimension()));
    }

    /** 按区块保留发布和释放语义，只共享不随区块改变的空间证据。 */
    public void publishPrepared(ChunkKey featuresOwner, CorridorIndex snapshot) {
        if (!snapshot.dimension().equals(featuresOwner.dimension())) {
            throw new IllegalArgumentException("植被保护索引与区块维度不一致");
        }
        corridors.put(featuresOwner, snapshot);
        var bounds = snapshot.envelope();
        if (bounds != null) growEnvelope(bounds);
    }

    /** True when {@code featuresOwner} could contain a tree that touches a published corridor band. */
    public boolean mayAffect(ChunkKey featuresOwner, int marginBlocks) {
        int[] box = envelope;
        if (box == null) return false;
        int minX = featuresOwner.x() << 4;
        int minZ = featuresOwner.z() << 4;
        int maxX = minX + 16;
        int maxZ = minZ + 16;
        return minX - marginBlocks < box[2] && maxX + marginBlocks > box[0]
                && minZ - marginBlocks < box[3] && maxZ + marginBlocks > box[1];
    }

    public boolean intersects(ChunkKey featuresOwner, Bounds completeFeatureBounds) {
        var snapshot = corridors.get(featuresOwner);
        if (snapshot == null) {
            // Outside the published window: nothing to suppress. Counted so a genuine hole inside
            // the wall's own band stays visible in diagnostics instead of failing world generation.
            misses.incrementAndGet();
            return false;
        }
        if (!snapshot.intersects(completeFeatureBounds)) return false;
        suppresses.incrementAndGet();
        return true;
    }

    public void release(ChunkKey featuresOwner) { corridors.remove(featuresOwner); }
    public int leases() { return corridors.size(); }
    /** Queries that found no snapshot; expected for chunks outside the prewarmed window. */
    public long misses() { return misses.get(); }
    /** Trees actually suppressed by a corridor band. */
    public long suppressed() { return suppresses.get(); }

    private synchronized void growEnvelope(Bounds box) {
        int[] current = envelope;
        // 同一窗口已登记时无需再分配边界数组。
        if (current == null) {
            envelope = new int[] {box.minX(), box.minZ(), box.maxXExclusive(), box.maxZExclusive()};
        } else if (box.minX() < current[0] || box.minZ() < current[1]
                || box.maxXExclusive() > current[2] || box.maxZExclusive() > current[3]) {
            envelope = new int[] {Math.min(current[0], box.minX()), Math.min(current[1], box.minZ()),
                    Math.max(current[2], box.maxXExclusive()), Math.max(current[3], box.maxZExclusive())};
        }
    }
}
