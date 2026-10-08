package local.plaincraft.greatwall.client.config;

import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.config.GreatWallConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The wall's player-facing options.
 *
 * <h2>What this screen is for</h2>
 * The wall's two real choices — how far it runs, and how much of it is ready
 * before play starts — used to be launcher JVM arguments. They are gameplay
 * decisions, so they belong here, reachable from Mod Menu and from the world
 * creation screen.
 *
 * <h2>Layout</h2>
 * A two-column grid: every label sits in the left column and its control in the
 * right, on the same row. An earlier revision drew the labels manually at
 * offsets derived from the control positions and the two overlapped. Vanilla's
 * button rows are 24 pixels apart, so the rows below use the same pitch.
 *
 * <h2>Why the widgets are plain</h2>
 * Only vanilla widgets are used. A config screen that fails to build must not
 * take the game down with it, and Cloth Config is not required.
 */
public final class GreatWallConfigScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int LABEL_WIDTH = 110;
    private static final int CONTROL_WIDTH = 180;
    private static final int COLUMN_GAP = 8;
    private static final int ROW_GAP = 6;
    private final Screen parent;
    private final GreatWallConfig draft;
    private EditBox lengthField;
    private EditBox prewarmField;
    private Button lengthModeButton;
    private Button fullPrewarmButton;
    private int left;
    private int top;

    public GreatWallConfigScreen(Screen parent) {
        super(Component.translatable("screen.plaincraft_great_wall.config"));
        this.parent = parent;
        this.draft = GreatWallConfig.get().copy();
    }

    @Override
    protected void init() {
        int totalWidth = LABEL_WIDTH + COLUMN_GAP + CONTROL_WIDTH;
        this.left = (this.width - totalWidth) / 2;
        // Six rows: four settings plus the summary and the two action buttons.
        int totalHeight = ROW_HEIGHT * 4 + ROW_GAP * 3 + 40;
        this.top = Math.max(32, (this.height - totalHeight) / 2);
        int controlX = left + LABEL_WIDTH + COLUMN_GAP;

        int row = top;
        this.lengthModeButton = addRenderableWidget(Button.builder(lengthModeLabel(), button -> {
            draft.setWallLengthBlocks(draft.bounded()
                    ? GreatWallConfig.UNLIMITED_LENGTH
                    : GreatWallConfig.FULL_PREWARM_MAX_LENGTH);
            button.setMessage(lengthModeLabel());
            // The mode change rewrites the length, so the field must follow it or the
            // screen would show a stale number next to the new mode.
            if (lengthField != null) lengthField.setValue(Long.toString(draft.wallLengthBlocks()));
            refreshEnabledStates();
        }).bounds(controlX, row, CONTROL_WIDTH, 20).build());
        row += ROW_HEIGHT + ROW_GAP;

        this.lengthField = new EditBox(this.font, controlX, row, CONTROL_WIDTH, 20,
                Component.translatable("option.plaincraft_great_wall.wallLength"));
        this.lengthField.setValue(Long.toString(draft.wallLengthBlocks()));
        this.lengthField.setResponder(text -> {
            Long parsed = parseLong(text);
            if (parsed != null) draft.setWallLengthBlocks(parsed);
        });
        addRenderableWidget(this.lengthField);
        row += ROW_HEIGHT + ROW_GAP;

        this.prewarmField = new EditBox(this.font, controlX, row, CONTROL_WIDTH, 20,
                Component.translatable("option.plaincraft_great_wall.prewarmSegments"));
        this.prewarmField.setValue(Integer.toString(draft.prewarmSegments()));
        this.prewarmField.setResponder(text -> {
            Integer parsed = parseInt(text);
            if (parsed != null) draft.setPrewarmSegments(parsed);
        });
        addRenderableWidget(this.prewarmField);
        row += ROW_HEIGHT + ROW_GAP;

        this.fullPrewarmButton = addRenderableWidget(Button.builder(fullPrewarmLabel(), button -> {
            draft.setPrewarmAllWhenBounded(!draft.prewarmAllWhenBounded());
            button.setMessage(fullPrewarmLabel());
        }).bounds(controlX, row, CONTROL_WIDTH, 20).build());
        row += ROW_HEIGHT + 12;

        int half = (totalWidth - COLUMN_GAP) / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> saveAndClose())
                .bounds(left, row, half, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(left + half + COLUMN_GAP, row, half, 20).build());

        refreshEnabledStates();
    }

    private void refreshEnabledStates() {
        // The numeric length is meaningless while the wall is unbounded, so it is
        // disabled rather than silently ignored.
        if (lengthField != null) lengthField.setEditable(draft.bounded());
        if (fullPrewarmButton != null) fullPrewarmButton.active = draft.bounded();
    }

    private void saveAndClose() {
        var live = GreatWallConfig.get();
        live.applyFrom(draft);
        GreatWallConfig.save();
        GreatWallMod.LOGGER.info("[GreatWall:config] wallLength={} prewarmSegments={} prewarmAllWhenBounded={} "
                        + "-> effectivePrewarmSegments={}",
                live.wallLengthBlocks(), live.prewarmSegments(), live.prewarmAllWhenBounded(),
                live.prewarmSegmentsTarget());
        onClose();
    }

    @Override
    public void onClose() {
        // Minecraft.getInstance() and the captured parent, because Screen.minecraft
        // is protected and remapped in production. An earlier revision read it
        // reflectively by its development name, which silently returned null there
        // and left the screen unable to close.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, top - 24, 0xFFFFFF);

        int controlX = left + LABEL_WIDTH + COLUMN_GAP;
        int row = top;
        // Labels are drawn in the left column, vertically centred against their own
        // row's control, which is what keeps text and widget from overlapping.
        drawLabel(graphics, "option.plaincraft_great_wall.lengthMode", row);
        row += ROW_HEIGHT + ROW_GAP;
        drawLabel(graphics, "option.plaincraft_great_wall.wallLength", row);
        row += ROW_HEIGHT + ROW_GAP;
        drawLabel(graphics, "option.plaincraft_great_wall.prewarmSegments", row);
        row += ROW_HEIGHT + ROW_GAP;
        drawLabel(graphics, "option.plaincraft_great_wall.fullPrewarm", row);

        int summaryY = top + (ROW_HEIGHT + ROW_GAP) * 4 + 2;
        var summary = Component.translatable("option.plaincraft_great_wall.summary",
                Long.toString(draft.wallLengthBlocks()),
                Long.toString(draft.configuredSegments()),
                Long.toString(draft.prewarmSegmentsTarget()));
        graphics.drawCenteredString(this.font, summary, this.width / 2, summaryY, 0x55FF55);
        graphics.drawCenteredString(this.font,
                Component.translatable("option.plaincraft_great_wall.bodyHint"),
                this.width / 2, summaryY + 14, 0x808080);
    }

    /** Draws a label in the left column, centred vertically against a control at {@code row}. */
    private void drawLabel(GuiGraphics graphics, String key, int row) {
        var text = Component.translatable(key);
        graphics.drawString(this.font, text, left, row + 6, 0xA0A0A0);
    }

    private Component lengthModeLabel() {
        return Component.translatable(draft.bounded()
                ? "option.plaincraft_great_wall.lengthMode.bounded"
                : "option.plaincraft_great_wall.lengthMode.unlimited");
    }

    private Component fullPrewarmLabel() {
        return Component.translatable(draft.prewarmAllWhenBounded()
                ? "option.plaincraft_great_wall.fullPrewarm.on"
                : "option.plaincraft_great_wall.fullPrewarm.off");
    }

    private static Long parseLong(String text) {
        try { return Long.parseLong(text.trim()); } catch (RuntimeException malformed) { return null; }
    }

    private static Integer parseInt(String text) {
        try { return Integer.parseInt(text.trim()); } catch (RuntimeException malformed) { return null; }
    }
}
