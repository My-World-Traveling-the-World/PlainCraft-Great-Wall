package local.plaincraft.greatwall.gametest;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import local.plaincraft.greatwall.compat.biome.GreatWallBiomeWriter;
import local.plaincraft.greatwall.compat.biome.RouteBiomeMask;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Executes {@link GreatWallBiomeWriter#paint}, which nothing else does.
 *
 * <h2>Why these tests exist</h2>
 * The two long-running verifications (the 505-owner real-terrain traversal and the 24h stress
 * profile) both run with {@code production=OFF} and without Lithostitched on the classpath, so
 * {@code ChunkGeneratorBiomeMixin} returns before reaching the writer in either of them. The smoke
 * run dir loads only fabric-api, this mod and the smoke harness, and the server log confirms
 * "Applied 0 biome modifications to 0 of 64 new biomes". The painter therefore had no execution
 * evidence at all.
 *
 * <h2>Scope: what this does and does not prove</h2>
 * These tests cover the writer's own contract on a real chunk with a real registry biome holder:
 * the {@code [H - 8, H + 32)} band, refusal when no surface is usable, and that mask-excluded
 * columns are left alone. They do <b>not</b> prove that Lithostitched registers the identity, nor
 * that the mixin reaches the writer in a real client. That still requires the user's instance with
 * the biome data pack enabled.
 *
 * <h2>Fixture constraints discovered while writing these</h2>
 * <ul>
 *   <li>{@code Station} carries arc length ({@code globalSQ8}, must be non-negative) separately from
 *       its world position ({@code point}, may be negative). Using world X for both throws
 *       "station globalSQ8&gt;=0 required".</li>
 *   <li>The GameTest level's chunk position differs between runs (observed [94509, -531712] and
 *       [-32390, -139948]), so no fixture may be anchored at the origin.</li>
 *   <li>A new {@code ProtoChunk} already reports {@code WORLD_SURFACE} as primed; there is no public
 *       setter to un-prime it, so the "no usable surface" case is produced with an empty chunk
 *       instead of by removing the heightmap.</li>
 *   <li>The corridor's coverage radius is wider than a chunk, so a ribbon aimed at the chunk covers
 *       all 16 of its columns. The partial-coverage test calibrates its offset by probing the mask
 *       rather than assuming a margin.</li>
 * </ul>
 */
public final class BiomeWriterGameTests implements FabricGameTest {
    private static final String H = "a".repeat(64);

    /**
     * The band is half-open and surface-relative: inside it the identity is painted, below it the
     * original biome survives. Painting every Y would also rewrite cave and deep-underground identity.
     */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void paintRespectsTheSurfaceRelativeBand(GameTestHelper helper) {
        var chunk = chunk(helper, 1, true);
        int surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).getHighestTaken(8, 8);
        var identity = identity(helper);
        var mask = mask(helper, chunk, 1, 32);

        int covered = countCoveredColumns(mask, chunk);
        helper.assertTrue(covered > 0, "the fixture mask must cover part of the chunk (covered=" + covered + ")");

        int quartX = QuartPos.fromBlock(chunk.getPos().getMinBlockX() + 8);
        int quartZ = QuartPos.fromBlock(chunk.getPos().getMinBlockZ() + 8);
        Map<Integer, Holder<Biome>> before = readColumn(chunk, quartX, quartZ);

        boolean painted = GreatWallBiomeWriter.paint(chunk, sampler(helper), mask, identity);
        helper.assertTrue(painted, "a chunk with a covered column must report a change");
        // 补建可能再次确认同一计划；没有新写入就不触发客户端区块群系重发。
        helper.assertFalse(GreatWallBiomeWriter.paint(chunk, sampler(helper), mask, identity),
                "repainting the same shape must leave the existing biome data alone");

        int loQuart = QuartPos.fromBlock(surface - GreatWallBiomeWriter.BAND_BELOW);
        int hiQuartExclusive = QuartPos.fromBlock(surface + GreatWallBiomeWriter.BAND_ABOVE - 1) + 1;
        int minQuart = QuartPos.fromBlock(chunk.getMinBuildHeight());
        int maxQuartExclusive = QuartPos.fromBlock(chunk.getMaxBuildHeight() - 1) + 1;
        int inside = 0;
        int below = 0;
        for (int quartY = minQuart; quartY < maxQuartExclusive; quartY++) {
            Holder<Biome> actual = readQuart(chunk, quartX, quartZ, quartY);
            if (quartY >= loQuart && quartY < hiQuartExclusive) {
                helper.assertTrue(actual.equals(identity),
                        "inside the band the identity must be painted at quartY=" + quartY
                                + " (surface=" + surface + ", band=[" + loQuart + "," + hiQuartExclusive + "))");
                inside++;
            } else if (quartY < loQuart) {
                helper.assertTrue(actual.equals(before.get(quartY)),
                        "below the band the original biome must survive at quartY=" + quartY
                                + " (was " + before.get(quartY) + ", now " + actual + ")");
                below++;
            }
        }
        helper.assertTrue(inside > 0, "the band must cover at least one quart cell");
        helper.assertTrue(below > 0, "cells below the band must exist for the survival check to mean anything");
        helper.succeed();
    }

    /**
     * A chunk whose surface heightmap has no usable entry must not be painted. An unset heightmap
     * entry reads back as {@code minBuildHeight - 1}, so treating it as a real surface would place the
     * band at the bottom of the world.
     */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void paintRefusesWhenNoSurfaceIsUsable(GameTestHelper helper) {
        // No blocks placed at all, so every heightmap entry is unset.
        var chunk = chunk(helper, 2, false);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        helper.assertTrue(chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE),
                "the heightmap must be primed, otherwise this tests a different branch");

        var mask = mask(helper, chunk, 2, 32);
        helper.assertTrue(countCoveredColumns(mask, chunk) > 0,
                "the mask must cover the chunk, so a refusal can only come from the heightmap guard");

        boolean painted = GreatWallBiomeWriter.paint(chunk, sampler(helper), mask, identity(helper));
        helper.assertFalse(painted, "a heightmap with no usable surface must not be painted over");
        helper.succeed();
    }

    /**
     * Columns the mask excludes keep the biome the sampler produced. The mask is the corridor
     * authority and the writer must never widen it.
     *
     * <p>The corridor's reach exceeds one chunk, so the offset that yields partial coverage is found
     * by probing the mask over a range of offsets rather than by assuming a margin. If no offset gives
     * partial coverage the test says so instead of asserting something vacuous.
     */
    @GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
    public void columnsOutsideTheMaskKeepTheirOriginalBiome(GameTestHelper helper) {
        var chunk = chunk(helper, 3, true);
        var identity = identity(helper);

        RouteBiomeMask mask = null;
        List<int[]> excluded = null;
        int scannedFrom = -400;
        int scannedTo = 400;
        int inside = 0;
        for (int offset = scannedFrom; offset <= scannedTo && mask == null; offset += 8) {
            var candidate = mask(helper, chunk, 3, offset);
            var gaps = new ArrayList<int[]>();
            int covered = 0;
            for (int qx = 0; qx < 4; qx++) {
                for (int qz = 0; qz < 4; qz++) {
                    int blockX = chunk.getPos().getMinBlockX() + qx * 4;
                    int blockZ = chunk.getPos().getMinBlockZ() + qz * 4;
                    if (candidate.paintQuart(blockX, blockZ)) covered++;
                    else gaps.add(new int[]{blockX, blockZ});
                }
            }
            if (covered > 0 && covered < 16) {
                mask = candidate;
                excluded = gaps;
                inside = covered;
            }
        }
        helper.assertTrue(mask != null, "no corridor offset in [" + scannedFrom + "," + scannedTo
                + "] produced partial coverage of the chunk; the partial-coverage assertion cannot be"
                + " made meaningful, so it is not asserted");
        helper.assertTrue(!excluded.isEmpty(), "partial coverage must leave excluded columns");
        helper.assertTrue(inside > 0, "partial coverage must also include covered columns");

        // Snapshot every excluded column BEFORE painting, so "unchanged" compares against the real
        // original. Comparing against the identity instead would be wrong: the chunk's own biome may
        // already be the identity, which is how the first version of this test mis-reported.
        var wasExcluded = new HashMap<Integer, Holder<Biome>>();
        for (int[] column : excluded) {
            int quartX = QuartPos.fromBlock(column[0]);
            int quartZ = QuartPos.fromBlock(column[1]);
            for (Map.Entry<Integer, Holder<Biome>> entry : readColumn(chunk, quartX, quartZ).entrySet()) {
                wasExcluded.put(columnKey(quartX, quartZ, entry.getKey()), entry.getValue());
            }
        }

        boolean painted = GreatWallBiomeWriter.paint(chunk, sampler(helper), mask, identity);
        // Without this the test could pass vacuously: if the painter declined, every column would be
        // unchanged and the "excluded columns are unchanged" assertion below would hold for the wrong
        // reason. That is the same trap this test already fell into once by asserting coverage in a way
        // that was true regardless of the mask.
        helper.assertTrue(painted, "the painter must actually have painted the covered columns; otherwise "
                + "the unchanged-column assertion below cannot distinguish correct behaviour from doing nothing");

        for (int[] column : excluded) {
            int quartX = QuartPos.fromBlock(column[0]);
            int quartZ = QuartPos.fromBlock(column[1]);
            for (int index = 0; index < chunk.getSections().length; index++) {
                LevelChunkSection section = chunk.getSections()[index];
                if (section == null) continue;
                int base = QuartPos.fromSection(chunk.getMinSection() + index);
                for (int local = 0; local < 4; local++) {
                    Holder<Biome> actual = section.getBiomes()
                            .get(QuartPos.quartLocal(quartX), local, QuartPos.quartLocal(quartZ));
                    Holder<Biome> original = wasExcluded.get(columnKey(quartX, quartZ, base + local));
                    helper.assertTrue(original != null,
                            "the pre-paint snapshot must cover quart (" + column[0] + "," + (base + local)
                                    + "," + column[1] + "), otherwise the comparison is vacuous");
                    helper.assertTrue(actual.equals(original),
                            "a column the mask excludes must keep its biome, but quart ("
                                    + column[0] + "," + (base + local) + "," + column[1] + ") changed from "
                                    + original + " to " + actual);
                }
            }
        }
        helper.succeed();
    }

    /** Packs a quart coordinate into one key for the before/after map. */
    private static int columnKey(int quartX, int quartZ, int quartY) {
        return (quartX * 31 + quartZ) * 31 + quartY;
    }

    // -------------------------------------------------------------------------------------------

    private static int countCoveredColumns(RouteBiomeMask mask, ProtoChunk chunk) {
        int covered = 0;
        for (int qx = 0; qx < 4; qx++) {
            for (int qz = 0; qz < 4; qz++) {
                if (mask.paintQuart(chunk.getPos().getMinBlockX() + qx * 4,
                        chunk.getPos().getMinBlockZ() + qz * 4)) covered++;
            }
        }
        return covered;
    }

    /**
     * Builds a real chunk at the level's own chunk position. {@code withSurface} places a stone layer
     * and primes WORLD_SURFACE, matching what {@code ChunkStatusTasks.generateFeatures} provides
     * before {@code applyBiomeDecoration} in production.
     */
    private static ProtoChunk chunk(GameTestHelper helper, long seed, boolean withSurface) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 0, 0));
        var pos = new ChunkPos(origin.getX() >> 4, origin.getZ() >> 4);
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, biomes, null);
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        // 默认调色板可能已是 plains，显式换成沙漠才能证明测试真的改写了群系。
        var original = biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.DESERT);
        chunk.fillBiomesFromNoise((x, y, z, noise) -> original, sampler(helper));
        if (!withSurface) return chunk;

        int surfaceY = level.getMinBuildHeight() + 96;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunk.setBlockState(new BlockPos(pos.getMinBlockX() + x, surfaceY - 1, pos.getMinBlockZ() + z),
                        Blocks.STONE.defaultBlockState(), false);
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        return chunk;
    }

    private static RouteBiomeMask mask(GameTestHelper helper, ProtoChunk chunk, long seed, int corridorEndOffset) {
        var pos = chunk.getPos();
        return RouteBiomeMask.compile(
                new RouteBiomeMask.MaskKey(UUID.nameUUIDFromBytes(new byte[]{(byte) seed}), "minecraft:overworld",
                        H, 1L, pos.x, pos.z),
                plans(pos, corridorEndOffset), 4, pos.getMinBlockX(), pos.getMinBlockZ());
    }

    /**
     * A corridor along X ending {@code corridorEndOffset} blocks east of the chunk's west edge, plus a
     * plan owner, so the mask has something to paint. Arc length and world position are kept separate:
     * the former runs 0,1,2,... and the latter follows the chunk.
     */
    private static PlanWindow plans(ChunkPos pos, int corridorEndOffset) {
        int minBlockX = pos.getMinBlockX();
        int centreZ = pos.getMinBlockZ() + 8;
        int eastEnd = minBlockX + corridorEndOffset;
        int westEnd = eastEnd - 64;
        long stepQ8 = 256L;
        int count = 64;
        var stations = new ArrayList<Station>();
        for (int i = 0; i <= count; i++) {
            stations.add(new Station((long) i * stepQ8,
                    new Point2q(((long) westEnd + i) * 256L, (long) centreZ * 256L), 84, 0));
        }
        long endArc = count * stepQ8;
        var start = new Boundary(0, new Point2q((long) westEnd * 256L, (long) centreZ * 256L), 84, H);
        var end = new Boundary(1, new Point2q((long) eastEnd * 256L, (long) centreZ * 256L), 84, H);
        var geometry = new SegmentGeometry(new SegmentKey(H, "minecraft:overworld", 0),
                List.of(start.point(), end.point()),
                List.of(0L, endArc), List.of(),
                Optional.of(new Terminal(0, endArc, TerminalKind.WORLD_LIMIT)), start, end, 0, 0, H);
        return new PlanWindow(List.of(new SegmentPlan(geometry,
                new PrefixSummary(0, 0, Optional.empty(), H),
                new StationTable(stations, List.of(), H), List.of(), H)));
    }

    private static Holder<Biome> identity(GameTestHelper helper) {
        return helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME)
                .getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
    }

    private static net.minecraft.world.level.biome.Climate.Sampler sampler(GameTestHelper helper) {
        return helper.getLevel().getChunkSource().randomState().sampler();
    }

    /**
     * Reads one quart cell by absolute quart Y. {@code QuartPos.fromSection} is absolute (negative on
     * a world whose minimum section is below zero) while {@code PalettedContainerRO.get} wants the 0..3
     * local coordinate; mixing the two is what produced "Index -16 out of bounds for length 96" on the
     * first run of this test.
     */
    private static Holder<Biome> readQuart(ProtoChunk chunk, int quartX, int quartZ, int quartY) {
        for (int index = 0; index < chunk.getSections().length; index++) {
            LevelChunkSection section = chunk.getSections()[index];
            if (section == null) continue;
            int local = quartY - QuartPos.fromSection(chunk.getMinSection() + index);
            if (local < 0 || local > 3) continue;
            return section.getBiomes().get(QuartPos.quartLocal(quartX), local, QuartPos.quartLocal(quartZ));
        }
        throw new IllegalStateException("no section covers quartY=" + quartY);
    }

    /** The painted column's original biomes, keyed by absolute quart Y. */
    private static Map<Integer, Holder<Biome>> readColumn(ProtoChunk chunk, int quartX, int quartZ) {
        var out = new HashMap<Integer, Holder<Biome>>();
        for (int index = 0; index < chunk.getSections().length; index++) {
            LevelChunkSection section = chunk.getSections()[index];
            if (section == null) continue;
            int base = QuartPos.fromSection(chunk.getMinSection() + index);
            for (int local = 0; local < 4; local++) {
                out.put(base + local,
                        section.getBiomes().get(QuartPos.quartLocal(quartX), local, QuartPos.quartLocal(quartZ)));
            }
        }
        return out;
    }
}
