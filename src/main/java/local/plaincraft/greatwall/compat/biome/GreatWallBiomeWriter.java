package local.plaincraft.greatwall.compat.biome;

import java.util.Objects;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * Paints the Great Wall biome into the owner chunk's own biome array.
 *
 * <h2>Why the owner chunk only</h2>
 * The native FEATURES step is the first place the biome array is authoritative for decoration, and
 * the writing chunk is the only one this thread owns. Neighbour chunks are never touched, no chunk
 * is loaded, no sampler is re-run and nothing is awaited. Biome arrays are a per-chunk field, so a
 * single-chunk write is the whole contract.
 *
 * <h2>Why the public fill method</h2>
 * {@code LevelChunkSection} exposes no biome setter. {@link LevelChunkSection#fillBiomesFromNoise}
 * is the supported entry point and replaces the section's container wholesale, which is exactly
 * what the reader in {@code applyBiomeDecoration} observes. No reflection, no access widener and no
 * field-accessor mixin is involved.
 *
 * <h2>thinY banding</h2>
 * Painting every Y would also rewrite cave and deep-underground identity. Instead the band is
 * derived from the already-primed worldgen surface heightmap: {@code [H - 8, H + 32)}. Columns with
 * no surface height keep their original value, so oceans and unloaded sections are untouched.
 */
public final class GreatWallBiomeWriter {
    /** Blocks below the surface height that stay painted. */
    public static final int BAND_BELOW = 8;
    /** Blocks above the surface height that stay painted. */
    public static final int BAND_ABOVE = 32;

    private GreatWallBiomeWriter() {}

    /**
     * Applies the corridor biome to one owner chunk.
     *
     * @return true when at least one section was rewritten
     */
    public static boolean paint(ChunkAccess chunk, Climate.Sampler sampler, RouteBiomeMask mask,
                                Holder<Biome> identity) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(sampler, "sampler");
        Objects.requireNonNull(mask, "mask");
        Objects.requireNonNull(identity, "identity");

        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();

        // One target per owner-local quart column (4x4 grid), plus its painted Y band in quart space.
        var columns = new Column[16];
        boolean any = false;
        for (int localQuartZ = 0; localQuartZ < 4; localQuartZ++) {
            for (int localQuartX = 0; localQuartX < 4; localQuartX++) {
                int blockX = minX + localQuartX * 4;
                int blockZ = minZ + localQuartZ * 4;
                int index = localQuartX + localQuartZ * 4;
                if (!mask.paintQuart(blockX, blockZ)) { columns[index] = null; continue; }
                int surface = surfaceHeight(chunk, localQuartX, localQuartZ);
                if (surface == Integer.MIN_VALUE) { columns[index] = null; continue; }
                int lo = Math.max(minY, surface - BAND_BELOW);
                int hi = Math.min(maxY, surface + BAND_ABOVE);
                if (hi <= lo) { columns[index] = null; continue; }
                // Quart cells are inclusive at the low end; hi is exclusive, so convert hi - 1.
                columns[index] = new Column(QuartPos.fromBlock(lo), QuartPos.fromBlock(hi - 1) + 1);
                any = true;
            }
        }
        if (!any) return false;

        int quartX = QuartPos.fromBlock(minX);
        int quartZ = QuartPos.fromBlock(minZ);
        var sections = chunk.getSections();
        boolean changed = false;
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            var section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) continue;
            int quartY = QuartPos.fromSection(chunk.getMinSection() + sectionIndex);
            if (!touchesBand(columns, quartY)) continue;
            PalettedContainerRO<Holder<Biome>> original = section.getBiomes();
            int baseQuartX = quartX;
            int baseQuartY = quartY;
            int baseQuartZ = quartZ;
            Column[] frozen = columns;
            section.fillBiomesFromNoise((sampleQuartX, sampleQuartY, sampleQuartZ, ignored) -> {
                var column = frozen[QuartPos.quartLocal(sampleQuartX)
                        + QuartPos.quartLocal(sampleQuartZ) * 4];
                int localY = QuartPos.quartLocal(sampleQuartY);
                if (column != null && column.minQuartY() <= localY && localY < column.maxQuartYExclusive()) {
                    return identity;
                }
                return original.get(QuartPos.quartLocal(sampleQuartX), localY,
                        QuartPos.quartLocal(sampleQuartZ));
            }, sampler, baseQuartX, baseQuartY, baseQuartZ);
            changed = true;
        }
        if (changed) chunk.setUnsaved(true);
        return changed;
    }

    private static boolean touchesBand(Column[] columns, int quartY) {
        for (var column : columns) {
            if (column == null) continue;
            if (column.minQuartY() < quartY + 4 && column.maxQuartYExclusive() > quartY) return true;
        }
        return false;
    }

    /**
     * The highest taken block in a heightmap column.
     *
     * <p>Only an <em>already present</em> heightmap is read. {@code getOrCreateHeightmapUnprimed}
     * would silently install an empty map whose entries read back as {@code minBuildHeight - 1},
     * turning a missing prerequisite into a bogus band; the writer must instead refuse to paint.
     * Priming a new heightmap here would also add hidden work during FEATURES.
     *
     * @return the surface Y, or {@link #NO_SURFACE} when the prerequisite is absent or empty
     */
    private static int surfaceHeight(ChunkAccess chunk, int localQuartX, int localQuartZ) {
        if (!chunk.hasPrimedHeightmap(SURFACE_HEIGHTMAP)) return NO_SURFACE;
        var heightmap = chunk.getOrCreateHeightmapUnprimed(SURFACE_HEIGHTMAP);
        // Sample the centre block of the quart column.
        int localX = Math.min(15, localQuartX * 4 + 2);
        int localZ = Math.min(15, localQuartZ * 4 + 2);
        int highest = heightmap.getHighestTaken(localX, localZ);
        // An unset entry reads back as minBuildHeight - 1; treat that as "no surface".
        return highest < chunk.getMinBuildHeight() ? NO_SURFACE : highest;
    }

    /** Sentinel for "no usable surface height". */
    private static final int NO_SURFACE = Integer.MIN_VALUE;
    /**
     * The heightmap read for the paint band.
     *
     * <p>{@code ChunkStatusTasks.generateFeatures} calls
     * {@code Heightmap.primeHeightmaps(chunk, EnumSet.of(MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES,
     * OCEAN_FLOOR, WORLD_SURFACE))} unconditionally a few bytecodes before it invokes
     * {@code ChunkGenerator.applyBiomeDecoration} — which is exactly our injection point. So
     * {@code WORLD_SURFACE} is guaranteed present and reflects the post-carver terrain. The
     * {@code _WG} variants are primed during other statuses and are deliberately not relied upon.
     */
    private static final net.minecraft.world.level.levelgen.Heightmap.Types SURFACE_HEIGHTMAP =
            net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE;

    /** Painted quart-Y band for one owner-local column. */
    private record Column(int minQuartY, int maxQuartYExclusive) {
        Column {
            if (maxQuartYExclusive <= minQuartY) {
                throw new WallFailure(FailureCode.INVALID_RESOURCE, "empty biome paint band");
            }
        }
    }
}
