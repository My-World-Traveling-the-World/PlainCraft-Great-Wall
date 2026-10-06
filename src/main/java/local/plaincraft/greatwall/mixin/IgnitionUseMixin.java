package local.plaincraft.greatwall.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import local.plaincraft.greatwall.gameplay.GameplayTypes;
import local.plaincraft.greatwall.lifecycle.WorldServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Observes {@code useItemOn} on the server owner so a flint-and-steel or fire
 * charge ignition of a wall-owned fire pit can be promoted to a durable beacon
 * credit. The wrapper only observes; it never mutates the result, and an
 * external protection cancellation with no edge keeps the count at zero. No
 * static ThreadLocal single-slot, and no +1 at HEAD just because a tool is held.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class IgnitionUseMixin {
    @WrapMethod(method = "useItemOn")
    private InteractionResult greatwall$observe(ServerPlayer player, Level level, ItemStack stack,
            InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
        var services = WorldServices.find(level.getServer());
        // Non-throwing accessor: a player can interact before the journal has been recovered, and an
        // unavailable ignition service must simply mean "no beacon credit" for this action rather than
        // an exception that fails the interaction.
        var ignition = services == null ? null : services.ignitionIfReady();
        GameplayTypes.BeforeUse token = null;
        if (ignition != null) {
            token = ignition.beforeUse(player, level, stack, hand, hit).orElse(null);
        }
        InteractionResult result = original.call(player, level, stack, hand, hit);
        if (token != null) {
            ignition.afterUse(token, player, result);
        }
        return result;
    }
}
