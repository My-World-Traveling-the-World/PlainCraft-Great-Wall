package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.BlockPos;
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
    private CorridorStructureScan() {
    }

    /** A predicted locate point plus the structure set it came from. */
    public record Candidate(BlockPos pos, Holder<StructureSet> structureSet) {
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
                            maxChunkX, maxChunkZ, result);
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
                                maxChunkX, maxChunkZ, result);
                    }
                }
                continue;
            }
            // Custom placements expose no region formula; the native predicate is authoritative.
            for (int x = minChunkX; x <= maxChunkX; x++) {
                for (int z = minChunkZ; z <= maxChunkZ; z++) {
                    addCandidate(state, holder, placement, new ChunkPos(x, z), minChunkX, minChunkZ,
                            maxChunkX, maxChunkZ, result);
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Stage 2: exact piece boxes via in-memory {@code Structure.generate}.
     *
     * <p>Only chunks that pass {@code placement.isStructureChunk} reach {@code generate}; the
     * weighted structure selection mirrors vanilla {@code ChunkGenerator.createStructures}. This
     * never touches {@code ChunkMap}, {@code distanceManager}, or a real chunk future.
     */
    public static List<OwnedBox> resolveBoxes(ServerLevel level, List<Candidate> candidates) {
        if (level == null || candidates == null || candidates.isEmpty()) return List.of();
        var generatorState = level.getChunkSource().getGeneratorState();
        var generator = level.getChunkSource().getGenerator();
        var structureManager = level.getStructureManager();
        long seed = generatorState.getLevelSeed();
        List<OwnedBox> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Candidate candidate : candidates) {
            Holder<StructureSet> setHolder = candidate.structureSet();
            StructureSet structureSet = setHolder.value();
            ChunkPos pos = new ChunkPos(candidate.pos());
            if (!structureSet.placement().isStructureChunk(generatorState, pos.x, pos.z)) continue;
            List<StructureSet.StructureSelectionEntry> entries = structureSet.structures();
            if (entries.isEmpty()) continue;
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
                    for (var piece : start.getPieces()) {
                        var box = piece.getBoundingBox();
                        String key = id + ":" + box.minX() + "," + box.minY() + "," + box.minZ()
                                + ":" + box.maxX() + "," + box.maxY() + "," + box.maxZ();
                        if (seen.add(key)) result.add(new OwnedBox(id, pos.toLong(), box));
                    }
                    break;
                }
                if (remaining == null) break;
                remaining.remove(selectedIndex);
            }
        }
        return List.copyOf(result);
    }

    private static void addCandidate(ChunkGeneratorStructureState state, Holder<StructureSet> setHolder,
            StructurePlacement placement, ChunkPos candidate, int minChunkX, int minChunkZ,
            int maxChunkX, int maxChunkZ, List<Candidate> result) {
        if (candidate.x < minChunkX || candidate.x > maxChunkX
                || candidate.z < minChunkZ || candidate.z > maxChunkZ) return;
        if (!placement.isStructureChunk(state, candidate.x, candidate.z)) return;
        result.add(new Candidate(placement.getLocatePos(candidate), setHolder));
    }

    private static boolean isSurfaceRelevant(Holder<StructureSet> structureSet) {
        if (structureSet == null) return false;
        String id = structureSet.unwrapKey().map(key -> key.location().toString()).orElse("")
                .toLowerCase(Locale.ROOT);
        return !id.contains("monster_room") && !id.contains("nether_fossil");
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
