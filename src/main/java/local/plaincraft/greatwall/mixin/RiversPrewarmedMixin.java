package local.plaincraft.greatwall.mixin;

import java.util.List;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Releases the wall's bulk prewarm as soon as the rivers are ready.
 *
 * <h2>The ordering this produces</h2>
 * <pre>
 *   rivers (RoadWeaver asks Streams to build the plates it needs)
 *     -&gt; wall prewarm          &lt;- this mixin
 *     -&gt; roads
 * </pre>
 *
 * <h2>Why here and not {@code prepareLevels} TAIL</h2>
 * The wall used to start its bulk prewarm at the tail of
 * {@code MinecraftServer.prepareLevels}, which is ordered after RoadWeaver's initial road
 * generation. That made the wall's prewarm the last thing to run, so it competed with the
 * roads instead of preceding them.
 *
 * <p>RoadWeaver's {@code InitialGenManager.prewarmInitialRiverPlates} is the one place that
 * runs after the rivers exist and before any road is built: it blocks on
 * {@code StreamsReflowingCompat.prewarmInitialRoutes} and returns once those plates are
 * present. Hooking its return therefore splits the three stages exactly as intended.
 *
 * <h2>The signature is part of the contract</h2>
 * An earlier revision of this class injected {@code method = "prewarmInitialRiverPlates"}
 * with a callback taking only {@code CallbackInfoReturnable}, while the target is
 * {@code private static List&lt;BlockPos&gt; prewarmInitialRiverPlates(ServerLevel, List)}.
 * Mixin matched nothing and {@code require = 0} made that silent, so the wall fell back to
 * the TAIL path - which is why a measured session showed the prewarm starting only after
 * roads were already running. Both are corrected here: the callback now declares the
 * target's own parameters, and {@code require = 1} makes a future signature change fail
 * loudly at class load instead of quietly restoring the old behaviour.
 *
 * <h2>Why a pseudo mixin</h2>
 * RoadWeaver is an optional peer, not a dependency. {@code @Pseudo} means a session without
 * RoadWeaver simply skips this injection, and the wall falls back to the
 * {@code prepareLevels} TAIL release, which is unchanged.
 */
@Pseudo
@Mixin(targets = "net.shiroha233.roadweaver.generation.InitialGenManager", remap = false)
public abstract class RiversPrewarmedMixin {
    @Inject(method = "prewarmInitialRiverPlates", at = @At("RETURN"), require = 1, remap = false)
    private static void greatwall$riversPrewarmed(ServerLevel level, List<?> connections,
                                                  CallbackInfoReturnable<List<?>> callback) {
        try {
            WorldServices.riversPrewarmed();
        } catch (Throwable ignored) {
            // Called from RoadWeaver's startup path: a wall-side failure must never make road
            // generation fail. The wall's own TAIL fallback still runs.
        }
    }
}
