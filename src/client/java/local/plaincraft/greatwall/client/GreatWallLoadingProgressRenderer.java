package local.plaincraft.greatwall.client;

import local.plaincraft.greatwall.lifecycle.PrewarmProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Draws the wall's startup prewarm as a card on the level loading screen.
 *
 * <h2>Why</h2>
 * The wall's prewarm is the longest single stage of a new world, and it was invisible - the
 * loading screen showed the rivers finish and then appeared to hang. This card makes the two
 * wall stages visible: choosing the root position, then prewarming segments, with a stage
 * fraction each and one overall bar.
 *
 * <h2>Shape</h2>
 * The same card geometry as the river patch's progress display, but taller: it shows the two
 * wall stages as separate lines, each with its own moving number, and one combined bar so the
 * player always sees a number going up and never reads a stall as a freeze.
 */
public final class GreatWallLoadingProgressRenderer {
    private static final int CARD_WIDTH = 360;
    private static final int CARD_HEIGHT = 88;
    private static final int CARD_BACKGROUND = 0xB010131A;
    private static final int CARD_BORDER = 0x80404B5A;
    private static final int BAR_BACKGROUND = 0x60242D38;
    private static final int BAR_FILL = 0xFF55B9E8;
    private static final int STAGE_ACTIVE = 0xFFB7C0CC;
    private static final int STAGE_PENDING = 0xFF6A7482;

    private GreatWallLoadingProgressRenderer() {
    }

    public static void render(GuiGraphics graphics) {
        PrewarmProgress.Snapshot snapshot = PrewarmProgress.snapshot();
        if (!snapshot.active()) return;

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
        graphics.drawString(minecraft.font, title, innerLeft, top + 7, 0xFFFFFFFF, false);

        // Stage 1: choosing the root position. Active while rootSearch, dimmed after. The
        // phase distinguishes coarse screening from the structure screen, which is the longest
        // invisible stretch and used to read as a freeze.
        Component stage1;
        if (snapshot.rootSearch()) {
            if (snapshot.rootPhase() == 4 && snapshot.structureTotal() > 0) {
                stage1 = Component.translatable("plaincraft.great_wall.loading_progress.structure_n",
                        snapshot.structureCandidate(), snapshot.structureTotal());
            } else if (snapshot.rootTotal() > 0) {
                stage1 = Component.translatable("plaincraft.great_wall.loading_progress.searching_n",
                        snapshot.rootCandidate(), snapshot.rootTotal(), snapshot.rootPercent());
            } else {
                stage1 = Component.translatable("plaincraft.great_wall.loading_progress.searching");
            }
        } else {
            stage1 = Component.translatable("plaincraft.great_wall.loading_progress.searching_done");
        }
        graphics.drawString(minecraft.font, stage1, innerLeft, top + 21,
                snapshot.rootSearch() ? STAGE_ACTIVE : STAGE_PENDING, false);

        // Stage 2: prewarming segments. Active once the root is chosen, pending before that.
        Component stage2;
        if (snapshot.rootSearch()) {
            stage2 = Component.translatable("plaincraft.great_wall.loading_progress.segments_pending");
        } else {
            stage2 = Component.translatable("plaincraft.great_wall.loading_progress.summary",
                    snapshot.completed(), snapshot.total(), snapshot.percent());
        }
        graphics.drawString(minecraft.font, stage2, innerLeft, top + 35,
                snapshot.rootSearch() ? STAGE_PENDING : STAGE_ACTIVE, false);

        // Combined bar: 30% root search + 70% segments.
        drawBar(graphics, innerLeft, top + 51, innerWidth, snapshot.overallPercent());

        Component detail = Component.translatable("plaincraft.great_wall.loading_progress.detail",
                snapshot.lanes(), snapshot.overallPercent(), snapshot.elapsedSeconds());
        graphics.drawString(minecraft.font, detail, innerLeft, top + 65, 0xFF8D97A6, false);
    }

    private static void drawBar(GuiGraphics graphics, int x, int y, int width, int percent) {
        graphics.fill(x, y, x + width, y + 8, BAR_BACKGROUND);
        int filledWidth = Math.round(width * Math.max(0, Math.min(100, percent)) / 100.0F);
        if (filledWidth > 0) {
            graphics.fill(x, y, x + filledWidth, y + 8, BAR_FILL);
        }
    }
}
