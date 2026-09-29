package local.plaincraft.greatwall.generation;

import java.util.Objects;
import java.util.OptionalInt;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Reads only captured T0 evidence. Missing pages are errors, never guessed air. */
public final class SurfaceProbe {
    public interface ColumnView {
        String state(int y);
        boolean solidSupport(int y);
        boolean protectedAt(int y);
        boolean water(int y);
        boolean airOrReplaceablePlant(int y);
        int highestNonPlantFeet();
        String hash();
        default boolean naturalThinIce(int y) { return false; }
    }

    public SurfaceProbe() {}

    public ProbeResult probe(ColumnView column, int walkY, DimensionBounds bounds) {
        Objects.requireNonNull(column); Objects.requireNonNull(bounds);
        int g = Math.subtractExact(walkY, 20);
        int lo = Math.max(Math.subtractExact(walkY, 32), Math.addExact(bounds.minY(), 3));
        int hi = Math.min(Math.addExact(walkY, 6), bounds.maxYExclusive() - 1);
        String hash = column.hash();
        if (lo > hi || walkY >= bounds.maxYExclusive() || walkY < bounds.minY())
            return result(ProbeKind.UPPER_CENSORED, g, OptionalInt.empty(), 0, hash);
        if (column.highestNonPlantFeet() > hi || hi + 1 >= bounds.maxYExclusive()
                || !column.airOrReplaceablePlant(hi + 1))
            return result(ProbeKind.UPPER_CENSORED, g, OptionalInt.empty(), 0, hash);
        // Inspect the full potential write span before choosing a lower support surface.
        for (int y = hi; y >= lo - 3; y--)
            if (column.protectedAt(y)) return result(ProbeKind.PROTECTED, g, OptionalInt.empty(), 0, hash);
        OptionalInt water = OptionalInt.empty();
        OptionalInt ice = OptionalInt.empty();
        boolean exposed = true;
        for (int h = hi; h >= lo; h--) {
            if (column.water(h) || column.naturalThinIce(h)) {
                if(exposed && water.isEmpty() && column.naturalThinIce(h))ice=OptionalInt.of(h);
                if (exposed && water.isEmpty()) water = OptionalInt.of(Math.addExact(h, 1));
            } else if (!column.airOrReplaceablePlant(h)) {
                exposed = false;
            }
            if (column.solidSupport(h - 1) && column.solidSupport(h - 2) && column.solidSupport(h - 3)) {
                int drop = Math.max(0, Math.subtractExact(g, h));
                if (h >= walkY) return result(ProbeKind.UPPER_CENSORED, h, water, drop, hash);
                if (drop > 12) return result(ProbeKind.NO_SUPPORT, h, water, drop, hash);
                return new ProbeResult(water.isPresent() ? ProbeKind.WATER : ProbeKind.SUPPORTED,h,water,
                        water.isPresent()?OptionalInt.of(h):OptionalInt.empty(),drop,hash,ice);
            }
        }
        return result(ProbeKind.NO_SUPPORT, lo, water, Math.max(0, g - lo), hash);
    }

    private static ProbeResult result(ProbeKind kind, int ground, OptionalInt water, int drop, String hash) {
        return new ProbeResult(kind, ground, water, water.isPresent() ? OptionalInt.of(ground) : OptionalInt.empty(), drop, hash);
    }
}
