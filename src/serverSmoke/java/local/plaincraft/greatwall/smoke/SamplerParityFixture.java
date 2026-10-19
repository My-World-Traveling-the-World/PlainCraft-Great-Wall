package local.plaincraft.greatwall.smoke;

import java.util.Random;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.terrain.CpuTerrainOracle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Compares the wall's terrain sampler against the world's own heightmap.
 *
 * <h2>Why this fixture exists</h2>
 * Every wall height is now decided by {@code NoiseSurfaceSampler} - a NoiseChunk sweep with the
 * vanilla heightmap predicates - and nothing in the unit suite can touch it: the sampler needs a
 * real level, a real generator and a real RandomState. That left the single most important claim in
 * the mod ("our heights are the game's heights") untested, after the density sampler it replaced was
 * measured being 40 to 140 blocks off.
 *
 * <p>This fixture generates real chunks, reads their recorded {@code WORLD_SURFACE_WG} height, and
 * compares it with what the sampler believes. Exact agreement is the expectation; the few legitimate
 * differences are columns whose surface was changed after the noise stage - a cave that breaches the
 * surface, a structure, a feature. They are counted and reported rather than hidden.
 *
 * <p>Run with {@code -Dgreatwall.smoke.samplerParity=true}.
 */
final class SamplerParityFixture {
    /** Columns compared: enough to catch a systematic error, small enough to keep the smoke fast. */
    private static final int SAMPLES = 512;
    /** A fingerprint is required by the oracle and never used by this comparison. */
    private static final String FINGERPRINT = "0".repeat(64);

    private int startedAt;
    private boolean done;

    boolean tick(MinecraftServer server, int ticks) {
        if (done) return false;
        if (ticks < 20) return false;
        if (startedAt == 0) startedAt = ticks;
        if (ticks - startedAt > 600) throw new IllegalStateException("sampler parity fixture timed out");

        ServerLevel level = server.overworld();
        CpuTerrainOracle oracle = CpuTerrainOracle.freeze(level, FINGERPRINT).createOracle();
        try {
            Random random = new Random(level.getSeed());
            int exact = 0;
            int within4 = 0;
            int within16 = 0;
            int gross = 0;
            int worst = 0;
            int worstX = 0, worstZ = 0, worstSampled = 0, worstVanilla = 0;
            for (int i = 0; i < SAMPLES; i++) {
                int x = random.nextInt(2048) - 1024;
                int z = random.nextInt(2048) - 1024;
                int vanilla = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
                var cursor = oracle.begin(x, z, local.plaincraft.greatwall.model.WallTypes.SampleKind.REFERENCE);
                oracle.advance(cursor, 64);
                int sampled = oracle.finish(cursor).groundTopY();
                int delta = Math.abs(sampled - vanilla);
                if (delta == 0) exact++;
                else if (delta <= 4) within4++;
                else if (delta <= 16) within16++;
                else gross++;
                if (delta > worst) {
                    worst = delta;
                    worstX = x;
                    worstZ = z;
                    worstSampled = sampled;
                    worstVanilla = vanilla;
                }
            }
            double exactPct = 100.0 * exact / SAMPLES;
            String summary = String.format(
                    "SAMPLER_PARITY samples=%d exact=%d(%.1f%%) within4=%d within16=%d gross=%d "
                            + "worst=%d at %d,%d sampled=%d vanilla=%d",
                    SAMPLES, exact, exactPct, within4, within16, gross, worst,
                    worstX, worstZ, worstSampled, worstVanilla);
            GreatWallMod.LOGGER.info("[GreatWall:smoke] {}", summary);
            if (exactPct < 90.0) {
                throw new IllegalStateException("sampler parity below 90%: " + summary);
            }
        } finally {
            oracle.close();
            done = true;
        }
        return true;
    }

    void close() {
    }
}
