package local.plaincraft.greatwall.compat.road;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import local.plaincraft.greatwall.model.WallTypes.ArcInterval;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import net.shiroha233.roadweaver.api.interop.PlanningObstacleProvider;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Passage;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Pos;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Request;
import net.shiroha233.roadweaver.api.interop.WallInteropTypes.Strip;

/**
 * Publishes the wall as an A* obstacle. Preparation is lock-external: it
 * only copies already-frozen plan data into an immutable snapshot and takes
 * a pin that the snapshot releases exactly once. It never waits on another
 * owner's APPLY and never starts a future from inside A*.
 */
public final class WallObstacleProvider implements PlanningObstacleProvider {
    /** One prepared strip may never span more than this arc length. */
    public static final long MAX_STRIP_ARC_Q8 = 512L * 256;
    /** A request domain is bounded; a long road is split by the caller. */
    public static final int MAX_REQUEST_SPAN = 2048;

    private final Executor ioOwner;
    private final java.util.function.BiFunction<Request, BooleanSupplier,
            CompletableFuture<WallSnapshotSource>> source;

    /** Frozen inputs the provider reads. Implementations must be lock-external. */
    public interface WallSnapshotSource {
        String revision();

        List<Strip> strips();

        List<Passage> gates();

        Map<Long, String> gateByArcBucket();
    }

    public WallObstacleProvider(Executor ioOwner,
            java.util.function.BiFunction<Request, BooleanSupplier,
                    CompletableFuture<WallSnapshotSource>> source) {
        this.ioOwner = Objects.requireNonNull(ioOwner, "ioOwner");
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override public String providerId() { return "plaincraft_great_wall:wall"; }

    @Override public int protocolVersion() { return 1; }

    @Override public CompletionStage<net.shiroha233.roadweaver.api.interop.ObstacleSnapshot> prepare(
            Request request, BooleanSupplier cancelled) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancelled, "cancelled");
        int spanX = request.bounds().maxXExclusive() - request.bounds().minX();
        int spanZ = request.bounds().maxZExclusive() - request.bounds().minZ();
        if (spanX > MAX_REQUEST_SPAN || spanZ > MAX_REQUEST_SPAN) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "wall obstacle request domain exceeds " + MAX_REQUEST_SPAN + " blocks; split it"));
        }
        if (cancelled.getAsBoolean()) {
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(
                    "wall obstacle preparation was cancelled before it started"));
        }
        return source.apply(request, cancelled).thenApplyAsync(frozen -> {
            if (cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("wall obstacle preparation was cancelled");
            }
            List<Strip> bounded = new ArrayList<>();
            for (Strip strip : frozen.strips()) {
                if (strip.toSQ8() - strip.fromSQ8() > MAX_STRIP_ARC_Q8) {
                    throw new IllegalStateException("wall strip exceeds the 512-block arc bound: " + strip.id());
                }
                if (overlaps(strip, request.bounds())) bounded.add(strip);
            }
            bounded.sort(java.util.Comparator.comparingLong(Strip::fromSQ8).thenComparing(Strip::id));
            Map<Long, String> buckets = new LinkedHashMap<>();
            for (Map.Entry<Long, String> entry : frozen.gateByArcBucket().entrySet()) {
                buckets.put(entry.getKey(), entry.getValue());
            }
            List<Passage> gates = new ArrayList<>();
            for (Passage gate : frozen.gates()) {
                if (request.bounds().contains(gate.center().x(), gate.center().z())) gates.add(gate);
            }
            return new PinnedWallSnapshot(bounded, gates, buckets, frozen.revision(),
                    () -> { /* the pin is released by the owner that created the source */ });
        }, ioOwner);
    }

    private static boolean overlaps(Strip strip, WallInteropTypes.Rect bounds) {
        for (Pos point : strip.centerline()) {
            if (bounds.contains(point.x(), point.z())) return true;
        }
        return false;
    }

    /** Builds one bounded strip from a centreline; the caller guarantees <= 512 arc blocks. */
    public static Strip strip(String id, List<Point3i> centerline, long halfWidth) {
        Objects.requireNonNull(centerline, "centerline");
        if (centerline.size() < 2) throw new IllegalArgumentException("centreline needs at least two points");
        long from = 0L;
        long to = 0L;
        List<Pos> points = new ArrayList<>(centerline.size());
        for (int i = 0; i < centerline.size(); i++) {
            Point3i point = centerline.get(i);
            points.add(new Pos(point.x(), point.y(), point.z()));
            if (i > 0) {
                Point3i previous = centerline.get(i - 1);
                to += (long) Math.round(Math.hypot(
                        point.x() - (double) previous.x(), point.z() - (double) previous.z()) * 256.0);
            }
        }
        if (to <= from) throw new IllegalArgumentException("strip arc length must be positive");
        if (to - from > MAX_STRIP_ARC_Q8) {
            throw new IllegalArgumentException("strip exceeds the 512-block arc bound");
        }
        return new Strip(id, points, from, to, (int) halfWidth);
    }

    static Optional<ArcInterval> interval(Strip strip) {
        return Optional.of(new ArcInterval(strip.fromSQ8(), strip.toSQ8()));
    }
}
