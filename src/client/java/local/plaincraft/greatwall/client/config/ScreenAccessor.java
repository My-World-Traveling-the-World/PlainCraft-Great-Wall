package local.plaincraft.greatwall.client.config;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to {@code Screen.addRenderableWidget}, which is {@code protected}.
 *
 * <p>Only callable from a mixin whose target is a {@link Screen} subclass, where
 * the cast to this interface is valid. Ordinary classes use {@link ScreenAccess}
 * instead, because casting an arbitrary {@code Screen} to a mixin interface is a
 * compile error.
 */
@Mixin(Screen.class)
public interface ScreenAccessor {
    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry> T greatwall$addRenderableWidget(T widget);
}
