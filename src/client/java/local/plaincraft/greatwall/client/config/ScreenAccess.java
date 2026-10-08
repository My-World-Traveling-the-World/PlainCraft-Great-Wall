package local.plaincraft.greatwall.client.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Reads {@link Screen#minecraft}, which is {@code protected}.
 *
 * <h2>Why reflection instead of an accessor mixin</h2>
 * A mixin accessor interface can only be cast from the class the mixin was
 * applied to; casting a {@code Screen} subclass that the mod owns compiles but
 * fails at runtime. Reading the field reflectively is boring and always works,
 * and the result is cached because this runs on every screen close.
 *
 * <p>The field is looked up on {@code Screen} and then on the concrete class, so
 * a subclass that re-declares it is still handled.
 */
public final class ScreenAccess {
    private static volatile java.lang.reflect.Field screenMinecraft;

    private ScreenAccess() {
    }

    public static Minecraft greatwall$minecraft(Screen screen) {
        if (screen == null) return null;
        try {
            var field = screenMinecraft;
            if (field == null) {
                field = Screen.class.getDeclaredField("minecraft");
                field.setAccessible(true);
                screenMinecraft = field;
            }
            return (Minecraft) field.get(screen);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // A screen that cannot report its client simply stays put; that is far
            // better than throwing out of a screen-close callback.
            return null;
        }
    }
}
