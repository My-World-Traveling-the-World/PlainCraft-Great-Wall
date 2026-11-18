package local.plaincraft.greatwall.planning;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
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

/** 按墙段顺序归约弧长、终点和链哈希；冷查找仍需读几何，不阻塞等待任务。 */
public final class PrefixSummaryIndex implements AutoCloseable {
    /** 只保留最近推进的少量摘要，连续窗口不必从磁盘检查点重走。 */
    private static final int RECENT_LIMIT = 64;
    private static final int CHECKPOINT_STRIDE = 256;
    private final LongFunction<PlanLease<SegmentGeometry>> geometries;
    private final ConcurrentHashMap<Long, CompletableFuture<PrefixSummary>> flights = new ConcurrentHashMap<>();
    // 两张有序表共用锁：关闭和回调交错时，不能把已清理的摘要重新放回。
    private final TreeMap<Long, PrefixSummary> checkpoints = new TreeMap<>();
    private final TreeMap<Long, PrefixSummary> recent = new TreeMap<>();
    private PrefixSummary terminal;
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
                    var before = nearestBefore(segment);
                    long diskIndex = segment / CHECKPOINT_STRIDE * CHECKPOINT_STRIDE;
                    if (before.terminal().isEmpty() && diskIndex > before.beforeSegment()
                            && !state.containsKey("cache-read-disabled")) {
                        try { CompletableFuture.supplyAsync(() -> {
                            try { return cache.readPrefix(diskIndex, cache.recipeHash()); }
                            catch (java.io.IOException failure) { state.put("cache-read-disabled", true); return Optional.<PrefixSummary>empty(); }
                        }, io).whenCompleteAsync((cached, failure) -> {
                            if (failure != null) identity.completeExceptionally(failure);
                            else {
                                cached.ifPresent(this::remember);
                                // IO 等待期间可能已有较新的归约完成；仍须取不超过目标的摘要。
                                var now = nearestBefore(segment);
                                // 较早请求保存的摘要可能已经被淘汰，本次仍可从那里继续。
                                var resume = now.terminal().isPresent() || now.beforeSegment() >= before.beforeSegment()
                                        ? now : before;
                                advance(new Reduction(segment, resume, identity));
                            }
                        }, owner); }
                        catch (java.util.concurrent.RejectedExecutionException full) {
                            state.put("cache-read-disabled", true); advance(new Reduction(segment, before, identity));
                        }
                    } else advance(new Reduction(segment, before, identity));
                });
            }
        }
        return new PlanLease<>(UUID.randomUUID(), promise, () -> {});
    }

    public PrefixSummary combine(PrefixSummary before, SegmentGeometry next) { return reduce(before, next); }

    /** 更晚的摘要含有目标之后的弧长和哈希，不能用于回查。 */
    private PrefixSummary nearestBefore(long segment) {
        synchronized (checkpoints) {
            if (terminal != null && terminal.beforeSegment() <= segment) return terminal;
            var checkpoint = checkpoints.floorEntry(segment);
            var nearby = recent.floorEntry(segment);
            if (nearby != null && (checkpoint == null || nearby.getKey() > checkpoint.getKey())) {
                return nearby.getValue();
            }
            // 关闭已清表时只把空摘要交给 advance，由它按关闭状态结束请求。
            return checkpoint == null ? origin() : checkpoint.getValue();
        }
    }

    /** 只保存不可变摘要，不钉住任何几何租约；海洋终点不参与淘汰。 */
    private void remember(PrefixSummary summary) {
        synchronized (checkpoints) {
            if (state.containsKey("closed")) return;
            long segment = summary.beforeSegment();
            if (summary.terminal().isPresent() && (terminal == null || segment < terminal.beforeSegment())) {
                terminal = summary;
            }
            if (segment % CHECKPOINT_STRIDE == 0) {
                checkpoints.putIfAbsent(segment, summary);
            } else if (summary.terminal().isEmpty()) {
                // 终点已经单独保留，留出最近摘要的空间给可继续推进的前缀。
                recent.putIfAbsent(segment, summary);
                if (recent.size() > RECENT_LIMIT) recent.pollFirstEntry();
            }
        }
    }

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
                    remember(r.before);
                    if (r.before.beforeSegment() % CHECKPOINT_STRIDE == 0) {
                        var checkpoint = r.before;
                        if (!state.containsKey("cache-write-disabled")) {
                            try { CompletableFuture.runAsync(() -> {
                                try { cache.writePrefix(checkpoint, geometry.key().recipeHash()); }
                                catch (java.io.IOException ignored) { state.put("cache-write-disabled", true); }
                            }, io); }
                            catch (java.util.concurrent.RejectedExecutionException full) { state.put("cache-write-disabled", true); }
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
        leases.clear(); flights.clear();
        synchronized (checkpoints) {
            checkpoints.clear();
            recent.clear();
            terminal = null;
        }
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
