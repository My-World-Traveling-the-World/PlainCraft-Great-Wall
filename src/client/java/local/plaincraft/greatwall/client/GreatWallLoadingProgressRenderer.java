package local.plaincraft.greatwall.client;

import local.plaincraft.greatwall.lifecycle.PrewarmProgress;
import local.plaincraft.greatwall.lifecycle.PrewarmUiModel;
import local.plaincraft.greatwall.lifecycle.LoadingCardLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Draws the wall's startup prewarm as a collapsible card on the level loading screen.
 *
 * <h2>Shape</h2>
 * The card shares its frame with the road and river cards so the three startup stages read as
 * one family: a theme dot and title, a line-art band that lights left-to-right with the overall
 * fraction, a stepper of the wall's large stages, a live status line, and the detail area.
 *
 * <h2>Details are always shown</h2>
 * The wall's startup has six stages, each with its own count. An earlier version hid them behind a
 * click, which turned out worse than the wall of text it avoided: a hidden card reads as a freeze,
 * and the click never worked anyway - the hook injected into an inherited method with an explicit
 * descriptor, which Mixin cannot resolve, and it was declared with {@code require = 0} so it failed
 * silently. Every stage row is therefore drawn unconditionally, and the card has no click behaviour
 * at all: no toggle, no collapse state, and no mixin forwarding clicks.
 */
public final class GreatWallLoadingProgressRenderer {
    private static final int PADDING = 10;
    private static final int LINE = 12;
    private static final int CARD_BACKGROUND = 0xFF10131A;
    private static final int CARD_BORDER = 0xFF404B5A;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFB7C0CC;
    private static final int TEXT_MUTED = 0xFF8D97A6;
    private static final int THEME = 0xFFE8B84C;          // gold
    private static final int THEME_DIM = 0xFF7A6430;      // unlit line-art
    private static final int STEP_DONE = 0xFF7FD07F;      // green check
    private static final int STEP_PENDING = 0xFF5A6472;   // grey hollow
    // Opaque on purpose (E6): the vanilla loading screen draws its own centred percentage, and a
    // translucent band let that text bleed through the artwork - it read as our card reporting a
    // second, contradictory number.
    private static final int BAND_BG = 0xFF101622;
    private static final int RIDGE_FAR = 0xFF141920;      // farthest mountain layer
    private static final int RIDGE_MID = 0xFF182029;      // middle mountain layer
    private static final int RIDGE = 0xFF1C222E;          // nearest mountain silhouette
    private static final int FLAG = 0xFFD98A3C;           // watchtower pennant
    private static final int WALL_DARK = 0xFF8A6E28;      // shaded wall side, for depth
    private static final int OPENING = 0xFF10141C;        // watchtower door / window
    private static final int TORCH_LIT = 0xFFFFD77A;
    private static final int TORCH_HOT = 0xFFFFF3C0;
    private static final int TORCH_GLOW = 0x3CFFB450;     // halo around the flame

    private GreatWallLoadingProgressRenderer() {
    }

    public static void render(GuiGraphics graphics) {
        PrewarmProgress.Snapshot snapshot = PrewarmProgress.snapshot();
        if (!snapshot.active() && !snapshot.failed()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        Font font = minecraft.font;

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        // 所有阶段详情保持可见；小屏先缩短装饰，不裁掉末行。
        LoadingCardLayout layout = LoadingCardLayout.fit(screenWidth, screenHeight, 280, detailLineCount(snapshot));
        int width = layout.width();
        int innerWidth = width - PADDING * 2;
        int height = layout.height();
        graphics.pose().pushPose();
        graphics.pose().translate(layout.x(), layout.y(), 0);
        graphics.pose().scale(layout.scale(), layout.scale(), 1F);
        int left = 0;
        int top = 0;
        int right = left + width;
        int bottom = top + height;
        int x = left + PADDING;
        int y = top + PADDING;

        graphics.fill(left, top, right, bottom, CARD_BACKGROUND);
        graphics.fill(left, top, right, top + 1, CARD_BORDER);
        graphics.fill(left, bottom - 1, right, bottom, CARD_BORDER);
        graphics.fill(left, top, left + 1, bottom, CARD_BORDER);
        graphics.fill(right - 1, top, right, bottom, CARD_BORDER);

        // Title row: theme dot, title, current stage, overall percent.
        drawDot(graphics, x + 3, y + 4, 3, THEME);
        Component title = Component.translatable("plaincraft.great_wall.loading_progress.title");
        graphics.drawString(font, title, x + 10, y, TEXT_PRIMARY, false);
        String pct = snapshot.overallPercent() + "%";
        graphics.drawString(font, pct, right - PADDING - font.width(pct), y, THEME, false);
        Component stageName = stageName(snapshot);
        // 主阶段文案可能较长，限定在标题与百分比之间，避免小屏文字相互覆盖。
        int stageX = x + 10 + font.width(title) + 8;
        drawFitted(graphics, font, stageName, stageX, y,
                Math.max(0, right - PADDING - font.width(pct) - 6 - stageX), TEXT_SECONDARY);

        y += LINE;
        drawBand(graphics, x, y, innerWidth, layout.bandHeight(), snapshot);

        y += layout.bandHeight() + 6;
        drawStepper(graphics, font, x, y, innerWidth, snapshot);

        y += LINE + 4;
        Component status = statusLine(snapshot);
        drawFitted(graphics, font, status, x, y, innerWidth, TEXT_SECONDARY);

        y += LINE;
        Component eta = etaLine(snapshot);
        drawFitted(graphics, font, eta, x, y, innerWidth, TEXT_MUTED);

        y += LINE + 4;
        drawDetail(graphics, font, snapshot, x, y, innerWidth);
        graphics.pose().popPose();
    }

    // ---------------------------------------------------------------------------------------------
    // Line-art band: a winding wall with two watchtowers, lit left-to-right by the overall
    // fraction, with a construction torch flickering at the lit frontier.
    // ---------------------------------------------------------------------------------------------

    private static void drawBand(GuiGraphics graphics, int x, int y, int width, int height,
                                 PrewarmProgress.Snapshot snapshot) {
        graphics.fill(x, y, x + width, y + height, BAND_BG);
        int litWidth = Math.round(width * snapshot.overallPercent() / 100.0F);
        // Three receding mountain layers give the scene depth; the wall rides the nearest ridge.
        drawMountainLayer(graphics, x, y, width, height, 42, 3.0, 7.0, RIDGE_FAR);
        drawMountainLayer(graphics, x, y, width, height, 27, 4.0, 9.0, RIDGE_MID);
        drawMountainLayer(graphics, x, y, width, height, 15, 3.0, 7.0, RIDGE);
        // Clip the lit pass to the lit width by drawing the wall twice: once dim in full, once
        // bright only over the lit prefix.
        drawWall(graphics, x, y, width, height, 0, width, THEME_DIM, false);
        drawWall(graphics, x, y, width, height, 0, litWidth, THEME, true);
        drawTorch(graphics, x + Math.min(litWidth, width - 6), y, height, snapshot);
    }

    /** One mountain silhouette layer. {@code lift} is how far above the band bottom the layer's
     *  lowest point sits; the two frequencies give it a jagged spine. */
    private static void drawMountainLayer(GuiGraphics graphics, int x, int y, int width, int height,
                                          int lift, double f1, double f2, int color) {
        int bottom = y + height;
        for (int i = 0; i < width; i++) {
            double t = i / (double) Math.max(1, width);
            int top = bottom - lift - (int) Math.round(
                    14 * Math.abs(Math.sin(t * Math.PI * f1)) + 8 * Math.abs(Math.sin(t * Math.PI * f2)));
            graphics.fill(x + i, top, x + i + 1, bottom, color);
        }
    }

    /**
     * Draws the wall over the x-range [{@code from}, {@code to}] of the band. The wall is a
     * castellated body with a shaded base, two watchtowers with doors and windows, and flags that
     * appear only once the lit prefix reaches them. Drawing per-column inside the range is how the
     * left-to-right lighting is done without a scissor.
     */
    private static void drawWall(GuiGraphics graphics, int x, int y, int width, int height,
                                 int from, int to, int color, boolean lit) {
        if (to <= from) return;
        int base0 = 15;
        int bodyA = 3, bodyB = 5;
        int crenel = 2;
        int towerRise = 13, roofRise = 2;
        int baseline = y + height - base0;
        int shaded = lit ? WALL_DARK : THEME_DIM;
        for (int i = Math.max(0, from); i < Math.min(width, to); i++) {
            int ridge = baseline - ridgeHeight(i, width);
            // Wall body: a thicker solid body (top line, mid tone, shaded base) so it reads as masonry.
            graphics.fill(x + i, ridge, x + i + 1, ridge + bodyA, color);
            graphics.fill(x + i, ridge + bodyA, x + i + 1, ridge + bodyB, shaded);
            // Crenellations every third column, above the ridge line.
            if ((i % 3) == 0) {
                graphics.fill(x + i, ridge - crenel, x + i + 1, ridge, color);
            }
            // Watchtowers at a third and two thirds across: a tall body with a door and windows.
            if (isTower(i, width)) {
                int towerTop = ridge - towerRise;
                graphics.fill(x + i, towerTop, x + i + 1, ridge, color);
                if (isTowerRoof(i, width)) {
                    graphics.fill(x + i, towerTop - roofRise, x + i + 1, towerTop, color);
                }
            }
        }
        // Tower openings and flags, drawn once per tower on the lit pass only.
        if (lit) {
            int t1 = width / 3;
            int t2 = (width * 2) / 3;
            drawTowerDetail(graphics, x + t1, baseline - ridgeHeight(t1, width));
            drawTowerDetail(graphics, x + t2, baseline - ridgeHeight(t2, width));
        }
    }

    /** Door, windows and flag on one watchtower. {@code ridge} is the wall-top y at the tower. */
    private static void drawTowerDetail(GuiGraphics graphics, int cx, int ridge) {
        // Door: a tall arched notch at the tower base.
        graphics.fill(cx - 1, ridge - 4, cx + 2, ridge, OPENING);
        graphics.fill(cx, ridge - 5, cx + 1, ridge - 4, OPENING);
        // Windows: two small slots higher up.
        graphics.fill(cx - 1, ridge - 9, cx, ridge - 7, OPENING);
        graphics.fill(cx + 1, ridge - 9, cx + 2, ridge - 7, OPENING);
        // Flag: a pole with a pennant flying right.
        int poleTop = ridge - 20;
        graphics.fill(cx, poleTop, cx + 1, ridge - 13, FLAG);
        graphics.fill(cx + 1, poleTop, cx + 5, poleTop + 2, FLAG);
    }

    private static int ridgeHeight(int i, int width) {
        // Two overlapping sines give a natural winding profile.
        double t = i / (double) Math.max(1, width);
        return 10 + (int) Math.round(8 * Math.sin(t * Math.PI * 2.0) + 5 * Math.sin(t * Math.PI * 5.0));
    }

    private static boolean isTower(int i, int width) {
        int t1 = width / 3;
        int t2 = (width * 2) / 3;
        return (Math.abs(i - t1) <= 4) || (Math.abs(i - t2) <= 4);
    }

    private static boolean isTowerRoof(int i, int width) {
        int t1 = width / 3;
        int t2 = (width * 2) / 3;
        return (Math.abs(i - t1) <= 6) || (Math.abs(i - t2) <= 6);
    }

    /** The construction torch at the lit frontier: a flickering point that marks "work in progress",
     *  planted on the wall top at the lit frontier. */
    private static void drawTorch(GuiGraphics graphics, int cx, int y, int height,
                                  PrewarmProgress.Snapshot snapshot) {
        // Flicker from the wall-clock: fast enough to read as a flame, slow enough to not strobe.
        long t = System.currentTimeMillis() / 90L;
        boolean hot = (t % 2L) == 0L;
        int flame = hot ? TORCH_HOT : TORCH_LIT;
        // Sit on the wall top at the frontier column, a couple of pixels above it.
        int baseline = y + height - 15;
        int wallTop = baseline - ridgeHeight(Math.max(0, cx), 1) - 5;
        int fx = cx;
        int fy = wallTop + (int) (t % 2L);
        // Soft halo behind the flame so it reads as a light source, not just a bright pixel.
        graphics.fill(fx - 4, fy - 4, fx + 6, fy + 6, TORCH_GLOW);
        graphics.fill(fx, fy, fx + 3, fy + 3, flame);
        if (hot) {
            graphics.fill(fx, fy - 2, fx + 2, fy, flame);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Stepper: one node per large stage, connected by a line. Done = green check, active = pulsing
    // gold ring, pending = grey hollow.
    // ---------------------------------------------------------------------------------------------

    private static void drawStepper(GuiGraphics graphics, Font font, int x, int y, int width,
                                    PrewarmProgress.Snapshot snapshot) {
        var stages = PrewarmUiModel.stages();
        int count = stages.size();
        int step = count > 1 ? width / (count - 1) : width;
        // Connecting line first, so the nodes draw over it.
        int lineY = y + 5;
        graphics.fill(x, lineY, x + width, lineY + 1, STEP_PENDING);
        int litX = x + Math.round(width * snapshot.overallPercent() / 100.0F);
        graphics.fill(x, lineY, Math.min(x + width, litX), lineY + 1, THEME);
        for (int i = 0; i < count; i++) {
            int cx = x + i * step;
            if (PrewarmUiModel.complete(stages.get(i), snapshot)) {
                drawCheck(graphics, cx, lineY, STEP_DONE);
            } else if (PrewarmUiModel.running(stages.get(i), snapshot)) {
                drawPulse(graphics, cx, lineY, THEME);
            } else {
                drawHollow(graphics, cx, lineY, STEP_PENDING);
            }
        }
    }

    private static void drawCheck(GuiGraphics graphics, int cx, int cy, int color) {
        // A small green disc with a darker check notch.
        drawDot(graphics, cx, cy, 4, color);
        graphics.fill(cx - 1, cy, cx, cy + 2, 0xFF0E2A14);
        graphics.fill(cx, cy + 1, cx + 2, cy - 1, 0xFF0E2A14);
    }

    private static void drawPulse(GuiGraphics graphics, int cx, int cy, int color) {
        // Pulsing ring: the outline breathes between gold and bright gold.
        long t = System.currentTimeMillis() / 240L;
        int ring = (t % 2L) == 0L ? color : TORCH_HOT;
        drawRing(graphics, cx, cy, 4, ring);
        drawDot(graphics, cx, cy, 2, color);
    }

    private static void drawHollow(GuiGraphics graphics, int cx, int cy, int color) {
        drawRing(graphics, cx, cy, 3, color);
    }

    // ---------------------------------------------------------------------------------------------
    // Text lines.
    // ---------------------------------------------------------------------------------------------

    private static Component stageName(PrewarmProgress.Snapshot snapshot) {
        if (snapshot.failed()) return Component.translatable("plaincraft.great_wall.loading_progress.failed");
        return Component.translatable("plaincraft.great_wall.loading_progress.stage."
                + snapshot.stage().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Component statusLine(PrewarmProgress.Snapshot snapshot) {
        if (snapshot.failed()) return Component.translatable("plaincraft.great_wall.loading_progress.failed");
        return switch (snapshot.stage()) {
            case ROOT_SEARCH -> {
                if (snapshot.rootPhase() == 4 && snapshot.structureTotal() > 0) {
                    yield Component.translatable("plaincraft.great_wall.loading_progress.status_structure",
                            snapshot.structureCandidate(), snapshot.structureTotal());
                }
                if (snapshot.rootTotal() > 0) {
                    yield Component.translatable("plaincraft.great_wall.loading_progress.status_root",
                            snapshot.rootCandidate(), snapshot.rootTotal());
                }
                yield Component.translatable("plaincraft.great_wall.loading_progress.status_root_unknown");
            }
            case STRUCTURE_SCAN -> snapshot.subTotal() > 0
                    ? Component.translatable("plaincraft.great_wall.loading_progress.status_scan",
                            snapshot.subDone(), snapshot.subTotal())
                    : Component.translatable("plaincraft.great_wall.loading_progress.status_scan_unknown");
            case FINE_SAMPLE -> {
                int sampleDone = snapshot.subDone();
                int sampleTotal = snapshot.subTotal();
                // The fine-sample total is a column estimate that starts at 1 and only grows
                // while slices are enqueued; a cache-hit session answers every column without
                // enqueueing, so the total stays 1 while done climbs. A total that is still 1
                // (or 0) with a real done count is therefore untrustworthy: show the running
                // column count alone instead of a nonsense "29385/1" fraction.
                if (sampleTotal <= 1 && sampleDone > 1) {
                    yield Component.translatable(
                            "plaincraft.great_wall.loading_progress.status_sample_indeterminate",
                            sampleDone);
                }
                yield sampleTotal > 0
                        ? Component.translatable("plaincraft.great_wall.loading_progress.status_sample",
                                sampleDone, sampleTotal)
                        : Component.translatable("plaincraft.great_wall.loading_progress.status_sample_unknown");
            }
            case PREWARM -> snapshot.total() > 0
                    ? Component.translatable("plaincraft.great_wall.loading_progress.status_prewarm",
                            snapshot.completed(), snapshot.total())
                    : Component.translatable("plaincraft.great_wall.loading_progress.status_prewarm_unknown");
            case COMPILE, BACKFILL -> snapshot.subTotal() > 0
                    ? Component.translatable("plaincraft.great_wall.loading_progress.status_compile",
                            snapshot.subDone(), snapshot.subTotal())
                    : Component.translatable("plaincraft.great_wall.loading_progress.status_compile_unknown");
            case DONE -> Component.translatable("plaincraft.great_wall.loading_progress.status_done");
        };
    }

    private static Component etaLine(PrewarmProgress.Snapshot snapshot) {
        // 只显示已用时间：早期阶段没有可靠的剩余估算，显示 "--" 或跳动的时间反而让人以为卡了。
        // 走 formatDuration 而不是裸秒数：elapsedSeconds() 带小数，直接打印会得到「已用 4.1」这种缺单位读数。
        return Component.translatable("plaincraft.great_wall.loading_progress.elapsed_only",
                formatDuration(snapshot.elapsedMillis()));
    }

    private static int detailLineCount(PrewarmProgress.Snapshot snapshot) {
        // One line per stage, plus the lanes/summary line.
        return PrewarmUiModel.stages().size() + 1;
    }

    private static int drawDetail(GuiGraphics graphics, Font font, PrewarmProgress.Snapshot snapshot,
                                  int x, int y, int width) {
        var stages = PrewarmUiModel.stages();
        for (int i = 0; i < stages.size(); i++) {
            Component line = detailLine(stages.get(i), snapshot);
            int color = PrewarmUiModel.complete(stages.get(i), snapshot) ? STEP_DONE
                    : (PrewarmUiModel.running(stages.get(i), snapshot) ? TEXT_SECONDARY : TEXT_MUTED);
            drawFitted(graphics, font, line, x, y, width, color);
            y += LINE;
        }
        Component lanes = Component.translatable("plaincraft.great_wall.loading_progress.detail_lanes",
                snapshot.lanes(), snapshot.overallPercent());
        drawFitted(graphics, font, lanes, x, y, width, TEXT_MUTED);
        return y + LINE;
    }

    /**
     * The active stage's own progress line. The fine sample additionally reports its rate and a
     * remaining-time estimate, because "how many columns are left" says nothing about whether the
     * stage is moving at all - which is exactly how a real session sat on 0/48 for 27 minutes.
     */
    private static Component progressLine(PrewarmProgress.Stage stage, String key,
                                          PrewarmProgress.Snapshot snapshot) {
        return progressLine(stage, key, snapshot, snapshot.subDone(), snapshot.subTotal());
    }

    private static Component progressLine(PrewarmProgress.Stage stage, String key,
                                          PrewarmProgress.Snapshot snapshot, int done, int total) {
        if (total <= 0) return Component.translatable(key + "_unknown");
        // Same untrustworthy-total guard as the status line: the fine-sample total starts at 1
        // and does not grow on a cache-hit session, so a total of 1 with a real done count must
        // not render as "done/1". Show the running column count alone.
        if (stage == PrewarmProgress.Stage.FINE_SAMPLE && total <= 1 && done > 1) {
            return Component.translatable(key + "_indeterminate", done);
        }
        long elapsedMs = snapshot.stageElapsedMillis(stage);
        // The two long stages report a rate and a remaining-time estimate: "how many are left" says
        // nothing about whether the stage is moving at all, which is how a real session sat on 0/48 for
        // 27 minutes. ROOT_SEARCH and COMPILE keep the plain form - they are short, and a rate read off
        // a couple of hundred operations is noise.
        boolean rateWorthy = stage == PrewarmProgress.Stage.FINE_SAMPLE
                || stage == PrewarmProgress.Stage.STRUCTURE_SCAN;
        if (!rateWorthy || done <= 0 || total <= done || elapsedMs < 1000L) {
            return Component.translatable(key, done, total);
        }
        // 每秒不足一列也是有效速度，不能强行抬成一列/秒并缩短ETA。
        double rate = done * 1000.0 / elapsedMs;
        long etaMs = (long) Math.ceil((total - done) * 1000.0 / rate);
        String rateKey = stage == PrewarmProgress.Stage.FINE_SAMPLE
                ? "plaincraft.great_wall.loading_progress.detail.fine_sample_rate"
                : "plaincraft.great_wall.loading_progress.detail.structure_scan_rate";
        return Component.translatable(rateKey, done, total, String.format(java.util.Locale.ROOT, "%.1f", rate), formatDuration(etaMs));
    }

    private static Component detailLine(PrewarmProgress.Stage stage, PrewarmProgress.Snapshot snapshot) {
        String key = "plaincraft.great_wall.loading_progress.detail."
                + stage.name().toLowerCase(java.util.Locale.ROOT);
        return switch (stage) {
            case ROOT_SEARCH -> snapshot.rootTotal() > 0
                    ? Component.translatable(key, snapshot.rootCandidate(), snapshot.rootTotal())
                    : Component.translatable(key + "_unknown");
            // These three stages share ONE sub-progress pair in PrewarmProgress, owned by whichever of
            // them is running. Printing it from a stage that is not active made three rows show the
            // same numbers at once, which reads as three stages running in parallel - and it did, on a
            // real session, where 扫描结构 / 采样地形 / 编译区块 all read 3256/13085.
            // Each of these stages keeps its own counter now, so a stage that is running shows its own
            // number even while another one runs beside it. The previous shape printed the single
            // shared pair from whichever stage was current, which is why three rows once all read
            // 3256/13085 and why the fix at the time was to hide two of them.
            case STRUCTURE_SCAN, FINE_SAMPLE, COMPILE -> {
                int[] own = snapshot.subFor(stage);
                if (own[1] > 0) yield progressLine(stage, key, snapshot, own[0], own[1]);
                yield Component.translatable(stage.ordinal() < snapshot.stage().ordinal()
                        ? "plaincraft.great_wall.loading_progress.detail_done"
                        : "plaincraft.great_wall.loading_progress.detail_pending");
            }
            case PREWARM -> snapshot.total() > 0
                    ? Component.translatable(key, snapshot.completed(), snapshot.total())
                    : Component.translatable(key + "_unknown");
            // 补建发生在进入世界以后，永远不能在开局卡片上画成完成。
            case BACKFILL -> Component.translatable("plaincraft.great_wall.loading_progress.detail_pending");
            case DONE -> Component.translatable(key);
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Small drawing helpers.
    // ---------------------------------------------------------------------------------------------

    private static void drawDot(GuiGraphics graphics, int cx, int cy, int radius, int color) {
        graphics.fill(cx - radius, cy - radius + 1, cx + radius, cy + radius - 1, color);
        graphics.fill(cx - radius + 1, cy - radius, cx + radius - 1, cy + radius, color);
    }

    private static void drawRing(GuiGraphics graphics, int cx, int cy, int radius, int color) {
        graphics.fill(cx - radius, cy - radius + 1, cx - radius + 1, cy + radius - 1, color);
        graphics.fill(cx + radius - 1, cy - radius + 1, cx + radius, cy + radius - 1, color);
        graphics.fill(cx - radius + 1, cy - radius, cx + radius - 1, cy - radius + 1, color);
        graphics.fill(cx - radius + 1, cy + radius - 1, cx + radius - 1, cy + radius, color);
    }

    private static void drawFitted(GuiGraphics graphics, Font font, Component text,
                                   int x, int y, int maxWidth, int color) {
        String raw = text.getString();
        if (font.width(raw) <= maxWidth) {
            graphics.drawString(font, raw, x, y, color, false);
            return;
        }
        String clipped = font.plainSubstrByWidth(raw, Math.max(0, maxWidth - font.width("...")));
        graphics.drawString(font, clipped + "...", x, y, color, false);
    }

    private static String formatDuration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        if (seconds >= 60L) {
            return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
        }
        return seconds + "s";
    }
}
