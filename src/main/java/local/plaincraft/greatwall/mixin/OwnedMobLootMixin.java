package local.plaincraft.greatwall.mixin;

import local.plaincraft.greatwall.generation.TerrainMarkers;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scopes the garrison loot table and zero XP to mobs carrying the persistent
 * OWNED_MOB attachment. Vanilla mobs, player summons and patrols are untouched;
 * the attachment is not copied to death clones, so custom loot never leaks.
 */
@Mixin(Mob.class)
public abstract class OwnedMobLootMixin {
    private static final ResourceKey<LootTable> GARRISON = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath("plaincraft_great_wall", "entities/garrison"));

    @Inject(method = "getLootTable", at = @At("RETURN"), cancellable = true, require = 1)
    private void greatwall$loot(CallbackInfoReturnable<ResourceKey<LootTable>> cir) {
        var mob = (Mob) (Object) this;
        if (mob.getAttached(TerrainMarkers.OWNED_MOB) != null) {
            cir.setReturnValue(GARRISON);
        }
    }

    @Inject(method = "getBaseExperienceReward", at = @At("RETURN"), cancellable = true, require = 1)
    private void greatwall$xp(CallbackInfoReturnable<Integer> cir) {
        var mob = (Mob) (Object) this;
        if (mob.getAttached(TerrainMarkers.OWNED_MOB) != null) {
            cir.setReturnValue(0);
        }
    }
}
