package local.plaincraft.greatwall.client.mixin;

import local.plaincraft.greatwall.client.GreatWallClient;
import local.plaincraft.greatwall.client.config.GreatWallConfigScreen;
import local.plaincraft.greatwall.client.config.ScreenAccess;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Great Wall config" button to the world creation screen.
 *
 * <h2>Why this lives in its own package</h2>
 * A package named as a mixin config's {@code package} makes every class in it a
 * mixin candidate, and Mixin then refuses to let ordinary code reference them.
 * Keeping this file in {@code client.mixin} while the screens it opens stay in
 * {@code client.config} is what allows the button to construct a screen at all;
 * mixing the two in one package crashed the game the moment the button was
 * pressed.
 *
 * <h2>Why the world creation screen</h2>
 * The wall's length is a world-generation choice. Offering it next to the other
 * world options is the only place a player can reasonably set it before the
 * wall already exists, and it puts this mod next to the other PlainCraft
 * worldgen config rows instead of hiding the option behind Mod Menu.
 *
 * <h2>Why the injection is defensive</h2>
 * This runs inside vanilla's screen construction. If the button cannot be added
 * the player must still be able to create a world, so the whole body is guarded
 * and every failure is logged once and swallowed. The mixin config also sets
 * {@code required: false} and {@code defaultRequire: 0}, so a changed vanilla
 * signature skips the injection instead of failing the game.
 */
@Environment(EnvType.CLIENT)
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldConfigButtonMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void greatwall$addConfigButton(CallbackInfo callback) {
        try {
            var self = (Screen) (Object) this;
            var minecraft = ScreenAccess.greatwall$minecraft(self);
            if (minecraft == null) return;
            // Vanilla lays the creation screen out from its own fields; a fixed
            // anchor near the bottom-left keeps the button clear of the tab
            // layout at every GUI scale.
            int x = 8;
            int y = self.height - 28;
            var button = Button.builder(
                            Component.translatable("text.plaincraft_great_wall.configButton"),
                            pressed -> minecraft.setScreen(new GreatWallConfigScreen(self)))
                    .bounds(x, y, 130, 20)
                    .build();
            // The cast is valid here: this mixin's target is a Screen subclass.
            ((ScreenAccessor) self).greatwall$addRenderableWidget(button);
        } catch (Throwable unavailable) {
            GreatWallClient.reportConfigButtonFailure(unavailable);
        }
    }
}
