package local.plaincraft.greatwall.compat.road;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.PlanWindow;
import local.plaincraft.greatwall.model.WallTypes.ArcInterval;
import net.shiroha233.roadweaver.api.interop.ObstacleSnapshot;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Edge;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Passage;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Penalty;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Pos;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Rect;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Strip;

/**
 * Immutable wall obstacle snapshot for A*. Every query only reads the
 * prepared lists; nothing here loads a chunk, waits on a future or touches
 * the live world. Closing releases the pin exactly once.
 */
public final class PinnedWallSnapshot implements ObstacleSnapshot {
    /** A road lane wider than this is never blocked by the wall alone. */
    public static final long WALL_PENALTY_COST = 100_000L;

    private final String revision;
    private final List<Strip> strips;
    private final List<Passage> gates;
    private final Map<Long, String> gateByArcBucket;
    private final Runnable release;
    private final ConcurrentHashMap<String, Boolean> closed = new ConcurrentHashMap<>();
    private final Map<Long, List<Strip>> buckets;

    public PinnedWallSnapshot(List<Strip> strips, List<Passage> gates,
            List<PlanLease<PlanWindow>> leases, String revision) {
        this(strips, gates, Map.of(), revision, release(List.copyOf(leases)));
    }

    PinnedWallSnapshot(List<Strip> strips, List<Passage> gates,
            List<PlanLease<PlanWindow>> leases, Map<Long,String> arcBuckets, String revision) {
        this(strips, gates, arcBuckets, revision, release(List.copyOf(leases)));
    }

    private static Runnable release(List<PlanLease<PlanWindow>> leases) {
        return () -> {
            RuntimeException failure = null;
            for (var lease : leases) {
                try { lease.close(); }
                catch (RuntimeException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        };
    }

    public PinnedWallSnapshot(List<Strip> strips, List<Passage> gates,
            Map<Long, String> gateByArcBucket, String revision, Runnable release) {
        Objects.requireNonNull(strips, "strips");
        Objects.requireNonNull(gates, "gates");
        Objects.requireNonNull(gateByArcBucket, "gateByArcBucket");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(release, "release");
        this.strips = List.copyOf(strips);
        this.gates = List.copyOf(gates);
        this.gateByArcBucket = Map.copyOf(gateByArcBucket);
        this.revision = revision;
        this.release = release;
        var index = new java.util.HashMap<Long, List<Strip>>();
        for (Strip strip : this.strips) {
            int minX = strip.centerline().stream().mapToInt(Pos::x).min().orElseThrow() - strip.halfWidth();
            int maxX = strip.centerline().stream().mapToInt(Pos::x).max().orElseThrow() + strip.halfWidth();
            int minZ = strip.centerline().stream().mapToInt(Pos::z).min().orElseThrow() - strip.halfWidth();
            int maxZ = strip.centerline().stream().mapToInt(Pos::z).max().orElseThrow() + strip.halfWidth();
            for (int x = Math.floorDiv(minX, 256); x <= Math.floorDiv(maxX, 256); x++)
                for (int z = Math.floorDiv(minZ, 256); z <= Math.floorDiv(maxZ, 256); z++)
                    index.computeIfAbsent(bucket(x, z), ignored -> new ArrayList<>()).add(strip);
        }
        var immutable = new java.util.HashMap<Long, List<Strip>>();
        index.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        buckets = Map.copyOf(immutable);
    }

    @Override public String revision() { return revision; }

    @Override public List<Strip> queryXZ(Rect bounds) {
        Objects.requireNonNull(bounds, "bounds");
        ensureOpen();
        var candidates = new java.util.LinkedHashSet<Strip>();
        for (int x = Math.floorDiv(bounds.minX(), 256); x <= Math.floorDiv(bounds.maxXExclusive() - 1, 256); x++)
            for (int z = Math.floorDiv(bounds.minZ(), 256); z <= Math.floorDiv(bounds.maxZExclusive() - 1, 256); z++)
                candidates.addAll(buckets.getOrDefault(bucket(x, z), List.of()));
        List<Strip> result = new ArrayList<>();
        for (Strip strip : candidates) {
            if (WallObstacleGeometry.intersects(strip, bounds)) result.add(strip);
        }
        result.sort(Comparator.comparingLong(Strip::fromSQ8).thenComparing(Strip::id));
        return List.copyOf(result);
    }

    @Override public Penalty evaluateSweptEdge(Edge edge, int roadRadius) {
        Objects.requireNonNull(edge, "edge");
        ensureOpen();
        if (roadRadius < 0 || roadRadius > 32) {
            throw new IllegalArgumentException("roadRadius 0..32 required");
        }
        Passage gate = gateForCrossing(edge).orElse(null);
        boolean intersection = false;
        for (Strip strip : strips) {
            for (int i = 1; i < strip.centerline().size(); i++) {
                Pos a = strip.centerline().get(i - 1), b = strip.centerline().get(i);
                if (!WallObstacleGeometry.swept(edge, a, b, strip.halfWidth() + roadRadius)) continue;
                intersection = true;
                if (gate == null || !WallObstacleGeometry.opensSegment(edge, gate, a, b,
                        strip.halfWidth(), roadRadius)) {
                    return new Penalty(WALL_PENALTY_COST, true, Optional.empty());
                }
            }
        }
        if (intersection && gate != null)
            return new Penalty(0, !gate.physicallyReady(), Optional.of(gate.gateId()));
        return new Penalty(0L, false, Optional.empty());
    }

    @Override public Optional<Passage> gateForCrossing(Edge edge) {
        Objects.requireNonNull(edge, "edge");
        ensureOpen();
        return gates.stream().filter(gate -> WallObstacleGeometry.crosses(edge, gate, 0))
                .min(Comparator.comparingDouble((Passage gate) -> {
                    double dx = (double) edge.from().x() - gate.center().x();
                    double dz = (double) edge.from().z() - gate.center().z();
                    return dx * dx + dz * dz;
                }).thenComparing(Passage::gateId));
    }

    /** Arc bucket lookup used by the endpoint agreement path. */
    public Optional<String> gateIdForArcQuality(long arcQ8) {
        ensureOpen();
        return Optional.ofNullable(gateByArcBucket.get(Math.floorDiv(arcQ8, 128L * 256)));
    }

    @Override public void close() {
        if (closed.putIfAbsent("closed", true) == null) release.run();
    }

    private void ensureOpen() {
        if (closed.containsKey("closed")) throw new IllegalStateException("snapshot is closed");
    }

    private static long bucket(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    static ArcInterval intervalFor(Strip strip) {
        return new ArcInterval(strip.fromSQ8(), strip.toSQ8());
    }
}
