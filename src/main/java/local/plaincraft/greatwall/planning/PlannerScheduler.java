package local.plaincraft.greatwall.planning;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import local.plaincraft.greatwall.diagnostics.BudgetCache;
import local.plaincraft.greatwall.geometry.CanonicalCodec;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.geometry.Projection;
import local.plaincraft.greatwall.model.PlanLease;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import local.plaincraft.greatwall.persistence.SegmentCacheStore;
import local.plaincraft.greatwall.terrain.HydrologyClassifier;
import local.plaincraft.greatwall.terrain.NaturalTerrainOracle;

/** Pure S1A scheduler. Integration must acquire its futures before any FEATURES region lock. */
public final class PlannerScheduler implements AutoCloseable {
    private static final long MIB = 1024L * 1024;
    private final WorldRecipe recipe;
    private final ConcurrentHashMap<SegmentKey, CompletableFuture<RouteShape>> routeFlights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SegmentKey, CompletableFuture<SegmentGeometry>> geometryFlights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SegmentKey, CompletableFuture<SegmentPlan>> planFlights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, JobProgress> progress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlanLease<?>> callerLeases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<PlanLease<Object>>> acquisitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> state = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor pump;
    private final PrefixSummaryIndex prefixes;
    private final Supplier<NaturalTerrainOracle> oracleFactory;
    private final SegmentCacheStore cache;
    private final Executor io;
    private final AnchorField anchors;
    private final RidgePlanner ridge;
    private final HydrologyClassifier hydrology = new HydrologyClassifier();
    private final HeightContractPlanner heights;
    private final SiteScheduler sites;
    private final BudgetCache<CacheKey, Object> memory;
    private final List<ExecutorService> lanes;
    private final List<Lane> laneState = List.of(new Lane(), new Lane());
    private final PriorityQueue<Job<?>> pending = new PriorityQueue<>(Comparator.<Job<?>, Priority>comparing(j -> j.priority)
            .thenComparingLong(j -> j.sequence));
    private final ArrayDeque<Runnable> geometryQueue = new ArrayDeque<>();
    private final ArrayDeque<Runnable> ioQueue = new ArrayDeque<>();
    private final ArrayDeque<Runnable> planAdmissions = new ArrayDeque<>(), geometryAdmissions = new ArrayDeque<>();
    private final Map<UUID, Job<?>> activeJobs = new HashMap<>();
    private int activeGeometry, credits, activeIo, admittedPlans, admittedGeometries;
    private long deadline, sequence, background = 4;
    private ScheduledFuture<?> startupTimer;
    private PlanLease<SegmentPlan> backgroundLease;

    public PlannerScheduler(WorldRecipe recipe, Supplier<NaturalTerrainOracle> oracleFactory, SegmentCacheStore cache, Executor io) {
        this.recipe = Objects.requireNonNull(recipe); this.oracleFactory = Objects.requireNonNull(oracleFactory);
        this.cache = Objects.requireNonNull(cache); this.io = Objects.requireNonNull(io);
        if (!"minecraft:overworld".equals(recipe.dimension())) {
            throw new WallFailure(FailureCode.INVALID_RESOURCE, "only the overworld may own a wall planner");
        }
        if (!DeterministicHash.sha256(CanonicalCodec.recipe(recipe)).equals(recipe.recipeHash())) {
            throw new WallFailure(FailureCode.RECIPE_MISMATCH, "recipe semantic hash is not canonical");
        }
        if (!cache.recipeHash().equals(recipe.recipeHash())) throw new IllegalArgumentException("cache recipe mismatch");
        this.anchors = new AnchorField(recipe); this.ridge = new RidgePlanner(recipe, anchors);
        this.heights = new HeightContractPlanner(recipe); this.sites = new SiteScheduler(recipe);
        this.pump = new ScheduledThreadPoolExecutor(1, factory("pgw-planner-" + recipe.instance()));
        pump.setRemoveOnCancelPolicy(true);
        this.lanes = List.of(Executors.newSingleThreadExecutor(factory("pgw-oracle-0-" + recipe.instance())),
                Executors.newSingleThreadExecutor(factory("pgw-oracle-1-" + recipe.instance())));
        this.memory = new BudgetCache<>(recipe.config().segmentBytes(), 256, 4096, pump, PlannerScheduler::retainedBytes);
        this.prefixes = new PrefixSummaryIndex(i -> requireGeometry(i, Priority.PLAYER_PREFETCH), pump, cache, this::submitIo);
    }

    public PlanLease<RouteShape> requireRoute(long segment, Priority priority) {
        var key = key(segment);
        promote(segment, priority);
        return acquire(new CacheKey("route", segment), MIB, RouteShape.class, () -> {
            var result = flight(routeFlights, key);
            enqueue(new RouteJob(key, priority, result));
            return result.thenApply(v -> v);
        });
    }

    public PlanLease<SegmentGeometry> requireGeometry(long segment, Priority priority) {
        var key = key(segment);
        promote(segment, priority);
        return acquire(new CacheKey("geometry", segment), 2 * MIB, SegmentGeometry.class, () -> {
            var result = flight(geometryFlights, key);
            geometryQueue.add(() -> startGeometry(key, priority, result));
            drainGeometry();
            return result.thenApply(v -> v);
        });
    }

    public PlanLease<SegmentPlan> requirePlan(long segment, Priority priority) {
        var key = key(segment);
        promote(segment, priority);
        return acquire(new CacheKey("plan", segment), 4 * MIB, SegmentPlan.class, () -> {
            var result = flight(planFlights, key);
            var geometry = requireGeometry(segment, priority);
            var prefix = prefixes.requireBefore(segment);
            geometry.future().thenCombineAsync(prefix.future(), Pair::new, pump).whenCompleteAsync((inputs, error) -> {
                if (error != null) { geometry.close(); prefix.close(); result.completeExceptionally(error); return; }
                if (closed()) { geometry.close(); prefix.close(); result.completeExceptionally(closedFailure()); return; }
                if (inputs.prefix.terminal().isPresent() && inputs.prefix.terminal().get().segment() < segment) {
                    var table = new StationTable(List.of(), List.of(), "0".repeat(64));
                    table = new StationTable(List.of(), List.of(), DeterministicHash.sha256(CanonicalCodec.stations(table)));
                    result.complete(hashedPlan(inputs.geometry, inputs.prefix, table, List.of()));
                    geometry.close(); prefix.close(); return;
                }
                ioRead(() -> cache.readPlan(key)).whenCompleteAsync((cached, cacheError) -> {
                    if (closed()) { geometry.close(); prefix.close(); result.completeExceptionally(closedFailure()); return; }
                    if (cacheError == null && cached.isPresent() && cached.get().prefix().equals(inputs.prefix)
                            && cached.get().geometry().hash().equals(inputs.geometry.hash())) {
                        result.complete(cached.get()); geometry.close(); prefix.close(); return;
                    }
                    preparePlan(key, priority, inputs, result, List.of(geometry, prefix));
                }, pump);
            }, pump);
            return result.thenApply(v -> v);
        });
    }

    public PlanLease<PlanWindow> requireWindow(ChunkKey chunk, int marginBlocks, Priority priority) {
        if (closed()) throw closedFailure();
        if (marginBlocks < 0 || marginBlocks > 512) throw new IllegalArgumentException("window margin 0..512");
        if (!chunk.dimension().equals(recipe.dimension())) {
            return new PlanLease<>(UUID.randomUUID(), CompletableFuture.completedFuture(new PlanWindow(List.of())), () -> {});
        }
        int x0 = Math.toIntExact(Math.multiplyExact((long) chunk.x(), 16));
        int z0 = Math.toIntExact(Math.multiplyExact((long) chunk.z(), 16));
        long minU = Long.MAX_VALUE, maxU = Long.MIN_VALUE, minB = Long.MAX_VALUE, maxB = Long.MIN_VALUE;
        for (int dx : new int[] {0, 15}) for (int dz : new int[] {0, 15}) {
            int x = Math.addExact(x0, dx), z = Math.addExact(z0, dz);
            long u = Projection.axisUQ8(recipe.axis(), x, z);
            minU = Math.min(minU, u); maxU = Math.max(maxU, u);
            long side = Math.addExact(-Math.multiplyExact((long) x - recipe.axis().origin().x(), recipe.axis().ezQ30()),
                    Math.multiplyExact((long) z - recipe.axis().origin().z(), recipe.axis().exQ30()));
            long b = FixedMath.roundDiv(side, 1L << 22);
            minB = Math.min(minB, b); maxB = Math.max(maxB, b);
        }
        long lateral = (512L + 192 + 96 + marginBlocks) * 256;
        if (minB > lateral || maxB < -lateral || maxU + (96L + marginBlocks) * 256 < 0) {
            return new PlanLease<>(UUID.randomUUID(), CompletableFuture.completedFuture(new PlanWindow(List.of())), () -> {});
        }
        long first = Math.max(0, Projection.segmentAt(minU - (96L + marginBlocks) * 256));
        long last = Math.max(0, Projection.segmentAt(maxU + (96L + marginBlocks) * 256));
        if (last - first >= 5) throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "window requires more than five segments");
        var leases = new ArrayList<PlanLease<SegmentPlan>>();
        CompletableFuture<List<SegmentPlan>> chain = CompletableFuture.completedFuture(List.of());
        try {
            for (long i = first; i <= last; i++) {
                if (!safeSegment(i)) break;
                var lease = requirePlan(i, priority); leases.add(lease);
                chain = chain.thenCombineAsync(lease.future(), (list, plan) -> {
                    var copy = new ArrayList<>(list); copy.add(plan); return List.copyOf(copy);
                }, pump);
            }
        } catch (Throwable failure) {
            leases.forEach(PlanLease::close);
            return new PlanLease<>(UUID.randomUUID(), CompletableFuture.failedFuture(failure), () -> {});
        }
        return outer(chain.thenApply(list -> new PlanWindow(list.stream().filter(p -> p.prefix().terminal().isEmpty()
                || p.prefix().terminal().get().segment() >= p.geometry().key().index()).toList())), () -> leases.forEach(PlanLease::close));
    }

    public void startupPump() {
        if (closed()) throw closedFailure();
        if (state.putIfAbsent("startup", 1L) != null) return;
        pump.execute(() -> {
            startupTimer = pump.scheduleWithFixedDelay(() -> {
                credits = 4096; deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25); dispatch();
            }, 0, 1, TimeUnit.MILLISECONDS);
            var prewarm = new ArrayList<PlanLease<SegmentPlan>>();
            for (long i = 0; i < 4 && safeSegment(i); i++) prewarm.add(requirePlan(i, Priority.PLAYER_PREFETCH));
            CompletableFuture.allOf(prewarm.stream().map(PlanLease::future).toArray(CompletableFuture[]::new))
                    .whenCompleteAsync((value, error) -> {
                        prewarm.forEach(PlanLease::close);
                        state.put(error == null ? "startup-ready" : "startup-failed", 1L);
                        if (startupTimer != null) startupTimer.cancel(false);
                    }, pump);
        });
    }

    public void tick(SliceBudget budget) {
        Objects.requireNonNull(budget);
        if (closed()) return;
        // Only the most recent server budget is retained; a stalled pump cannot queue unbounded ticks.
        state.put("tick-operations", (long) budget.maxOperations()); state.put("tick-deadline", budget.deadlineNanos());
        if (state.putIfAbsent("tick-enqueued", 1L) != null) return;
        pump.execute(() -> {
            state.remove("tick-enqueued");
            credits = Math.toIntExact(Math.min(65536, state.getOrDefault("tick-operations", 0L)));
            deadline = state.getOrDefault("tick-deadline", 0L);
            if (state.containsKey("startup-ready") && !state.containsKey("background-terminal") && backgroundLease == null && safeSegment(background)) {
                backgroundLease = requirePlan(background++, Priority.BACKGROUND);
                var lease = backgroundLease;
                lease.future().whenCompleteAsync((v, e) -> {
                    if (v != null && (v.geometry().terminal().isPresent() || v.prefix().terminal().isPresent())) state.put("background-terminal", 1L);
                    lease.close(); backgroundLease = null;
                }, pump);
            }
            dispatch();
        });
    }

    public Map<UUID, JobProgress> progress() { return Map.copyOf(progress); }
    public Map<String, Long> diagnostics() { return Map.copyOf(state); }

    public SegmentGeometry computeGeometryNow(long segment) {
        key(segment);
        String thread = Thread.currentThread().getName().toLowerCase(java.util.Locale.ROOT);
        if (thread.contains("worldgen") || thread.contains("c2me")) {
            throw new IllegalStateException("synchronous diagnostic computation is forbidden on worldgen threads");
        }
        long started = System.nanoTime();
        try (var oracle = oracleFactory.get()) {
            var center = computeRoute(segment, oracle);
            var previous = segment == 0 ? Optional.<RouteShape>empty() : Optional.of(computeRoute(segment - 1, oracle));
            var next = safeSegment(segment + 1) ? Optional.of(computeRoute(segment + 1, oracle)) : Optional.<RouteShape>empty();
            var cursor = hydrology.begin(new RouteNeighborhood(center, previous, next), oracle, 24000);
            while (hydrology.advance(cursor, 64) != WorkState.DONE) if (closed()) throw closedFailure();
            return geometry(center, hydrology.finish(cursor), cursor.logicalColumns(), next.isEmpty());
        } finally {
            state.merge("synchronous-count", 1L, Long::sum);
            state.merge("synchronous-max-nanos", System.nanoTime() - started, Math::max);
        }
    }

    private RouteShape computeRoute(long segment, NaturalTerrainOracle oracle) {
        var c = ridge.begin(key(segment), oracle);
        while (ridge.advance(c, 64) != WorkState.DONE) if (closed()) throw closedFailure();
        return ridge.finish(c);
    }

    private void startGeometry(SegmentKey key, Priority priority, CompletableFuture<SegmentGeometry> result) {
        activeGeometry++;
        result.whenCompleteAsync((v, error) -> { activeGeometry--; drainGeometry(); }, pump);
        ioRead(() -> cache.readGeometry(key)).whenCompleteAsync((cached, error) -> {
            if (closed()) { result.completeExceptionally(closedFailure()); return; }
            if (error == null && cached.isPresent()) { result.complete(cached.get()); return; }
            var center = requireRoute(key.index(), priority);
            var previous = key.index() == 0 ? null : requireRoute(key.index() - 1, priority);
            var next = safeSegment(key.index() + 1) ? requireRoute(key.index() + 1, priority) : null;
            var previousFuture = previous == null ? CompletableFuture.<RouteShape>completedFuture(null) : previous.future();
            var nextFuture = next == null ? CompletableFuture.<RouteShape>completedFuture(null) : next.future();
            var owned = new ArrayList<PlanLease<?>>(); owned.add(center); if (previous != null) owned.add(previous); if (next != null) owned.add(next);
            center.future().thenCombineAsync(previousFuture, (a, b) -> new RouteNeighborhood(a, Optional.ofNullable(b), Optional.empty()), pump)
                    .thenCombineAsync(nextFuture, (a, b) -> new RouteNeighborhood(a.center(), a.previous(), Optional.ofNullable(b)), pump)
                    .whenCompleteAsync((routes, failure) -> {
                        if (failure != null) { owned.forEach(PlanLease::close); result.completeExceptionally(failure); return; }
                        var job = new HydroJob(routes, priority, result);
                        result.whenComplete((v, e) -> owned.forEach(PlanLease::close));
                        enqueue(job);
                    }, pump);
        }, pump);
    }

    private void drainGeometry() {
        while (!closed() && activeGeometry < recipe.config().maxGeometryInflight() && !geometryQueue.isEmpty()) geometryQueue.removeFirst().run();
    }

    private void preparePlan(SegmentKey key, Priority priority, Pair inputs, CompletableFuture<SegmentPlan> result, List<PlanLease<?>> initial) {
        var owned = new ArrayList<PlanLease<?>>(initial);
        CompletableFuture<GeometryWindow> window = CompletableFuture.completedFuture(new GeometryWindow(List.of(inputs.geometry), List.of(inputs.prefix)));
        for (long neighbor : new long[] {key.index() - 1, key.index() + 1}) {
            if (neighbor < 0 || !safeSegment(neighbor) || inputs.geometry.terminal().isPresent() && neighbor > key.index()) continue;
            var geometry = requireGeometry(neighbor, priority); var prefix = prefixes.requireBefore(neighbor);
            owned.add(geometry); owned.add(prefix);
            var pair = geometry.future().thenCombineAsync(prefix.future(), Pair::new, pump);
            window = window.thenCombineAsync(pair, (w, p) -> {
                if (p.prefix.beforeSegment() != p.geometry.key().index()) return w;
                var gs = new ArrayList<>(w.segments()); gs.add(p.geometry);
                var ps = new ArrayList<>(w.prefixes()); ps.add(p.prefix);
                return new GeometryWindow(gs, ps);
            }, pump);
        }
        result.whenComplete((v, e) -> owned.forEach(PlanLease::close));
        window.whenCompleteAsync((w, error) -> {
            if (error != null) { result.completeExceptionally(error); return; }
            try {
                long from = inputs.prefix.lengthBeforeQ8(), to = from + inputs.geometry.localSQ8().getLast();
                var provisional = sites.schedule(w, from, to);
                var points = heights.samplePoints(inputs.geometry, inputs.prefix, provisional);
                enqueue(new ContractJob(inputs, provisional, points, priority, result));
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        }, pump);
    }

    private SegmentGeometry geometry(RouteShape route, List<WaterEvidence> water, int hydroColumns, boolean last) {
        Optional<Terminal> terminal = water.stream().filter(w -> w.classification() == WaterClass.WIDE
                && w.crossing().fromQ8() >= 0 && w.crossing().fromQ8() < route.localSQ8().getLast())
                .map(w -> new Terminal(route.key().index(), w.crossing().fromQ8(), TerminalKind.OCEAN))
                .min(Comparator.comparingLong(Terminal::localSQ8));
        if (terminal.isEmpty() && last) terminal = Optional.of(new Terminal(route.key().index(), route.localSQ8().getLast(), TerminalKind.WORLD_LIMIT));
        var g = new SegmentGeometry(route.key(), route.points(), route.localSQ8(), water, terminal, route.start(), route.end(),
                route.searchColumns() + route.boundaryColumns() + hydroColumns, route.degradation(), "0".repeat(64));
        return new SegmentGeometry(g.key(), g.points(), g.localSQ8(), g.water(), g.terminal(), g.start(), g.end(), g.logicalColumns(), g.degradation(),
                DeterministicHash.sha256(CanonicalCodec.geometry(g)));
    }

    private static SegmentPlan hashedPlan(SegmentGeometry g, PrefixSummary p, StationTable t, List<SiteRecord> sites) {
        var plan = new SegmentPlan(g, p, t, sites, "0".repeat(64));
        return new SegmentPlan(g, p, t, sites, DeterministicHash.sha256(CanonicalCodec.plan(plan)));
    }

    private void enqueue(Job<?> job) {
        if (closed()) { job.result.completeExceptionally(closedFailure()); return; }
        pending.add(job); activeJobs.put(job.id, job);
        progress.put(job.id, new JobProgress(job.id, job.phase, 0, 1, true));
        dispatch();
    }

    private void promote(long segment, Priority priority) {
        Objects.requireNonNull(priority);
        pump.execute(() -> {
            for (var job : activeJobs.values()) {
                long index = switch (job) {
                    case RouteJob route -> route.key.index();
                    case HydroJob hydro -> hydro.routes.center().key().index();
                    case ContractJob contract -> contract.inputs.geometry.key().index();
                    default -> throw new IllegalStateException("unknown job");
                };
                if (index == segment && priority.compareTo(job.priority) < 0) {
                    boolean queued = pending.remove(job);
                    job.priority = priority;
                    if (queued) pending.add(job);
                }
            }
        });
    }

    private void dispatch() {
        if (closed()) return;
        for (int lane = 0; lane < laneState.size() && credits > 0 && System.nanoTime() < deadline; lane++) {
            var slot = laneState.get(lane);
            if (slot.busy) continue;
            if (slot.job == null) slot.job = pending.poll();
            if (slot.job == null) continue;
            int operations = Math.min(64, credits); credits -= operations;
            var job = slot.job; slot.busy = true;
            lanes.get(lane).execute(() -> {
                Throwable error = null; boolean done = false; Object value = null;
                long started = System.nanoTime();
                try {
                    if (closed()) throw closedFailure();
                    if (job.oracle == null) {
                        job.oracle = oracleFactory.get();
                        if (!job.oracle.fingerprint().equals(recipe.terrainHash())) throw new WallFailure(
                                FailureCode.RECIPE_MISMATCH, "lane oracle fingerprint mismatch");
                    }
                    done = job.advance(operations);
                    if (done) value = job.value();
                } catch (Throwable failure) { error = failure; done = true; }
                long duration = System.nanoTime() - started;
                state.merge("max-batch-nanos", duration, Math::max);
                if (duration > TimeUnit.MILLISECONDS.toNanos(40)) state.merge("over-40ms-batches", 1L, Long::sum);
                if (done && job.oracle != null) {
                    try { job.oracle.close(); } catch (Throwable closeError) { if (error == null) error = closeError; }
                    finally { job.oracle = null; }
                }
                boolean finished = done; Throwable failure = error; Object output = value;
                try { pump.execute(() -> {
                    slot.busy = false;
                    if (closed()) { job.result.completeExceptionally(closedFailure()); return; }
                    if (finished) {
                        slot.job = null; activeJobs.remove(job.id); progress.remove(job.id);
                        if (failure != null) job.result.completeExceptionally(failure); else job.complete(output);
                    } else {
                        progress.put(job.id, new JobProgress(job.id, job.phase, job.operations, Long.MAX_VALUE, true));
                    }
                    dispatch();
                }); } catch (RejectedExecutionException stopped) { job.result.completeExceptionally(closedFailure()); }
            });
        }
    }

    private <T> CompletableFuture<T> flight(ConcurrentHashMap<SegmentKey, CompletableFuture<T>> flights, SegmentKey key) {
        var future = new CompletableFuture<T>();
        if (flights.putIfAbsent(key, future) != null) throw new IllegalStateException("cache started duplicate loader");
        future.whenComplete((v, error) -> flights.remove(key, future)); return future;
    }

    private <T> PlanLease<T> acquire(CacheKey key, long upper, Class<T> type, Supplier<CompletionStage<Object>> loader) {
        if (closed()) return new PlanLease<>(UUID.randomUUID(), CompletableFuture.failedFuture(closedFailure()), () -> {});
        var acquired = new CompletableFuture<PlanLease<Object>>();
        UUID id = UUID.randomUUID();
        boolean[] accepted = {false};
        state.compute("acquisitions", (k, old) -> {
            long count = old == null ? 0 : old;
            if (count >= 4096) return count;
            accepted[0] = true; return count + 1;
        });
        if (!accepted[0]) return new PlanLease<>(id, CompletableFuture.failedFuture(
                new WallFailure(FailureCode.CAPACITY, "planner acquisition capacity")), () -> {});
        acquisitions.put(id, acquired);
        acquired.whenComplete((v, e) -> {
            acquisitions.remove(id); state.compute("acquisitions", (k, old) -> old - 1);
        });
        pump.execute(() -> {
            if (closed()) { acquired.completeExceptionally(closedFailure()); return; }
            Runnable start = () -> startAcquire(key, upper, loader, acquired);
            if (key.kind.equals("plan")) planAdmissions.add(start);
            else if (key.kind.equals("geometry")) geometryAdmissions.add(start);
            else start.run();
            drainAdmissions();
        });
        CompletableFuture<T> view = acquired.thenCompose(lease -> lease.future().thenApply(type::cast));
        return outer(view, () -> acquired.thenAccept(PlanLease::close));
    }

    private void startAcquire(CacheKey key, long upper, Supplier<CompletionStage<Object>> loader,
            CompletableFuture<PlanLease<Object>> result) {
        if (key.kind.equals("plan")) admittedPlans++;
        if (key.kind.equals("geometry")) admittedGeometries++;
        memory.acquire(key, upper, loader).whenCompleteAsync((lease, failure) -> {
            if (key.kind.equals("plan")) admittedPlans--;
            if (key.kind.equals("geometry")) admittedGeometries--;
            if (failure != null) result.completeExceptionally(failure);
            else if (!result.complete(lease)) lease.close();
            drainAdmissions();
        }, pump);
    }

    private void drainAdmissions() {
        // Reserve dependency headroom before allocating a parent plan/geometry reservation.
        while (!closed() && admittedGeometries < 4 && !geometryAdmissions.isEmpty()) geometryAdmissions.removeFirst().run();
        while (!closed() && admittedPlans < 4 && !planAdmissions.isEmpty()) planAdmissions.removeFirst().run();
    }

    private <T> PlanLease<T> outer(CompletionStage<T> shared, Runnable release) {
        UUID id = UUID.randomUUID();
        boolean[] admitted = {false};
        state.compute("leases", (k, old) -> {
            long count = old == null ? 0 : old;
            if (closed() || count >= 4096) return count;
            admitted[0] = true; return count + 1;
        });
        if (!admitted[0]) {
            release.run();
            return new PlanLease<>(id, CompletableFuture.failedFuture(closed() ? closedFailure()
                    : new WallFailure(FailureCode.CAPACITY, "planner lease limit")), () -> {});
        }
        var lease = new PlanLease<T>(id, shared, () -> {
            callerLeases.remove(id); state.compute("leases", (k, old) -> old - 1); release.run();
        });
        callerLeases.put(id, lease);
        if (closed()) lease.close();
        return lease;
    }

    private SegmentKey key(long segment) {
        if (closed()) throw closedFailure();
        if (!safeSegment(segment)) throw new WallFailure(FailureCode.OUT_OF_BOUNDS, "segment exceeds complete frozen envelope");
        return new SegmentKey(recipe.recipeHash(), recipe.dimension(), segment);
    }

    private boolean safeSegment(long segment) {
        if (segment < 0) return false;
        try {
            // 512 carrier + 192 ridge + 96 component + 48 reference side-column envelope.
            long radius = 848L * 256, limit = recipe.bounds().horizontalLimit() * 256L;
            for (long index : new long[] {segment, Math.addExact(segment, 1)}) {
                long u = Math.multiplyExact(index, 2048L * 256);
                long x = Math.addExact(recipe.axis().origin().x() * 256L, FixedMath.mulDiv(u, recipe.axis().exQ30(), 1L << 30));
                long z = Math.addExact(recipe.axis().origin().z() * 256L, FixedMath.mulDiv(u, recipe.axis().ezQ30(), 1L << 30));
                if (x - radius < -limit || x + radius > limit || z - radius < -limit || z + radius > limit) return false;
            }
            return true;
        } catch (ArithmeticException overflow) { return false; }
    }

    private <T> CompletableFuture<Optional<T>> ioRead(IoRead<T> operation) {
        if (state.containsKey("cache-disabled")) return CompletableFuture.completedFuture(Optional.empty());
        var result = new CompletableFuture<Optional<T>>();
        try {
            submitIo(() -> {
                try { result.complete(operation.read()); }
                catch (IOException error) { state.put("cache-disabled", 1L); result.complete(Optional.empty()); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
        } catch (RejectedExecutionException full) { state.put("cache-disabled", 1L); result.complete(Optional.empty()); }
        return result;
    }

    private void submitIo(Runnable task) {
        if (closed()) throw new RejectedExecutionException("planner closed");
        boolean[] admitted = {false};
        state.compute("io-pending", (k, old) -> {
            long count = old == null ? 0 : old;
            if (count >= 128) return count;
            admitted[0] = true; return count + 1;
        });
        if (!admitted[0]) throw new RejectedExecutionException("planner cache IO queue capacity");
        try {
            pump.execute(() -> {
                ioQueue.add(() -> { try { task.run(); } finally { state.compute("io-pending", (k, old) -> old - 1); } });
                drainIo();
            });
        } catch (RuntimeException rejected) { state.compute("io-pending", (k, old) -> old - 1); throw rejected; }
    }

    private void drainIo() {
        if (activeIo != 0 || ioQueue.isEmpty() || closed()) return;
        activeIo = 1; Runnable next = ioQueue.removeFirst();
        try { io.execute(() -> {
            try { next.run(); }
            finally {
                try { pump.execute(() -> { activeIo = 0; drainIo(); }); }
                catch (RejectedExecutionException ignored) { /* Closing already completed public promises. */ }
            }
        }); } catch (RuntimeException rejected) {
            activeIo = 0; state.put("cache-disabled", 1L);
            var failure = new WallFailure(FailureCode.IO_FAILED, "IO executor rejected a required continuation", rejected);
            routeFlights.forEach((key, future) -> future.completeExceptionally(failure));
            geometryFlights.forEach((key, future) -> future.completeExceptionally(failure));
            planFlights.forEach((key, future) -> future.completeExceptionally(failure));
            close();
        }
    }

    private void persist(SegmentGeometry value) {
        if (!state.containsKey("cache-disabled") && !closed()) try {
            submitIo(() -> { try { cache.writeGeometry(value); } catch (IOException error) { state.put("cache-disabled", 1L); } });
        } catch (RejectedExecutionException full) { state.put("cache-disabled", 1L); }
    }
    private void persist(SegmentPlan value) {
        if (!state.containsKey("cache-disabled") && !closed()) try {
            submitIo(() -> { try { cache.writePlan(value); } catch (IOException error) { state.put("cache-disabled", 1L); } });
        } catch (RejectedExecutionException full) { state.put("cache-disabled", 1L); }
    }

    private static long retainedBytes(Object value) {
        if (value instanceof RouteShape r) return 4096L + 80L * r.points().size();
        if (value instanceof SegmentGeometry g) {
            long result = 4096L + 80L * g.points().size();
            for (var w : g.water()) result += 512L + 48L * (w.banks().size() + w.piers().size());
            return result;
        }
        if (value instanceof SegmentPlan p) return retainedBytes(p.geometry()) + 4096L + 96L * p.stations().stations().size()
                + 128L * p.stations().breaks().size() + 2048L * p.sites().size();
        throw new IllegalArgumentException("unknown cached object");
    }

    private boolean closed() { return state.containsKey("closed"); }
    private static WallFailure closedFailure() { return new WallFailure(FailureCode.CLOSED, "planner session closed"); }
    private static ThreadFactory factory(String name) {
        return runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; };
    }

    @Override public void close() {
        if (state.putIfAbsent("closed", 1L) != null) return;
        routeFlights.forEach((key, future) -> future.completeExceptionally(closedFailure()));
        geometryFlights.forEach((key, future) -> future.completeExceptionally(closedFailure()));
        planFlights.forEach((key, future) -> future.completeExceptionally(closedFailure()));
        acquisitions.forEach((id, future) -> future.completeExceptionally(closedFailure()));
        prefixes.close(); callerLeases.forEach((id, lease) -> lease.close()); memory.close();
        pump.execute(() -> {
            if (startupTimer != null) startupTimer.cancel(false);
            pending.forEach(job -> job.result.completeExceptionally(closedFailure()));
            pending.clear(); geometryQueue.clear(); ioQueue.clear(); planAdmissions.clear(); geometryAdmissions.clear();
            activeJobs.clear(); progress.clear();
            for (int lane = 0; lane < lanes.size(); lane++) {
                var job = laneState.get(lane).job;
                lanes.get(lane).execute(() -> { if (job != null && job.oracle != null) job.oracle.close(); });
                lanes.get(lane).shutdown();
            }
            pump.shutdown();
        });
    }

    private record CacheKey(String kind, long segment) {}
    private record Pair(SegmentGeometry geometry, PrefixSummary prefix) {}
    @FunctionalInterface private interface IoRead<T> { Optional<T> read() throws IOException; }
    private static final class Lane { private Job<?> job; private boolean busy; }

    private abstract class Job<T> {
        private final UUID id = UUID.randomUUID();
        private Priority priority;
        private final long sequence = PlannerScheduler.this.sequence++;
        private final String phase;
        final CompletableFuture<T> result;
        NaturalTerrainOracle oracle;
        long operations;
        private Job(Priority priority, String phase, CompletableFuture<T> result) {
            this.priority = Objects.requireNonNull(priority); this.phase = phase; this.result = result;
        }
        abstract boolean advance(int limit);
        abstract T value();
        @SuppressWarnings("unchecked") private void complete(Object value) { result.complete((T) value); }
    }

    private final class RouteJob extends Job<RouteShape> {
        private final SegmentKey key;
        private RidgePlanner.Cursor cursor;
        private RouteJob(SegmentKey key, Priority priority, CompletableFuture<RouteShape> result) {
            super(priority, "ROUTE", result); this.key = key;
        }
        @Override boolean advance(int limit) {
            if (cursor == null) cursor = ridge.begin(key, oracle);
            operations += limit; return ridge.advance(cursor, limit) == WorkState.DONE;
        }
        @Override RouteShape value() { return ridge.finish(cursor); }
    }

    private final class HydroJob extends Job<SegmentGeometry> {
        private final RouteNeighborhood routes;
        private HydrologyClassifier.Cursor cursor;
        private HydroJob(RouteNeighborhood routes, Priority priority, CompletableFuture<SegmentGeometry> result) {
            super(priority, "HYDROLOGY", result); this.routes = routes;
            result.thenAcceptAsync(PlannerScheduler.this::persist, pump);
        }
        @Override boolean advance(int limit) {
            if (cursor == null) cursor = hydrology.begin(routes, oracle, 24000);
            operations += limit; return hydrology.advance(cursor, limit) == WorkState.DONE;
        }
        @Override SegmentGeometry value() { return geometry(routes.center(), hydrology.finish(cursor), cursor.logicalColumns(), routes.next().isEmpty()); }
    }

    private final class ContractJob extends Job<SegmentPlan> {
        private final Pair inputs;
        private final List<SiteRecord> provisional;
        private final List<Point2q> points;
        private final Map<Point2q, ColumnSample> columns = new HashMap<>();
        private int index;
        private NaturalTerrainOracle.ColumnCursor column;
        private ContractJob(Pair inputs, List<SiteRecord> provisional, List<Point2q> points, Priority priority, CompletableFuture<SegmentPlan> result) {
            super(priority, "HEIGHT_CONTRACT", result); this.inputs = inputs; this.provisional = provisional; this.points = points;
            result.thenAcceptAsync(PlannerScheduler.this::persist, pump);
        }
        @Override boolean advance(int limit) {
            for (int i = 0; i < limit && index < points.size(); i++) {
                if (closed()) throw closedFailure();
                var p = points.get(index); operations++;
                if (column == null) column = oracle.begin(FixedMath.floorBlock(p.xQ8()), FixedMath.floorBlock(p.zQ8()), SampleKind.REFERENCE);
                else if (!column.done()) oracle.advance(column, 1);
                else { columns.put(p, oracle.finish(column)); column = null; index++; }
            }
            return index == points.size();
        }
        @Override SegmentPlan value() {
            var table = heights.solve(inputs.geometry, inputs.prefix, provisional, columns);
            return hashedPlan(inputs.geometry, inputs.prefix, table, sites.bindHeights(provisional, table));
        }
    }
}
