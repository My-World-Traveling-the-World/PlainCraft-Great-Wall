package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
     * @return a future of resolved boxes; empty when the scanner is closed or inputs are invalid
     */
    public CompletableFuture<List<CorridorStructureScan.OwnedBox>> scan(
            ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        return scan(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ, null);
    }

    /** Scan with an optional wall corridor; candidates outside the band are dropped up front. */
    public CompletableFuture<List<CorridorStructureScan.OwnedBox>> scan(
            ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
            CorridorStructureScan.Corridor corridor) {
        if (closed.get() || level == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        Objects.requireNonNull(level, "level");
        return CompletableFuture.supplyAsync(() -> {
            if (closed.get()) return List.<CorridorStructureScan.OwnedBox>of();
            long startedAt = System.nanoTime();
            var candidates = CorridorStructureScan.predictCandidates(
                    level, minChunkX, minChunkZ, maxChunkX, maxChunkZ, corridor);
            // The loading card's structure-scan stage: candidates known up front, done count ticking
            // live as the scan walks them, total/total once resolved.
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(0, candidates.size());
            var boxes = CorridorStructureScan.resolveBoxes(level, candidates,
                    done -> local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                            done, candidates.size()));
            // The finished count stays in the candidate口径 even though the result is shorter: the live
            // ticks above count candidates, so reporting boxes here would walk the displayed number
            // backwards (13085 candidates resolving to 4262 boxes reads as a stalled meter).
            local.plaincraft.greatwall.lifecycle.PrewarmProgress.setSubProgress(
                    candidates.size(), candidates.size());
            GreatWallMod.LOGGER.info(
                    "[GreatWall:corridor] structure scan corridor=[{},{}]..[{},{}] candidates={} boxes={} elapsedMs={}",
                    minChunkX, minChunkZ, maxChunkX, maxChunkZ, candidates.size(), boxes.size(),
                    (System.nanoTime() - startedAt) / 1_000_000L);
            return boxes;
        }, pool);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pool.shutdownNow();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
