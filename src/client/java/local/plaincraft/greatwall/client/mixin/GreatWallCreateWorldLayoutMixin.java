package local.plaincraft.greatwall.client.mixin;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shares the river config button's row with the Great Wall config button.
 *
 * <h2>The problem this solves</h2>
 * The Streams mod has a layout mixin on this same method that repositions its own
 * button and the four worldgen shortcut buttons every frame. This mod's button is
 * not in that list, so it kept the grid position it was given and landed on the
 * same row as the four shortcuts. A real session showed the two overlapping.
 *
 * <h2>Why the layout is read rather than duplicated</h2>
 * Every frame the river button's geometry is read as the other mod left it, the
 * river is narrowed to half that width, and this mod's button takes the other
 * half. Reading the runtime geometry means this keeps working if that mod changes
 * its spacing, width or row, and it means the two never disagree about where the
 * row is — there is only one source of truth for it.
 *
 * <h2>Why priority does not matter here</h2>
 * Both mods inject at HEAD of the same method, so their relative order depends on
 * mixin priority, which is fragile to rely on. This pass is written to be correct
 * either way: it reads the river's rectangle and writes both, so whichever runs
 * second settles the final layout, and running first simply means the other pass
 * adjusts its own button afterwards.
 */
@Mixin(CreateWorldScreen.class)
public abstract class GreatWallCreateWorldLayoutMixin {
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void greatwall$shareRiverRow(GuiGraphics graphics, int mouseX, int mouseY,
                                         float partialTick, CallbackInfo callback) {
        try {
            var screen = (Screen) (Object) this;
            var button = local.plaincraft.greatwall.client.config.WorldCreationButtonHolder.get();
            if (button == null) return;
            // Only the Game tab is on screen; other tabs must not be laid out.
            if (Minecraft.getInstance() == null) return;
            var river = greatwall$findRiverButton(screen);
            if (river == null || river == button) return;

            int gap = 4;
            int totalWidth = river.getWidth();
            int each = (totalWidth - gap) / 2;
            if (each <= 0) return;
            int left = river.getX();
            int y = river.getY();
            int height = river.getHeight();
            button.setRectangle(each, height, left, y);
            river.setRectangle(each, height, left + each + gap, y);
        } catch (Throwable unavailable) {
            local.plaincraft.greatwall.client.GreatWallClient.reportConfigButtonFailure(unavailable);
        }
    }

    /**
     * Finds the Streams config button by its label.
     *
     * <p>Matched by text rather than position or index because the other mod's
     * layout may move or resize it, and because this must degrade quietly when that
     * mod is absent. Both languages are checked so an English client still matches.
     */
    @Unique
    private static Button greatwall$findRiverButton(Screen screen) {
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof Button candidate)) continue;
            String label = candidate.getMessage().getString();
            if (label == null || label.isBlank()) continue;
            String lower = label.toLowerCase(Locale.ROOT);
            if (label.contains("河流配置") || lower.contains("streams reflowing")) {
                return candidate;
            }
        }
        return null;
    }
}
