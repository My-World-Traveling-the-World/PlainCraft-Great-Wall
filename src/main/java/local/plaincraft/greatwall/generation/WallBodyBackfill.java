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
 *   <li><b>Never request a chunk.</b> Only {@code getChunkNow}; an unloaded chunk stays pending
 *       until the player loads it.</li>
 *   <li><b>Never block.</b> At most {@link #MAX_CHUNKS_PER_DRAIN} chunks per drain, one drain
 *       every {@link #DRAIN_INTERVAL_TICKS} ticks.</li>
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
        GreatWallMod.LOGGER.info("[GreatWall:S1C] WALL_BACKFILL_STORE_INSTALLED root={}", greatWallRoot);
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
        List<ChunkKey> dropped = new ArrayList<>();
        for (ChunkKey key : List.copyOf(state.pending)) {
            if (settledThisDrain >= MAX_CHUNKS_PER_DRAIN) break;
            if (state.settling.contains(key)) continue;
            LevelChunk chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
            if (chunk == null) continue; // not loaded: stays pending, and is never requested
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
                switch (sweep.status()) {
                    case SETTLED -> {
                        state.pending.remove(key);
                        state.settled.add(key);
                        state.attempts.remove(key);
                        settledThisDrain++;
                        writtenBlocks += sweep.written();
                    }
                    case FOREIGN -> {
                        // No plan for this node, or another dimension's chunk: retrying cannot help.
                        state.pending.remove(key);
                        state.settled.add(key);
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
        if (settledThisDrain > 0 || !dropped.isEmpty()) {
            GreatWallMod.LOGGER.info(
                    "[GreatWall:S1C] WALL_BACKFILL_DRAIN settled={} pending={} written={} dropped={} total={}",
                    settledThisDrain, state.pending.size(), writtenBlocks, dropped.size(), total);
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
            if (before != null && !before.startsWith("minecraft:air")) {
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

    private static final class State {
        final Set<ChunkKey> pending = ConcurrentHashMap.newKeySet();
        final Set<ChunkKey> settled = ConcurrentHashMap.newKeySet();
        final Set<ChunkKey> settling = ConcurrentHashMap.newKeySet();
        final Map<ChunkKey, Integer> attempts = new ConcurrentHashMap<>();
        int tick;
        int total;
    }
}
