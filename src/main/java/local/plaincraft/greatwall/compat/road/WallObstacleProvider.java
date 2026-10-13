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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallTypes.PlanWindow;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.Priority;
import local.plaincraft.greatwall.persistence.GateLedger;
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
    private record Binding(WorldServices services, GateLedger gates) {}
    private static final ConcurrentHashMap<UUID, Binding> SESSIONS = new ConcurrentHashMap<>();
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

        default List<PlanLease<PlanWindow>> leases() { return List.of(); }
    }

    private static final java.util.concurrent.ExecutorService SHARED_IO = java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)), task -> {
        Thread thread = new Thread(task, "pgw-wall-provider-io");
        thread.setDaemon(true);
        return thread;
    });

    public WallObstacleProvider() {
        // A bounded dedicated pool, never ForkJoinPool.commonPool: the shared pool is borrowed by
        // mod loading and other systems, and a snapshot build queued there would stall the wall's
        // obstacle provider (and through it, road planning). RoadWeaver avoids commonPool for the
        // same reason (see RoadChunkStore).
        this(SHARED_IO, WallObstacleProvider::prepareRuntime);
    }

    static void bind(WorldServices services, GateLedger gates) {
        var level=services.overworld();
        if(!level.getServer().isSameThread())throw new IllegalStateException("wall provider binding requires server owner");
        var binding=new Binding(services,Objects.requireNonNull(gates));
        var previous=SESSIONS.putIfAbsent(services.runtimeSession(),binding);
        if(previous!=null&&(!previous.services().equals(services)||previous.gates()!=gates))
            throw new IllegalStateException("wall provider session already bound");
    }

    static void closeSession(UUID session) { SESSIONS.remove(session); }

    private static CompletableFuture<WallSnapshotSource> prepareRuntime(Request request,BooleanSupplier cancelled) {
        var binding=SESSIONS.get(request.session());
        if(binding==null)return CompletableFuture.failedFuture(new IllegalStateException("wall session has no prepared history binding"));
        var services=binding.services();
        try { services.assertActiveSession(); }
        catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}
        var leases=new ConcurrentHashMap<UUID,PlanLease<PlanWindow>>();
        var result=new CompletableFuture<WallSnapshotSource>();
        services.recipeReady().thenCompose(recipe->{
            services.assertActiveSession();
            if(SESSIONS.get(request.session())!=binding)throw new IllegalStateException("closed wall provider session");
            if(!recipe.dimension().equals(request.dimension()))
                throw new IllegalArgumentException("wall request dimension does not match session");
            CompletableFuture<List<PlanWindow>> chain=CompletableFuture.completedFuture(List.of());
            try {
                for(long x=request.bounds().minX();x<request.bounds().maxXExclusive();x+=512)
                    for(long z=request.bounds().minZ();z<request.bounds().maxZExclusive();z+=512) {
                        if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException("wall request cancelled");
                        int cx=Math.toIntExact(Math.floorDiv(Math.min(x+256,(long)request.bounds().maxXExclusive()-1),16));
                        int cz=Math.toIntExact(Math.floorDiv(Math.min(z+256,(long)request.bounds().maxZExclusive()-1),16));
                        var lease=services.planning().requireWindow(new ChunkKey(request.dimension(),cx,cz),512,Priority.ROAD);
                        leases.put(lease.id(),lease);
                        chain=chain.thenCombine(lease.future(),(windows,window)->{
                            var copy=new ArrayList<>(windows);copy.add(window);return List.copyOf(copy);
                        });
                    }
            } catch(Throwable error){return CompletableFuture.failedFuture(error);}
            return chain.thenApply(windows->{
                services.assertActiveSession();
                if(SESSIONS.get(request.session())!=binding)throw new IllegalStateException("closed wall provider session");
                if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException("wall request cancelled");
                return WallSnapshotBuilder.build(recipe.recipeHash(),recipe.wallId(),request,windows,
                        binding.gates().activeSnapshot(),List.copyOf(leases.values()));
            });
        }).whenComplete((snapshot,error)->{
            if(error!=null){leases.values().forEach(PlanLease::close);result.completeExceptionally(error);}
            else if(!result.complete(snapshot))snapshot.leases().forEach(PlanLease::close);
        });
        return result;
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
        long spanX = (long)request.bounds().maxXExclusive() - request.bounds().minX();
        long spanZ = (long)request.bounds().maxZExclusive() - request.bounds().minZ();
        if (spanX > MAX_REQUEST_SPAN || spanZ > MAX_REQUEST_SPAN) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "wall obstacle request domain exceeds " + MAX_REQUEST_SPAN + " blocks; split it"));
        }
        if (cancelled.getAsBoolean()) {
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(
                    "wall obstacle preparation was cancelled before it started"));
        }
        CompletableFuture<WallSnapshotSource> sourceFuture;
        try {
            new local.plaincraft.greatwall.diagnostics.StageTrace(request.session()).assertOutsideLock();
            sourceFuture=source.apply(request, cancelled);
        } catch(Throwable failure){return CompletableFuture.failedFuture(failure);}
        var result=sourceFuture.thenApplyAsync(frozen -> {
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
            return (net.shiroha233.roadweaver.api.interop.ObstacleSnapshot)new PinnedWallSnapshot(bounded, gates,
                    frozen.leases(), buckets, frozen.revision());
        }, ioOwner);
        result.whenComplete((snapshot,error)->{
            if(error!=null)sourceFuture.thenAccept(frozen->frozen.leases().forEach(PlanLease::close));
        });
        return result;
    }

    private static boolean overlaps(Strip strip, WallInteropTypes.Rect bounds) {
        return WallObstacleGeometry.intersects(strip,bounds);
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
