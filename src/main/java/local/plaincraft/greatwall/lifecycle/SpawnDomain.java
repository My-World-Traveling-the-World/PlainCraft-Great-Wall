package local.plaincraft.greatwall.lifecycle;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Recorded native spawn dependencies only; no global bootstrap exemption. */
public final class SpawnDomain {
    private static final int MAX_CHUNKS = 4096;
    private final ConcurrentHashMap<ChunkKey, Boolean> chunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> state = new ConcurrentHashMap<>();

    public void authorize(ChunkKey candidate, int nativeDependencyRadius) {
        active();
        if (!candidate.dimension().equals("minecraft:overworld") || nativeDependencyRadius < 0) {
            throw new IllegalArgumentException("overworld native dependency radius required");
        }
        int radius = Math.addExact(nativeDependencyRadius, 2);
        long side = 2L * radius + 1;
        if (side * side > MAX_CHUNKS) { state.put("failed", true); throw exhausted(); }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                var key = new ChunkKey(candidate.dimension(), Math.addExact(candidate.x(), dx),
                        Math.addExact(candidate.z(), dz));
                chunks.putIfAbsent(key, Boolean.TRUE);
                if (chunks.size() > MAX_CHUNKS) { state.put("failed", true); throw exhausted(); }
            }
        }
    }

    public boolean contains(ChunkKey key) { active(); return chunks.containsKey(key); }

    public List<ChunkKey> snapshot() {
        active();
        return chunks.keySet().stream().sorted(java.util.Comparator.comparing(ChunkKey::dimension)
                .thenComparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z)).toList();
    }

    /** Route centers have nonnegative u. Template reach 96 + vegetation 32 + rounding 2, then gap 16. */
    public static boolean disjointFuture(Axis axis, List<ChunkKey> early) {
        if (early.isEmpty()) return false;
        long permittedMaxU = -(96L + 32 + 2 + 16) * 256;
        for (var chunk : early) {
            if (!chunk.dimension().equals("minecraft:overworld")) throw new IllegalArgumentException("foreign early chunk");
            int x = Math.multiplyExact(chunk.x(), 16), z = Math.multiplyExact(chunk.z(), 16);
            for (int dx : new int[] {0, 15}) for (int dz : new int[] {0, 15}) {
                if (Projection.axisUQ8(axis, Math.addExact(x, dx), Math.addExact(z, dz)) >= permittedMaxU) return false;
            }
        }
        return true;
    }

    public Map<String, Long> diagnostics() { return Map.of("early-chunks", (long) chunks.size()); }
    private void active() { if (state.containsKey("failed")) throw exhausted(); }

    private static WallFailure exhausted() {
        return new WallFailure(FailureCode.ROOT_NOT_FOUND, "native spawn dependency domain exceeds 4096 chunks");
    }
}
