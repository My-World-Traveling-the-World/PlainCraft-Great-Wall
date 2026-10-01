package local.plaincraft.greatwall.smoke.mixin;

import local.plaincraft.greatwall.smoke.RootHydrologyFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerRespawnLogic.class)
public abstract class SeedSurveySpawnProbeMixin {
    @Inject(method = "getSpawnPosInChunk", at = @At("HEAD"), require = 1)
    private static void survey$recordProbe(ServerLevel level, ChunkPos pos, CallbackInfoReturnable<BlockPos> callback) {
        RootHydrologyFixture.recordProbe(level, pos);
    }
}
