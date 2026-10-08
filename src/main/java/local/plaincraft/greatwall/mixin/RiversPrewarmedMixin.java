package local.plaincraft.greatwall.mixin;

import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.minecraft.server.MinecraftServer;
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
 *     -> wall prewarm          &lt;- this mixin
 *     -> roads
 * </pre>
 *
 * <h2>Why here and not {@code prepareLevels} TAIL</h2>
 * The wall used to start its bulk prewarm at the tail of
 * {@code MinecraftServer.prepareLevels}, which is ordered after RoadWeaver's
 * initial road generation. That made the wall's prewarm the last thing to run,
 * so it competed with nothing but also could not start until the roads were
 * done — the opposite of "the wall is ready before play begins".
 *
 * <p>RoadWeaver's {@code InitialGenManager.prewarmInitialRiverPlates} is the one
 * place that runs after the rivers exist and before any road is built: it blocks
 * on {@code StreamsReflowingCompat.prewarmInitialRoutes} and returns once those
 * plates are present. Hooking its return therefore splits the three stages
 * exactly as intended, and it is the same instant RoadWeaver itself considers
 * "rivers are ready".
 *
 * <h2>Why a pseudo mixin</h2>
 * RoadWeaver is an optional peer, not a dependency. {@code @Pseudo} plus
 * {@code require = 0} means a session without RoadWeaver, or with a RoadWeaver
 * whose method has been renamed, simply skips this injection. The wall then
 * falls back to the {@code prepareLevels} TAIL release, which is unchanged.
 */
@Pseudo
@Mixin(targets = "net.shiroha233.roadweaver.generation.InitialGenManager", remap = false)
public abstract class RiversPrewarmedMixin {
    @Inject(method = "prewarmInitialRiverPlates", at = @At("RETURN"), require = 0, remap = false)
    private static void greatwall$riversPrewarmed(CallbackInfoReturnable<?> callback) {
        try {
            WorldServices.riversPrewarmed();
        } catch (Throwable ignored) {
            // Called from RoadWeaver's startup path: a wall-side failure must never
            // make road generation fail. The wall's own TAIL fallback still runs.
        }
    }

    /** Kept so the signature above cannot silently drift away from the server type. */
    static MinecraftServer serverTypeAnchor() { return null; }
}
