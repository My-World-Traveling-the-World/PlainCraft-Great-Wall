package local.plaincraft.greatwall.generation;

import local.plaincraft.greatwall.model.WallFailure;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import local.plaincraft.greatwall.model.WallTypes.ChunkWritePlan;
import local.plaincraft.greatwall.model.WallTypes.IntentKind;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Writes a published wall plan into chunks that were already generated.
 *
 * <h2>Why this exists</h2>
 * {@code WallFeature} only runs while a chunk's own world generation reaches the feature stage.
 * The wall's sampling generates that terrain minutes before the plan exists: the measured session
 * spent 17.6 minutes on the root search and published at 13:26, and the 1024-block prewarm radius
 * had already generated the entire corridor. From then on no chunk of the corridor would ever
 * reach the feature stage again, so every published plan sat in a cache nothing could consume -
 * the map had a wall, the world had none. RoadWeaver documents the identical failure for its own
 * roads ("the map showed blue lines and the world had no road under them") and fixes it with a
 * queue drained against loaded chunks; this is that queue for the wall body.
 *
 * <h2>Hard constraints</h2>
 * <ul>
 *   <li><b>Never wait on a chunk request.</b> A chunk is asked for through a ticket and
 *       {@code getChunkFutureMainThread}, and the wait is always a bounded
 *       {@code managedBlock} that drains the world-local queue; {@code ServerChunkCache.getChunkFuture}
 *       parks the server thread and deadlocked a measured session. See {@link #requestChunk}.</li>
 *   <li><b>Never block.</b> At most {@link #MAX_CHUNKS_PER_DRAIN} chunks per drain, one drain
 *       every {@link #DRAIN_INTERVAL_TICKS} ticks, and every wait in the startup pass carries a
 *       deadline ({@link #BATCH_TIMEOUT_NANOS}, {@code greatwall.backfillStartupBudgetMs}).</li>
 *   <li><b>Idempotent.</b> A block already equal to its target is skipped, so a restart re-walks
 *       for almost nothing and no durable ledger is needed.</li>
 *   <li><b>Never clobber.</b> Only air, replaceable plants and non-block-entity blocks are
 *       written - the same rule {@code WallFeature} applies during generation.</li>
 * </ul>
 */
public final class WallBodyBackfill {
    /** Chunks settled per drain. The road uses 8; the wall writes far more blocks per chunk. */
    private static final int MAX_CHUNKS_PER_DRAIN = 4;
    /** Ticks between drains (0.5 s at 20 tps). */
    private static final int DRAIN_INTERVAL_TICKS = 10;
    /** A chunk that cannot settle after this many drains leaves the queue. */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * The durable build-proof store. Planner publishes into it once per chunk; the backfill reads
     * from it when a chunk is loaded in a later session and the in-memory cache is gone (the cache
     * only lives as long as the publish that filled it). Null when the store could not be opened.
     */
    public interface PlanStore {
        java.util.Optional<ChunkWritePlan> read(ChunkKey owner) throws java.io.IOException;

        void write(ChunkWritePlan plan) throws java.io.IOException;
    }

    private static volatile PlanStore planStore;
    private static final AtomicLong PERSISTED = new AtomicLong();
    private static final AtomicLong PERSIST_FAILURES = new AtomicLong();

    /** Installed once per server by the production session, which owns the durable store. */
    /**
     * Installs the durable reader from the save directory when no session has done it.
     *
     * <p>{@code ProductionSession} installs the reader while a build is running. A reloaded save has no
     * session, so nothing installed one: a measured run published 456 chunks, was restarted a minute
     * later, and from then on wrote zero blocks - the drain ran every tick against an empty plan cache
     * and every chunk stayed PENDING. The reader only needs the save directory, so it can be installed
     * the first time a tick runs.
     */
    public static void installIfAbsent(java.nio.file.Path greatWallRoot) {
        if (planStore != null) return;
        var evidence = local.plaincraft.greatwall.persistence.ChunkEvidenceStore
                .production(greatWallRoot.resolve("evidence"));
        install(new PlanStore() {
            @Override public java.util.Optional<ChunkWritePlan> read(ChunkKey owner) throws java.io.IOException {
                return evidence.readPlan(owner);
            }
            @Override public void write(ChunkWritePlan plan) throws java.io.IOException {
                evidence.persistPlan(plan);
            }
        });
        loadDone(greatWallRoot);
        GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_BACKFILL_STORE_INSTALLED root={}", greatWallRoot);
    }

    /**
     * Seeds the queue from the plans on disk, the way the road seeds its construction backfill from
     * its ledger. A save whose corridor was generated before this session has no CHUNK_LOAD event
     * left to fire, so nothing would ever be queued; the plan directories name their own chunk.
     */
    public static int seedFromDisk(ServerLevel level, java.nio.file.Path greatWallRoot) {
        State state = state(level);
        int added = 0;
        // The plans live at greatwall/evidence/<world hash>/<x>_<z>/, one hash directory per world
        // and 456 chunk directories under it in the measured save. Scanning greatwall/ itself finds
        // nothing, which is what the first attempt reported (added=0).
        java.nio.file.Path evidence = greatWallRoot.resolve("evidence");
        if (!java.nio.file.Files.isDirectory(evidence)) return added;
        try (var walk = java.nio.file.Files.walk(evidence, 2)) {
            for (java.nio.file.Path dir : (Iterable<java.nio.file.Path>) walk::iterator) {
                if (!java.nio.file.Files.isDirectory(dir)) continue;
                String name = dir.getFileName().toString();
                int split = name.indexOf('_');
                if (split <= 0 || split != name.lastIndexOf('_')) continue;
                try {
                    int x = Integer.parseInt(name.substring(0, split));
                    int z = Integer.parseInt(name.substring(split + 1));
                    ChunkKey key = new ChunkKey(level.dimension().location().toString(), x, z);
                    if (state.settled.contains(key)) continue;
                    if (DONE.contains(x + "_" + z)) continue;
                    if (state.pending.add(key)) added++;
                } catch (NumberFormatException notAChunkDirectory) {
                    // FILES/ and the world hash directory are not chunk directories.
                }
            }
        } catch (java.io.IOException unreadable) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] WALL_BACKFILL_SEED_FAILED root={} reason={}",
                    evidence, unreadable.toString());
            return added;
        }
        state.total = Math.max(state.total, state.settled.size() + state.pending.size());
        GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_BACKFILL_SEEDED pending={} added={} settled={}",
                state.pending.size(), added, state.settled.size());
        return added;
    }
    /** Time one startup batch may wait for its chunks before it is written as far as it got. */
    private static final long BATCH_TIMEOUT_NANOS = 20_000_000_000L;
    /** How long a startup request may stay unanswered before its ticket is dropped and it is retried. */
    private static final long REQUEST_GIVE_UP_NANOS = 90_000_000_000L;
    /** Chunk requests the startup pass may hold at once; each one pins its chunk in memory. */
    private static final int MAX_STARTUP_INFLIGHT = 16;
    /** Time one in-game drain may wait for a single chunk. */
    private static final long DRAIN_TIMEOUT_NANOS = 5_000_000_000L;
    /**
     * The ticket that keeps a chunk loaded while the wall is written into it.
     *
     * <p>A request alone is not enough: the chunk manager is free to drop a chunk nobody holds a
     * ticket for between the request and the write.
     */
    private static final net.minecraft.server.level.TicketType<String> BACKFILL_TICKET =
            net.minecraft.server.level.TicketType.create("greatwall_s1c_backfill",
                    java.util.Comparator.<String>naturalOrder());

    /** One chunk asked for: its future, plus the ticket that keeps it loaded until it is written. */
    private record StartupRequest(ChunkKey key, net.minecraft.world.level.ChunkPos pos, String ticketId,
                                  java.util.concurrent.CompletableFuture<?> future, long requestedAt) {
    }

    /**
     * Asks for one chunk without blocking the server thread.
     *
     * <h2>Why not {@code ServerChunkCache.getChunkFuture}</h2>
     * On the server thread that method does not hand back a pending future: it calls
     * {@code mainThreadProcessor.managedBlock(future::isDone)} and parks the calling thread until the
     * chunk is generated. Called from inside the startup pump - which is itself a {@code managedBlock}
     * and drains the world-local chunk queue only between calls - the promotion callbacks the wait
     * needs never run, so it parks for ever. A measured session froze this way for twenty-five minutes
     * with every c2me worker idle: the wall was waiting on its own chunk request.
     *
     * <p>{@code getChunkFutureMainThread} is the same request without the wait, and
     * {@link #drainWorldLocalQueues} pumps exactly what the request needs.
     */
    private static StartupRequest requestChunk(ServerLevel level, ChunkKey key, String ticketId) {
        var access = (local.plaincraft.greatwall.mixin.TerrainChunkAccess) level.getChunkSource();
        var pos = new net.minecraft.world.level.ChunkPos(key.x(), key.z());
        int fullTicketLevel = net.minecraft.server.level.ChunkLevel.byStatus(
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL);
        try {
            access.greatwall$distanceManager().addTicket(BACKFILL_TICKET, pos, fullTicketLevel, ticketId);
        } catch (RuntimeException cannotTicket) {
            return null;
        }
        try {
            var future = access.greatwall$request(key.x(), key.z(),
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            return new StartupRequest(key, pos, ticketId, future, System.nanoTime());
        } catch (RuntimeException cannotRequest) {
            releaseTicket(level, pos, ticketId);
            return null;
        }
    }

    /** Drops one request's ticket. Never throws: it runs on the error path too. */
    private static void releaseTicket(ServerLevel level, net.minecraft.world.level.ChunkPos pos,
            String ticketId) {
        try {
            ((local.plaincraft.greatwall.mixin.TerrainChunkAccess) level.getChunkSource())
                    .greatwall$distanceManager()
                    .removeTicket(BACKFILL_TICKET, pos,
                            net.minecraft.server.level.ChunkLevel.byStatus(
                                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL), ticketId);
        } catch (RuntimeException alreadyGone) {
            // A ticket that is already gone is exactly the state this method wants.
        }
    }

    /** Releases one key's ticket and forgets its request. */
    private static void releaseRequest(ServerLevel level, java.util.Map<ChunkKey, StartupRequest> inflight,
            ChunkKey key) {
        StartupRequest done = inflight.remove(key);
        if (done != null) releaseTicket(level, done.pos(), done.ticketId());
    }

    /**
     * Drains every world-local chunk queue.
     *
     * <p>{@code MinecraftServer.managedBlock} runs the global server queue only; {@code ChunkMap}
     * posts its completion callbacks to the world's own queue, and a chunk request whose callbacks
     * never run never completes. The startup pump does this once per round, which is not enough while
     * a whole backfill pass runs inside one of those rounds.
     */
    private static void drainWorldLocalQueues(net.minecraft.server.MinecraftServer server) {
        for (var world : server.getAllLevels()) {
            try {
                while (world.getChunkSource().pollTask()) {
                    // keep draining until the local queue is empty
                }
            } catch (RuntimeException closing) {
                // A level shutting down mid-pass is not a reason to abort the pass.
            }
        }
    }

    /**
     * Paints the whole queue now, on the server thread that owns the startup stage.
     *
     * <p>The drain spreads the work over ticks, which is right for a player walking past 456 chunks but
     * wrong for startup: the wall is already planned and published, the loading screen is still up, and
     * nothing else can run anyway. Each round forces a batch of chunks through the chunk source (C2ME
     * generates them on its workers while managedBlock pumps), then writes those that arrived.
     *
     * <p>The pass is bounded in three ways, because a loading screen that never ends is worse than a
     * corridor that fills in late: {@code greatwall.backfillStartupBudgetMs} for the whole pass (five
     * minutes by default), {@link #BATCH_TIMEOUT_NANOS} for one batch, and {@link #REQUEST_GIVE_UP_NANOS}
     * for one unanswered chunk request. Anything left over stays queued and the in-game drain paints it.
     *
     * @return chunks written
     */
    public static int paintPendingAtStartup(ServerLevel level) {
        State state = state(level);
        if (state.pending.isEmpty()) return 0;
        // The card gets a row of its own: this pass is the one the player actually waits for, and it
        // runs while nothing else can, so an unlabelled freeze is the worst possible feedback.
        final int total = state.pending.size();
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setStage(
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.BACKFILL);
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.BACKFILL, 0, total);
        int written = 0;
        int rounds = 0;
        long voxels = 0L;
        long startedAt = System.nanoTime();
        // A budget for the whole pass, not just for one batch. Sixty-four rounds of twenty seconds is
        // twenty-one minutes, which is not a bound a player can plan around; whatever is left when the
        // budget runs out is exactly what the in-game drain is for, so the gate opens and the corridor
        // fills in behind the player instead of the loading screen hanging on it.
        long budgetNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                Math.max(30_000L, Long.getLong("greatwall.backfillStartupBudgetMs", 300_000L)));
        long passDeadline = startedAt + budgetNanos;
        boolean budgetHit = false;
        // Requests in flight, at most MAX_STARTUP_INFLIGHT: the future that says "this chunk reached
        // FULL", plus the ticket that keeps the chunk loaded until it is written.
        java.util.Map<ChunkKey, StartupRequest> inflight = new java.util.LinkedHashMap<>();
        try {
            while (!state.pending.isEmpty() && rounds++ < 64) {
                if (System.nanoTime() >= passDeadline) {
                    budgetHit = true;
                    break;
                }
                // Retire requests nobody answered: a chunk outside the border can stay pending for ever,
                // and holding its ticket would pin it and its neighbours in memory for the whole pass.
                for (var iterator = inflight.entrySet().iterator(); iterator.hasNext(); ) {
                    StartupRequest stale = iterator.next().getValue();
                    if (System.nanoTime() - stale.requestedAt() < REQUEST_GIVE_UP_NANOS) continue;
                    releaseTicket(level, stale.pos(), stale.ticketId());
                    iterator.remove();
                }
                java.util.List<ChunkKey> batch = new java.util.ArrayList<>(8);
                for (ChunkKey key : state.pending) {
                    batch.add(key);
                    if (batch.size() >= 8) break;
                }
                if (batch.isEmpty()) break;
                java.util.Map<ChunkKey, java.util.concurrent.CompletableFuture<?>> futures =
                        new java.util.LinkedHashMap<>();
                for (ChunkKey key : batch) {
                    StartupRequest held = inflight.get(key);
                    if (held == null) {
                        held = requestChunk(level, key, "startup");
                        if (held == null) {
                            state.pending.remove(key);
                            continue;
                        }
                        inflight.put(key, held);
                    }
                    futures.put(key, held.future());
                }
                // Request the batch after this one while this one is still being written.
                for (ChunkKey key : state.pending) {
                    if (inflight.size() >= MAX_STARTUP_INFLIGHT) break;
                    if (inflight.containsKey(key)) continue;
                    StartupRequest next = requestChunk(level, key, "startup");
                    if (next == null) continue;
                    inflight.put(key, next);
                }
                if (!futures.isEmpty()) {
                    java.util.concurrent.CompletableFuture<?> all = java.util.concurrent.CompletableFuture.allOf(
                            futures.values().toArray(java.util.concurrent.CompletableFuture[]::new));
                    try {
                        // A hard deadline: a chunk that never completes (outside the border, or a worker
                        // pool held by another generator - a measured run froze for ten minutes this way)
                        // must cost one batch, not the whole startup.
                        long deadline = System.nanoTime() + BATCH_TIMEOUT_NANOS;
                        level.getServer().managedBlock(() -> {
                            drainWorldLocalQueues(level.getServer());
                            return all.isDone() || System.nanoTime() >= deadline
                                    || System.nanoTime() >= passDeadline;
                        });
                    } catch (RuntimeException interrupted) {
                        GreatWallMod.LOGGER.warn("[GreatWall:S1C] WALL_BACKFILL_STARTUP_INTERRUPTED reason={}",
                                interrupted.toString());
                        break;
                    }
                }
                java.util.List<ChunkKey> arrived = new java.util.ArrayList<>(batch.size());
                for (ChunkKey key : batch) {
                    var future = futures.get(key);
                    if (future != null && !future.isDone()) continue;   // still generating: stays pending
                    arrived.add(key);
                }
                int settledThisRound = 0;
                for (ChunkKey key : arrived) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
                    if (chunk == null) {
                        state.pending.remove(key);
                        releaseRequest(level, inflight, key);
                        continue;
                    }
                    try {
                        Sweep sweep = settle(level, chunk, key);
                        var startupPlan = WallWorldgenPlanCache.get(level, key);
                        if (startupPlan != null) voxels += startupPlan.voxels().size();
                        if (sweep.status() == Status.SETTLED) {
                            written += sweep.written();
                            markDone(key);
                        }
                        state.pending.remove(key);
                        state.settled.add(key);
                        settledThisRound++;
                    } catch (RuntimeException failed) {
                        state.pending.remove(key);
                    } finally {
                        releaseRequest(level, inflight, key);
                    }
                }
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                        local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.BACKFILL,
                        total - state.pending.size(), total);
                GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_BACKFILL_STARTUP round={} settled={} written={} voxels={} pending={} elapsedMs={}",
                        rounds, settledThisRound, written, voxels, state.pending.size(),
                        (System.nanoTime() - startedAt) / 1_000_000L);
            }
        } finally {
            for (StartupRequest leftover : inflight.values()) {
                releaseTicket(level, leftover.pos(), leftover.ticketId());
            }
            inflight.clear();
        }
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.BACKFILL, total, total);
        GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_BACKFILL_STARTUP_DONE rounds={} written={} pending={} budgetHit={} elapsedMs={}",
                rounds, written, state.pending.size(), budgetHit,
                (System.nanoTime() - startedAt) / 1_000_000L);
        return written;
    }


    /** Loads the written-chunk marker; missing file just means nothing was written yet. */
    private static void loadDone(java.nio.file.Path greatWallRoot) {
        doneFile = greatWallRoot.resolve("backfilled.txt");
        DONE.clear();
        if (!java.nio.file.Files.isRegularFile(doneFile)) return;
        try {
            for (String line : java.nio.file.Files.readAllLines(doneFile)) {
                if (line != null && !line.isBlank()) DONE.add(line.trim());
            }
            GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_BACKFILL_DONE_LOADED chunks={}", DONE.size());
        } catch (java.io.IOException | RuntimeException unreadable) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] WALL_BACKFILL_DONE_LOAD_FAILED reason={}",
                    unreadable.toString());
        }
    }

    /** Remembers one finished chunk, flushing every 32 so a crash costs at most that many re-writes. */
    private static void markDone(ChunkKey key) {
        String token = key.x() + "_" + key.z();
        if (!DONE.add(token)) return;
        java.nio.file.Path file = doneFile;
        if (file == null) return;
        try {
            java.nio.file.Files.writeString(file, token + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException | RuntimeException unwritable) {
            if (DONE_PENDING.incrementAndGet() % 64 == 1) {
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] WALL_BACKFILL_DONE_WRITE_FAILED reason={}",
                        unwritable.toString());
            }
        }
    }

    public static void install(PlanStore store) {
        planStore = store;
    }

    /** Writes one published plan to the durable store; a failure costs a backfill, never a session. */
    public static void persist(ChunkWritePlan plan) {
        PlanStore store = planStore;
        if (store == null || plan == null) return;
        try {
            store.write(plan);
            PERSISTED.incrementAndGet();
        } catch (java.io.IOException | RuntimeException failure) {
            if (PERSIST_FAILURES.incrementAndGet() <= 4) {
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] WALL_PLAN_PERSIST_FAILED owner={},{} reason={}",
                        plan.owner().x(), plan.owner().z(), failure.toString());
            }
        }
    }

    public static long persisted() { return PERSISTED.get(); }

    public static long persistFailures() { return PERSIST_FAILURES.get(); }
    private static final Map<ServerLevel, State> STATES = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL_BLOCKS = new AtomicLong();

    private WallBodyBackfill() {
    }

    /** Queues every chunk of a freshly published plan. Called once per successful compile. */
    public static void enqueuePublished(ServerLevel level, Collection<ChunkKey> keys) {
        if (level == null || keys == null || keys.isEmpty()) return;
        State state = state(level);
        int added = 0;
        for (ChunkKey key : keys) {
            if (key == null || state.settled.contains(key)) continue;
            if (state.pending.add(key)) added++;
        }
        state.total = Math.max(state.total, state.settled.size() + state.pending.size());
        GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_BACKFILL_QUEUED chunks={} added={} settled={}",
                state.pending.size(), added, state.settled.size());
    }

    /**
     * Plan reads, off the server thread.
     *
     * <p>They used to run on one thread. A player walking the wall reports loads faster than one
     * thread can read plans, so the queue grew while the wall behind them stayed missing - and the
     * read itself is I/O, which is exactly what a second thread helps with and CPU-bound work is not.
     * The default is small on purpose: this shares the machine with world generation.
     * {@code -Dgreatwall.backfillIoThreads} overrides it.
     */
    private static final int IO_THREADS = Math.max(1, Math.min(8,
            Integer.getInteger("greatwall.backfillIoThreads", 3)));

    private static final java.util.concurrent.ExecutorService IO =
            java.util.concurrent.Executors.newFixedThreadPool(IO_THREADS, task -> {
                Thread thread = new Thread(task, "pgw-wall-backfill-io");
                thread.setDaemon(true);
                return thread;
            });
    /** Chunks with a plan read in flight, so a burst of loads does not read the same file twice. */
    private static final Set<ChunkKey> LOADING = ConcurrentHashMap.newKeySet();
    /** Voxels skipped because the world changed after the plan was compiled. */
    private static int beforeConflicts;
    /** Voxels whose block id the registry could not resolve. */
    private static int unresolvableVoxels;

    /** A chunk the server just loaded: queue it when a published plan already covers it. */
    public static void onChunkLoaded(ServerLevel level, ChunkKey key) {
        if (level == null || key == null) return;
        State state = state(level);
        if (state.settled.contains(key) || state.settling.contains(key)) return;
        if (WallWorldgenPlanCache.get(level, key) != null) {
            state.pending.add(key);
            return;
        }
        PlanStore store = planStore;
        if (store == null || !LOADING.add(key)) return;
        try {
            IO.execute(() -> {
                try {
                    prefetch(level, key, store);
                } finally {
                    LOADING.remove(key);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            // The pool is shutting down with the server; the chunk stays queued for the next run.
            LOADING.remove(key);
        }
    }

    /** Reads one durable plan off the IO thread, then hands it back to the server thread. */
    private static void prefetch(ServerLevel level, ChunkKey key, PlanStore store) {
        ChunkWritePlan plan = null;
        try {
            plan = store.read(key).orElse(null);
        } catch (java.io.IOException | RuntimeException failure) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] WALL_PLAN_READ_FAILED owner={},{} reason={}",
                    key.x(), key.z(), failure.toString());
        }
        if (plan == null) return;
        ChunkWritePlan loaded = plan;
        try {
            level.getServer().execute(() -> {
                WallWorldgenPlanCache.put(level, loaded);
                state(level).pending.add(key);
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            // Server is stopping: the durable record stays readable next launch.
        }
    }

    /** One bounded step, driven by the production session's tick. */
    public static void drain(ServerLevel level) {
        if (level == null) return;
        State state = state(level);
        if (++state.tick < DRAIN_INTERVAL_TICKS) return;
        state.tick = 0;
        if (state.pending.isEmpty()) return;

        int settledThisDrain = 0;
        int writtenBlocks = 0;
        long voxelsSeen = 0L;
        List<ChunkKey> dropped = new ArrayList<>();
        for (ChunkKey key : List.copyOf(state.pending)) {
            if (settledThisDrain >= MAX_CHUNKS_PER_DRAIN) break;
            if (state.settling.contains(key)) continue;
            LevelChunk chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
            if (chunk == null) {
                // The road reaches the chunks it must paint by asking for them; the wall used to sit and
                // wait for the player to wander past. 558 chunks is a small batch next to a pregeneration
                // run, and forcing one at a time keeps the memory bounded while C2ME generates it on its
                // worker pool. The request is ticketed and non-blocking; see requestChunk for why the
                // public getChunkFuture cannot be used from the server thread.
                if (!FORCE_LOAD.get()) continue;
                StartupRequest request = requestChunk(level, key, "drain");
                if (request == null) continue;
                try {
                    long deadline = System.nanoTime() + DRAIN_TIMEOUT_NANOS;
                    level.getServer().managedBlock(() -> {
                        drainWorldLocalQueues(level.getServer());
                        return request.future().isDone() || System.nanoTime() >= deadline;
                    });
                    chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
                } catch (RuntimeException cannotLoad) {
                    if (FORCE_FAILURES.incrementAndGet() % 64 == 1) {
                        GreatWallMod.LOGGER.warn("[GreatWall:S1C] WALL_BACKFILL_LOAD_FAILED owner={},{} reason={}",
                                key.x(), key.z(), cannotLoad.toString());
                    }
                    releaseTicket(level, request.pos(), request.ticketId());
                    continue;
                }
                // The ticket only has to survive until the chunk is in hand: the same tick writes it, and
                // a chunk cannot unload while this thread is inside the loop.
                releaseTicket(level, request.pos(), request.ticketId());
                if (chunk == null) continue;
            }
            // Reading the plan only on CHUNK_LOAD is not enough: the wall's own sampling generates the
            // whole corridor, so by the time the queue exists every chunk it covers is already loaded
            // and no load event will ever fire for it again. A measured run queued 456 chunks that way
            // and wrote zero blocks - the drain ran every tick against an empty cache. Fetch the plan
            // here instead, on the IO pool, the way the road reads its construction data each pass.
            if (local.plaincraft.greatwall.generation.WallWorldgenPlanCache.get(level, key) == null) {
                PlanStore store = planStore;
                if (store != null && LOADING.add(key)) {
                    try {
                        IO.execute(() -> {
                            try {
                                prefetch(level, key, store);
                            } finally {
                                LOADING.remove(key);
                            }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException closing) {
                        LOADING.remove(key);
                    }
                }
                continue; // stays pending; the prefetch wakes it on the next drain
            }
            state.settling.add(key);
            try {
                Sweep sweep = settle(level, chunk, key);
                var settledPlan = WallWorldgenPlanCache.get(level, key);
                if (settledPlan != null) voxelsSeen += settledPlan.voxels().size();
                switch (sweep.status()) {
                    case SETTLED -> {
                        state.pending.remove(key);
                        state.settled.add(key);
                        state.attempts.remove(key);
                        settledThisDrain++;
                        writtenBlocks += sweep.written();
                        markDone(key);
                    }
                    case FOREIGN -> {
                        // No plan for this node, or another dimension's chunk: retrying cannot help.
                        state.pending.remove(key);
                        state.settled.add(key);
                        markDone(key);
                    }
                    case PENDING -> {
                        if (state.attempts.merge(key, 1, Integer::sum) >= MAX_ATTEMPTS) {
                            dropped.add(key);
                        }
                    }
                }
            } finally {
                state.settling.remove(key);
            }
        }
        for (ChunkKey key : dropped) {
            state.pending.remove(key);
            state.attempts.remove(key);
        }
        long total = TOTAL_BLOCKS.addAndGet(writtenBlocks);
        // Quiet by design: 456 chunks at four per drain is 114 lines if every drain speaks, and a log
        // that scrolls itself out of the file is a log nobody reads. Speak when blocks were written, when
        // the queue empties, or once every 200 drains as a heartbeat.
        drainsSinceLog++;
        boolean justEmptied = state.pending.isEmpty() && !state.settled.isEmpty();
        if (writtenBlocks > 0 || justEmptied || drainsSinceLog >= 200) {
            drainsSinceLog = 0;
            GreatWallMod.LOGGER.info(
                    "[GreatWall:S1C] WALL_BACKFILL_DRAIN settled={} pending={} written={} voxels={} dropped={} guardSkip={} total={}",
                    settledThisDrain, state.pending.size(), writtenBlocks, voxelsSeen, dropped.size(),
                    beforeConflicts, total);
        }
    }

    /** Whether every planned chunk loaded so far has been written. */
    public static boolean complete(ServerLevel level) {
        State state = level == null ? null : STATES.get(level);
        return state != null && state.pending.isEmpty() && state.settling.isEmpty();
    }

    public static int pending(ServerLevel level) {
        State state = level == null ? null : STATES.get(level);
        return state == null ? 0 : state.pending.size();
    }

    private enum Status { SETTLED, PENDING, FOREIGN }

    private record Sweep(Status status, int written) {
    }

    private static Sweep settle(ServerLevel level, LevelChunk chunk, ChunkKey key) {
        ChunkWritePlan plan = WallWorldgenPlanCache.get(level, key);
        if (plan == null) {
            // The durable read is prefetched on the IO thread when the chunk loads; doing it here
            // would put a file read on the server thread for every chunk the player walks into.
            // A miss means the prefetch is still in flight, so this chunk stays pending.
            return new Sweep(Status.PENDING, 0);
        }
        int written = 0;
        var beforeStates = new java.util.HashMap<Point3i, String>();
        for (var proof : plan.proofs()) {
            beforeStates.putIfAbsent(proof.pos(), proof.beforeState());
        }
        for (var voxel : plan.voxels()) {
            try {
            Point3i p = voxel.pos();
            if (Math.floorDiv(p.x(), 16) != key.x() || Math.floorDiv(p.z(), 16) != key.z()) continue;
            if (voxel.kind() == IntentKind.KEEP) continue;
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            BlockState target = BlockCatalog.resolveState(voxel.state());
            BlockState current = chunk.getBlockState(pos);
            if (current == target) continue;
            if (!replaceable(current)) continue;
            // The compile recorded what it saw here. If it was not air and the world has since
            // changed - a player placing a block, water flowing in - the plan's authority ends:
            // overwriting it would erase something the wall never owned.
            String before = beforeStates.get(p);
            // The recorded before-state comes from the sampled column (PlanningChunkCompiler), not from
            // this chunk: they agree only where nothing has touched the terrain since. When the world is
            // air here there is no player work to protect and the plan's own wall belongs in that space,
            // so the comparison is skipped; a solid block that differs is still left alone.
            if (before != null && !before.startsWith("minecraft:air") && !current.isAir()) {
                // before is the compile-time token ("minecraft:stone{}:fluid=minecraft:empty:be=false").
                // If the world still holds exactly that block, the plan owns this position and may
                // write. If it does not - a player placed something, water flowed in - the plan's
                // authority ends here.
                String beforeId = before.substring(0, before.indexOf('{'));
                String currentId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(current.getBlock()).toString();
                if (!beforeId.equals(currentId)) {
                    if (beforeConflicts++ % 512 == 1) {
                        GreatWallMod.LOGGER.warn("[GreatWall:S1C] BACKFILL_CONFLICT pos={} before={} current={}",
                                p, beforeId, current.getBlock());
                    }
                    continue;
                }
            }
            // Flag 3 notifies clients and neighbours, and setBlock keeps the heightmaps honest -
            // the raw ChunkAccess path used during generation has to prime them by hand.
            // Flag 2 notifies clients but does not cascade neighbour updates: a chunk of wall is two
            // thousand writes, and eight thousand cascades every half second is a visible stutter. The
            // chunk still updates its own heightmaps, and lighting is recomputed per section on save.
            level.setBlock(pos, target, 2);
            written++;
            } catch (WallFailure unresolvable) {
                // A voxel that names a block the registry does not have (a palette referencing a
                // removed mod, a template drift) would otherwise kill this chunk silently: the
                // exception escapes, the chunk stays in settling, and every drain retries it.
                // Drop the voxel, count it, and let the rest of the wall write.
                if (++unresolvableVoxels % 512 == 1) {
                    GreatWallMod.LOGGER.warn("[GreatWall:S1C] BACKFILL_VOXEL_DROPPED owner={},{} reason={}",
                            key.x(), key.z(), unresolvable.getMessage());
                }
            }
        }
        return new Sweep(Status.SETTLED, written);
    }

    private static boolean replaceable(BlockState current) {
        if (current.isAir()) return true;
        if (current.hasBlockEntity()) return false;
        return !current.is(Blocks.BEDROCK);
    }

    private static State state(ServerLevel level) {
        return STATES.computeIfAbsent(level, ignored -> new State());
    }

    /** Drains since the last log line; see the throttle in drain(). */
    private static long drainsSinceLog;
    /** Whether the drain may ask for a chunk it needs; see the note in drain(). */
    private static final java.util.concurrent.atomic.AtomicBoolean FORCE_LOAD =
            new java.util.concurrent.atomic.AtomicBoolean(Boolean.parseBoolean(
                    System.getProperty("greatwall.backfillForceLoad", "true")));
    private static final java.util.concurrent.atomic.AtomicInteger FORCE_FAILURES =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Chunks already written, as "x_z" lines in greatwall/backfilled.txt; empty until installed. */
    private static final java.util.Set<String> DONE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile java.nio.file.Path doneFile;
    private static final java.util.concurrent.atomic.AtomicInteger DONE_PENDING = new java.util.concurrent.atomic.AtomicInteger();

    private static final class State {
        final Set<ChunkKey> pending = ConcurrentHashMap.newKeySet();
        final Set<ChunkKey> settled = ConcurrentHashMap.newKeySet();
        final Set<ChunkKey> settling = ConcurrentHashMap.newKeySet();
        final Map<ChunkKey, Integer> attempts = new ConcurrentHashMap<>();
        int tick;
        int total;
    }
}
