package local.plaincraft.greatwall.mixin;

import local.plaincraft.greatwall.gameplay.WalkwaySpawnGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnMixin {
    @Inject(method = "isValidSpawnPostitionForType", at = @At("HEAD"),
            cancellable = true, require = 1)
    private static void greatwall$walkway(ServerLevel level, MobCategory category,
            StructureManager structures, ChunkGenerator generator,
            MobSpawnSettings.SpawnerData data, BlockPos.MutableBlockPos pos, double distance,
            CallbackInfoReturnable<Boolean> cir) {
        if (WalkwaySpawnGuard.blocksNaturalSpawn(level, pos)) {
            cir.setReturnValue(false);
        }
    }
}
