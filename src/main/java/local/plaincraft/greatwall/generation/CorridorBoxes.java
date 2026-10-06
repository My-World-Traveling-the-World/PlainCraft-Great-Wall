package local.plaincraft.greatwall.generation;

import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.component.EndCap;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes.*;

/**
 * The single source of truth for "which XZ region is hard-protected", shared by the existing
 * vegetation guard and the Great Wall biome corridor mask.
 *
 * <p>This is a pure function of a frozen {@link PlanWindow}: no world access, no sampling, no
 * threading state. It was extracted verbatim from {@code VegetationGuard.publish} so that the
 * legacy tree protection cannot shift by a single block; {@code CorridorBoxesTest} pins the
 * per-box equality.
 *
 * <p>Boxes carry a full-Y extent ({@code minY = Integer.MIN_VALUE},
 * {@code maxYExclusive = Integer.MAX_VALUE}); only the XZ half-open rectangle
 * {@code [minX, maxXExclusive) x [minZ, maxZExclusive)} is meaningful here.
 */
public final class CorridorBoxes {
    /** 10 half-wall + 32 vegetation margin + 2 raster rounding. */
    private static final int SEGMENT_MARGIN = 44;
    /** A realized exit can begin anywhere on the ribbon, including the 4-block retreat grid. */
    private static final int EXIT_MARGIN_BASE = 34;
    /** Site footprint margin. */
    private static final int SITE_MARGIN = 32;
    /** Mirrors the historical snapshot capacity guard. */
    private static final int MAX_BOXES = 8192;

    private CorridorBoxes() {}

    /**
     * The hard protection domain {@code H}: the XZ union of every box that already suppresses
     * vanilla tree placement. Ordinary ribbons use ±{@value #SEGMENT_MARGIN}, potential exits add
     * the rotated end-cap envelope, and every non-suppressed site is grown by
     * {@value #SITE_MARGIN}.
     */
    public static List<Bounds> hard(PlanWindow plans, String dimension) {
        var boxes = new ArrayList<Bounds>();
        var cap = EndCap.potentialLocalBounds();
        long tangent = Math.max(Math.abs((long) cap.minX()), Math.abs((long) cap.maxXExclusive()));
        long normal = Math.max(Math.abs((long) cap.minZ()), Math.abs((long) cap.maxZExclusive()));
        int exitMargin = Math.addExact(EXIT_MARGIN_BASE,
                Math.toIntExact((long) StrictMath.ceil(StrictMath.hypot(tangent, normal))));
        for (var plan : plans.plans()) {
            if (!plan.geometry().key().dimension().equals(dimension)) {
                throw new IllegalArgumentException("foreign vegetation plan dimension");
            }
            var stations = plan.stations().stations();
            for (int index = 1; index < stations.size(); index++) {
                var a = stations.get(index - 1).point();
                var b = stations.get(index).point();
                int minX = Math.toIntExact(Math.floorDiv(Math.min(a.xQ8(), b.xQ8()), 256));
                int maxX = Math.toIntExact(Math.floorDiv(Math.max(a.xQ8(), b.xQ8()), 256));
                int minZ = Math.toIntExact(Math.floorDiv(Math.min(a.zQ8(), b.zQ8()), 256));
                int maxZ = Math.toIntExact(Math.floorDiv(Math.max(a.zQ8(), b.zQ8()), 256));
                // 10 half-wall +32 vegetation margin +2 raster rounding; conservative segment rectangle.
                boxes.add(new Bounds(Math.subtractExact(minX, SEGMENT_MARGIN), Integer.MIN_VALUE,
                        Math.subtractExact(minZ, SEGMENT_MARGIN),
                        Math.addExact(maxX, SEGMENT_MARGIN + 1), Integer.MAX_VALUE,
                        Math.addExact(maxZ, SEGMENT_MARGIN + 1)));
                // A realized exit can start anywhere on this ribbon, including the 4-block retreat grid.
                // Rotated [-30,30)x[-15,15) fits a radius-34 disk; +2 raster insurance and +32 margin.
                boxes.add(new Bounds(Math.subtractExact(minX, exitMargin), Integer.MIN_VALUE,
                        Math.subtractExact(minZ, exitMargin),
                        Math.addExact(maxX, exitMargin + 1), Integer.MAX_VALUE,
                        Math.addExact(maxZ, exitMargin + 1)));
            }
            for (var site : plan.sites()) {
                if (site.state() == SiteState.SUPPRESSED) continue;
                var b = site.bounds();
                boxes.add(new Bounds(Math.subtractExact(b.minX(), SITE_MARGIN), Integer.MIN_VALUE,
                        Math.subtractExact(b.minZ(), SITE_MARGIN),
                        Math.addExact(b.maxXExclusive(), SITE_MARGIN), Integer.MAX_VALUE,
                        Math.addExact(b.maxZExclusive(), SITE_MARGIN)));
            }
        }
        if (boxes.size() > MAX_BOXES) {
            throw new WallFailure(FailureCode.CAPACITY, "vegetation snapshot box capacity");
        }
        return List.copyOf(boxes);
    }

    /** Disjoint-prefix ordinal for the closest box, purely to make ties deterministic. */
    public static int ordinal(List<Bounds> boxes, int x, int z) {
        long best = Long.MAX_VALUE;
        int bestIndex = -1;
        for (int index = 0; index < boxes.size(); index++) {
            long distance = distance(boxes.get(index), x, z);
            if (distance < best) { best = distance; bestIndex = index; }
        }
        return bestIndex;
    }

    /**
     * The L∞ (Chebyshev) discrete distance in blocks from {@code (x, z)} to the half-open rectangle,
     * collapsed over the whole box list. Zero means the point is inside the hard domain.
     *
     * <p>All arithmetic is promoted to {@code long} and never uses truncating division, so negative
     * coordinates behave identically to positive ones.
     */
    public static long distance(List<Bounds> boxes, int x, int z) {
        long best = Long.MAX_VALUE;
        for (var box : boxes) {
            long distance = distance(box, x, z);
            if (distance < best) best = distance;
        }
        return best;
    }

    public static long distance(Bounds box, int x, int z) {
        long dx = Math.max(Math.max((long) box.minX() - x, 0L), (long) x - (box.maxXExclusive() - 1L));
        long dz = Math.max(Math.max((long) box.minZ() - z, 0L), (long) z - (box.maxZExclusive() - 1L));
        return Math.max(dx, dz);
    }

    /** {@code H} grown by {@code margin} on every XZ side. */
    public static List<Bounds> grow(List<Bounds> boxes, int margin) {
        if (margin < 0) throw new IllegalArgumentException("negative growth");
        var grown = new ArrayList<Bounds>(boxes.size());
        for (var box : boxes) {
            grown.add(new Bounds(Math.subtractExact(box.minX(), margin), box.minY(),
                    Math.subtractExact(box.minZ(), margin),
                    Math.addExact(box.maxXExclusive(), margin), box.maxYExclusive(),
                    Math.addExact(box.maxZExclusive(), margin)));
        }
        return List.copyOf(grown);
    }

    /** True when the half-open XZ rectangles overlap. */
    public static boolean intersects(Bounds a, Bounds b) {
        return a.minX() < b.maxXExclusive() && a.maxXExclusive() > b.minX()
                && a.minZ() < b.maxZExclusive() && a.maxZExclusive() > b.minZ();
    }
}
