package local.plaincraft.greatwall.smoke.mixin;

import local.plaincraft.greatwall.smoke.RootHydrologyFixture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class SeedSurveyInitialSpawnMixin {
    @Inject(method = "setInitialSpawn", at = @At("RETURN"), require = 1)
    private static void survey$recordChosen(ServerLevel level, ServerLevelData data,
            boolean bonusChest, boolean debug, CallbackInfo callback) {
        RootHydrologyFixture.recordChosen(level);
    }
}
