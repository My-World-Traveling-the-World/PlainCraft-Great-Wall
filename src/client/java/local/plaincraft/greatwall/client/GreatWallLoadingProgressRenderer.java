package local.plaincraft.greatwall.client;

import local.plaincraft.greatwall.lifecycle.PrewarmProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Draws the wall's startup prewarm as a card on the level loading screen.
 *
 * <h2>Why</h2>
 * The wall's prewarm is the longest single stage of a new world - measured at 22 minutes
 * before the changes alongside it - and it was invisible. The loading screen showed the
 * rivers finish and then appeared to hang, with nothing distinguishing a working prewarm
 * from a stalled one.
 *
 * <h2>Shape</h2>
 * Deliberately the same card as the river patch's progress display: same width, colours,
 * bar geometry and line layout, so the two read as one sequence of stages rather than two
 * unrelated overlays. The only differences are the strings and that this one also reports
 * how many segments are finished.
 */
public final class GreatWallLoadingProgressRenderer {
    private static final int CARD_WIDTH = 360;
    private static final int CARD_HEIGHT = 66;
    private static final int CARD_BACKGROUND = 0xB010131A;
    private static final int CARD_BORDER = 0x80404B5A;
    private static final int BAR_BACKGROUND = 0x60242D38;
    private static final int BAR_FILL = 0xFF55B9E8;

    private GreatWallLoadingProgressRenderer() {
    }

    public static void render(GuiGraphics graphics) {
        PrewarmProgress.Snapshot snapshot = PrewarmProgress.snapshot();
        if (!snapshot.active() || snapshot.total() <= 0) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int width = Math.min(CARD_WIDTH, Math.max(240, screenWidth - 40));
        int left = (screenWidth - width) / 2;
        int top = Math.max(26, screenHeight - CARD_HEIGHT - 28);
        int right = left + width;
        int bottom = top + CARD_HEIGHT;
        int innerLeft = left + 10;
        int innerWidth = width - 20;

        graphics.fill(left, top, right, bottom, CARD_BACKGROUND);
        graphics.fill(left, top, right, top + 1, CARD_BORDER);
        graphics.fill(left, bottom - 1, right, bottom, CARD_BORDER);
        graphics.fill(left, top, left + 1, bottom, CARD_BORDER);
        graphics.fill(right - 1, top, right, bottom, CARD_BORDER);

        Component title = Component.translatable("plaincraft.great_wall.loading_progress.title");
        graphics.drawString(minecraft.font, title, innerLeft, top + 9, 0xFFFFFFFF, false);

        // The root search happens before any segment exists, so it reports a stage rather
        // than a fraction - a 0/4 bar during a thirteen-minute search reads as a stall.
        Component progress = snapshot.rootSearch()
                ? Component.translatable("plaincraft.great_wall.loading_progress.searching")
                : Component.translatable("plaincraft.great_wall.loading_progress.summary",
                        snapshot.completed(), snapshot.total(), snapshot.percent());
        graphics.drawString(minecraft.font, progress, innerLeft, top + 23, 0xFFB7C0CC, false);
        drawBar(graphics, innerLeft, top + 35, innerWidth, snapshot.rootSearch() ? 0 : snapshot.percent());

        Component detail = Component.translatable("plaincraft.great_wall.loading_progress.detail",
                snapshot.lanes(), snapshot.elapsedSeconds());
        graphics.drawString(minecraft.font, detail, innerLeft, top + 49, 0xFF8D97A6, false);
    }

    private static void drawBar(GuiGraphics graphics, int x, int y, int width, int percent) {
        graphics.fill(x, y, x + width, y + 8, BAR_BACKGROUND);
        int filledWidth = Math.round(width * Math.max(0, Math.min(100, percent)) / 100.0F);
        if (filledWidth > 0) {
            graphics.fill(x, y, x + filledWidth, y + 8, BAR_FILL);
        }
    }
}
