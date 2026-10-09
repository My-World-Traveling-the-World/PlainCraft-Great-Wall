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
 * The wall's one real choice — how far it runs — used to be a launcher JVM
 * argument. It is a gameplay decision, so it belongs here, reachable from Mod Menu
 * and from the world creation screen.
 *
 * <h2>One setting, two rows</h2>
 * Only the length is entered. The prewarm segment count is shown beside it but is
 * derived from that length, because the two used to be independent inputs and
 * could disagree: a length of 8189 blocks with a prewarm count of 4 prewarmed 4 of
 * the 5 segments the wall would build, which is not what either number says on its
 * own. Deriving it means the pair can never disagree.
 *
 * <h2>Layout</h2>
 * Every row is positioned from one running {@code y}, and the same value is stored
 * in fields so {@link #render} draws each label against the control it belongs to.
 * An earlier revision recomputed the label rows in {@code render} with a different
 * formula than {@code init} used, and the two drifted.
 */
public final class GreatWallConfigScreen extends Screen {
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_STEP = 26;
    private static final int LABEL_WIDTH = 110;
    private static final int CONTROL_WIDTH = 180;
    private static final int COLUMN_GAP = 8;
    /** Space between the last setting and the summary, and between summary and buttons. */
    private static final int SECTION_GAP = 16;
    private static final int SUMMARY_BLOCK = 28;
    private static final int BUTTON_HEIGHT = 20;
    private static final int LABEL_COLOUR = 0xFFFFFF;
    private static final int DERIVED_COLOUR = 0xA0A0A0;
    private static final int SUMMARY_COLOUR = 0x55FF55;
    private static final int HINT_COLOUR = 0xA0A0A0;
    private final Screen parent;
    private final GreatWallConfig draft;
    private EditBox lengthField;
    private Button lengthModeButton;
    private int left;
    private int top;
    /** Top of each setting row, filled by {@link #init} and read by {@link #render}. */
    private final int[] settingRows = new int[3];
    private int summaryY;
    private int buttonY;

    public GreatWallConfigScreen(Screen parent) {
        super(Component.translatable("screen.plaincraft_great_wall.config"));
        this.parent = parent;
        this.draft = GreatWallConfig.get().copy();
    }

    @Override
    protected void init() {
        int totalWidth = LABEL_WIDTH + COLUMN_GAP + CONTROL_WIDTH;
        this.left = (this.width - totalWidth) / 2;
        int totalHeight = ROW_STEP * settingRows.length + SECTION_GAP + SUMMARY_BLOCK
                + SECTION_GAP + BUTTON_HEIGHT;
        this.top = Math.max(28, (this.height - totalHeight) / 2);
        int controlX = left + LABEL_WIDTH + COLUMN_GAP;

        for (int i = 0; i < settingRows.length; i++) settingRows[i] = top + i * ROW_STEP;

        this.lengthModeButton = addRenderableWidget(Button.builder(lengthModeLabel(), button -> {
            draft.setWallLengthBlocks(draft.bounded()
                    ? GreatWallConfig.UNLIMITED_LENGTH
                    : GreatWallConfig.FULL_PREWARM_MAX_LENGTH);
            button.setMessage(lengthModeLabel());
            // The mode change rewrites the length, so the field must follow it or the
            // screen would show a stale number next to the new mode.
            if (lengthField != null) lengthField.setValue(Long.toString(draft.wallLengthBlocks()));
            refreshEnabledStates();
        }).bounds(controlX, settingRows[0], CONTROL_WIDTH, ROW_HEIGHT).build());

        this.lengthField = new EditBox(this.font, controlX, settingRows[1], CONTROL_WIDTH, ROW_HEIGHT,
                Component.translatable("option.plaincraft_great_wall.wallLength"));
        this.lengthField.setValue(Long.toString(draft.wallLengthBlocks()));
        this.lengthField.setResponder(text -> {
            Long parsed = parseLong(text);
            if (parsed != null) draft.setWallLengthBlocks(parsed);
        });
        addRenderableWidget(this.lengthField);

        // Row 2 is the derived prewarm count: a label and a value, both drawn in
        // render(), with no widget because there is nothing to edit.

        this.summaryY = top + ROW_STEP * settingRows.length + SECTION_GAP;
        this.buttonY = summaryY + SUMMARY_BLOCK + SECTION_GAP;

        int half = (totalWidth - COLUMN_GAP) / 2;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> saveAndClose())
                .bounds(left, buttonY, half, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(left + half + COLUMN_GAP, buttonY, half, BUTTON_HEIGHT).build());

        refreshEnabledStates();
    }

    private void refreshEnabledStates() {
        // The numeric length is meaningless while the wall is unbounded, so it is
        // disabled rather than silently ignored.
        if (lengthField != null) lengthField.setEditable(draft.bounded());
    }

    private void saveAndClose() {
        var live = GreatWallConfig.get();
        live.applyFrom(draft);
        GreatWallConfig.save();
        GreatWallMod.LOGGER.info("[GreatWall:config] wallLength={} -> configuredSegments={} "
                        + "effectivePrewarmSegments={}",
                live.wallLengthBlocks(), live.configuredSegments(), live.prewarmSegmentsTarget());
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
        graphics.drawCenteredString(this.font, this.title, this.width / 2, top - 20, LABEL_COLOUR);

        // Positions come from the fields init() filled, so a label can never be drawn
        // against a different row than its control.
        drawLabel(graphics, "option.plaincraft_great_wall.lengthMode", settingRows[0], LABEL_COLOUR);
        drawLabel(graphics, "option.plaincraft_great_wall.wallLength", settingRows[1], LABEL_COLOUR);
        drawLabel(graphics, "option.plaincraft_great_wall.prewarmSegments", settingRows[2], LABEL_COLOUR);

        int controlX = left + LABEL_WIDTH + COLUMN_GAP;
        Long segments = draft.bounded() ? draft.configuredSegments() : null;
        var derived = segments == null
                ? Component.translatable("option.plaincraft_great_wall.prewarmSegments.unlimited",
                        Long.toString(draft.prewarmSegmentsTarget()))
                : Component.translatable("option.plaincraft_great_wall.prewarmSegments.value",
                        Long.toString(segments), Long.toString(draft.prewarmSegmentsTarget()));
        graphics.drawString(this.font, derived, controlX,
                settingRows[2] + (ROW_HEIGHT - this.font.lineHeight) / 2, DERIVED_COLOUR);

        graphics.drawCenteredString(this.font,
                Component.translatable("option.plaincraft_great_wall.bodyHint"),
                this.width / 2, summaryY, HINT_COLOUR);
        graphics.drawCenteredString(this.font,
                Component.translatable("option.plaincraft_great_wall.bodyHint2"),
                this.width / 2, summaryY + 12, HINT_COLOUR);
    }

    /** Draws a label in the left column, centred against a control at {@code rowY}. */
    private void drawLabel(GuiGraphics graphics, String key, int rowY, int colour) {
        // Centred on the control's own height rather than offset by a constant, so the
        // text stays aligned whatever ROW_HEIGHT becomes.
        int textY = rowY + (ROW_HEIGHT - this.font.lineHeight) / 2;
        graphics.drawString(this.font, Component.translatable(key), left, textY, colour);
    }

    private Component lengthModeLabel() {
        return Component.translatable(draft.bounded()
                ? "option.plaincraft_great_wall.lengthMode.bounded"
                : "option.plaincraft_great_wall.lengthMode.unlimited");
    }

    private static Long parseLong(String text) {
        try { return Long.parseLong(text.trim()); } catch (RuntimeException malformed) { return null; }
    }
}
