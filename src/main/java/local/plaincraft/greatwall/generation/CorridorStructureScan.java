package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Two-stage corridor structure scan, planning-only and never loading a physical chunk.
 *
 * <h2>Why two stages</h2>
 * A structure's exact footprint (its {@link BoundingBox} pieces) cannot be derived from placement
 * math alone: surface structures use the jigsaw system, so their true boxes are decided by runtime
 * template assembly. {@link #predictCandidates} narrows the corridor to a handful of <em>locate
 * points</em> purely from seed math (zero chunk work); {@link #resolveBoxes} then runs the vanilla
 * {@code Structure.generate} on each point in memory, which yields the real {@link StructureStart}
 * and its pieces <em>without</em> allocating a {@code ProtoChunk}, adding a ticket, or entering the
 * chunk pipeline. That is exactly the RoadWeaver path ({@code StructurePredictor} + {@code
 * resolvePredictedStructureSetBoxes}) and it is safe while the server thread is parked in the
 * prewarm's managed block.
 */
public final class CorridorStructureScan {
    /** 两级派生缓存共用修订号；候选坐标修正后，旧的漏扫结果不能参与新计划。 */
    public static final String CACHE_REVISION = "v3";

    private CorridorStructureScan() {
    }

    /** 保留起点区块：定位偏移可以跨区块，不能用定位点还原生成种子和坐标。 */
    public record Candidate(ChunkPos startChunk, Holder<StructureSet> structureSet) {
    }

    /** One resolved structure-piece box with its owner identity. */
    public record OwnedBox(String structureId, long startChunkKey, BoundingBox box) {
    }

    /**
     * Stage 1: seed-math locate points inside {@code [minChunkX..maxChunkX] x [minChunkZ..maxChunkZ]}.
     *
     * <p>Mirrors RoadWeaver {@code StructurePredictor.predictAvoidanceStructuresInRect}. Underground
     * or dimension-incompatible sets are skipped so the scan cannot balloon into tens of thousands of
     * candidates for a surface wall.
     */
    public static List<Candidate> predictCandidates(ServerLevel level, int minChunkX, int minChunkZ,
            int maxChunkX, int maxChunkZ) {
        return predictCandidates(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ, null);
    }

    /**
     * Predict with an optional wall corridor segment {@code (ox,oz)->(ex,ez)} in block coords and
     * a half-width; candidates farther than {@code corridorHalfWidth} from the segment are
     * dropped. This trims the AABB's 3.5× overscan when the wall runs at an angle, so only chunks
     * the wall can actually reach are generated.
     */
    public static List<Candidate> predictCandidates(ServerLevel level, int minChunkX, int minChunkZ,
            int maxChunkX, int maxChunkZ, Corridor corridor) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) return List.of();
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        List<Candidate> result = new ArrayList<>();
        for (Holder<StructureSet> holder : state.possibleStructureSets()) {
            if (!isSurfaceRelevant(holder)) continue;
            StructurePlacement placement = holder.value().placement();
            if (placement instanceof ConcentricRingsStructurePlacement rings) {
                List<ChunkPos> positions = state.getRingPositionsFor(rings);
                if (positions == null) continue;
                for (ChunkPos candidate : positions) {
                    addCandidate(state, holder, placement, candidate, minChunkX, minChunkZ,
                            maxChunkX, maxChunkZ, corridor, result);
                }
                continue;
            }
            if (placement instanceof RandomSpreadStructurePlacement randomSpread) {
                int spacing = randomSpread.spacing();
                int startI = Math.floorDiv(minChunkX, spacing);
                int endI = Math.floorDiv(maxChunkX, spacing);
                int startJ = Math.floorDiv(minChunkZ, spacing);
                int endJ = Math.floorDiv(maxChunkZ, spacing);
                for (int i = startI; i <= endI; i++) {
                    for (int j = startJ; j <= endJ; j++) {
                        ChunkPos candidate = randomSpread.getPotentialStructureChunk(
                                state.getLevelSeed(), i * spacing, j * spacing);
                        addCandidate(state, holder, placement, candidate, minChunkX, minChunkZ,
                                maxChunkX, maxChunkZ, corridor, result);
                    }
                }
                continue;
            }
            predictCustom(state, holder, placement, minChunkX, minChunkZ, maxChunkX, maxChunkZ, corridor, result);
        }
        return List.copyOf(result);
    }

    /** 自定义定位点可随区块变化；只按真实定位点过滤，不能拿区块坐标裁方块走廊。 */
    static void predictCustom(ChunkGeneratorStructureState state, Holder<StructureSet> holder,
                              StructurePlacement placement, int minX, int minZ, int maxX, int maxZ,
                              Corridor corridor, List<Candidate> result) {
        // 已取消的扫描不分派工作；否则快速完成的首带可能先被收进半批结果。
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("长城候选扫描已中止");
        int rows = maxX - minX + 1;
        int crews = Math.min(adaptiveScanWorkers(), rows);
        if (crews <= 1) {
            predictCustomRows(state, holder, placement, minX, minZ, maxX, maxZ, corridor, result);
            return;
        }
        // 各带独立扫描，最后按原来的 X/Z 顺序合并，解析不依赖线程先后。
        int rowsPerCrew = Math.ceilDiv(rows, crews);
        var pool = Executors.newFixedThreadPool(crews, task -> {
            Thread thread = new Thread(task, "pgw-corridor-predict");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var bands = new ArrayList<Future<List<Candidate>>>(crews);
            for (int x = minX; x <= maxX; x += rowsPerCrew) {
                int from = x;
                int to = Math.min(maxX, from + rowsPerCrew - 1);
                bands.add(pool.submit(() -> {
                    var matches = new ArrayList<Candidate>();
                    predictCustomRows(state, holder, placement, from, minZ, to, maxZ, corridor, matches);
                    return matches;
                }));
            }
            for (var band : bands) result.addAll(band.get());
        } catch (InterruptedException stopped) {
            // 退出世界时停止整带枚举，别把尚未扫完的候选作为可缓存的空结果。
            Thread.currentThread().interrupt();
            throw new CancellationException("长城候选扫描已中止");
        } catch (ExecutionException failure) {
            throw new CompletionException(failure.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static void predictCustomRows(ChunkGeneratorStructureState state, Holder<StructureSet> holder,
                                          StructurePlacement placement, int fromX, int minZ, int toX, int maxZ,
                                          Corridor corridor, List<Candidate> result) {
        for (int x = fromX; x <= toX; x++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("长城候选扫描已中止");
            for (int z = minZ; z <= maxZ; z++) {
                addCandidate(state, holder, placement, new ChunkPos(x, z), fromX, minZ, toX, maxZ, corridor, result);
            }
        }
    }

    /** The wall corridor segment in block coords plus its half-width; null means no trimming. */
    public record Corridor(int originX, int originZ, int endX, int endZ, int halfWidth) {
        /** 保留原来向零取整的投影，再判断点是否落入走廊。 */
        boolean within(int x, int z) {
            long dx = (long) endX - originX, dz = (long) endZ - originZ;
            long lenSq = dx * dx + dz * dz;
            if (lenSq == 0) {
                long ddx = x - originX, ddz = z - originZ;
                return ddx * ddx + ddz * ddz <= (long) halfWidth * halfWidth;
            }
            long t = ((long) (x - originX) * dx + (long) (z - originZ) * dz);
            long cx, cz;
            if (t <= 0) { cx = originX; cz = originZ; }
            else if (t >= lenSq) { cx = endX; cz = endZ; }
            else { cx = originX + projectionOffset(dx, t, lenSq); cz = originZ + projectionOffset(dz, t, lenSq); }
            long ddx = x - cx, ddz = z - cz;
            return ddx * ddx + ddz * ddz <= (long) halfWidth * halfWidth;
        }

        private static long projectionOffset(long delta, long dot, long lengthSquared) {
            // 超长斜线的三次量级乘积会溢出；绝对值相除再还原符号，保持原取整方向。
            long magnitude = local.plaincraft.greatwall.geometry.FixedMath.mulDiv(Math.abs(delta), dot, lengthSquared);
            return delta < 0 ? -magnitude : magnitude;
        }
    }

    /**
     * Stage 2: exact piece boxes via in-memory {@code Structure.generate}.
     *
     * <p>Only chunks that pass {@code placement.isStructureChunk} reach {@code generate}; the
     * weighted structure selection mirrors vanilla {@code ChunkGenerator.createStructures}. This
     * never touches {@code ChunkMap}, {@code distanceManager}, or a real chunk future.
     */
    public static List<OwnedBox> resolveBoxes(ServerLevel level, List<Candidate> candidates) {
        return resolveBoxes(level, candidates, null);
    }

    /** Resolve with an optional per-candidate progress sink (done count). */
    public static List<OwnedBox> resolveBoxes(ServerLevel level, List<Candidate> candidates,
                                              java.util.function.IntConsumer progress) {
        if (level == null || candidates == null || candidates.isEmpty()) return List.of();
        // Hardware-scaled parallelism, mirroring RoadWeaver's StartupHardwareProfile.nativeWorkers:
        // each candidate's Structure.generate is read-only here (frozen registries, generator and
        // structure manager), and RoadWeaver runs the same call in a parallel native-structure pool,
        // so the corridor scan uses the same adaptive budget instead of a fixed small pool.
        int parallelism = adaptiveScanWorkers();
        if (parallelism <= 1 || candidates.size() < 64) {
            return resolveBoxesSerial(level, candidates, progress);
        }
        var done = new java.util.concurrent.atomic.AtomicInteger();
        var seedParts = java.util.Collections.synchronizedList(new ArrayList<List<OwnedBox>>());
        var parts = new ArrayList<java.util.concurrent.CompletableFuture<Void>>(parallelism);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(parallelism, task -> {
            Thread thread = new Thread(task, "pgw-corridor-resolve");
            thread.setDaemon(true);
            return thread;
        });
        try {
            // Dynamic work stealing, not static contiguous slices.
            //
            // The candidate list is grouped by structure set (predictCandidates walks
            // possibleStructureSets in registry order) and one Structure.generate spans orders of
            // magnitude in cost - a simple scatter versus a jigsaw village. Static slices therefore
            // hand one worker the cheap candidates and another all the expensive ones, and the tail
            // then runs on a single thread: a measured session finished the scan with the CPU at
            // ~2.5 cores of 8 while the last slice ground on alone. Batches stay small so the tail is
            // bounded by one batch instead of one slice.
            int batch = Math.max(1, Math.min(32, candidates.size() / (parallelism * 8) + 1));
            var cursor = new java.util.concurrent.atomic.AtomicInteger();
            for (int p = 0; p < parallelism; p++) {
                parts.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    List<OwnedBox> collected = new ArrayList<>();
                    while (true) {
                        int from = cursor.getAndAdd(batch);
                        if (from >= candidates.size()) break;
                        int to = Math.min(candidates.size(), from + batch);
                        collected.addAll(resolveBoxesSerial(level, candidates.subList(from, to), count -> {
                            int d = done.addAndGet(count);
                            if (progress != null) progress.accept(d);
                        }));
                    }
                    seedParts.add(collected);
                }, executor));
            }
            java.util.concurrent.CompletableFuture.allOf(parts.toArray(new java.util.concurrent.CompletableFuture[0])).join();
        } finally {
            executor.shutdownNow();
        }
        // Merge per-slice results with a global seen pass (each slice already deduped internally,
        // but the same structure box can straddle two slices' corridor edge).
        Set<String> seen = new HashSet<>();
        List<OwnedBox> merged = new ArrayList<>();
        for (var sliceResult : seedParts) {
            for (var box : sliceResult) {
                String key = box.structureId() + ":" + box.box().minX() + "," + box.box().minY() + ","
                        + box.box().minZ() + ":" + box.box().maxX() + "," + box.box().maxY() + ","
                        + box.box().maxZ();
                if (seen.add(key)) merged.add(box);
            }
        }
        // Deterministic order. seedParts is appended in completion order, so the merged list would
        // otherwise depend on thread scheduling: the same world could hand downstream planning a
        // differently ordered obstacle list on every run. Sort by identity, then geometry.
        merged.sort(java.util.Comparator.<OwnedBox, String>comparing(OwnedBox::structureId)
                .thenComparingInt(box -> box.box().minX())
                .thenComparingInt(box -> box.box().minY())
                .thenComparingInt(box -> box.box().minZ())
                .thenComparingInt(box -> box.box().maxX())
                .thenComparingInt(box -> box.box().maxY())
                .thenComparingInt(box -> box.box().maxZ()));
        return List.copyOf(merged);
    }

    /**
     * Hardware-scaled scan budget, mirroring RoadWeaver's {@code StartupHardwareProfile.resolve}:
     * reserve cores on big machines, cap by heap so a small JVM cannot over-subscribe, and stay
     * aggressive for startup prewarm while bounded so a huge world cannot queue unbounded work.
     *
     * <p>The heap tiers are deliberately generous at 16 GiB and up: a scan worker holds chunk leases
     * and parses structure references in short-lived allocations, so the scan's own footprint is far
     * smaller than the walk phases that run around it. Measured on the 8-core/16-thread pack machine
     * with a 16 GiB heap, the 8-worker cap left the scan at 148-186 s while the CPU had room.
     */
    static int adaptiveScanWorkers() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        long heapGiB = Math.max(1L, Math.max(512L, Runtime.getRuntime().maxMemory() / (1024L * 1024L)) / 1024L);
        int cpuBudget = processors >= 12 ? Math.max(2, processors - 2) : Math.max(2, processors / 3);
        int memoryBudget = heapGiB <= 4L ? 2
                : heapGiB <= 8L ? 4
                : heapGiB <= 16L ? 12
                : heapGiB <= 24L ? 14
                : 16;
        return Math.max(2, Math.min(16, Math.min(cpuBudget, memoryBudget)));
    }

    /**
     * Scan yield counters, summed over every slice: candidates that produced at least one piece, candidates
     * that produced none, and the piece total. The scanner logs them next to its candidate/box counts, which
     * is what makes "how much of the 128 s is wasted on candidates that die inside structure.generate" a
     * measurement instead of a guess.
     */
    private static final java.util.concurrent.atomic.LongAdder STARTS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder EMPTY_CANDIDATES = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder PIECES = new java.util.concurrent.atomic.LongAdder();
    /**
     * DRY_RUN counters for a dimension-level biome filter: candidates tested, and how many of them hold no
     * structure whose biome set intersects the biome source's possible biomes.
     *
     * <p>Such a candidate cannot produce a piece anywhere in this dimension, so `structure.generate` is pure
     * waste on it. The filter is exact - it uses the same set the generator samples from - but enabling it
     * changes the scan's cost model on every world, so the count is measured first: a filter that removes
     * almost nothing is not worth a new failure mode that can silently drop a structure.
     */
    private static final java.util.concurrent.atomic.LongAdder FILTER_TESTED = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder FILTER_WOULD_SKIP = new java.util.concurrent.atomic.LongAdder();

    /** {@return {starts, emptyCandidates, pieces} accumulated since the process started} */
    public static long[] scanYield() {
        return new long[] {STARTS.sum(), EMPTY_CANDIDATES.sum(), PIECES.sum()};
    }

    /** {@return {filterTested, filterWouldSkip} for the dimension-level biome filter dry run} */
    public static long[] filterYield() {
        return new long[] {FILTER_TESTED.sum(), FILTER_WOULD_SKIP.sum()};
    }
    private static List<OwnedBox> resolveBoxesSerial(ServerLevel level, List<Candidate> candidates,
                                                      java.util.function.IntConsumer sliceProgress) {
        if (level == null || candidates == null || candidates.isEmpty()) return List.of();
        var generatorState = level.getChunkSource().getGeneratorState();
        var generator = level.getChunkSource().getGenerator();
        var structureManager = level.getStructureManager();
        long seed = generatorState.getLevelSeed();
        List<OwnedBox> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int processed = 0;
        for (Candidate candidate : candidates) {
            processed++;
            int boxesBefore = result.size();
            if (sliceProgress != null && (processed % 8) == 0) sliceProgress.accept(8);
            Holder<StructureSet> setHolder = candidate.structureSet();
            StructureSet structureSet = setHolder.value();
            // 候选已用原始区块通过放置规则，这里沿用同一区块；再查一次只会重复排斥区计算。
            ChunkPos pos = candidate.startChunk();
            List<StructureSet.StructureSelectionEntry> entries = structureSet.structures();
            if (entries.isEmpty()) continue;
            // DRY_RUN only: count the candidates a dimension-level biome filter would never generate.
            // Nothing is skipped here - the count is what decides whether the filter is worth enabling.
            {
                boolean possible = false;
                for (var entry : entries) {
                    for (var holder : entry.structure().value().biomes()) {
                        if (generator.getBiomeSource().possibleBiomes().contains(holder)) { possible = true; break; }
                    }
                    if (possible) break;
                }
                FILTER_TESTED.increment();
                if (!possible) FILTER_WOULD_SKIP.increment();
            }
            ArrayList<StructureSet.StructureSelectionEntry> remaining =
                    entries.size() == 1 ? null : new ArrayList<>(entries);
            var random = STRUCTURE_SELECTION_RANDOM.get();
            random.setLargeFeatureSeed(seed, pos.x, pos.z);
            while (remaining == null || !remaining.isEmpty()) {
                List<StructureSet.StructureSelectionEntry> candidates1 =
                        remaining == null ? entries : remaining;
                int totalWeight = 0;
                for (StructureSet.StructureSelectionEntry entry : candidates1) {
                    totalWeight += entry.weight();
                }
                int selected = random.nextInt(totalWeight);
                int selectedIndex = 0;
                for (int i = 0; i < candidates1.size(); i++) {
                    selected -= candidates1.get(i).weight();
                    if (selected < 0) {
                        selectedIndex = i;
                        break;
                    }
                }
                StructureSet.StructureSelectionEntry entry = candidates1.get(selectedIndex);
                Structure structure = entry.structure().value();
                StructureStart start = structure.generate(
                        level.registryAccess(),
                        generator,
                        generator.getBiomeSource(),
                        generatorState.randomState(),
                        structureManager,
                        seed,
                        pos,
                        0,
                        level,
                        structure.biomes()::contains);
                if (start != null && start.isValid()) {
                    String id = structureId(level, structure);
                    STARTS.increment();
                    for (var piece : start.getPieces()) {
                        var box = piece.getBoundingBox();
                        PIECES.increment();
                        String key = id + ":" + box.minX() + "," + box.minY() + "," + box.minZ()
                                + ":" + box.maxX() + "," + box.maxY() + "," + box.maxZ();
                        if (seen.add(key)) result.add(new OwnedBox(id, pos.toLong(), box));
                    }
                    break;
                }
                if (remaining == null) break;
                remaining.remove(selectedIndex);
            }
            // Every path that got here without adding a box died inside structure.generate: the placement
            // test, the biome filter, the height check, or a valid start with no pieces. Counting those tells
            // a pre-filter's expected yield - the one number that decides whether the 128 s scan can be cut
            // down - and it costs nothing.
            if (result.size() == boxesBefore) EMPTY_CANDIDATES.increment();
        }
        // 分片末尾的余数上报（前面的按 8 批量已上报，这里补最后一段）
        int remainder = processed % 8;
        if (sliceProgress != null && remainder > 0) sliceProgress.accept(remainder);
        return List.copyOf(result);
    }

    private static void addCandidate(ChunkGeneratorStructureState state, Holder<StructureSet> setHolder,
            StructurePlacement placement, ChunkPos candidate, int minChunkX, int minChunkZ,
            int maxChunkX, int maxChunkZ, Corridor corridor, List<Candidate> result) {
        if (candidate.x < minChunkX || candidate.x > maxChunkX
                || candidate.z < minChunkZ || candidate.z > maxChunkZ) return;
        // 走廊外无需调用原版放置与排斥区判定；模组自定义偏移仍由它自己的定位规则解释。
        if (corridor != null) {
            var locatePos = placement.getLocatePos(candidate);
            if (!corridor.within(locatePos.getX(), locatePos.getZ())) return;
        }
        if (!placement.isStructureChunk(state, candidate.x, candidate.z)) return;
        result.add(new Candidate(candidate, setHolder));
    }

    private static boolean isSurfaceRelevant(Holder<StructureSet> structureSet) {
        if (structureSet == null) return false;
        String id = structureSet.unwrapKey().map(key -> key.location().toString()).orElse("")
                .toLowerCase(Locale.ROOT);
        // Blacklist underground and underwater structures: they never touch the wall's surface
        // ridge, so generating their boxes wastes scan time and can wrongly deflect the ridge.
        return !id.contains("monster_room") && !id.contains("nether_fossil")
                && !id.contains("mineshaft") && !id.contains("ancient_city")
                && !id.contains("stronghold") && !id.contains("trial_chambers")
                && !id.contains("buried_treasure") && !id.contains("ocean_ruin")
                && !id.contains("shipwreck") && !id.contains("monument");
    }

    private static String structureId(ServerLevel level, Structure structure) {
        var registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var key = registry.getKey(structure);
        return key == null ? "unknown" : key.toString();
    }

    private static final ThreadLocal<net.minecraft.world.level.levelgen.WorldgenRandom> STRUCTURE_SELECTION_RANDOM =
            ThreadLocal.withInitial(() -> new net.minecraft.world.level.levelgen.WorldgenRandom(
                    new net.minecraft.world.level.levelgen.LegacyRandomSource(0L)));
}
