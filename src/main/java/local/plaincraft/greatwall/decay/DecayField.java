package local.plaincraft.greatwall.decay;

import java.util.List;
import java.util.Objects;
import local.plaincraft.greatwall.geometry.DeterministicHash;
import local.plaincraft.greatwall.geometry.FixedMath;
import local.plaincraft.greatwall.model.WallRecipeRules;
import local.plaincraft.greatwall.model.WallTypes.*;

/** Immutable Q16 field sampled by global arc length, never by chunk arrival order. */
public final class DecayField {
    private static final DecaySample BUILT_INTACT = new DecaySample(DecayState.INTACT, 0, 20);
    private final long seed;
    private final boolean weathers;

    public DecayField(long seed) { this(seed, WallRecipeRules.AUTHORED); }

    public DecayField(long seed, int recipeVersion) {
        this.seed = seed;
        this.weathers = WallRecipeRules.weathers(recipeVersion);
    }

    public boolean weathers() { return weathers; }

    public DecaySample sample(long globalSQ8, PaletteFamily family, List<Long> maintainedSitesQ8) {
        if (globalSQ8 < 0) throw new IllegalArgumentException("global S must be nonnegative");
        Objects.requireNonNull(family); Objects.requireNonNull(maintainedSitesQ8);
        // 正式墙的马道和垛口按图施工，不做噪声求值，也不把完好的道路截成废墟。
        if (!weathers) return BUILT_INTACT;
        int amount = noise(globalSQ8, 320) * 55 / 100 + noise(globalSQ8, 80) * 45 / 100;
        amount += switch (family) { case STONE -> 0; case DEEPSLATE -> 3277; case SANDSTONE -> 5243; };
        long distance = 48L * 256;
        for (Long position : maintainedSitesQ8) {
            if (position == null || position < 0) throw new IllegalArgumentException("invalid maintained site");
            long delta = globalSQ8 >= position ? globalSQ8 - position : position - globalSQ8;
            distance = Math.min(distance, delta);
        }
        int fraction = (int) FixedMath.mulDiv(distance, 65536, 48L * 256);
        amount -= (int) (16384L * (65536 - FixedMath.smoothQ16(fraction)) / 65536);
        amount = Math.clamp(amount, 0, 65535);
        DecayState state = amount < 19661 ? DecayState.INTACT
                : amount < 36045 ? DecayState.PARTIAL : amount < 51118 ? DecayState.RUIN : DecayState.BURIED;
        int height = amount <= 19661 ? 20 : amount <= 36045
                ? 20 - (int) FixedMath.roundDiv(14L * (amount - 19661), 36045 - 19661)
                : amount <= 51118 ? 6 - (int) FixedMath.roundDiv(4L * (amount - 36045), 51118 - 36045) : 2;
        return new DecaySample(state, amount, height);
    }

    public boolean keep(DecaySample decay, Part part, Point3i local, long globalSQ8,
            long componentHash, boolean required) {
        Objects.requireNonNull(decay); Objects.requireNonNull(part); Objects.requireNonNull(local);
        if (globalSQ8 < 0) throw new IllegalArgumentException("global S must be nonnegative");
        if (required || decay.state() == DecayState.INTACT) return true;
        int height = Math.addExact(local.y(), 20);
        if ((decay.state() == DecayState.RUIN || decay.state() == DecayState.BURIED)
                && height >= decay.keepHeight()) return false;
        long group = Math.floorDiv(globalSQ8, 4L * 256);
        if (decay.state() == DecayState.PARTIAL && (part == Part.PARAPET || part == Part.LAMP)) {
            return (DeterministicHash.fields(seed, "decay-group", group, componentHash) & 65535) >= decay.amountQ16();
        }
        if (part != Part.SKIN && part != Part.RUBBLE && part != Part.COVER) return true;
        long detail = DeterministicHash.fields(seed, "decay-detail", group, componentHash,
                Math.floorDiv(local.x(), 2), Math.floorDiv(height, 2), Math.floorDiv(local.z(), 2));
        return (detail & 65535) >= decay.amountQ16() / 4;
    }

    private int noise(long position, int scale) {
        long period = scale * 256L, index = Math.floorDiv(position, period);
        long a = DeterministicHash.fields(seed, "decay-noise", scale, index) & 65535;
        long b = DeterministicHash.fields(seed, "decay-noise", scale, index + 1) & 65535;
        int t = (int) FixedMath.mulDiv(Math.floorMod(position, period), 65536, period);
        return (int) FixedMath.lerp(a, b, FixedMath.smoothQ16(t));
    }
}
