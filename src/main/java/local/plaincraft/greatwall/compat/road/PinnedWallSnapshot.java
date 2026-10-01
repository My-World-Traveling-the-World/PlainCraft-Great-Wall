package local.plaincraft.greatwall.compat.road;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicBoolean closed = new AtomicBoolean();

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
    }

    @Override public String revision() { return revision; }

    @Override public List<Strip> queryXZ(Rect bounds) {
        Objects.requireNonNull(bounds, "bounds");
        ensureOpen();
        List<Strip> result = new ArrayList<>();
        for (Strip strip : strips) {
            if (intersectsStrip(strip, bounds)) result.add(strip);
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
        if (gate != null) {
            // A physically ready gate lets the road through at zero wall cost;
            // any other building penalty the caller adds stays untouched.
            return new Penalty(0L, false, Optional.of(gate.gateId()));
        }
        // The swept edge is more than its two endpoints: sample the corridor.
        int steps = Math.max(2, Math.min(64, (int) Math.max(
                Math.abs((long) edge.to().x() - edge.from().x()),
                Math.abs((long) edge.to().z() - edge.from().z())) / 4 + 2));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(edge.from().x() + (edge.to().x() - edge.from().x()) * t);
            int z = (int) Math.round(edge.from().z() + (edge.to().z() - edge.from().z()) * t);
            if (intersectsAnyStrip(x, z, roadRadius)) {
                return new Penalty(WALL_PENALTY_COST, true, Optional.empty());
            }
        }
        return new Penalty(0L, false, Optional.empty());
    }

    @Override public Optional<Passage> gateForCrossing(Edge edge) {
        Objects.requireNonNull(edge, "edge");
        ensureOpen();
        for (Passage gate : gates) {
            if (!gate.physicallyReady()) continue;
            if (segmentIntersectsGate(edge, gate)) return Optional.of(gate);
        }
        return Optional.empty();
    }

    /** Arc bucket lookup used by the endpoint agreement path. */
    public Optional<String> gateIdForArcQuality(long arcQ8) {
        ensureOpen();
        return Optional.ofNullable(gateByArcBucket.get(Math.floorDiv(arcQ8, 128L * 256)));
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) release.run();
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("snapshot is closed");
    }

    private static boolean intersectsStrip(Strip strip, Rect bounds) {
        // Strips are bounded by their centerline plus half width; the exact
        // arc interval is authoritative, so a coarse box test only prunes.
        for (Pos point : strip.centerline()) {
            if (bounds.contains(point.x(), point.z())) return true;
        }
        return false;
    }

    private boolean intersectsAnyStrip(int x, int z, int radius) {
        for (Strip strip : strips) {
            for (Pos point : strip.centerline()) {
                long dx = (long) point.x() - x;
                long dz = (long) point.z() - z;
                long reach = strip.halfWidth() + radius;
                if (dx * dx + dz * dz <= reach * reach) return true;
            }
        }
        return false;
    }

    private static boolean segmentIntersectsGate(Edge edge, Passage gate) {
        long minX = Math.min(edge.from().x(), edge.to().x()) - gate.clearWidth() / 2;
        long maxX = Math.max(edge.from().x(), edge.to().x()) + gate.clearWidth() / 2;
        long minZ = Math.min(edge.from().z(), edge.to().z()) - gate.clearWidth() / 2;
        long maxZ = Math.max(edge.from().z(), edge.to().z()) + gate.clearWidth() / 2;
        return gate.center().x() >= minX && gate.center().x() <= maxX
                && gate.center().z() >= minZ && gate.center().z() <= maxZ;
    }

    static ArcInterval intervalFor(Strip strip) {
        return new ArcInterval(strip.fromSQ8(), strip.toSQ8());
    }
}
