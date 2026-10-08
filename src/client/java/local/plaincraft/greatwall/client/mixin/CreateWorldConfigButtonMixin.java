package local.plaincraft.greatwall.client.mixin;

import local.plaincraft.greatwall.client.config.GreatWallConfigScreen;
import local.plaincraft.greatwall.client.config.WorldCreationButtonHolder;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Great Wall config" button to the world creation screen's Game tab.
 *
 * <h2>Why the Game tab's constructor</h2>
 * The Game tab lays its children out through a {@code GridLayout} that runs after
 * the screen's {@code init}. A button added to the screen directly is therefore
 * overwritten by the tab's next layout pass — an earlier attempt injected into
 * {@code CreateWorldScreen.init} and produced no visible button at all. Attaching
 * to the layout is what survives.
 *
 * <p>The grid position below is only a starting point. The Game tab is a
 * {@code Tab}, not a {@code Screen}, and declares no render method, so a tab mixin
 * cannot reposition anything per frame; {@link GreatWallCreateWorldLayoutMixin}
 * does that from the screen, which is where the Streams mod does it too.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$GameTab")
public abstract class CreateWorldConfigButtonMixin extends GridLayoutTab {
    protected CreateWorldConfigButtonMixin(Component title) {
        super(title);
    }

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void greatwall$addConfigButton(CallbackInfo callback) {
        try {
            var button = Button.builder(
                            Component.translatable("text.plaincraft_great_wall.configButton"),
                            pressed -> {
                                // Minecraft.getInstance() rather than the screen's protected
                                // field: the field is remapped in production, and reflection on
                                // it silently returns null there.
                                var minecraft = Minecraft.getInstance();
                                if (minecraft != null && minecraft.screen instanceof CreateWorldScreen screen) {
                                    minecraft.setScreen(new GreatWallConfigScreen((Screen) screen));
                                }
                            })
                    .width(210)
                    .build();
            WorldCreationButtonHolder.set(button);
            // Starts one row below the Streams button. The screen-side layout pass
            // re-anchors it every frame, so this only decides where it first appears.
            int streamsRow = SharedConstants.getCurrentVersion().isStable() ? 4 : 5;
            this.layout.addChild(button, streamsRow + 2, 0,
                    this.layout.newCellSettings().alignHorizontallyCenter());
        } catch (Throwable unavailable) {
            local.plaincraft.greatwall.client.GreatWallClient.reportConfigButtonFailure(unavailable);
        }
    }
}
