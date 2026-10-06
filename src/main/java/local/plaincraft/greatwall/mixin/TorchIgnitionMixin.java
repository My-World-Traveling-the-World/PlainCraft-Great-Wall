package local.plaincraft.greatwall.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Preserves the v2.0 torch-ignites-wall-campfire interaction from inside
 * {@code ItemStack.useOn}, after the adventure CanPlaceOn check already passed.
 * Returns a custom SUCCESS only for a legal unlit wall fire pit; otherwise the
 * original call runs untouched. The client never builds a service or changes
 * LIT; a dedicated server never loads any client class.
 */
@Mixin(ItemStack.class)
public abstract class TorchIgnitionMixin {
    @WrapOperation(method = "useOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/Item;useOn(Lnet/minecraft/world/item/context/UseOnContext;)"
                    + "Lnet/minecraft/world/InteractionResult;"), require = 1)
    private InteractionResult greatwall$torch(Item item, UseOnContext context,
            Operation<InteractionResult> original) {
        var services = WorldServices.find(context.getLevel().getServer());
        if (services != null) {
            // Non-throwing accessor: this mixin also runs in the client process and can fire before
            // gameplay assembly, so an absent service must mean "ordinary torch behaviour" rather
            // than an exception on a player interaction.
            var ignition = services.ignitionIfReady();
            if (ignition != null) {
                var custom = ignition.useTorchAfterPermission(context);
                if (custom.isPresent()) return custom.get();
            }
        }
        return original.call(item, context);
    }
}
