package local.plaincraft.greatwall.decay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import local.plaincraft.greatwall.model.WallFailure;
import local.plaincraft.greatwall.model.WallTypes;
import local.plaincraft.greatwall.decay.PaletteTypes.Variant;

/**
 * Piecewise-constant biome blend over an eight-block window. Pure function;
 * the ribbons and window lengths are frozen by the S4 contract.
 */
public final class PaletteBlend {
    /** Half-window in Q8 arc units: the window is [S-4*256, S+4*256). */
    public static final long HALF_WINDOW_Q8 = 4L * 256;

    private PaletteBlend() {}

    /**
     * The last ribbon whose monotonicity this thread checked, by identity.
     *
     * <p>The check below used to run on every call, and this method is called once per emitted voxel: with
     * the opening segment's ~2048 stations that is ~2048 comparisons per voxel, ~20 us of the ~23 us this
     * method costs - about 250 ms per wall component per owner for a property of an immutable list. One
     * check per ribbon instance is exactly as strong, because the ribbon is immutable and the check is
     * deterministic; the thread-local keeps it free of cross-thread state.
     */
    private static final ThreadLocal<List<?>> VALIDATED = new ThreadLocal<>();

    public static PaletteTypes.Mix at(long globalSQ8, List<PaletteTypes.BiomeStation> ribbon) {
        Objects.requireNonNull(ribbon, "ribbon");
        if (ribbon.isEmpty()) {
            throw new WallFailure(WallTypes.FailureCode.INVALID_RESOURCE, "palette ribbon is empty");
        }
        if (VALIDATED.get() != ribbon) {
            for (int i = 1; i < ribbon.size(); i++) {
                if (ribbon.get(i).globalSQ8() <= ribbon.get(i - 1).globalSQ8()) {
                    throw new WallFailure(WallTypes.FailureCode.INVALID_RESOURCE,
                            "ribbon stations must be strictly increasing");
                }
            }
            VALIDATED.set(ribbon);
        }
        long windowFrom = globalSQ8 - HALF_WINDOW_Q8;
        long windowTo = globalSQ8 + HALF_WINDOW_Q8;
        long total = windowTo - windowFrom;
        if (total <= 0) {
            throw new WallFailure(WallTypes.FailureCode.INVALID_RESOURCE, "palette window is empty");
        }
        // Build piecewise-constant intervals from adjacent station midpoints.
        List<long[]> pieces = new ArrayList<>();
        List<Variant> variants = new ArrayList<>();
        int firstIndex = Math.max(0, indexBefore(ribbon, windowFrom) - 1);
        int lastIndex = Math.min(ribbon.size() - 1, indexBefore(ribbon, windowTo) + 1);
        if (firstIndex > 0) firstIndex--;
        if (lastIndex < ribbon.size() - 1) lastIndex++;
        for (int i = firstIndex; i <= lastIndex; i++) {
            long pieceFrom = i == 0 ? Long.MIN_VALUE / 4
                    : Math.floorDiv(ribbon.get(i - 1).globalSQ8() + ribbon.get(i).globalSQ8(), 2);
            long pieceTo = i == ribbon.size() - 1 ? Long.MAX_VALUE / 4
                    : Math.floorDiv(ribbon.get(i).globalSQ8() + ribbon.get(i + 1).globalSQ8(), 2);
            pieces.add(new long[] {pieceFrom, pieceTo});
            variants.add(ribbon.get(i).variant());
        }
        long[] accumulated = new long[Variant.values().length];
        for (int i = 0; i < pieces.size(); i++) {
            long overlap = Math.min(pieces.get(i)[1], windowTo) - Math.max(pieces.get(i)[0], windowFrom);
            if (overlap <= 0) continue;
            accumulated[variants.get(i).ordinal()] += overlap;
        }
        long summed = 0;
        for (long value : accumulated) summed += value;
        if (summed != total) {
            throw new WallFailure(WallTypes.FailureCode.INVALID_RESOURCE,
                    "palette blend window " + total + " but intervals cover " + summed);
        }
        List<PaletteTypes.Weight> weights = new ArrayList<>();
        long assigned = 0;
        for (Variant variant : Variant.values()) {
            long share = accumulated[variant.ordinal()];
            if (share <= 0) continue;
            long weight = Math.floorDiv(share * 65536L, total);
            weights.add(new PaletteTypes.Weight(variant, (int) weight));
            assigned += weight;
        }
        // Largest-remainder distribution keeps the total exactly 65536.
        long remainder = 65536L - assigned;
        if (remainder > 0 && !weights.isEmpty()) {
            int largest = 0;
            for (int i = 1; i < weights.size(); i++) {
                if (weights.get(i).weightQ16() > weights.get(largest).weightQ16()) largest = i;
            }
            var bumped = weights.get(largest);
            weights.set(largest, new PaletteTypes.Weight(bumped.variant(),
                    bumped.weightQ16() + (int) remainder));
        }
        long check = 0;
        for (PaletteTypes.Weight weight : weights) check += weight.weightQ16();
        if (check != 65536L) {
            throw new WallFailure(WallTypes.FailureCode.INVALID_RESOURCE, "palette weights do not sum to 65536: " + check);
        }
        weights.sort((a, b) -> a.variant().compareTo(b.variant()));
        StringBuilder text = new StringBuilder();
        for (PaletteTypes.Weight weight : weights) {
            text.append(weight.variant().name()).append(':').append(weight.weightQ16()).append(';');
        }
        return new PaletteTypes.Mix(List.copyOf(weights),
                local.plaincraft.greatwall.geometry.DeterministicHash.sha256(
                        text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Deterministic weighted pick; the voxel hash is the only entropy source. */
    public static Variant choose(PaletteTypes.Mix mix, long voxelHash) {
        Objects.requireNonNull(mix, "mix");
        if (mix.weights().isEmpty()) {
            throw new WallFailure(WallTypes.FailureCode.INVALID_RESOURCE, "palette mix has no weights");
        }
        long roll = Long.remainderUnsigned(voxelHash, 65536L);
        long cursor = 0;
        for (PaletteTypes.Weight weight : mix.weights()) {
            cursor += weight.weightQ16();
            if (roll < cursor) return weight.variant();
        }
        return mix.weights().getLast().variant();
    }

    private static int indexBefore(List<PaletteTypes.BiomeStation> ribbon, long globalSQ8) {
        int low = 0;
        int high = ribbon.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (ribbon.get(mid).globalSQ8() <= globalSQ8) low = mid;
            else high = mid - 1;
        }
        return low;
    }
}
