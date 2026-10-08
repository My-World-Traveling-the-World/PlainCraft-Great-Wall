package local.plaincraft.greatwall.client.config;

import java.util.ArrayList;
import java.util.List;
import local.plaincraft.greatwall.GreatWallMod;
import local.plaincraft.greatwall.config.GreatWallConfig;
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
 * <h2>Why the widgets are plain</h2>
 * The screen deliberately uses only vanilla widgets. A config screen that fails
 * to build must not take the game down with it, and every extra rendering
 * dependency is another way for that to happen; Cloth Config is not required.
 */
public final class GreatWallConfigScreen extends Screen {
    private static final String TITLE_KEY = "screen.plaincraft_great_wall.config";
    private final Screen parent;
    private final GreatWallConfig draft;
    private EditBox lengthField;
    private EditBox prewarmField;
    private Button lengthModeButton;
    private Button fullPrewarmButton;

    public GreatWallConfigScreen(Screen parent) {
        super(Component.translatable(TITLE_KEY));
        this.parent = parent;
        this.draft = GreatWallConfig.get().copy();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 4;

        this.lengthModeButton = Button.builder(lengthModeLabel(), button -> {
            draft.setWallLengthBlocks(draft.bounded() ? GreatWallConfig.UNLIMITED_LENGTH : GreatWallConfig.FULL_PREWARM_MAX_LENGTH);
            button.setMessage(lengthModeLabel());
            refreshEnabledStates();
        }).bounds(centerX - 150, y, 300, 20).build();
        addRenderableWidget(this.lengthModeButton);
        y += 24;

        this.lengthField = new EditBox(this.font, centerX - 150, y, 300, 20,
                Component.translatable("option.plaincraft_great_wall.wallLength"));
        this.lengthField.setValue(Long.toString(draft.wallLengthBlocks()));
        this.lengthField.setResponder(text -> {
            Long parsed = parseLong(text);
            if (parsed != null) draft.setWallLengthBlocks(parsed);
            refreshEnabledStates();
        });
        addRenderableWidget(this.lengthField);
        y += 24;

        this.prewarmField = new EditBox(this.font, centerX - 150, y, 300, 20,
                Component.translatable("option.plaincraft_great_wall.prewarmSegments"));
        this.prewarmField.setValue(Integer.toString(draft.prewarmSegments()));
        this.prewarmField.setResponder(text -> {
            Integer parsed = parseInt(text);
            if (parsed != null) draft.setPrewarmSegments(parsed);
        });
        addRenderableWidget(this.prewarmField);
        y += 24;

        this.fullPrewarmButton = Button.builder(fullPrewarmLabel(), button -> {
            draft.setPrewarmAllWhenBounded(!draft.prewarmAllWhenBounded());
            button.setMessage(fullPrewarmLabel());
        }).bounds(centerX - 150, y, 300, 20).build();
        addRenderableWidget(this.fullPrewarmButton);
        y += 34;

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> saveAndClose())
                .bounds(centerX - 154, y, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(centerX + 4, y, 150, 20).build());

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
        // `Screen.minecraft` is protected, and a mixin accessor cannot be cast from
        // an arbitrary subclass, so the field is read through a tiny accessor mixin
        // applied to Screen itself (see ScreenAccessor) via the static helper below.
        var minecraft = local.plaincraft.greatwall.client.config.ScreenAccess.greatwall$minecraft(this);
        if (minecraft != null) minecraft.setScreen(parent);
    }

    /** The parent screen to return to; exposed so the world-creation button can reuse this screen. */
    public Screen parent() { return parent; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4 - 32, 0xFFFFFF);
        int centerX = this.width / 2;
        int y = this.height / 4;
        // Labels and the derived summary sit between the widgets; the summary in
        // particular tells the operator what the two independent settings combine
        // into, which is not obvious from either field alone.
        graphics.drawString(this.font, Component.translatable("option.plaincraft_great_wall.lengthMode"),
                centerX - 150, y - 11, 0xA0A0A0);
        graphics.drawString(this.font, Component.translatable("option.plaincraft_great_wall.wallLength"),
                centerX - 150, y + 13, 0xA0A0A0);
        graphics.drawString(this.font, Component.translatable("option.plaincraft_great_wall.prewarmSegments"),
                centerX - 150, y + 37, 0xA0A0A0);
        graphics.drawString(this.font, Component.translatable("option.plaincraft_great_wall.fullPrewarm"),
                centerX - 150, y + 61, 0xA0A0A0);
        var summary = Component.translatable("option.plaincraft_great_wall.summary",
                Long.toString(draft.wallLengthBlocks()),
                Long.toString(draft.configuredSegments()),
                Long.toString(draft.prewarmSegmentsTarget()));
        graphics.drawCenteredString(this.font, summary, centerX, y + 96, 0x55FF55);
        graphics.drawCenteredString(this.font,
                Component.translatable("option.plaincraft_great_wall.bodyHint"), centerX, y + 112, 0x808080);
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

    /** Unused today; kept so a future layout pass has the widget list in one place. */
    static List<String> optionKeys() {
        var keys = new ArrayList<String>();
        keys.add("option.plaincraft_great_wall.lengthMode");
        keys.add("option.plaincraft_great_wall.wallLength");
        keys.add("option.plaincraft_great_wall.prewarmSegments");
        keys.add("option.plaincraft_great_wall.fullPrewarm");
        return List.copyOf(keys);
    }
}
