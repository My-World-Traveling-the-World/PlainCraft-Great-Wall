package local.plaincraft.greatwall.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan;

/** 启动发布仅保留区块及哈希；完整施工表交给现有缓存与存储。 */
final class StartupPlanPublication {
    private final Map<ChunkKey, String> queuedHashes = new LinkedHashMap<>();
    private int published;
    private int failed;
    private Throwable firstFailure;
    private long publishNanos;

    void accept(ChunkWritePlan plan, Consumer<ChunkWritePlan> install,
            Consumer<ChunkWritePlan> publishFootprint) {
        long start = System.nanoTime();
        try {
            install.accept(plan);
            // 缓存接纳后仍须按原契约排队；若后续足迹失败，单独统计为发布失败。
            queuedHashes.put(plan.owner(), plan.hash());
            publishFootprint.accept(plan);
            published++;
        } catch (Throwable failure) {
            failed++;
            if (firstFailure == null) firstFailure = failure;
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] PLANNING_COMPILE_OWNER_FAILED owner={},{} reason={}",
                    plan.owner().x(), plan.owner().z(), failure.toString());
        } finally {
            publishNanos += System.nanoTime() - start;
        }
    }

    int published() { return published; }
    int failed() { return failed; }
    Throwable firstFailure() { return firstFailure; }
    long publishNanos() { return publishNanos; }
    Map<ChunkKey, String> queuedHashes() { return java.util.Collections.unmodifiableMap(queuedHashes); }
}
