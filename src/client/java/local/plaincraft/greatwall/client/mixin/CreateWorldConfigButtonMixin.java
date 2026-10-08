package local.plaincraft.greatwall.client.mixin;

import local.plaincraft.greatwall.client.config.GreatWallConfigScreen;
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
 * The Game tab lays its own buttons out through a {@code GridLayout}, and that
 * layout runs after the screen's {@code init}. Adding a widget to the screen
 * directly therefore places a button that the tab's next layout pass immediately
 * overwrites — which is why an earlier attempt injected into
 * {@code CreateWorldScreen.init} and produced no visible button at all. Attaching
 * to the layout itself, at the moment the tab is constructed, is what survives.
 *
 * <p>This mirrors the approach the Streams config mod already uses successfully
 * in this pack, including the row index and the stable/unstable version check
 * that keeps the button clear of the world-type buttons on snapshots.
 *
 * <h2>Why the injection is defensive</h2>
 * This runs inside vanilla's screen construction. A failure here must not stop a
 * player from creating a world, so the mixin config sets {@code required: false}
 * and {@code require = 0}: a changed vanilla signature skips the injection rather
 * than failing the game.
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
            // Same row the Streams config button occupies plus one, so the two sit in a
            // predictable order instead of racing for the same cell.
            int streamsRow = SharedConstants.getCurrentVersion().isStable() ? 4 : 5;
            this.layout.addChild(button, streamsRow + 2, 0,
                    this.layout.newCellSettings().alignHorizontallyCenter());
        } catch (Throwable unavailable) {
            local.plaincraft.greatwall.client.GreatWallClient.reportConfigButtonFailure(unavailable);
        }
    }
}
