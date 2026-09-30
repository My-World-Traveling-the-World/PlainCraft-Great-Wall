package local.plaincraft.greatwall.mixin;

import local.plaincraft.greatwall.lifecycle.WorldServices;
import local.plaincraft.greatwall.model.WallTypes.Point3i;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class BootstrapSpawnMixin {
    @Inject(method = "setInitialSpawn", at = @At("RETURN"), require = 1)
    private static void greatwall$afterSpawn(ServerLevel level, ServerLevelData data,
            boolean bonusChest, boolean debug, CallbackInfo callback) {
        var services = WorldServices.find(level.getServer());
        if (services == null || !services.productionFixture()) return;
        var spawn = level.getSharedSpawnPos();
        services.bootstrap().spawnChosen(level, new Point3i(spawn.getX(), spawn.getY(), spawn.getZ()));
    }
}
