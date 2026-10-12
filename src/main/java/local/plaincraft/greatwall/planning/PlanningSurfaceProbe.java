package local.plaincraft.greatwall.planning;

import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.model.WallTypes.ColumnSample;
import local.plaincraft.greatwall.model.WallTypes.DimensionBounds;
import local.plaincraft.greatwall.model.WallTypes.ProbeKind;
import local.plaincraft.greatwall.model.WallTypes.ProbeResult;

/**
 * Planning-time surface probe: derives the same {@link ProbeResult} the generation stage consumes,
 * but from the planning-resolved column sample (height + water) plus a structure obstacle index —
 * never from a per-block T0 snapshot. This is the root-cause replacement for the per-block
 * {@code SurfaceProbe.probe(ColumnView, ...)}: the wall no longer needs per-block terrain evidence,
 * only the accurate ground/water surface and the already-resolved structure boxes.
 *
 * <p>Semantics, by design:
 * <ul>
 *   <li>groundTopY = the sample's ground surface (the wall base clamps to it).</li>
 *   <li>waterTopY/bedTopY pass straight through; a water column is {@link ProbeKind#WATER}.</li>
 *   <li>protected = the column's (x,z) falls inside a planning structure box at the wall's height.</li>
 *   <li>drop = wallBaseY - groundTopY; no per-block support walk, so no NO_SUPPORT/UPPER_CENSORED
 *       derived from per-block evidence — a column is buildable whenever it has a ground surface.</li>
 * </ul>
 * The wall base is chosen from the full-width cross-section (see {@link WallFoundationPlanner}), so
 * a column below the base is filled down to its own surface rather than rejected.
 */
public final class PlanningSurfaceProbe {
    private PlanningSurfaceProbe() {
    }

    public static ProbeResult probe(ColumnSample sample, int walkY, int baseY,
            boolean protectedAtColumn, DimensionBounds bounds) {
        int ground = sample.groundTopY();
        int drop = Math.max(0, baseY - ground);
        ProbeKind kind;
        if (protectedAtColumn) kind = ProbeKind.PROTECTED;
        else if (sample.waterTopY().isPresent()) kind = ProbeKind.WATER;
        else kind = ProbeKind.SUPPORTED;
        OptionalInt water = sample.waterTopY().isPresent()
                ? OptionalInt.of(sample.waterTopY().getAsInt()) : OptionalInt.empty();
        OptionalInt bed = sample.bedTopY().isPresent()
                ? OptionalInt.of(sample.bedTopY().getAsInt()) : OptionalInt.empty();
        String evidence = DeterministicHash.sha256((ground + ":" + water + ":" + bed + ":" + protectedAtColumn
                + ":" + sample.biomeKey()).getBytes(StandardCharsets.UTF_8));
        return new ProbeResult(kind, ground, water, bed, drop, evidence, OptionalInt.empty());
    }
}
