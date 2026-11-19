package local.plaincraft.greatwall.gameplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import local.plaincraft.greatwall.generation.TerrainMarkers;
import local.plaincraft.greatwall.model.WallTypes.BeaconId;
import local.plaincraft.greatwall.model.WallTypes.BlockEntityIntent;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.DecayState;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.model.WallTypes.SiteId;
import local.plaincraft.greatwall.model.WallTypes.SpawnSlot;
import local.plaincraft.greatwall.model.WallTypes.WorldRecipe;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Loaded-site snapshot index. It answers only from chunks the server has
 * already loaded; a missing entry is an empty result, never a reason to
 * plan or load a chunk. Permanent history is never deleted here.
 */
public final class SiteRuntimeIndex implements AutoCloseable {
    private final WorldRecipe recipe;
    private final ConcurrentHashMap<ChunkKey, List<BlockEntityIntent>> loaded = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkKey, List<SpawnSlot>> slotBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SiteId, DecayState> realizedStates = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SiteRuntimeIndex(WorldRecipe recipe) {
        this.recipe = Objects.requireNonNull(recipe, "recipe");
    }

    public void loaded(LevelChunk chunk, TerrainMarkers.Marker marker) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(marker, "marker");
        ensureOpen();
        if (marker.recipe() == null || !marker.recipe().equals(recipe.recipeHash())) return;
        if (!marker.complete()) return;
        ChunkKey key = new ChunkKey(recipe.dimension(), chunk.getPos().x, chunk.getPos().z);
        // Same hash is idempotent: a reload must not duplicate the bucket.
        loaded.putIfAbsent(key, List.copyOf(marker.gameplay()));
        slotBuckets.putIfAbsent(key, List.copyOf(marker.spawnSlots()));
        marker.realizedStates().forEach(realizedStates::putIfAbsent);
    }

    public void unloaded(ChunkKey chunk) {
        if (chunk == null) return;
        loaded.remove(chunk);
        slotBuckets.remove(chunk);
    }

    public Optional<GameplayTypes.Firepit> firepit(Point3i pos) {
        Objects.requireNonNull(pos, "pos");
        ensureOpen();
        for (List<BlockEntityIntent> bucket : loaded.values()) {
            for (BlockEntityIntent intent : bucket) {
                if (!intent.pos().equals(pos)) continue;
                if (!"minecraft:campfire".equals(intent.type())) return Optional.empty();
                // 新模板里门楼也有装饰营火，只有挂了烽火身份的营火才参与点火接力。
                if (intent.fields().containsKey(local.plaincraft.greatwall.component.ImportedWallTemplate.DECORATION)
                        && !intent.fields().containsKey("beaconWall")) return Optional.empty();
                int ordinal = Integer.parseInt(intent.fields().getOrDefault("firepitOrdinal", Integer.toString(intent.localId())));
                BeaconId beacon = new BeaconId(intent.site().wallId(), intent.site().ordinal());
                boolean lit = Boolean.parseBoolean(intent.fields().getOrDefault("lit", "false"));
                boolean waterlogged = Boolean.parseBoolean(
                        intent.fields().getOrDefault("waterlogged", "false"));
                boolean credited = Boolean.parseBoolean(intent.fields().getOrDefault("credited", "false"));
                return Optional.of(new GameplayTypes.Firepit(beacon, intent.site(), ordinal, pos,
                        lit, waterlogged, credited));
            }
        }
        return Optional.empty();
    }

    public Optional<BlockEntityIntent> spawner(Point3i pos) {
        Objects.requireNonNull(pos, "pos");
        ensureOpen();
        for (List<BlockEntityIntent> bucket : loaded.values()) {
            for (BlockEntityIntent intent : bucket) {
                if (!intent.pos().equals(pos)) continue;
                if (!"SPAWNER".equals(intent.type())) return Optional.empty();
                if (Boolean.parseBoolean(intent.fields().getOrDefault("tombstoned", "false"))) {
                    return Optional.empty();
                }
                return Optional.of(intent);
            }
        }
        return Optional.empty();
    }

    public List<SpawnSlot> nearbySlots(Point3i pos, int radius) {
        Objects.requireNonNull(pos, "pos");
        if (radius < 0 || radius > 64) throw new IllegalArgumentException("radius 0..64 required");
        ensureOpen();
        long limit = (long) radius * radius;
        List<SpawnSlot> result = new ArrayList<>();
        for (List<SpawnSlot> bucket : slotBuckets.values()) {
            for (SpawnSlot slot : bucket) {
                long dx = (long) slot.feet().x() - pos.x();
                long dz = (long) slot.feet().z() - pos.z();
                if (dx * dx + dz * dz <= limit) result.add(slot);
            }
        }
        result.sort(Comparator.comparing((SpawnSlot slot) -> slot.site().kind().name())
                .thenComparingLong(slot -> slot.site().ordinal())
                .thenComparingInt(SpawnSlot::slot)
                .thenComparingInt(slot -> slot.feet().y())
                .thenComparingInt(slot -> slot.feet().z())
                .thenComparingInt(slot -> slot.feet().x()));
        return List.copyOf(result);
    }

    /**
     * True only when the recorded proof matches and the block was not
     * tombstoned. A campfire whose LIT toggled keeps its identity; replacing
     * or moving the block revokes it permanently.
     */
    public boolean generatedAndUntouched(Point3i pos, String proofHash) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(proofHash, "proofHash");
        ensureOpen();
        for (List<BlockEntityIntent> bucket : loaded.values()) {
            for (BlockEntityIntent intent : bucket) {
                if (!intent.pos().equals(pos)) continue;
                if (Boolean.parseBoolean(intent.fields().getOrDefault("tombstoned", "false"))) return false;
                String recorded = intent.fields().get("proof");
                return proofHash.equals(recorded);
            }
        }
        return false;
    }

    /** The recorded realized decay state for a site, or empty when not loaded. */
    public Optional<DecayState> realized(SiteId site) {
        Objects.requireNonNull(site, "site");
        ensureOpen();
        return Optional.ofNullable(realizedStates.get(site));
    }

    /** Every loaded spawn slot, unsorted, for the garrison tick loop. */
    public List<SpawnSlot> allSlots() {
        ensureOpen();
        List<SpawnSlot> result = new ArrayList<>();
        for (List<SpawnSlot> bucket : slotBuckets.values()) result.addAll(bucket);
        return List.copyOf(result);
    }

    /** Snapshot of every loaded site bucket, for diagnostics. */
    public int loadedChunks() {
        ensureOpen();
        return loaded.size();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("STALE_SESSION: site index belongs to a closed session");
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        loaded.clear();
        slotBuckets.clear();
        realizedStates.clear();
    }

    private static int parseInt(String raw) {
        if (raw == null) return 0;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private static long parseLong(String raw) {
        if (raw == null) return 0L;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException malformed) {
            return 0L;
        }
    }

    static SiteId siteOf(BlockEntityIntent intent) { return intent.site(); }
}
