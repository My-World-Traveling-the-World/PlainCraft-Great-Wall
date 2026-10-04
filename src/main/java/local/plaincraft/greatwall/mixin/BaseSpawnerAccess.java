package local.plaincraft.greatwall.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.world.level.BaseSpawner;

/**
 * Exposes {@code BaseSpawner.spawnDelay} so {@code WallSpawnerGuard} can hold or
 * reset the owned-spawner countdown. The field is the only one touched; the
 * client still observes the vanilla sync animation and never makes a spawn
 * decision here.
 */
@Mixin(BaseSpawner.class)
public interface BaseSpawnerAccess {
    @Accessor("spawnDelay") int greatwall$delay();
    @Accessor("spawnDelay") void greatwall$delay(int value);
}
