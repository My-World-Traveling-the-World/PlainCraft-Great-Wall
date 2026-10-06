package local.plaincraft.greatwall.mixin;

import local.plaincraft.greatwall.compat.biome.GreatWallBiomeRuntime;
import local.plaincraft.greatwall.compat.biome.GreatWallBiomeWriter;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.model.WallTypes.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paints the Great Wall biome into the owner chunk immediately before vanilla dispatches features.
 *
 * <h2>Why this point</h2>
 * {@code ChunkStatusTasks.generateFeatures} primes the worldgen heightmaps and then calls
 * {@code ChunkGenerator.applyBiomeDecoration}, which is the first place the chunk's own biome array
 * decides which features run. Writing here means the very same call observes the new identity, and
 * the 3×3 neighbour biomes used by the union are already written for owners that reached this step.
 *
 * <h2>Dimension safety (three independent gates)</h2>
 * <ol>
 *   <li>The worldgen level must report {@link Level#OVERWORLD} by key, never by generator type.</li>
 *   <li>The owning {@link ServerLevel}'s dimension must also be {@code OVERWORLD}.</li>
 *   <li>The runtime is itself constructed overworld-only and holds exactly one dimension key, so a
 *       non-overworld owner can never resolve to a mask.</li>
 * </ol>
 * Any failure of gates 1–2 returns silently, so the nether, the end and modded dimensions are
 * never touched — this is the regression guard for the historical "nether generated terrain but no
 * vegetation" incident.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorBiomeMixin {
    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void greatwall$paintRouteBiome(WorldGenLevel level, ChunkAccess chunk,
                                           StructureManager structures, CallbackInfo callback) {
        // Gate 1: overworld by dimension key only. ResourceKey equality is by value, so this stays
        // correct across datapack reloads where key instances may be recreated.
        ServerLevel owner;
        try {
            owner = level.getLevel();
        } catch (RuntimeException unsupported) {
            return;
        }
        if (owner == null) return;
        String dimension = owner.dimension().location().toString();
        // Gate 2: compare the registry key, not just the location string.
        if (!Level.OVERWORLD.equals(owner.dimension())) return;
        if (!Level.OVERWORLD.location().toString().equals(dimension)) return;

        var services = WorldServices.find(owner.getServer());
        if (services == null || !services.productionFixture()) return;
        var runtime = services.biomeRuntime();
        if (runtime == null || runtime.closed() || !runtime.identityBound()) return;
        // Gate 3: the runtime holds exactly one dimension key and it must match.
        if (!runtime.dimension().equals(dimension)) return;
        var key = new ChunkKey(dimension, chunk.getPos().x, chunk.getPos().z);
        var mask = runtime.find(key).orElse(null);
        if (mask == null) return;
        GreatWallBiomeWriter.paint(chunk, owner.getChunkSource().randomState().sampler(), mask,
                runtime.requireIdentity());
    }
}
