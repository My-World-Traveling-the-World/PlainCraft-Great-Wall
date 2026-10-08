package local.plaincraft.greatwall.client.config;

import net.minecraft.client.gui.components.Button;

/**
 * Hands the world-creation config button from the mixin that creates it to the
 * mixin that positions it.
 *
 * <h2>Why a holder instead of a field on the mixin</h2>
 * The button is created inside the Game tab, which is a package-private inner
 * class of {@code CreateWorldScreen}, and positioned from a mixin on the screen
 * itself. A {@code @Unique} field on the tab mixin would be merged into that
 * package-private class and could not be read from the screen-side mixin. A small
 * holder in this mod's own package avoids the visibility problem entirely.
 *
 * <p>This is a plain class and deliberately sits outside the mixin package, which
 * Mixin treats as its own and refuses to let ordinary code reference.
 */
public final class WorldCreationButtonHolder {
    private static volatile Button button;

    private WorldCreationButtonHolder() {
    }

    public static void set(Button value) { button = value; }

    /** The button created for the most recent world-creation screen, or null. */
    public static Button get() { return button; }
}
