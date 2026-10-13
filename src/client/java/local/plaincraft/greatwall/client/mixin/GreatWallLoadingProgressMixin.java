package local.plaincraft.greatwall.client.mixin;

import local.plaincraft.greatwall.client.GreatWallLoadingProgressRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    /**
     * Lets the card's detail toggle receive clicks. The loading screen has no widgets of its own,
     * so a click the toggle claims is simply consumed; any other click falls through to the
     * screen untouched.
     *
     * <p>The method name must stay descriptor-free: {@code mouseClicked} is inherited from
     * {@code Screen}, not declared on {@code LevelLoadingScreen}, and an explicit descriptor makes
     * Mixin search only the target class's own method list — the injection then silently never
     * applies and the toggle appears dead.
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void greatwall$clickPrewarmProgress(double mouseX, double mouseY, int button,
                                                CallbackInfoReturnable<Boolean> callback) {
        if (button == 0 && GreatWallLoadingProgressRenderer.mouseClicked(mouseX, mouseY)) {
            callback.setReturnValue(true);
        }
    }
}
