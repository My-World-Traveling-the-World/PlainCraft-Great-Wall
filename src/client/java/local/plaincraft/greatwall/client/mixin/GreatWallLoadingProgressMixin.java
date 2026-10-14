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
 *
 * <p>The card has no click behaviour (details are always drawn), so this mixin carries only
 * the {@code render} hook. The earlier {@code mouseClicked} hook was removed in 79dbbaa2
 * because it injected an inherited method with an explicit descriptor, which Mixin cannot
 * resolve and which was declared {@code require = 0} so it failed silently. Do not reintroduce
 * it: an explicit descriptor on an inherited method never applies.
 */
@Mixin(value = LevelLoadingScreen.class, priority = 900)
public abstract class GreatWallLoadingProgressMixin {
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void greatwall$renderPrewarmProgress(GuiGraphics graphics, int mouseX, int mouseY,
                                                 float partialTick, CallbackInfo callback) {
        GreatWallLoadingProgressRenderer.render(graphics);
    }
}
