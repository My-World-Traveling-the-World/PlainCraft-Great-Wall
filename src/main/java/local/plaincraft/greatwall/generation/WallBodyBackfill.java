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
                    if (DONE.contains(x + "_" + z)) { state.verify.add(key); continue; }
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
    /** Done-ledger entries one drain may re-verify; each costs one block read. */
    private static final int MAX_VERIFY_PER_DRAIN = 8;
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
        List<ChunkKey> keys = new ArrayList<>(pending);
        if (keys.size() < 2) return keys;
        var players = level.players();
        if (players.isEmpty()) return keys;
        double cx = 0.0D;
        double cz = 0.0D;
        for (var player : players) {
            cx += player.getX();
            cz += player.getZ();
        }
        cx /= players.size();
        cz /= players.size();
        final double centreX = cx;
        final double centreZ = cz;
        keys.sort(java.util.Comparator.comparingDouble(key -> {
            double dx = (key.x() << 4) + 8 - centreX;
            double dz = (key.z() << 4) + 8 - centreZ;
            return dx * dx + dz * dz;
        }));
        return keys;
    }

    /**
     * Re-proves the done ledger against the world, one loaded chunk at a time.
     *
     * <h2>Why the ledger cannot be trusted on its own</h2>
     * {@code backfilled.txt} is appended the moment a chunk is written, but the chunk itself is only
     * flushed to disk when the world saves. A crash (or a force-kill) between the two leaves the
     * ledger ahead of the world: the save then skips those chunks for ever, and the wall has holes the
     * log never mentions. A measured session marked 88 chunks done and was killed before its first
     * autosave. So a ledger entry buys a chunk one free pass, not a permanent one: the first time the
     * chunk loads, one planned voxel is checked, and a mismatch puts the whole chunk back in the queue.
     */
    private static void verifyDone(ServerLevel level, State state) {
        if (state.verify.isEmpty()) return;
        int checks = 0;
        for (ChunkKey key : List.copyOf(state.verify)) {
            if (checks++ >= MAX_VERIFY_PER_DRAIN) break;
            LevelChunk chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
            if (chunk == null) continue;
            ChunkWritePlan plan = WallWorldgenPlanCache.get(level, key);
            if (plan == null) {
                requestPlanRead(level, key);
                continue;
            }
            // A DONE chunk is already built; its columns must still be visible to the road guard.
            WallPlanSupport.markPlannedColumns(plan);
            // Three candidate voxels, and one match is enough: a chunk that was written normally
            // matches all of them, and requiring only one keeps a single player-placed block (or a
            // voxel the write skipped as a conflict) from repainting a chunk that is otherwise done.
            int matched = 0;
            int candidates = 0;
            for (var voxel : plan.voxels()) {
                if (voxel.kind() == IntentKind.KEEP) continue;
                // No before-state filter: a voxel whose compile-time neighbour was solid may have
                // been skipped as a conflict, and one skipped voxel must not condemn the chunk.
                BlockState expected;
                try {
                    expected = BlockCatalog.resolveState(voxel.state());
                } catch (RuntimeException unresolvable) {
                    continue;
                }
                candidates++;
                Point3i at = voxel.pos();
                if (chunk.getBlockState(new BlockPos(at.x(), at.y(), at.z())) == expected) {
                    matched++;
                    break;
                }
                if (candidates >= 3) break;
            }
            state.verify.remove(key);
            if (candidates == 0 || matched > 0) continue;
            state.pending.add(key);
            if (REPAINT_LOGS.incrementAndGet() <= 8) {
                GreatWallMod.LOGGER.info(
                        "[GreatWall:S1C] WALL_BACKFILL_REPAINTED owner={},{} reason=sentinel-mismatch",
                        key.x(), key.z());
            }
        }
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
            if (PERSIST_FAILURES.incrementAndGet() <= 4) {
                GreatWallMod.LOGGER.warn("[GreatWall:S1C] WALL_PLAN_PERSIST_FAILED owner={},{} reason={}",
                        plan.owner().x(), plan.owner().z(), failure.toString());
            }
        }
    }

    public static long persisted() { return PERSISTED.get(); }

    public static long persistFailures() { return PERSIST_FAILURES.get(); }
    private static final Map<ServerLevel, State> STATES = new ConcurrentHashMap<>();

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
            // The ledger buys a chunk one free pass, not a permanent one: verifyDone re-proves it the
            // first time it loads. Honouring it here is what keeps a reload from repainting all 456
            // chunks that an earlier session already wrote.
            if (DONE.contains(key.x() + "_" + key.z())) {
                if (state.verify.add(key)) proven++;
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
        // The ledger first: it is the only thing between a crashed session and a wall with holes in it,
        // and it costs one block read per loaded chunk. See verifyDone.
        verifyDone(level, state);
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
                        if (state.attempts.merge(key, 1, Integer::sum) >= MAX_ATTEMPTS) {
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
            GreatWallMod.LOGGER.info(
                    "[GreatWall:S1C] WALL_BACKFILL_DRAIN settled={} pending={} written={} voxels={} dropped={} guardSkip={} total={} verifyMissing={} verifyWrong={}",
                    settledThisDrain, state.pending.size(), writtenBlocks, voxelsSeen, dropped.size(),
                    beforeConflicts, total, verifyMissing, verifyWrong);
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
        // DONE chunks live in verify, not in pending. Omitting them meant a reloaded save could
        // report missed=0 while every already-built chunk was invisible to the road guard.
        keys.addAll(state.verify);
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
        int written = 0;
        var touched = new java.util.HashSet<Point3i>();
        // Mark the complete plan before any idempotence/conflict skip. These are read-only guard
        // marks, not writes: an already-built column must be protected just as much as a new one.
        WallPlanSupport.markPlannedColumns(plan);
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
            touched.add(p);
            local.plaincraft.greatwall.compat.road.WallRoadGuard.markColumn(pos.getX(),pos.getZ());
            written++;
            // ---- 只读自检（B 线）：每 64 个刚写完的体素抽 1 个立刻回读对一遍。
            // 不补洞、不重试、不改写，只上报 —— 以后任何"盖完却缺格"一次跑就能定位，
            // 不用像 2026-10-24 那样去解 region 文件猜（当时是道路把墙挖掉的）。
            if ((written & 63) == 0) {
                BlockState landed = chunk.getBlockState(pos);
                if (landed != target) {
                    if (landed.isAir()) verifyMissing++; else verifyWrong++;
                    if ((verifyMissing + verifyWrong) % 64 == 1) {
                        GreatWallMod.LOGGER.warn("[GreatWall:verify] drain owner={},{} pos={} want={} got={}",
                                key.x(), key.z(), pos, target.getBlock(), landed.getBlock());
                    }
                }
            }
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
        try {
            WallPlanSupport.writeBlockEntities(chunk, plan, touched);
            WallPlanSupport.writeMarkers(chunk, plan);
        } catch (RuntimeException metadataFailure) {
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] WALL_PLAN_METADATA_FAILED owner={},{} reason={}",
                    key.x(), key.z(), metadataFailure.toString());
        }
        local.plaincraft.greatwall.lifecycle.WorldServices.onWallChunkReady(level, chunk, plan);
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
    /** The save this process last installed the plan reader for; a second world re-installs. */
    private static volatile java.nio.file.Path installedRoot;
    /** Mismatched ledger entries reported so far; the log stays readable on a regenerated world. */
    private static final AtomicLong REPAINT_LOGS = new AtomicLong();
    private static final java.util.concurrent.atomic.AtomicInteger DONE_PENDING = new java.util.concurrent.atomic.AtomicInteger();

    private static final class State {
        final Set<ChunkKey> pending = ConcurrentHashMap.newKeySet();
        final Set<ChunkKey> settled = ConcurrentHashMap.newKeySet();
        final Set<ChunkKey> settling = ConcurrentHashMap.newKeySet();
        final Map<ChunkKey, Integer> attempts = new ConcurrentHashMap<>();
        /** Ledger entries whose chunk has to prove itself before it is trusted. See verifyDone. */
        final Set<ChunkKey> verify = ConcurrentHashMap.newKeySet();
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