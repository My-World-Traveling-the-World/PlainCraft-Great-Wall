package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import local.plaincraft.greatwall.GreatWallMod;
import net.minecraft.server.level.ServerLevel;

/**
 * Orchestrates the planning-time corridor structure scan on a dedicated worker pool.
 *
 * <p>The pool is deliberately separate from {@code OracleLane} (which is thread-confined to run
 * {@code CpuTerrainOracle} over pure noise) because {@code Structure.generate} reads the level's
 * registries, generator and structure manager. The scan is self-contained and its result feeds the
 * detour stage, so callers obtain a future and only {@link #close} on session shutdown.
 */
public final class CorridorStructureScanner implements AutoCloseable {
    private final ExecutorService pool;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();

    public CorridorStructureScanner() {
        // Adaptive rather than a fixed four. This pool bounds how many scans may run at once, and the
        // resolve stage inside a scan already scales itself to the hardware, so a hardcoded four only
        // capped the outer fan-out on machines that can afford more.
        this(CorridorStructureScan.adaptiveScanWorkers());
    }

    public CorridorStructureScanner(int threads) {
        pool = Executors.newFixedThreadPool(Math.max(1, threads), task -> {
            Thread thread = new Thread(task, "pgw-corridor-scan");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Scans the corridor around a wall axis and returns the resolved structure boxes.
     *
     * @param level       the overworld level (frozen, read-only during planning)
     * @param minChunkX   corridor west bound (chunk coordinate)
     * @param minChunkZ   corridor north bound (chunk coordinate)
     * @param maxChunkX   corridor east bound (chunk coordinate)
     * @param maxChunkZ   corridor south bound (chunk coordinate)
     * @return 扫描完整结果；世界无效或扫描关闭时异常结案，空列表仅表示成功但无结构
     */
    public CompletableFuture<List<CorridorStructureScan.OwnedBox>> scan(
            ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        return scan(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ, null);
    }

    /** Scan with an optional wall corridor; candidates outside the band are dropped up front. */
    public CompletableFuture<List<CorridorStructureScan.OwnedBox>> scan(
            ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
            CorridorStructureScan.Corridor corridor) {
        if (level == null || !net.minecraft.world.level.Level.OVERWORLD.equals(level.dimension())
                || minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("结构扫描需要有效的主世界和区块范围"));
        }
        return submit(() -> {
            long startedAt = System.nanoTime();
            // A structure start is a pure function of the seed, the structure sets and the chunk, so
            // the same corridor always resolves to the same boxes. The scan measured 268,569 ms - 4.5
            // minutes - per startup, which is why RoadWeaver caches its structure starts too.
            String cacheKey = StructureScanCache.key(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
            var cached = StructureScanCache.load(level, cacheKey);
            if (cached.isPresent()) {
                GreatWallMod.LOGGER.info(
                        "[GreatWall:corridor] structure scan cache hit corridor=[{},{}]..[{},{}] boxes={} elapsedMs={}",
                        minChunkX, minChunkZ, maxChunkX, maxChunkZ, cached.get().size(),
                        (System.nanoTime() - startedAt) / 1_000_000L);
                return cached.get();
            }
            var candidates = CorridorStructureScan.predictCandidates(
                    level, minChunkX, minChunkZ, maxChunkX, maxChunkZ, corridor);
            // The loading card's structure-scan stage: candidates known up front, done count ticking
            // live as the scan walks them, total/total once resolved.
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                    local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.STRUCTURE_SCAN,
                    0, candidates.size());
            var boxes = CorridorStructureScan.resolveBoxes(level, candidates,
                    done -> local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                            local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.STRUCTURE_SCAN,
                            done, candidates.size()));
            // The finished count stays in the candidate口径 even though the result is shorter: the live
            // ticks above count candidates, so reporting boxes here would walk the displayed number
            // backwards (13085 candidates resolving to 4262 boxes reads as a stalled meter).
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                    local.plaincraft.greatwall.lifecycle.PrewarmProgress.Stage.STRUCTURE_SCAN,
                    candidates.size(), candidates.size());
            long[] yield = CorridorStructureScan.scanYield();
            long[] filter = CorridorStructureScan.filterYield();
            GreatWallMod.LOGGER.info(
                    "[GreatWall:corridor] structure scan corridor=[{},{}]..[{},{}] candidates={} boxes={} "
                            + "starts={} emptyCandidates={} pieces={} filterWouldSkip={}/{} elapsedMs={}",
                    minChunkX, minChunkZ, maxChunkX, maxChunkZ, candidates.size(), boxes.size(),
                    yield[0], yield[1], yield[2], filter[1], filter[0],
                    (System.nanoTime() - startedAt) / 1_000_000L);
            if (closed.get()) throw new CancellationException("结构扫描已关闭");
            StructureScanCache.store(level, cacheKey, boxes);
            return boxes;
        });
    }

    <T> CompletableFuture<T> submit(Callable<T> scan) {
        var future = new CompletableFuture<T>();
        pending.add(future);
        future.whenComplete((result, error) -> pending.remove(future));
        // 注册后再核对关闭：被 shutdownNow 从队列移除的任务也必须通知调用方。
        if (closed.get()) {
            future.cancel(false);
            return future;
        }
        try {
            pool.execute(() -> {
                if (future.isDone() || closed.get()) {
                    future.cancel(false);
                    return;
                }
                try {
                    T result = scan.call();
                    if (closed.get()) future.cancel(false);
                    else future.complete(result);
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException stopped) {
            future.completeExceptionally(stopped);
        }
        return future;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pending.forEach(future -> future.cancel(false));
        pool.shutdownNow();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
