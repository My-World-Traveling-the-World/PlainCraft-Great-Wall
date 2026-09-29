package local.plaincraft.greatwall.planning;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongFunction;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;

/** Ordered reduction, not O(1) cold lookup. No blocking future consumption or world access. */
public final class PrefixSummaryIndex implements AutoCloseable {
    private final LongFunction<PlanLease<SegmentGeometry>> geometries;
    private final ConcurrentHashMap<Long, CompletableFuture<PrefixSummary>> flights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PrefixSummary> checkpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlanLease<SegmentGeometry>> leases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> state = new ConcurrentHashMap<>();
    private final Executor owner;
    private final Executor io;
    private final SegmentCacheStore cache;

    public PrefixSummaryIndex(LongFunction<PlanLease<SegmentGeometry>> geometries, Executor owner, SegmentCacheStore cache) {
        this(geometries, owner, cache, owner);
    }

    public PrefixSummaryIndex(LongFunction<PlanLease<SegmentGeometry>> geometries, Executor owner,
            SegmentCacheStore cache, Executor io) {
        this.geometries = Objects.requireNonNull(geometries); this.owner = Objects.requireNonNull(owner);
        this.cache = Objects.requireNonNull(cache); this.io = Objects.requireNonNull(io);
        checkpoints.put(0L, origin());
    }

    public static PrefixSummary origin() {
        return new PrefixSummary(0, 0, Optional.empty(), DeterministicHash.sha256("prefix-v1".getBytes(StandardCharsets.UTF_8)));
    }

    public PlanLease<PrefixSummary> requireBefore(long segment) {
        if (segment < 0) throw new IllegalArgumentException("negative prefix");
        var promise = new CompletableFuture<PrefixSummary>();
        if (state.containsKey("closed")) promise.completeExceptionally(closed());
        else {
            var previous = flights.putIfAbsent(segment, promise);
            if (previous != null) promise = previous;
            else {
                var identity = promise;
                identity.whenComplete((v, error) -> flights.remove(segment, identity));
                owner.execute(() -> {
                    if (state.containsKey("closed")) { identity.completeExceptionally(closed()); return; }
                    var before = checkpoints.values().stream().filter(p -> p.beforeSegment() <= segment)
                            .max(java.util.Comparator.comparingLong(PrefixSummary::beforeSegment)).orElseThrow();
                    long diskIndex = segment / 256 * 256;
                    if (diskIndex > before.beforeSegment() && !state.containsKey("cache-read-disabled")) {
                        CompletableFuture.supplyAsync(() -> {
                            try { return cache.readPrefix(diskIndex, cache.recipeHash()); }
                            catch (java.io.IOException failure) { state.put("cache-read-disabled", true); return Optional.<PrefixSummary>empty(); }
                        }, io).whenCompleteAsync((cached, failure) -> {
                            if (failure != null) identity.completeExceptionally(failure);
                            else advance(new Reduction(segment, cached.orElse(before), identity));
                        }, owner);
                    } else advance(new Reduction(segment, before, identity));
                });
            }
        }
        return new PlanLease<>(UUID.randomUUID(), promise, () -> {});
    }

    public PrefixSummary combine(PrefixSummary before, SegmentGeometry next) { return reduce(before, next); }

    public static PrefixSummary reduce(PrefixSummary before, SegmentGeometry next) {
        if (before.terminal().isPresent() || before.beforeSegment() != next.key().index()) {
            throw new IllegalArgumentException("prefix requires exact next segment before terminal");
        }
        long length = Math.addExact(before.lengthBeforeQ8(), next.localSQ8().getLast());
        var provisional = new PrefixSummary(Math.addExact(before.beforeSegment(), 1), length, next.terminal(), before.proofHash());
        byte[] prefix = CanonicalCodec.prefix(provisional);
        byte[] geometryHash = java.util.HexFormat.of().parseHex(next.hash());
        byte[] input = java.util.Arrays.copyOf(prefix, prefix.length + geometryHash.length);
        System.arraycopy(geometryHash, 0, input, prefix.length, geometryHash.length);
        return new PrefixSummary(provisional.beforeSegment(), length, next.terminal(), DeterministicHash.sha256(input));
    }

    private void advance(Reduction r) {
        if (state.containsKey("closed")) { fail(r, closed()); return; }
        if (r.before.beforeSegment() == r.target || r.before.terminal().isPresent()) {
            r.result.complete(r.before); release(r); return;
        }
        try {
            long next = r.before.beforeSegment();
            for (long i = next; i < r.target && i - next < 4; i++) {
                if (!r.pending.containsKey(i)) {
                    var lease = geometries.apply(i);
                    r.pending.put(i, lease); leases.put(lease.id(), lease);
                    if (state.containsKey("closed")) { fail(r, closed()); return; }
                }
            }
            var nextLease = r.pending.get(next);
            nextLease.future().whenCompleteAsync((geometry, error) -> {
                if (error != null) { fail(r, error); return; }
                try {
                    r.before = reduce(r.before, geometry);
                    r.pending.remove(next); leases.remove(nextLease.id()); nextLease.close();
                    if (r.before.beforeSegment() % 256 == 0) {
                        checkpoints.putIfAbsent(r.before.beforeSegment(), r.before);
                        var checkpoint = r.before;
                        if (!state.containsKey("cache-write-disabled")) {
                            CompletableFuture.runAsync(() -> {
                                try { cache.writePrefix(checkpoint, geometry.key().recipeHash()); }
                                catch (java.io.IOException ignored) { state.put("cache-write-disabled", true); }
                            }, io);
                        }
                    }
                    advance(r);
                } catch (Throwable failure) { fail(r, failure); }
            }, owner);
        } catch (Throwable failure) { fail(r, failure); }
    }

    private void fail(Reduction r, Throwable error) { r.result.completeExceptionally(error); release(r); }
    private void release(Reduction r) {
        for (var lease : r.pending.values()) { leases.remove(lease.id()); lease.close(); }
        r.pending.clear();
    }
    private static WallFailure closed() { return new WallFailure(FailureCode.CLOSED, "prefix index closed"); }

    @Override public void close() {
        if (state.putIfAbsent("closed", true) != null) return;
        flights.forEach((key, promise) -> promise.completeExceptionally(closed()));
        leases.forEach((id, lease) -> lease.close());
        leases.clear(); flights.clear(); checkpoints.clear();
    }

    private static final class Reduction {
        private final long target;
        private PrefixSummary before;
        private final CompletableFuture<PrefixSummary> result;
        private final Map<Long, PlanLease<SegmentGeometry>> pending = new HashMap<>();
        private Reduction(long target, PrefixSummary before, CompletableFuture<PrefixSummary> result) {
            this.target = target; this.before = before; this.result = result;
        }
    }
}
