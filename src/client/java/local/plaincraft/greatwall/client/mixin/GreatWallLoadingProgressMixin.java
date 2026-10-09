package local.plaincraft.greatwall.client.mixin;

import local.plaincraft.greatwall.client.GreatWallLoadingProgressRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the wall's prewarm card on the level loading screen.
 *
 * <p>Injected at TAIL so the card sits above the vanilla loading text rather than under
 * it, and with a priority above the default so it lands after any other mod drawing its
 * own overlay in the same place - the river patch uses the same hook.
 */
@Mixin(value = LevelLoadingScreen.class, priority = 900)
public abstract class GreatWallLoadingProgressMixin {
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void greatwall$renderPrewarmProgress(GuiGraphics graphics, int mouseX, int mouseY,
                                                 float partialTick, CallbackInfo callback) {
        GreatWallLoadingProgressRenderer.render(graphics);
    }
}
