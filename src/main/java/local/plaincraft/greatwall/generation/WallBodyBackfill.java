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
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import local.plaincraft.greatwall.terrain.BlockCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
 *   <li>相同方块不重复写入；持久完成章负责重开后保留玩家修改，部分施工仍可重试。</li>
 *   <li>首次落块以已发布计划为准；已完成同hash标记继续保护玩家后续编辑。</li>
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
        // The root can change inside one game process: quitting to the title and loading another save
        // must not keep reading the first save's plans nor append to its ledger (that is how a second
        // world's chunk keys ended up in the first world's backfilled.txt).
        if (planStore != null && greatWallRoot.equals(installedRoot)) return;
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
        installedRoot = greatWallRoot;
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
                    // DONE is only a hint that the chunk may have been written. The persistent
                    // PRODUCTION marker is the seal; a crash between ledger append and region save
                    // must still be allowed to finish the missing blocks on next load.
                    if (DONE.contains(x + "_" + z)) { state.doneHint.add(key); continue; }
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
    /** How long a request may stay unanswered before its ticket is dropped and the chunk retried. */
    private static final long REQUEST_GIVE_UP_NANOS = 90_000_000_000L;
    /** Wall-clock budget one in-game drain may spend writing before it yields the tick. */
    private static final long DRAIN_BUDGET_NANOS = 150_000_000L;
    /** Chunk requests the in-game drain may keep in flight. It never waits on one. */
    private static final int MAX_DRAIN_REQUESTS = 8;
    /** Pending chunks one drain may inspect; a long queue must not cost a tick. */
    private static final int MAX_DRAIN_CHECKS = 64;
    /** How long the road gate may stay closed after the startup pass hands the thread back. */
    private static final long GATE_DEFER_LIMIT_NANOS = 600_000_000_000L;
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

    /** Releases the in-game drain's request for one key, if it has one. */
    private static void releaseDrainRequest(ServerLevel level, State state, ChunkKey key) {
        StartupRequest done = state.requests.remove(key);
        if (done != null) releaseTicket(level, done.pos(), done.ticketId());
    }

    /** Asks the IO pool for one plan; the caller keeps the chunk pending until it arrives. */
    private static void requestPlanRead(ServerLevel level, ChunkKey key) {
        PlanStore store = planStore;
        if (store == null || !LOADING.add(key)) return;
        try {
            IO.execute(() -> {
                try {
                    prefetch(level, key, store);
                } finally {
                    // The key alone is not an ownership token: this request may belong to a
                    // different save whose level was replaced while the IO thread was reading.
                    if (STATES.containsKey(level)) LOADING.remove(key);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            LOADING.remove(key);
        }
    }

    /**
     * Pending chunks ordered by distance to the centroid of the players in this level.
     *
     * <p>The queue is a hash set, so without this a drain could spend its budget pre-generating
     * chunks three hundred blocks away while the wall in front of the player stayed missing.
     */
    private static List<ChunkKey> nearestFirst(ServerLevel level, Set<ChunkKey> pending) {
        var players = level.players();
        if (players.isEmpty()) return BackfillCandidateSelection.select(pending, MAX_DRAIN_CHECKS, false, 0, 0);
        double cx = 0.0D;
        double cz = 0.0D;
        for (var player : players) {
            cx += player.getX();
            cz += player.getZ();
        }
        cx /= players.size();
        cz /= players.size();
        // 下游最多检查64项，仅选择同序前缀，避免为全部待建区块做排序。
        return BackfillCandidateSelection.select(pending, MAX_DRAIN_CHECKS, true, cx, cz);
    }

    /**
     * Paints every chunk the startup thread already has in memory, and only those.
     *
     * <h2>Why nothing is requested here</h2>
     * This runs on the server thread inside {@code prepareLevels}, where the chunk source is <em>not</em>
     * ticked. A ticket added here therefore never turns into a chunk-level update, the request never
     * completes, and the wait spins for as long as the pass is allowed to run: a measured session sat
     * on one request for twenty-five minutes - every c2me worker idle, not a single generation task
     * queued - and another burned its whole eight-minute budget on 343 chunks that never arrived.
     * Chunks that were generated while sampling are still loaded and are written here for free; the
     * rest of the corridor is finished by the in-game drain, which runs with the server ticking, and
     * {@link #deferGateToDrain} keeps the road gate closed until that queue empties.
     *
     * @return chunks written
     */
    public static int paintPendingAtStartup(ServerLevel level) {
        State state = state(level);
        if (state.pending.isEmpty()) return 0;
        final int total = state.pending.size();
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setStage(
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.BACKFILL);
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.BACKFILL, 0, total);
        int painted = 0;
        int written = 0;
        long voxels = 0L;
        long startedAt = System.nanoTime();
        for (ChunkKey key : List.copyOf(state.pending)) {
            try {
                LevelChunk chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
                if (chunk == null) continue;                  // not loaded: the drain owns it now
                if (WallWorldgenPlanCache.get(level, key) == null) {
                    // A reloaded save has the plan on disk but not in memory yet; read it off the IO
                    // pool and let the drain write the chunk once it arrives.
                    requestPlanRead(level, key);
                    continue;
                }
                Sweep sweep = settle(level, chunk, key);
                var plan = WallWorldgenPlanCache.get(level, key);
                if (plan != null) voxels += plan.voxels().size();
                if (sweep.status() != Status.SETTLED) continue;
                written += sweep.written();
                markDone(key);
                state.pending.remove(key);
                state.settled.add(key);
                painted++;
            } catch (RuntimeException failed) {
                // Leave it queued; the in-game drain retries with the server ticking.
            }
        }
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.BACKFILL,
                total - state.pending.size(), total);
        GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_BACKFILL_STARTUP_DONE painted={} written={} voxels={} pending={} elapsedMs={}",
                painted, written, voxels, state.pending.size(),
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
            // A persist failure is how "the wall is still being painted from the OLD plan on disk" shows
            // up, and that is exactly the state a terrain-semantics change must not leave behind silently.
            // The first four carry the detail; every 512th keeps the scale of it visible without flooding.
            long failed = PERSIST_FAILURES.incrementAndGet();
            if (failed <= 4 || failed % 512 == 0) {
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] WALL_PLAN_PERSIST_FAILED owner={},{} totalFailures={} reason={}",
                        plan.owner().x(), plan.owner().z(), failed, failure.toString());
            }
        }
    }

    public static long persisted() { return PERSISTED.get(); }

    public static long persistFailures() { return PERSIST_FAILURES.get(); }
    private static final Map<ServerLevel, State> STATES = new ConcurrentHashMap<>();
    /** Explicit `/greatwall repaint` targets: only these may bypass a complete PRODUCTION seal. */
    private static final Set<ChunkKey> FORCE_REPAIR = ConcurrentHashMap.newKeySet();

    /**
     * Releases every ticket and drops all level-keyed state before the server closes.
     * Without this, quitting to the title pinned the whole old ServerLevel (and its chunk
     * manager) for the rest of the JVM and the next save inherited its queue.
     */
    public static void clearAll() {
        for (var entry : STATES.entrySet()) {
            ServerLevel level = entry.getKey();
            State state = entry.getValue();
            try {
                for (StartupRequest request : state.requests.values()) {
                    releaseTicket(level, request.pos(), request.ticketId());
                }
            } catch (RuntimeException ignored) {
                // The level may already be closing; the tickets die with it.
            }
        }
        STATES.clear();
        LOADING.clear();
        FORCE_REPAIR.clear();
        DONE.clear();
        doneFile = null;
        installedRoot = null;
        planStore = null;
        gateDeferredAtNanos = 0L;
        drainsSinceLog = 0L;
        beforeConflicts = 0;
        unresolvableVoxels = 0;
        verifyMissing = 0;
        verifyWrong = 0;
    }
    private static final AtomicLong TOTAL_BLOCKS = new AtomicLong();

    private WallBodyBackfill() {
    }

    /** Queues every chunk of a freshly published plan. Called once per successful compile. */
    public static void enqueuePublished(ServerLevel level, Collection<ChunkKey> keys) {
        if (level == null || keys == null || keys.isEmpty()) return;
        State state = state(level);
        int added = 0;
        int proven = 0;
        for (ChunkKey key : keys) {
            if (key == null || state.settled.contains(key)) continue;
            // DONE is a hint; the chunk's persistent PRODUCTION marker decides whether it is sealed.
            if (DONE.contains(key.x() + "_" + key.z())) {
                if (state.doneHint.add(key)) proven++;
                continue;
            }
            if (state.pending.add(key)) added++;
        }
        state.total = Math.max(state.total, state.settled.size() + state.pending.size());
        GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_BACKFILL_QUEUED chunks={} added={} settled={} verify={}",
                state.pending.size(), added, state.settled.size(), proven);
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
    /** 只读自检（B 线）：抽样回读时"应该是实心却是空气"的格数。只上报，绝不补洞。 */
    private static int verifyMissing;
    /** 只读自检（B 线）：抽样回读时"写成了别的方块"的格数。 */
    private static int verifyWrong;
    /**
     * 0.7.118：命中 settle() 里「本区块已建成、planHash 一致」早退的区块数。
     *
     * <p>专门用来区分两种 written=0：命中它 ⇒ 墙本来就在世界里（不是没铺）；
     * 没命中它却 written=0 ⇒ 真的一个方块都没写，那才是 bug。此前两者共用 written=0，
     * 真机上一局 WALL_BACKFILL_DRAIN 三行全 0，被误读成「墙没铺出去」。
     */
    private static final java.util.concurrent.atomic.AtomicLong ALREADY_BUILT =
            new java.util.concurrent.atomic.AtomicLong();

    /** A chunk the server just loaded: queue it when a published plan already covers it. */
    public static void onChunkLoaded(ServerLevel level, ChunkKey key) {
        if (level == null || key == null) return;
        State state = state(level);
        if (state.settled.contains(key) || state.settling.contains(key)) return;
        if (state.doneHint.remove(key)) {
            LevelChunk hinted = level.getChunkSource().getChunkNow(key.x(), key.z());
            if (hinted != null) {
                var marker = hinted.getAttached(TerrainMarkers.PRODUCTION);
                if (marker != null && marker.complete()) {
                    state.settled.add(key);
                    return;
                }
            }
            state.pending.add(key);
        }
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
                    // The key alone is not an ownership token: this request may belong to a
                    // different save whose level was replaced while the IO thread was reading.
                    if (STATES.containsKey(level)) LOADING.remove(key);
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

    /**
     * One bounded step, driven by the production session's tick.
     *
     * <p>This runs on the tick thread, so it must never wait for anything: the queue is walked
     * nearest-player-first, at most {@link #MAX_DRAIN_CHECKS} keys and {@link #DRAIN_BUDGET_NANOS} of
     * wall clock per call, chunks that are not loaded are <em>requested</em> (ticketed, generated by
     * c2me in the background) and skipped, and {@link #MAX_CHUNKS_PER_DRAIN} settles end the pass. An
     * earlier shape waited inside this loop for each request, with no per-drain bound at all: one
     * unloadable chunk cost five seconds each and a long queue could hold the server for half an hour.
     */
    public static void drain(ServerLevel level) {
        if (level == null) return;
        State state = state(level);
        if (++state.tick < DRAIN_INTERVAL_TICKS) return;
        state.tick = 0;
        pollDrainRequests(level, state);
        if (state.pending.isEmpty()) {
            openGateIfDrained(level, state);
            return;
        }

        long startedAt = System.nanoTime();
        int settledThisDrain = 0;
        int writtenBlocks = 0;
        long voxelsSeen = 0L;
        int inspected = 0;
        List<ChunkKey> dropped = new ArrayList<>();
        for (ChunkKey key : nearestFirst(level, state.pending)) {
            if (settledThisDrain >= MAX_CHUNKS_PER_DRAIN) break;
            if (inspected++ >= MAX_DRAIN_CHECKS) break;
            if (System.nanoTime() - startedAt >= DRAIN_BUDGET_NANOS) break;
            if (state.settling.contains(key)) continue;
            LevelChunk chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
            if (chunk == null) {
                // Never wait for a chunk here: this is the tick thread, and one slow request would cost
                // the player a frozen frame per attempt. The request is ticketed, c2me generates it on
                // its workers while the player walks, and a later drain writes whatever arrived.
                if (FORCE_LOAD.get() && state.requests.size() < MAX_DRAIN_REQUESTS
                        && !state.requests.containsKey(key)) {
                    StartupRequest request = requestChunk(level, key, "drain");
                    if (request != null) state.requests.put(key, request);
                }
                continue;
            }
            // Reading the plan only on CHUNK_LOAD is not enough: the wall's own sampling generates the
            // whole corridor, so by the time the queue exists every chunk it covers is already loaded
            // and no load event will ever fire for it again. A measured run queued 456 chunks that way
            // and wrote zero blocks - the drain ran every tick against an empty cache. Fetch the plan
            // here instead, on the IO pool, the way the road reads its construction data each pass.
            if (local.plaincraft.greatwall.generation.WallWorldgenPlanCache.get(level, key) == null) {
                requestPlanRead(level, key);
                continue; // stays pending; the prefetch wakes it on a later drain
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
                        // 部分施工也有真实成功写入，计数与是否结案分别记录。
                        writtenBlocks += sweep.written();
                        if (state.attempts.merge(key, 1, Integer::sum) >= MAX_ATTEMPTS) {
                            GreatWallMod.LOGGER.warn("[GreatWall:施工] 补建重试耗尽，未记录完成 owner={},{} attempts={}",
                                    key.x(),key.z(),MAX_ATTEMPTS);
                            dropped.add(key);
                        }
                    }
                }
            } finally {
                state.settling.remove(key);
                // The chunk is in hand, so the ticket that kept it loaded has done its job.
                releaseDrainRequest(level, state, key);
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
            // alreadyBuilt：命中 settle() 的「本区块已建成」早退（written 必然是 0）。
            // written=0 且 alreadyBuilt>0 ⇒ 墙本来就在世界里，不是没铺；
            // written=0 且 alreadyBuilt=0 ⇒ 真的一个方块都没写，那才是 bug。
            GreatWallMod.LOGGER.info(
                    "[GreatWall:S1C] WALL_BACKFILL_DRAIN settled={} pending={} written={} voxels={} dropped={} guardSkip={} total={} verifyMissing={} verifyWrong={} alreadyBuilt={}",
                    settledThisDrain, state.pending.size(), writtenBlocks, voxelsSeen, dropped.size(),
                    beforeConflicts, total, verifyMissing, verifyWrong, ALREADY_BUILT.get());
        }
        openGateIfDrained(level, state);
    }

    /** Drops drain requests that expired without their chunk arriving, tickets included. */
    private static void pollDrainRequests(ServerLevel level, State state) {
        if (state.requests.isEmpty()) return;
        for (var iterator = state.requests.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (System.nanoTime() - entry.getValue().requestedAt() < REQUEST_GIVE_UP_NANOS) continue;
            releaseTicket(level, entry.getValue().pos(), entry.getValue().ticketId());
            iterator.remove();
        }
    }
    /**
     * 预热阶段就把"哪些列是墙"从规划里全部标出来。
     *
     * <p>启动期不再画方块之后，墙真正写进世界要等到进游戏的 drain；而道路是立刻放行的，所以
     * 避让不能再依赖"写块时打标记"。这里直接把 ChunkWritePlan 里的每个体素列标进
     * {@code WallRoadGuard}，道路在墙还没长出来之前就能正确避开。
     */
    public static int seedRoadGuardFromPlans(ServerLevel level) {
        State state = level == null ? null : STATES.get(level);
        if (state == null) return 0;
        java.util.Set<ChunkKey> keys = new java.util.LinkedHashSet<>(state.pending);
        keys.addAll(state.settled);
        keys.addAll(state.doneHint);
        // DONE chunks live in verify, not in pending. Omitting them meant a reloaded save could
        // report missed=0 while every already-built chunk was invisible to the road guard.
        int columns = 0;
        int voxels = 0;
        int missed = 0;
        int fromDisk = 0;
        for (ChunkKey key : keys) {
            ChunkWritePlan plan = WallWorldgenPlanCache.get(level, key);
            if (plan == null && planStore != null) {
                try {
                    plan = planStore.read(key).orElse(null);
                    if (plan != null) {
                        WallWorldgenPlanCache.put(level, plan);
                        fromDisk++;
                    }
                } catch (java.io.IOException | RuntimeException unreadable) {
                    GreatWallMod.LOGGER.warn("[GreatWall:S1C] ROAD_GUARD_PLAN_READ_FAILED owner={},{} reason={}",
                            key.x(), key.z(), unreadable.toString());
                }
            }
            if (plan == null) {
                // A missing plan is a real coverage hole. Report it instead of silently marking fewer
                // columns than the world contains.
                missed++;
                continue;
            }
            voxels += plan.voxels().size();
            WallPlanSupport.markPlannedColumns(plan);
            columns += plan.voxels().size();
        }
        GreatWallMod.LOGGER.info("[GreatWall:S1C] ROAD_GUARD_SEEDED_FROM_PLANS chunks={} columns={} voxels={} missed={} fromDisk={}",
                keys.size(), columns, voxels, missed, fromDisk);
        if (missed > 0) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] ROAD_GUARD_SEED_INCOMPLETE missed={}/{} chunks had no plan in the "
                    + "in-memory cache; the road is already released and those wall columns are unmarked until the "
                    + "chunk is written by the drain (or /greatwall repaint)", missed, keys.size());
        }
        return columns;
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

    /** Set when the startup pass handed the thread back with chunks still queued; see deferGateToDrain. */
    private static volatile long gateDeferredAtNanos;

    /**
     * Hands the untouched part of the corridor to the in-game drain instead of holding the world.
     *
     * <p>The startup thread cannot generate a chunk it does not already have: {@code prepareLevels}
     * does not tick the chunk source, so tickets added there never become chunk-level updates. Rather
     * than spin a budget away, the pass paints what is loaded and returns, the world starts ticking,
     * the drain finishes the corridor, and only then does {@link #openGateIfDrained} release the road.
     * The road already waits for this latch off-thread ({@code ROAD_INITIAL_GEN_WAIT}), so releasing
     * the server thread without opening the gate is exactly the handover it expects.
     */
    public static void deferGateToDrain(ServerLevel level) {
        gateDeferredAtNanos = System.nanoTime();
        GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_BACKFILL_DEFERRED pending={} - the world starts "
                + "ticking and the road gate stays closed until the drain finishes", pending(level));
    }

    /** Opens the road gate once the drain has painted everything, or when the wait ran out. */
    private static void openGateIfDrained(ServerLevel level, State state) {
        long deferredAt = gateDeferredAtNanos;
        if (deferredAt == 0L) return;
        boolean drained = state.pending.isEmpty() && state.settling.isEmpty();
        if (!drained && System.nanoTime() - deferredAt < GATE_DEFER_LIMIT_NANOS) return;
        gateDeferredAtNanos = 0L;
        long elapsedMs = (System.nanoTime() - deferredAt) / 1_000_000L;
        if (!drained) {
            GreatWallMod.LOGGER.error("[GreatWall:S1C] WALL_BACKFILL_DRAIN_TIMEOUT pending={} "
                    + "elapsedMs={}; the road gate is opened with the corridor unfinished",
                    state.pending.size(), elapsedMs);
        }
        local.plaincraft.greatwall.lifecycle.PrewarmProgress.finish(true);
        GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_BACKFILL_COMPLETE pending={} elapsedMs={} - "
                + "the road gate is open", state.pending.size(), elapsedMs);
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
        boolean forcedRepair = FORCE_REPAIR.remove(key);
        var existingMarker = chunk.getAttached(TerrainMarkers.PRODUCTION);
        if (existingMarker != null && PublishedPlanPainter.preserveCompleted(forcedRepair,
                existingMarker.complete(),existingMarker.planHash(),plan.hash())) {
            // This chunk has already been built and saved. Never turn a player's edit back into wall.
            // 0.7.118：单独计数。这一条是「written=0 但世界里明明有墙」的唯一出口 —— 之前它与
            // 「真的什么都没写」共用一个 written=0，导致日志无法区分，误判成墙没铺。
            ALREADY_BUILT.incrementAndGet();
            WallPlanSupport.markPlannedColumns(plan);
            local.plaincraft.greatwall.lifecycle.WorldServices.onWallChunkReady(level, chunk, plan);
            return new Sweep(Status.SETTLED, 0);
        }
        WallPlanSupport.markPlannedColumns(plan);
        // 强制修复或新版本施工先撤销旧完成章，后续实体失败不能留下旧的成功证据。
        if (existingMarker != null && existingMarker.complete()) WallPlanSupport.writeMarkers(chunk,plan,false);
        var result = PublishedPlanPainter.paint(plan, new PublishedPlanPainter.World<BlockState>() {
            public boolean validHeight(int y) { return !level.isOutsideBuildHeight(y); }
            public BlockState resolve(local.plaincraft.greatwall.model.WallTypes.BlockSpec spec) {
                return BlockCatalog.resolveState(spec);
            }
            public BlockState read(Point3i p) { return chunk.getBlockState(new BlockPos(p.x(),p.y(),p.z())); }
            public boolean write(Point3i p,BlockState target) {
                // 规划已经负责结构避让；首次施工按计划覆盖漏检建筑，沿用服务端高度/光照与客户端更新。
                return level.setBlock(new BlockPos(p.x(),p.y(),p.z()),target,2);
            }
            public void written(Point3i p,BlockState target,int count) {
                if ((count & 63) != 0) return;
                BlockState landed=read(p);
                if (landed != target) {
                    if (landed.isAir()) verifyMissing++; else verifyWrong++;
                    // 抽样发现未落地也必须保持未完成，不能只有告警却宣布建成。
                    throw new IllegalStateException("长城方块回读不一致: "+p);
                }
            }
            public void finish(java.util.Set<Point3i> satisfied,boolean complete) {
                WallPlanSupport.writeBlockEntities(chunk,plan,satisfied);
                WallPlanSupport.writeMarkers(chunk,plan,complete);
            }
            public void failure(Point3i p,RuntimeException failure) {
                if (++unresolvableVoxels % 512 == 1) {
                    GreatWallMod.LOGGER.warn("[GreatWall:施工] 首次补建未完成，保留重试 owner={},{} pos={} 原因={}",
                            key.x(),key.z(),p,failure.toString());
                }
            }
        });
        if (result.complete()) {
            local.plaincraft.greatwall.lifecycle.WorldServices.onWallChunkReady(level, chunk, plan);
        }
        // 不完整计划不得进入SETTLED，否则外层会移出队列并持久写入DONE。
        return new Sweep(result.complete() ? Status.SETTLED : Status.PENDING,result.written());
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
    /** The save this process last installed the plan reader for; a second world re-installs. */
    private static volatile java.nio.file.Path installedRoot;
    private static final java.util.concurrent.atomic.AtomicInteger DONE_PENDING = new java.util.concurrent.atomic.AtomicInteger();

    private static final class State {
        final Set<ChunkKey> pending = ConcurrentHashMap.newKeySet();
        /** DONE ledger entries waiting for their chunk's persistent PRODUCTION marker check. */
        final Set<ChunkKey> doneHint = ConcurrentHashMap.newKeySet();
        final Set<ChunkKey> settled = ConcurrentHashMap.newKeySet();
        final Set<ChunkKey> settling = ConcurrentHashMap.newKeySet();
        final Map<ChunkKey, Integer> attempts = new ConcurrentHashMap<>();
        /** In-game chunk requests in flight; this path never blocks on one. */
        final Map<ChunkKey, StartupRequest> requests = new ConcurrentHashMap<>();
        int tick;
        int total;
    }
    /**
     * 把某片已经"画完"的区块重新排队，让 drain 再画一遍。
     *
     * <p>墙画完之后被别的系统挖掉的格子，只有再走一次 {@code settle} 的自补步骤才能补回来；
     * 而已完成的区块被记录在 backfilled.txt 里，不会再被访问，所以这里把记录一并撤回。
     */
    public static int requeueAround(ServerLevel level, int centerChunkX, int centerChunkZ, int radiusChunks) {
        State state = level == null ? null : STATES.get(level);
        if (state == null) return 0;
        java.util.List<ChunkKey> back = new java.util.ArrayList<>();
        for (ChunkKey k : state.settled) {
            if (Math.abs(k.x() - centerChunkX) <= radiusChunks && Math.abs(k.z() - centerChunkZ) <= radiusChunks) {
                back.add(k);
            }
        }
        for (ChunkKey k : back) {
            state.settled.remove(k);
            state.pending.add(k);
            FORCE_REPAIR.add(k);
            DONE.remove(k.x() + "_" + k.z());
        }
        java.nio.file.Path file = doneFile;
        if (file != null) {
            try {
                java.nio.file.Files.write(file, DONE, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException | RuntimeException unwritable) {
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] WALL_REPAINT_LEDGER_FAILED reason={}", unwritable.toString());
            }
        }
        GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_REPAINT_REQUEUED chunks={} center={},{} radius={}",
                back.size(), centerChunkX, centerChunkZ, radiusChunks);
        return back.size();
    }
}
