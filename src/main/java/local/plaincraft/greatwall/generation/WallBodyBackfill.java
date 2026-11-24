package local.plaincraft.greatwall.generation;

import local.plaincraft.greatwall.model.WallFailure;
import java.util.ArrayList;
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
 *   <li>候选扫描按间隔节流，已开始的单块施工按 tick 推进，避免大地基独占服务端线程。</li>
 *   <li>相同方块不重复写入；持久完成章负责重开后保留玩家修改，部分施工仍可重试。</li>
 *   <li>首次落块以已发布计划为准；已完成同hash标记继续保护玩家后续编辑。</li>
 * </ul>
 */
public final class WallBodyBackfill {
    // 仅显式采样时输出单轮/单块耗时，避免把持续日志IO计入默认游戏负担。
    private static final boolean BACKFILL_TIMINGS="on".equalsIgnoreCase(
            System.getProperty("greatwall.backfillTimings","off"));
    // 只在刷新峰值时输出，不让逐 slice 日志干扰主线程计时。
    private static final boolean PEAK_TIMINGS = "on".equalsIgnoreCase(
            System.getProperty("greatwall.backfillPeakTimings", "off"));
    /** 没有可续建工作时节流，避免每 tick 重扫候选队列。 */
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
        var evidence = greatWallRoot.resolve("evidence");
        String dimension = level.dimension().location().toString();
        // 完工提示只由主世界长城写入，没有维度字段；即使计划目录丢了也保留已施工线索。
        if (level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            for (String token : DONE) {
                int split = token.indexOf('_');
                if (split <= 0) continue;
                try {
                    state.doneHint.add(new ChunkKey(dimension, Integer.parseInt(token.substring(0, split)),
                            Integer.parseInt(token.substring(split + 1))));
                } catch (NumberFormatException ignored) {
                    // 不把非法坐标伪造成某个区块。
                }
            }
        }
        try {
            var store = local.plaincraft.greatwall.persistence.ChunkEvidenceStore.production(evidence);
            for (ChunkKey key : store.planOwners(dimension)) {
                if (state.completed.contains(key) || state.doneHint.contains(key)) continue;
                if (state.pending.add(key)) added++;
            }
        } catch (java.io.IOException | java.nio.file.DirectoryIteratorException unreadable) {
            state.inventoryFailed = true;
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] WALL_BACKFILL_SEED_FAILED 无法盘点长城计划 root={} reason={}",
                    evidence, unreadable.toString());
        }
        state.total = Math.max(state.total, state.completed.size() + state.pending.size());
        GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_BACKFILL_SEEDED pending={} added={} settled={}",
                state.pending.size(), added, state.completed.size());
        return added;
    }
    /** How long a request may stay unanswered before its ticket is dropped and the chunk retried. */
    private static final long REQUEST_GIVE_UP_NANOS = 90_000_000_000L;
    /** 体素施工使用软预算；单次原版更新和实体结案不能从中间打断。 */
    private static final long DRAIN_BUDGET_NANOS = 4_000_000L;
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

    /** 去重覆盖从提交到主线程接收的整个窗口，避免同一计划重复读盘。 */
    private static void requestPlanRead(ServerLevel level, State state, ChunkKey key) {
        PlanStore store = planStore;
        if (store == null) return;
        var session = WallWorldgenPlanCache.readSession(level);
        var ticket = session.beginRead(key);
        if (ticket == null) return;
        try {
            IO.execute(() -> {
                ChunkWritePlan loaded = readPlan(store, key);
                try {
                    level.getServer().execute(() -> {
                        // 回到服务器线程后验证原会话与存储，不能借回调重新创建已卸载的状态。
                        boolean current = STATES.get(level) == state && planStore == store
                                && WallWorldgenPlanCache.isReadSessionCurrent(level, session);
                        if (session.finishRead(key, ticket, current ? loaded : null)) state.pending.add(key);
                    });
                } catch (java.util.concurrent.RejectedExecutionException closing) {
                    session.finishRead(key, ticket, null);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            session.finishRead(key, ticket, null);
        }
    }

    private static ChunkWritePlan readPlan(PlanStore store, ChunkKey key) {
        try {
            return store.read(key).orElse(null);
        } catch (java.io.IOException | RuntimeException failure) {
            GreatWallMod.LOGGER.warn("[GreatWall:施工] 读取施工计划失败 owner={},{} 原因={}",
                    key.x(), key.z(), failure.toString());
            return null;
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
                    requestPlanRead(level, state, key);
                    continue;
                }
                Sweep sweep = settle(level, chunk, key);
                var plan = WallWorldgenPlanCache.get(level, key);
                if (plan != null) voxels += plan.voxels().size();
                if (sweep.status() != Status.SETTLED) continue;
                written += sweep.written();
                markDone(key);
                state.pending.remove(key);
                state.completed.record(key, sweep.planHash());
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
        doneReadFailed = false;
        if (java.nio.file.Files.notExists(doneFile)) return;
        try {
            for (String line : java.nio.file.Files.readAllLines(doneFile)) {
                if (line != null && !line.isBlank()) DONE.add(line.trim());
            }
            GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_BACKFILL_DONE_LOADED chunks={}", DONE.size());
        } catch (java.io.IOException | RuntimeException unreadable) {
            doneReadFailed = true;
            GreatWallMod.LOGGER.warn("[GreatWall:S1C] WALL_BACKFILL_DONE_LOAD_FAILED reason={}",
                    unreadable.toString());
        }
    }

    /** 完工后追加恢复提示；真正的完成证据仍然是区块的持久完成章。 */
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

    /** 先取得持久确认再发布；临时 IO 只重试三次，避免无限卡住整个编译窗口。 */
    public static void persist(ChunkWritePlan plan) {
        java.util.Objects.requireNonNull(plan, "施工计划不能为空");
        PlanStore store = planStore;
        if (store == null) throw new IllegalStateException("长城计划存储未安装，禁止发布未落盘计划");
        for (int attempt = 1; attempt <= 3; attempt++) {
            // 取消不是磁盘故障；保留中断位，让会话清理仍能识别关闭请求。
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("长城计划持久化已取消");
            }
            try {
                store.write(plan);
                PERSISTED.incrementAndGet();
                return;
            } catch (java.io.IOException failure) {
                recordPersistFailure(plan, failure, attempt);
                if (attempt == 3) throw new java.io.UncheckedIOException("长城计划写盘失败，禁止发布", failure);
            } catch (RuntimeException failure) {
                // 配方/载荷非法不会因重复写入变好，原原因直接交给发布失败台账。
                recordPersistFailure(plan, failure, attempt);
                throw failure;
            }
        }
    }

    private static void recordPersistFailure(ChunkWritePlan plan, Exception failure, int attempt) {
        long failed = PERSIST_FAILURES.incrementAndGet();
        if (failed <= 4 || failed % 512 == 0) {
            GreatWallMod.LOGGER.warn("[GreatWall:持久化] 计划写入失败 WALL_PLAN_PERSIST_FAILED owner={},{} attempt={} totalFailures={} reason={}",
                    plan.owner().x(), plan.owner().z(), attempt, failed, failure.toString());
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
                // 未完成的游标不落盘，重开由同一存档的持久计划重新排队。
                if (state.active != null) state.active.cursor().cancel();
                for (StartupRequest request : state.requests.values()) {
                    releaseTicket(level, request.pos(), request.ticketId());
                }
            } catch (RuntimeException ignored) {
                // The level may already be closing; the tickets die with it.
            }
        }
        STATES.clear();
        FORCE_REPAIR.clear();
        DONE.clear();
        doneFile = null;
        doneReadFailed = false;
        installedRoot = null;
        planStore = null;
        gateDeferredAtNanos = 0L;
        drainsSinceLog = 0L;
        unresolvableVoxels = 0;
        verifyMissing = 0;
        verifyWrong = 0;
    }
    private static final AtomicLong TOTAL_BLOCKS = new AtomicLong();

    private WallBodyBackfill() {
    }

    /** 新发布按实际计划哈希判断；旧 DONE 不能吞掉已经加载区块的新计划。 */
    public static void enqueueCompiled(ServerLevel level, Map<ChunkKey, String> hashes) {
        if (level == null || hashes.isEmpty()) return;
        State state = state(level);
        int added = 0;
        for (var entry : hashes.entrySet()) {
            ChunkKey key = entry.getKey();
            if (state.completed.matches(key, entry.getValue())) continue;
            boolean replaced = state.completed.forget(key);
            if (!replaced && DONE.contains(key.x() + "_" + key.z())
                    && level.getChunkSource().getChunkNow(key.x(), key.z()) == null) {
                // 未加载的旧完工区块等自然加载验证，不为重新核对强拉整条旧走廊。
                state.doneHint.add(key);
            } else {
                state.doneHint.remove(key);
                if (state.pending.add(key)) added++;
            }
        }
        state.total = Math.max(state.total, state.completed.size() + state.pending.size());
        GreatWallMod.LOGGER.info("[GreatWall:施工] 新计划入队 added={} pending={} 同会话完工={} 待加载复核={}",
                added, state.pending.size(), state.completed.size(), state.doneHint.size());
    }

    /** Feature 明确报告落块未完成，不受磁盘完成提示影响。 */
    public static void enqueueUnfinished(ServerLevel level, ChunkKey key) {
        State state = state(level);
        state.completed.forget(key);
        state.doneHint.remove(key);
        state.pending.add(key);
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

    /** 加载事件只排队；区块完成章必须在实际施工入口按当前计划哈希验证。 */
    public static void onChunkLoaded(ServerLevel level, ChunkKey key) {
        if (level == null || key == null) return;
        State state = state(level);
        if (state.settling.contains(key)) return;
        ChunkWritePlan plan = WallWorldgenPlanCache.get(level, key);
        if (plan != null && state.completed.matches(key, plan.hash())) return;
        if (state.doneHint.remove(key) || plan != null) state.pending.add(key);
        if (plan == null && (!state.completed.contains(key) || state.pending.contains(key))) {
            requestPlanRead(level, state, key);
        }
    }

    /** 续建时不重排候选；缺区块仍由异步请求负责，不能在 tick 内等待。 */
    public static void drain(ServerLevel level) {
        if (level == null) return;
        State state = state(level);
        // 空闲节流不取时钟；实际有工作才计完整回调，包含排队、落块、结案和放行。
        boolean measure = PEAK_TIMINGS && (state.active != null
                || state.tick + 1 >= DRAIN_INTERVAL_TICKS && (!state.pending.isEmpty() || !state.requests.isEmpty()));
        ChunkKey resumedOwner = measure && state.active != null ? state.active.key() : null;
        long tickStarted = measure ? System.nanoTime() : 0L;
        try {
            if (state.active != null) {
                advanceActive(level, state, System.nanoTime());
                openGateIfDrained(level, state);
                return;
            }
            if (++state.tick < DRAIN_INTERVAL_TICKS) return;
            state.tick = 0;
            pollDrainRequests(level, state);
            if (state.pending.isEmpty()) {
                openGateIfDrained(level, state);
                return;
            }

            long startedAt = System.nanoTime();
            List<ChunkKey> candidates = nearestFirst(level, state.pending);
            long selectionNanos = BACKFILL_TIMINGS ? System.nanoTime() - startedAt : 0L;
            // 选择开销单独计时，否则大队列选候选超时会导致每一轮都不检查区块。
            long scanStartedAt = System.nanoTime();
            int inspected = 0;
            for (ChunkKey key : candidates) {
                if (inspected == MAX_DRAIN_CHECKS || System.nanoTime() - scanStartedAt >= DRAIN_BUDGET_NANOS) break;
                inspected++;
                LevelChunk chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
                if (chunk == null) {
                    if (FORCE_LOAD.get() && state.requests.size() < MAX_DRAIN_REQUESTS
                            && !state.requests.containsKey(key)) {
                        StartupRequest request = requestChunk(level, key, "drain");
                        if (request != null) state.requests.put(key, request);
                    }
                    continue;
                }
                var session = WallWorldgenPlanCache.readSession(level);
                ChunkWritePlan plan = session.get(key);
                if (plan == null) {
                    // 区块可能比计划更早加载；异步读盘后才能开始施工。
                    requestPlanRead(level, state, key);
                    continue;
                }
                var cursor = createSettlement(level, chunk, key, plan,
                        () -> WallWorldgenPlanCache.isReadSessionCurrent(level, session)
                                && level.getChunkSource().getChunkNow(key.x(), key.z()) == chunk
                                && session.get(key) == plan);
                state.settling.add(key);
                state.active = new ActiveBackfill(key, plan.hash(), cursor);
                advanceActive(level, state, scanStartedAt);
                break;
            }
            if (!state.pending.isEmpty() && ++drainsSinceLog >= 200) {
                drainsSinceLog = 0;
                GreatWallMod.LOGGER.info("[GreatWall:施工] 长城补建进度 pending={} settled={} totalWritten={} alreadyBuilt={}",
                        state.pending.size(), state.completed.size(), TOTAL_BLOCKS.get(), ALREADY_BUILT.get());
            }
            if (BACKFILL_TIMINGS) {
                GreatWallMod.LOGGER.info("[GreatWall:耗时] WALL_BACKFILL_TIMING selectionUs={} totalUs={} candidates={} inspected={} pending={}",
                        selectionNanos / 1000, (System.nanoTime() - startedAt) / 1000,
                        candidates.size(), inspected, state.pending.size());
            }
            openGateIfDrained(level, state);
        } finally {
            if (measure) recordDrainPeak(state, resumedOwner, System.nanoTime() - tickStarted);
        }
    }

    private static void recordDrainPeak(State state, ChunkKey resumedOwner, long elapsed) {
        long samples = ++state.measuredDrainTicks;
        if (elapsed <= state.maxDrainNanos) return;
        state.maxDrainNanos = elapsed;
        GreatWallMod.LOGGER.info(
                "【长城单次 tick 补建耗时峰值】 WALL_BACKFILL_TICK_PEAK maxUs={} measuredTicks={} pending={} resumedOwner={}",
                elapsed / 1000, samples, state.pending.size(), resumedOwner);
    }

    /** 只有施工终态失败才消耗重试次数；区块卸载或换计划只撤销临时游标。 */
    private static void advanceActive(ServerLevel level, State state, long startedAt) {
        ActiveBackfill active = state.active;
        var cursor = active.cursor();
        ChunkKey key = active.key();
        var before = cursor.stage();
        int written = 0;
        try {
            WallBackfillSlice.advance(cursor, () -> System.nanoTime() - startedAt >= DRAIN_BUDGET_NANOS);
            if (cursor.stage() == WallSettlementCursor.Stage.CLOSED) {
                var result = cursor.result();
                written = result.written();
                TOTAL_BLOCKS.addAndGet(written);
                if (result.complete()) {
                    state.completed.record(key, active.hash());
                    state.pending.remove(key);
                    state.attempts.remove(key);
                    FORCE_REPAIR.remove(key);
                    markDone(key);
                    // 已有进展时下一 tick 可选新块；只有等区块或读盘时才节流。
                    state.tick = DRAIN_INTERVAL_TICKS - 1;
                    if (state.pending.isEmpty()) {
                        GreatWallMod.LOGGER.info("[GreatWall:施工] 长城补建队列已处理完，本会话完工区块={}", state.completed.size());
                    }
                } else {
                    recordBackfillFailure(state, key);
                }
            }
        } catch (RuntimeException failure) {
            GreatWallMod.LOGGER.warn("[GreatWall:施工] 补建事务异常 owner={},{}", key.x(), key.z(), failure);
            recordBackfillFailure(state, key);
        } finally {
            if (cursor.terminal()) {
                state.active = null;
                state.settling.remove(key);
                // 请求来的区块保留票据直到结案；自然加载的区块在续建时复核身份。
                releaseDrainRequest(level, state, key);
            }
            if (BACKFILL_TIMINGS) {
                GreatWallMod.LOGGER.info("[GreatWall:耗时] WALL_BACKFILL_SLICE owner={},{} stage={}->{} writtenAtClose={} sliceUs={}",
                        key.x(), key.z(), before, cursor.stage(), written, (System.nanoTime() - startedAt) / 1000);
            }
        }
    }

    private static void recordBackfillFailure(State state, ChunkKey key) {
        if (state.attempts.merge(key, 1, Integer::sum) < MAX_ATTEMPTS) return;
        state.pending.remove(key);
        state.attempts.remove(key);
        GreatWallMod.LOGGER.warn("[GreatWall:施工] 补建重试耗尽，未记录完成 owner={},{} attempts={}",
                key.x(), key.z(), MAX_ATTEMPTS);
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
    public static WallRoadRecovery.Report seedRoadGuardFromPlans(ServerLevel level) {
        State state = level == null ? null : STATES.get(level);
        if (state == null) return new WallRoadRecovery.Report(0, 0, 0, 0, 0, 0, doneReadFailed);
        java.util.Set<ChunkKey> keys = new java.util.LinkedHashSet<>(state.pending);
        keys.addAll(state.completed.keys());
        keys.addAll(state.doneHint);
        // 已完工区块也需恢复保护；否则旧档漏墙列却能通过只看待办的检查。
        PlanStore store = planStore;
        return WallRoadRecovery.restore(keys, state.inventoryFailed || doneReadFailed,
                key -> WallWorldgenPlanCache.get(level, key),
                key -> store == null ? null : store.read(key).orElse(null),
                plan -> WallWorldgenPlanCache.put(level, plan));
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
    private enum Status { SETTLED, PENDING }

    private record Sweep(Status status, int written, String planHash) {
    }

    private static Sweep settle(ServerLevel level, LevelChunk chunk, ChunkKey key) {
        ChunkWritePlan plan = WallWorldgenPlanCache.get(level, key);
        if (plan == null) {
            // The durable read is prefetched on the IO thread when the chunk loads; doing it here
            // would put a file read on the server thread for every chunk the player walks into.
            // A miss means the prefetch is still in flight, so this chunk stays pending.
            return new Sweep(Status.PENDING, 0, null);
        }
        var result = createSettlement(level, chunk, key, plan, () -> true).runAll();
        if (result.complete()) FORCE_REPAIR.remove(key);
        return new Sweep(result.complete() ? Status.SETTLED : Status.PENDING, result.written(), plan.hash());
    }

    private static WallSettlementCursor<BlockState> createSettlement(ServerLevel level, LevelChunk chunk,
            ChunkKey key, ChunkWritePlan plan, java.util.function.BooleanSupplier current) {
        // 强制重画请求必须保留到成功，否则暂停后会误用上一次完工证据。
        boolean forcedRepair = FORCE_REPAIR.contains(key);
        var existingMarker = chunk.getAttached(TerrainMarkers.PRODUCTION);
        return new WallSettlementCursor<>(plan, new WallSettlementCursor.Boundary<BlockState>() {
            public boolean current() { return current.getAsBoolean(); }
            public boolean preserveCompleted() {
                return existingMarker != null && PublishedPlanPainter.preserveCompleted(forcedRepair,
                        existingMarker.complete(),existingMarker.planHash(),plan.hash());
            }
            public void reuseCompleted() {
                ALREADY_BUILT.incrementAndGet();
                WallPlanSupport.markPlannedColumns(plan);
                local.plaincraft.greatwall.lifecycle.WorldServices.onWallChunkReady(level, chunk, plan);
            }
            public void prepare() {
                WallPlanSupport.markPlannedColumns(plan);
                // 强制修复先撤销旧完成章，后续实体失败不得留下旧成功证据。
                if (existingMarker != null && existingMarker.complete()) WallPlanSupport.writeMarkers(chunk,plan,false);
            }
            public PublishedPlanPainter.World<BlockState> world() {
                return createSettlementWorld(level,chunk,key,plan);
            }
            public void publish() {
                local.plaincraft.greatwall.lifecycle.WorldServices.onWallChunkReady(level, chunk, plan);
            }
        });
    }
    /** 世界适配仅搬读写边界，随机、更新标志、抽样回读和元数据次序保持原样。 */
    private static PublishedPlanPainter.World<BlockState> createSettlementWorld(ServerLevel level, LevelChunk chunk,
            ChunkKey key, ChunkWritePlan plan) {
        var services=local.plaincraft.greatwall.lifecycle.WorldServices.find(level.getServer());
        boolean authored=services!=null&&services.productionFixture()
                &&local.plaincraft.greatwall.component.ImportedSiteGeometry.enabled(services.recipe());
        return new PublishedPlanPainter.World<BlockState>() {
            public boolean validHeight(int y) { return !level.isOutsideBuildHeight(y); }
            public BlockState resolve(local.plaincraft.greatwall.model.WallTypes.BlockSpec spec) {
                return BlockCatalog.resolveState(spec);
            }
            public BlockState read(Point3i p) { return chunk.getBlockState(new BlockPos(p.x(),p.y(),p.z())); }
            public boolean write(Point3i p,BlockState target) {
                // 规划已经负责结构避让；首次施工按计划覆盖漏检建筑，沿用服务端高度/光照与客户端更新。
                // 实物已冻结墙连接和楼梯形状；避免补建按临时邻居改形，发光方块仍触发原版光照更新。
                int flags=updateFlags(authored,target.getLightEmission());
                return level.setBlock(new BlockPos(p.x(),p.y(),p.z()),target,flags);
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
            // 跨 tick 只保留需复核的实体坐标；普通墙体仍逐格校验写入结果。
            public boolean trackOnlyEntityPositions() { return true; }
            public void finish(java.util.Set<Point3i> satisfied,boolean complete) {
                // 跨 tick 时实体位置可能已变；重试该块，不能把模板数据装到别的方块上。
                WallPlanSupport.requireCurrentEntityBlocks(chunk, plan, satisfied);
                WallPlanSupport.writeBlockEntities(chunk,plan,satisfied);
                WallPlanSupport.writeMarkers(chunk,plan,complete);
            }
            public void failure(Point3i p,RuntimeException failure) {
                if (++unresolvableVoxels % 512 == 1) {
                    GreatWallMod.LOGGER.warn("[GreatWall:施工] 首次补建未完成，保留重试 owner={},{} pos={} 原因={}",
                            key.x(),key.z(),p,failure.toString());
                }
            }
        };
    }
    /** 旧配方保持原标志，新配方按冻结形状落块并唤醒光照相关邻居。 */
    static int updateFlags(boolean authored,int emission) {
        if(!authored)return net.minecraft.world.level.block.Block.UPDATE_CLIENTS;
        return net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE
                |(emission>0?net.minecraft.world.level.block.Block.UPDATE_NEIGHBORS:0);
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
    private static volatile boolean doneReadFailed;
    /** The save this process last installed the plan reader for; a second world re-installs. */
    private static volatile java.nio.file.Path installedRoot;
    private static final java.util.concurrent.atomic.AtomicInteger DONE_PENDING = new java.util.concurrent.atomic.AtomicInteger();

    /** 带上原计划的标识，因为完工台账必须登记实际落块的版本。 */
    private record ActiveBackfill(ChunkKey key, String hash, WallSettlementCursor<BlockState> cursor) {}

    private static final class State {
        final Set<ChunkKey> pending = ConcurrentHashMap.newKeySet();
        /** DONE ledger entries waiting for their chunk's persistent PRODUCTION marker check. */
        final Set<ChunkKey> doneHint = ConcurrentHashMap.newKeySet();
        final BackfillCompletions completed = new BackfillCompletions();
        final Set<ChunkKey> settling = ConcurrentHashMap.newKeySet();
        final Map<ChunkKey, Integer> attempts = new ConcurrentHashMap<>();
        /** In-game chunk requests in flight; this path never blocks on one. */
        final Map<ChunkKey, StartupRequest> requests = new ConcurrentHashMap<>();
        ActiveBackfill active;
        int tick;
        int total;
        long measuredDrainTicks;
        long maxDrainNanos;
        boolean inventoryFailed;
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
        for (ChunkKey k : state.completed.keys()) {
            if (Math.abs(k.x() - centerChunkX) <= radiusChunks && Math.abs(k.z() - centerChunkZ) <= radiusChunks) {
                back.add(k);
            }
        }
        for (ChunkKey k : back) {
            state.completed.forget(k);
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
