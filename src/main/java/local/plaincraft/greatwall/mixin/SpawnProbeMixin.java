package local.plaincraft.greatwall.mixin;

import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.model.WallTypes.ChunkKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerRespawnLogic.class)
public abstract class SpawnProbeMixin {
    @Inject(method = "getSpawnPosInChunk", at = @At("HEAD"), require = 1)
    private static void greatwall$markProbe(ServerLevel level, ChunkPos pos,
            CallbackInfoReturnable<BlockPos> callback) {
        var services = WorldServices.find(level.getServer());
        if (services == null || !services.productionFixture()) return;
        int radius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL)
                .accumulatedDependencies().getRadius();
        services.bootstrap().authorizeSpawnProbe(new ChunkKey(level.dimension().location().toString(), pos.x, pos.z), radius);
    }
}
