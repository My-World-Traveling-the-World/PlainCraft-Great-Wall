package local.plaincraft.greatwall.compat.biome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import local.plaincraft.greatwall.generation.CorridorIndex;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/**
 * An immutable, per-FEATURES-owner corridor mask compiled from a frozen {@link PlanWindow}.
 *
 * <h2>What it answers</h2>
 * For a World-Generation owner chunk it answers, at block resolution, whether an XZ column is
 * inside the hard protection domain {@code H}, inside the biome band {@code B} (the hard domain
 * grown by {@link #BIOME_GROWTH}), or inside one of the two outward landscape bands. Two query
 * shapes exist because the two consumers differ:
 *
 * <ul>
 *   <li>{@link #paintQuart} is conservative and operates on a 4×4 quart footprint, because a biome
 *       array has quart resolution and cannot express sub-quart detail.</li>
 *   <li>{@link #touchesHard} is exact and block-level, because tree suppression must not lose a
 *       single block to quart rounding. It is a rectangle-intersection test over the whole box
 *       union, so it covers the complete footprint rather than sampling corners — the union of
 *       boxes may contain holes.</li>
 * </ul>
 *
 * <h2>Why it is bounded</h2>
 * The wall is effectively infinite, so the whole corridor can never be cached. Each owner compiles
 * only the 16×16-block tiles covering its own write halo {@code D}. Distances are always measured
 * against the global box union, so a column inside {@code D} still receives the correct value even
 * when the nearest box lies outside {@code D}.
 *
 * <h2>Threading</h2>
 * Every field is immutable and every array is defensively copied and never handed out, so an
 * instance may be published to world-generation threads. The mutable builder is owner-local and
 * never escapes.
 */
public final class RouteBiomeMask {
    /** Outward growth of {@code H} that defines the biome band {@code B}. */
    public static final int BIOME_GROWTH = 64;
    /** Low landscape band: {@code LOW_MIN <= d < LOW_MAX}. */
    public static final int LOW_MIN = 8;
    public static final int LOW_MAX = 32;
    /** Edge landscape band: {@code EDGE_MIN <= d < EDGE_MAX}. */
    public static final int EDGE_MIN = 32;
    public static final int EDGE_MAX = 64;
    /** One tile is 16×16 block columns and 32 bytes per plane. */
    private static final int TILE_BITS = 16;
    private static final int PLANE_LONGS = 4;
    private static final int PLANES = 4;

    /** Identity of the production run this mask was compiled for. */
    public record MaskKey(UUID session, String dimension, String recipeHash, long registryEpoch,
                          int ownerX, int ownerZ) {
        public MaskKey {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(recipeHash, "recipeHash");
        }
    }

    /** Four 256-bit planes covering one 16×16-block tile. */
    public record TileBits(long[] hard, long[] biome, long[] low, long[] edge) {
        public TileBits {
            requirePlane(hard, "hard");
            requirePlane(biome, "biome");
            requirePlane(low, "low");
            requirePlane(edge, "edge");
        }

        private static void requirePlane(long[] plane, String name) {
            if (plane == null || plane.length != PLANE_LONGS) {
                throw new IllegalArgumentException(name + " plane must hold " + PLANE_LONGS + " longs");
            }
        }
    }

    private final MaskKey key;
    private final Map<Long, TileBits> tiles;
    private final CorridorIndex corridor;
    private final int writeRadius;
    private final Bounds domain;

    private RouteBiomeMask(MaskKey key, Map<Long, TileBits> tiles, CorridorIndex corridor,
                           int writeRadius, Bounds domain) {
        this.key = key;
        this.tiles = Map.copyOf(tiles);
        this.corridor = corridor;
        this.writeRadius = writeRadius;
        this.domain = domain;
    }

    public MaskKey key() { return key; }
    public List<Bounds> hardBoxes() { return corridor.boxes(); }
    public int writeRadius() { return writeRadius; }
    public int tileCount() { return tiles.size(); }
    /** The half-open XZ rectangle this mask can answer for. */
    public Bounds domain() { return domain; }

    /**
     * Conservative quart test for the writer: true when any block in the 4×4 footprint lies in
     * {@code B}. Over-coverage is bounded by at most 3 blocks per side and is deliberate.
     */
    public boolean paintQuart(int blockX, int blockZ) {
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                if (biomeAt(blockX + dx, blockZ + dz)) return true;
            }
        }
        return false;
    }

    /**
     * Exact block-level test for {@code H} over a complete feature footprint. A tree write that
     * intersects the hard domain is refused.
     */
    public boolean touchesHard(Bounds completeFeatureBounds) {
        // 树冠可伸出本区块的掩码范围，因此仍向共享索引查询整片足迹。
        return corridor.intersects(completeFeatureBounds);
    }

    /** Exact block-level membership in {@code H}. */
    public boolean hardAt(int blockX, int blockZ) {
        return test(0, blockX, blockZ);
    }

    /** Membership in {@code B}, the hard domain grown by {@link #BIOME_GROWTH}. */
    public boolean biomeAt(int blockX, int blockZ) {
        return test(1, blockX, blockZ);
    }

    /** Membership in the low outward band. */
    public boolean lowAt(int blockX, int blockZ) {
        return test(2, blockX, blockZ);
    }

    /** Membership in the edge outward band. */
    public boolean edgeAt(int blockX, int blockZ) {
        return test(3, blockX, blockZ);
    }

    private boolean test(int plane, int blockX, int blockZ) {
        var tile = tiles.get(tileKey(Math.floorDiv(blockX, TILE_BITS), Math.floorDiv(blockZ, TILE_BITS)));
        if (tile == null) {
            throw new WallFailure(FailureCode.MISSING_FEATURES_PLAN,
                    "column outside compiled corridor mask " + blockX + "," + blockZ);
        }
        int localX = Math.floorMod(blockX, TILE_BITS);
        int localZ = Math.floorMod(blockZ, TILE_BITS);
        int index = localX * TILE_BITS + localZ;
        long[] selected = switch (plane) {
            case 0 -> tile.hard();
            case 1 -> tile.biome();
            case 2 -> tile.low();
            default -> tile.edge();
        };
        return (selected[index >>> 6] & (1L << (index & 63))) != 0L;
    }

    private static long tileKey(int tileX, int tileZ) {
        return ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL);
    }

    /** A fresh owner-local builder. It is mutable and must never be published. */
    public static Builder builder(MaskKey key, PlanWindow plans, int writeRadius) {
        return new Builder(key, CorridorIndex.prepare(plans, key.dimension()), writeRadius);
    }

    /** Owner-local compiler that publishes exactly one immutable {@link RouteBiomeMask}. */
    public static final class Builder {
        private final MaskKey key;
        private final CorridorIndex corridor;
        private final int writeRadius;
        private final Map<Long, TileBits> tiles = new LinkedHashMap<>();

        private Builder(MaskKey key, CorridorIndex corridor, int writeRadius) {
            if (writeRadius < 0) throw new IllegalArgumentException("negative write radius");
            this.key = Objects.requireNonNull(key);
            if (!key.dimension().equals(corridor.dimension())) {
                throw new IllegalArgumentException("群系掩码与走廊索引维度不一致");
            }
            this.corridor = corridor;
            this.writeRadius = writeRadius;
        }

        /**
         * Compiles the {@code (2 * writeRadius + 1)²} tile union centred on the owner chunk. This is
         * exactly the chunk area the native FEATURES step may write, so no extra halo is needed:
         * tree suppression uses {@link #touchesHard}, which intersects the box union directly and
         * is therefore never limited by the compiled domain.
         */
        public void compile(int chunkMinX, int chunkMinZ) {
            int span = 2 * writeRadius + 1;
            int ownerTileX = Math.floorDiv(chunkMinX, TILE_BITS);
            int ownerTileZ = Math.floorDiv(chunkMinZ, TILE_BITS);
            for (int tileX = ownerTileX - writeRadius; tileX <= ownerTileX + writeRadius; tileX++) {
                for (int tileZ = ownerTileZ - writeRadius; tileZ <= ownerTileZ + writeRadius; tileZ++) {
                    long tileKey = tileKey(tileX, tileZ);
                    if (tiles.containsKey(tileKey)) continue;
                    tiles.put(tileKey, plane(tileX * TILE_BITS, tileZ * TILE_BITS));
                }
            }
            if (tiles.size() > span * span) {
                throw new WallFailure(FailureCode.CAPACITY, "corridor mask tile span exceeds write radius");
            }
        }

        private TileBits plane(int originX, int originZ) {
            long[][] planes = new long[PLANES][PLANE_LONGS];
            for (int localX = 0; localX < TILE_BITS; localX++) {
                for (int localZ = 0; localZ < TILE_BITS; localZ++) {
                    int blockX = originX + localX;
                    int blockZ = originZ + localZ;
                    long distance = corridor.distance(blockX, blockZ);
                    int index = localX * TILE_BITS + localZ;
                    set(planes[0], index, distance == 0L);
                    set(planes[1], index, distance <= BIOME_GROWTH);
                    set(planes[2], index, distance >= LOW_MIN && distance < LOW_MAX);
                    set(planes[3], index, distance >= EDGE_MIN && distance < EDGE_MAX);
                }
            }
            return new TileBits(planes[0], planes[1], planes[2], planes[3]);
        }

        private static void set(long[] plane, int index, boolean value) {
            if (value) plane[index >>> 6] |= 1L << (index & 63);
        }

        public RouteBiomeMask build() {
            if (tiles.isEmpty()) {
                throw new WallFailure(FailureCode.MISSING_FEATURES_PLAN, "empty corridor mask compile");
            }
            int minTileX = Integer.MAX_VALUE, maxTileX = Integer.MIN_VALUE;
            int minTileZ = Integer.MAX_VALUE, maxTileZ = Integer.MIN_VALUE;
            for (long packed : tiles.keySet()) {
                int tileX = (int) (packed >> 32);
                int tileZ = (int) packed;
                minTileX = Math.min(minTileX, tileX); maxTileX = Math.max(maxTileX, tileX);
                minTileZ = Math.min(minTileZ, tileZ); maxTileZ = Math.max(maxTileZ, tileZ);
            }
            var domain = new Bounds(Math.multiplyExact(minTileX, TILE_BITS), Integer.MIN_VALUE,
                    Math.multiplyExact(minTileZ, TILE_BITS),
                    Math.multiplyExact(Math.addExact(maxTileX, 1), TILE_BITS), Integer.MAX_VALUE,
                    Math.multiplyExact(Math.addExact(maxTileZ, 1), TILE_BITS));
            return new RouteBiomeMask(key, tiles, corridor, writeRadius, domain);
        }
    }

    /** Compile a single-owner mask; used by tests and diagnostics. */
    public static RouteBiomeMask compile(MaskKey key, PlanWindow plans, int writeRadius,
                                         int chunkMinX, int chunkMinZ) {
        var builder = builder(key, plans, writeRadius);
        builder.compile(chunkMinX, chunkMinZ);
        return builder.build();
    }

    /** 各区块只生成自己的位图；最近距离仍针对完整走廊计算。 */
    static RouteBiomeMask compilePrepared(MaskKey key, CorridorIndex corridor, int writeRadius,
            int chunkMinX, int chunkMinZ) {
        var builder = new Builder(key, corridor, writeRadius);
        builder.compile(chunkMinX, chunkMinZ);
        return builder.build();
    }
}
