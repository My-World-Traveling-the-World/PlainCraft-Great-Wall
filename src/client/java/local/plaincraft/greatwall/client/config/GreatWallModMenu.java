package local.plaincraft.greatwall.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Mod Menu entry point.
 *
 * <p>Mod Menu is a soft dependency: the class is referenced only through the
 * {@code modmenu} entrypoint in {@code fabric.mod.json}, which the loader
 * resolves lazily. Nothing else in this mod links against it, so a session
 * without Mod Menu never loads this class.
 *
 * <p>The factory is written as an explicit lambda rather than a constructor
 * reference so the erased type is unambiguous: {@code ConfigScreenFactory<?>}
 * alone leaves the compiler unable to see that the produced screen is a
 * {@code Screen}, which it needs in order to check the override.
 */
@Environment(EnvType.CLIENT)
public final class GreatWallModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return GreatWallConfigScreen::create;
    }
}
