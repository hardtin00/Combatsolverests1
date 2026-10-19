package com.combatsolver1.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.combatsolver1.search.Plan;
import com.combatsolver1.settings.SolverSettings;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only battle plan overlay; input returns existing controller actions. */
public class OverlayRenderer {
    public static final int ACTION_NONE = -1;
    public static final int ACTION_RECALCULATE = 0;
    public static final int ACTION_EXECUTE_TURN = 1;
    public static final int ACTION_TOGGLE_AUTO = 2;
    public static final int ACTION_CYCLE_BUDGET = 3;
    public static final int ACTION_CYCLE_OPACITY = 4;
    public static final int ACTION_TOGGLE_THEME = 5;
    public static final int ACTION_COLLAPSE = 6;
    public static final int ACTION_EXPAND = 7;
    public static final int ACTION_PAUSE = 8;
    public static final int ACTION_TOGGLE_ROUTE_EXPAND = 9;

    // 逻辑尺寸（Settings.scale = 1 时）。所有布局尺寸乘以 Settings.scale。
    private static final float MARGIN = 24f;
    private static final float HEADER_H = 44f;
    private static final float PAD = 16f;
    private static final float GAP = 8f;
    private static final float BADGE_H = 28f;
    private static final float BTN_H = 42f;
    private static final float CHIP_H = 28f;
    private static final float GRIP = 18f;
    private static final float PANEL_RADIUS = 10f;
    private static final float DEFAULT_W = 680f;
    private static final float MIN_W = 580f;
    private static final float MAX_W = 840f;
    private static final float DIVIDER_H = 1f;
    private static final float EMPTY_STATE_H = 72f;

    private static final Map<Integer, Texture> cornerTextures = new HashMap<>();
    private static final Map<Integer, TextureRegion[]> cornerRegions = new HashMap<>();
    private static BitmapFont titleFont;
    private static BitmapFont bodyFont;
    private static BitmapFont metricFont;
    private static BitmapFont captionFont;
    private static final GlyphLayout measure = new GlyphLayout();
    private static SolverSettings lastSettings;

    private static float panelX = Float.NaN;
    private static float panelTop = Float.NaN;
    private static float panelW = DEFAULT_W;
    private static float panelH = 420f;
    private static boolean sizeCustom;

    private boolean dragging;
    private boolean resizing;
    private float dragOffX;
    private float dragOffY;
    private float resizeStartW;
    private float resizeStartH;
    private boolean prevMouseDown;
    private boolean collapsed;
    private Rect expandToggleRect;
    // View state only: reset page when the plan changes; settings visibility lasts this session.
    private boolean settingsOpen;
    private boolean pausedStateForInput;
    private Rect settingsRect, previousPageRect, nextPageRect;
    private int routePage;
    private Plan displayedPlan;
    private float requestedHeight = 480f;
    private final List<String> displayLines = new ArrayList<>();
    private static final float LINE_H = 29f;

    private final List<Rect> headerChips = new ArrayList<>();
    private Rect collapseRect;
    private Rect expandRect;
    private Rect resizeRect;

    // ---------------------------------------------------------------- 布局与主题

    public void resetPosition() {
        panelX = Float.NaN;
        panelTop = Float.NaN;
        sizeCustom = false;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean value) {
        collapsed = value;
    }

    public void toggleRouteExpanded() {
        routePage = 0; // Compatibility with the existing controller action.
    }

    public void render(SpriteBatch sb, PanelModel model) {
        Color previous = new Color(sb.getColor());
        float s = Settings.scale;
        Theme t = model.settings.lightTheme ? Theme.light() : Theme.dark();
        lastSettings = model.settings;
        activeOpacity = model.settings.opacity();

        loadPersisted(model.settings);
        if (Float.isNaN(panelX)) {
            panelW = DEFAULT_W;
            panelX = MARGIN * s;
            panelTop = Settings.HEIGHT - 32f * s;
        }

        prepareRoute(model, s);
        float contentH = measureContentHeight(sb, model, s) / s;
        panelH = collapsed ? HEADER_H : Math.min(sizeCustom ? requestedHeight : contentH,
                Settings.HEIGHT / s * 0.46f);
        clampPanel(s);

        float left = panelX;
        float right = panelX + panelW * s;
        float bottom = panelTop - panelH * s;

        // 阴影
        fillRounded(sb, left + 4f * s, panelTop + 5f * s, panelW * s, panelH * s,
                PANEL_RADIUS * s, alpha(t.shadow));
        // 面板底 + 边框
        fillRounded(sb, left, panelTop, panelW * s, panelH * s, PANEL_RADIUS * s, alpha(t.border));
        fillRounded(sb, left + s, panelTop - s, panelW * s - 2f * s, panelH * s - 2f * s,
                (PANEL_RADIUS - 1f) * s, bgAlpha(t.bg));

        float y = panelTop - HEADER_H * s;
        expandToggleRect = null;
        previousPageRect = nextPageRect = null;
        resizeRect = null;
        renderHeader(sb, model, t, s, left, y, right, bottom);
        if (!collapsed) {
            // 内容区下限 = 面板底 + footer 高度，避免 info captions 溢出到按钮区
            float contentBottom = bottom + footerHeight(model, s);
            if (settingsOpen) y = renderSettings(sb, model, t, s, left, y, right);
            y = renderSummary(sb, model, t, s, left, y, right);
            y = renderRoute(sb, model, t, s, left, y, right, contentBottom);
            y = renderInfoCaptions(sb, model, t, s, left, y, right, contentBottom);
            renderFooter(sb, model, t, s, left, bottom, right);
            renderResizeGrip(sb, t, s, right, bottom);
        }
        sb.setColor(previous);
    }

    private float measureContentHeight(SpriteBatch sb, PanelModel model, float s) {
        // 与 render 中的分段一致，用于自动高度。
        float h = (HEADER_H + (settingsOpen ? 40f : 0f)) * s + GAP * s;
        h += summaryHeight(model, s);
        h += routeHeight(model, s);
        h += infoCaptionsHeight(model, s);
        h += footerHeight(model, s);
        return h + PAD * s;
    }

    private float summaryHeight(PanelModel model, float s) {
        float h = BADGE_H * s + GAP * s;
        if (!model.summary.isEmpty() || model.searching) {
            h += 28f * s;
        }
        if (model.searching) {
            h += 10f * s;
        }
        return h;
    }

    private void prepareRoute(PanelModel model, float s) {
        if (displayedPlan != model.plan) { routePage = 0; displayedPlan = model.plan; }
        displayLines.clear();
        float width = (panelW - PAD * 2 - 24f) * s;
        for (String line : model.routeLines()) {
            String rest = line;
            while (!rest.isEmpty()) {
                int end = rest.length();
                while (end > 1 && textW(bodyFont, rest.substring(0, end)) > width) end--;
                displayLines.add(rest.substring(0, end));
                rest = rest.substring(end);
            }
        }
    }

    private float routeHeight(PanelModel model, float s) {
        return (66f + (displayLines.isEmpty() ? EMPTY_STATE_H
                : Math.min(8, displayLines.size()) * LINE_H)) * s;
    }

    private float infoCaptionsHeight(PanelModel model, float s) {
        int n = 0;
        if (!model.enemyLines.isEmpty()) {
            n++;
        }
        if (!model.sourceLine.isEmpty()) {
            n++;
        }
        if (!model.potionLine.isEmpty() || !model.coverageLine.isEmpty()) {
            n++;
        }
        return n * 23f * s;
    }

    private float footerHeight(PanelModel model, float s) {
        // 底部只有操作按钮 + 分隔线 + 上下内边距，不再有设置 chip
        return DIVIDER_H * s + GAP * s + BTN_H * s + PAD * s;
    }

    private void clampPanel(float s) {
        float w = Math.max(MIN_W * s, Math.min(MAX_W * s, panelW * s));
        float h = Math.max((collapsed ? HEADER_H : 420f) * s,
                Math.min(Settings.HEIGHT - MARGIN * s * 2f, panelH * s));
        panelW = w / s;
        panelH = h / s;
        panelX = Math.max(8f * s, Math.min(panelX, Settings.WIDTH - w - 8f * s));
        panelTop = Math.max(h + 8f * s, Math.min(panelTop, Settings.HEIGHT - 8f * s));
    }

    private void loadPersisted(SolverSettings settings) {
        if (!Float.isNaN(panelX) || settings == null) {
            return;
        }
        if (settings.panelX > 0 && settings.panelY > 0) {
            panelX = settings.panelX;
            panelTop = settings.panelY;
        }
        if (settings.panelW >= MIN_W) {
            panelW = Math.min(MAX_W, settings.panelW);
            requestedHeight = Math.max(420f, settings.panelH);
            sizeCustom = true;
        }
    }

    // ---------------------------------------------------------------- 各分区

    private void renderHeader(SpriteBatch sb, PanelModel model, Theme t, float s,
                              float left, float y, float right, float bottom) {
        text(sb, titleFont, "Combat Solver", left + PAD * s, y + 32f * s, t.accent);
        // 标题旁的状态点：让标题栏也能一眼看出当前状态
        float dotX = left + PAD * s + textW(titleFont, "Combat Solver") + 8f * s;
        float dotY = y + 32f * s - lineH(titleFont) / 2f + 2f * s;
        Color dotColor = color(model.badgeColor, t);
        fillRounded(sb, dotX, dotY + 1f * s, 6f * s, 6f * s, 3f * s, dotColor);

        headerChips.clear();
        collapseRect = null;
        expandRect = null;

        settingsRect = null;
        if (collapsed) {
            String auto = "全自动：" + (model.fullAuto ? "开" : "关");
            float chipW = textW(captionFont, auto) + 20f * s;
            float cx = right - PAD * s - chipW;
            headerChips.add(chip(sb, captionFont, auto, cx, y + 36f * s, chipW, CHIP_H * s,
                    t, model.fullAuto ? t.positive : t.surfaceRaised, t.borderSubtle,
                    model.fullAuto ? Color.WHITE : t.textSecondary));
            String exec = "执行本回合";
            float exW = textW(captionFont, exec) + 20f * s;
            float exX = cx - GAP * s - exW;
            headerChips.add(chip(sb, captionFont, exec, exX, y + 36f * s, exW, CHIP_H * s,
                    t, t.accent, t.borderSubtle, Theme.c("201b13ff")));
            expandRect = chip(sb, captionFont, "展开", exX - GAP * s - 68f * s,
                    y + 36f * s, 66f * s, CHIP_H * s, t, t.surface, t.border, t.textSecondary);
            return;
        }

        collapseRect = chip(sb, captionFont, "折叠 −", right - 100f * s, y + 36f * s,
                84f * s, CHIP_H * s, t, t.surface, t.borderSubtle, t.textSecondary);
        settingsRect = chip(sb, captionFont, settingsOpen ? "收起设置" : "设置", right - 206f * s,
                y + 36f * s, 98f * s, CHIP_H * s, t, t.surface, t.borderSubtle, t.textSecondary);
    }

    private float renderSettings(SpriteBatch sb, PanelModel model, Theme t, float s,
                                 float left, float y, float right) {
        String[] labels = {"主题：" + (model.settings.lightTheme ? "浅色" : "深色"),
                "透明度 " + Math.round(model.settings.opacity() * 100) + "%",
                "预算：" + model.settings.presetName()};
        float x = left + PAD * s;
        for (String label : labels) {
            float w = textW(captionFont, label) + 20f * s;
            headerChips.add(chip(sb, captionFont, label, x, y, w, CHIP_H * s,
                    t, t.surface, t.borderSubtle, t.textSecondary));
            x += w + GAP * s;
        }
        return y - 40f * s;
    }

    private float renderSummary(SpriteBatch sb, PanelModel model, Theme t, float s,
                                float left, float y, float right) {
        y -= PAD * s;
        // 状态徽章
        String badge = model.badge;
        Color badgeColor = color(model.badgeColor, t);
        float bw = textW(captionFont, badge) + 18f * s;
        fillRounded(sb, left + PAD * s, y, bw, BADGE_H * s, 11f * s, alpha(t.surfaceRaised));
        fillRounded(sb, left + PAD * s, y, bw, BADGE_H * s, 11f * s, badgeColor, 0.18f);
        text(sb, captionFont, badge, left + PAD * s + 9f * s, y - (BADGE_H * s - lineH(captionFont)) / 2f,
                badgeColor);
        if (!model.context.isEmpty()) {
            float cx = left + PAD * s + bw + 10f * s;
            String context = ellipsize(captionFont, model.context, right - PAD * s - cx);
            text(sb, captionFont, context, cx, y - (BADGE_H * s - lineH(captionFont)) / 2f,
                    t.textSecondary);
        }
        y -= BADGE_H * s + 8f * s;

        if (model.searching) {
            if (!model.progressText.isEmpty()) {
                text(sb, bodyFont, ellipsize(bodyFont, model.progressText, right - left - PAD * s * 2), left + PAD * s, y + 2f * s, t.textPrimary);
                y -= 28f * s;
            }
            if (model.progressRatio >= 0f) {
                float px = left + PAD * s;
                float pw = right - left - PAD * s * 2f;
                fillRounded(sb, px, y + 2f * s, pw, 4f * s, 2f * s, alpha(t.progressBg));
                fillRounded(sb, px, y + 2f * s, pw * model.progressRatio, 4f * s, 2f * s,
                        alpha(t.accent));
                y -= 10f * s;
            }
        } else if (!model.summary.isEmpty()) {
            float tx = left + PAD * s;
            float ty = y + 2f * s;
            for (PanelModel.Segment seg : model.summary) {
                String shown = ellipsize(metricFont, seg.text, right - PAD * s - tx);
                text(sb, metricFont, shown, tx, ty, color(seg.color, t));
                tx += textW(metricFont, shown);
            }
            y -= 28f * s;
        }
        return y - GAP * s;
    }

    private float renderRoute(SpriteBatch sb, PanelModel model, Theme t, float s,
                              float left, float y, float right, float bottom) {
        String heading = model.heading + (model.potionOutcome.isEmpty() ? "" : " · " + model.potionOutcome);
        text(sb, bodyFont, ellipsize(bodyFont, heading, (panelW - PAD * 2) * s),
                left + PAD * s, y, color(model.headingColor, t));
        y -= 30f * s;
        if (displayLines.isEmpty()) return renderEmptyState(sb, model, t, s, left, right, y, bottom);
        float reserved = infoCaptionsHeight(model, s) + 42f * s;
        int capacity = Math.max(1, (int)((y - bottom - reserved) / (LINE_H * s)));
        int pages = (displayLines.size() + capacity - 1) / capacity;
        routePage = Math.max(0, Math.min(routePage, pages - 1));
        int start = routePage * capacity;
        for (int i = start; i < Math.min(displayLines.size(), start + capacity); i++) {
            if (y - LINE_H * s < bottom + 38f * s) break;
            box(sb, left + PAD * s, y, (panelW - PAD * 2) * s, (LINE_H - 2) * s,
                    3f * s, i % 2 == 0 ? t.surfaceRaised : t.surface, t.borderSubtle);
            text(sb, bodyFont, displayLines.get(i), left + (PAD + 10f) * s, y - 4f * s, t.textPrimary);
            y -= LINE_H * s;
        }
        float navY = y - 5f * s;
        text(sb, captionFont, "路线 " + (routePage + 1) + " / " + pages,
                left + PAD * s, navY - 3f * s, t.textSecondary);
        if (routePage > 0) previousPageRect = chip(sb, captionFont, "上一页",
                right - 190f * s, navY, 80f * s, CHIP_H * s, t, t.surface, t.border, t.accent);
        if (routePage + 1 < pages) nextPageRect = chip(sb, captionFont, "下一页",
                right - 100f * s, navY, 80f * s, CHIP_H * s, t, t.surface, t.border, t.accent);
        return y - 42f * s;
    }

    /** 空状态：等待 / 搜索中 / 未找到路线时给出居中的醒目提示。 */
    private float renderEmptyState(SpriteBatch sb, PanelModel model, Theme t, float s,
                                   float left, float right, float y, float footerTop) {
        float boxH = Math.min(EMPTY_STATE_H * s, y - footerTop);
        if (boxH < 24f * s) {
            // 可用高度不够（面板被屏幕高度限制）：不画空状态框，避免压到按钮区
            return footerTop - GAP * s;
        }
        float boxW = right - left - PAD * s * 2f;
        float boxTop = y;
        float boxBottom = y - boxH;

        box(sb, left + PAD * s, boxTop, boxW, boxH, 6f * s, t.surface, t.borderSubtle);

        String icon;
        String msg;
        Color iconColor;
        Color msgColor;
        if (model.searching) {
            icon = "…";
            msg = "正在搜索候选路线…";
            iconColor = t.accent;
            msgColor = t.textPrimary;
        } else if (model.showDeathOutcome) {
            icon = "！";
            msg = "预算内未找到生还路线";
            iconColor = t.danger;
            msgColor = t.danger;
        } else if (model.paused) {
            icon = "||";
            msg = "已暂停 — 点“重新计算”继续";
            iconColor = t.warning;
            msgColor = t.textSecondary;
        } else {
            icon = "…";
            msg = "等待战斗数据…";
            iconColor = t.textMuted;
            msgColor = t.textMuted;
        }

        float msgY = boxBottom + (boxH + lineH(bodyFont)) / 2f;
        float iconY = msgY + 6f * s;
        text(sb, bodyFont, icon, left + boxW / 2f + PAD * s - textW(bodyFont, icon) / 2f,
                iconY, iconColor);
        text(sb, bodyFont, msg, left + boxW / 2f + PAD * s - textW(bodyFont, msg) / 2f,
                msgY - 12f * s, msgColor);

        return boxBottom - GAP * s;
    }

    private float renderInfoCaptions(SpriteBatch sb, PanelModel model, Theme t, float s,
                                     float left, float y, float right, float bottom) {
        float yStart = y;
        if (!model.enemyLines.isEmpty()) {
            StringBuilder sb2 = new StringBuilder();
            for (String line : model.enemyLines) {
                if (sb2.length() > 0) {
                    sb2.append("　｜　");
                }
                sb2.append(line);
            }
            String text = ellipsize(captionFont, sb2.toString(), right - left - PAD * s * 2f);
            if (y - 23f * s >= bottom) {
                text(sb, captionFont, text, left + PAD * s, y + 4f * s, t.textSecondary);
                y -= 23f * s;
            }
        }
        if (!model.sourceLine.isEmpty()) {
            String text = ellipsize(captionFont, model.sourceLine, right - left - PAD * s * 2f);
            if (y - 23f * s >= bottom) {
                text(sb, captionFont, text, left + PAD * s, y + 4f * s, t.textSecondary);
                y -= 23f * s;
            }
        }
        String bottom2 = model.potionLine;
        if (bottom2.isEmpty()) {
            bottom2 = model.coverageLine;
        } else if (!model.coverageLine.isEmpty()) {
            bottom2 = bottom2 + "　｜　" + model.coverageLine;
        }
        if (!bottom2.isEmpty()) {
            String text = ellipsize(captionFont, bottom2, right - left - PAD * s * 2f);
            if (y - 23f * s >= bottom) {
                text(sb, captionFont, text, left + PAD * s, y + 4f * s, t.textSecondary);
                y -= 23f * s;
            }
        }
        return y - (y == yStart ? 0f : 4f * s);
    }

    private void renderFooter(SpriteBatch sb, PanelModel model, Theme t, float s,
                              float left, float bottom, float right) {
        // 分隔线：内容区与操作区的明确视觉边界
        float dividerY = bottom + BTN_H * s + PAD * s + GAP * s / 2f;
        fillRect(sb, left + PAD * s, dividerY, right - left - PAD * s * 2f,
                DIVIDER_H * s, t.borderSubtle);

        float by = bottom + PAD * s + BTN_H * s;
        float totalW = right - left - PAD * s * 2f;
        float gapSum = GAP * s * 3f;
        // 执行本回合作为主要操作，占更大宽度
        float execW = (totalW - gapSum) * 0.32f;
        float sideW = (totalW - gapSum - execW) / 3f;
        float bx = left + PAD * s;

        button(sb, "自动执行：" + (model.fullAuto ? "开" : "关"), bx, by, sideW,
                model.fullAuto, false, t, s);
        bx += sideW + GAP * s;

        button(sb, model.paused ? "执行（已暂停）" : "执行本回合", bx, by, execW,
                model.executing, true, t, s);
        bx += execW + GAP * s;

        Rect recalcBtn;
        if (model.searching) {
            recalcBtn = button(sb, "停止搜索", bx, by, sideW, false, false, t, s);
        } else {
            button(sb, model.paused ? "继续搜索" : "重新计算", bx, by, sideW,
                    false, false, t, s);
        }
        bx += sideW + GAP * s;

        button(sb, model.paused ? "继续" : "停止", bx, by, sideW,
                false, false, t, s);
    }

    private void renderResizeGrip(SpriteBatch sb, Theme t, float s, float right, float bottom) {
        resizeRect = new Rect(right - GRIP * s, bottom + GRIP * s, GRIP * s, GRIP * s);
        fillRect(sb, right - GRIP * s + 6f * s, bottom + 5f * s, 7f * s, 2f * s, t.textMuted);
        fillRect(sb, right - GRIP * s + 6f * s, bottom + 9f * s, 7f * s, 2f * s, t.textMuted);
        fillRect(sb, right - GRIP * s + 6f * s, bottom + 13f * s, 7f * s, 2f * s, t.textMuted);
    }

    private Rect chip(SpriteBatch sb, BitmapFont font, String label, float x, float y, float w,
                      float h, Theme t, Color bg, Color border, Color textColor) {
        boolean hover = inRect(InputHelper.mX, InputHelper.mY, x, y, w, h);
        box(sb, x, y, w, h, 4f, hover ? t.surfaceHover : bg, border);
        text(sb, font, label, x + w / 2f - textW(font, label) / 2f,
                y - (h - lineH(font)) / 2f, textColor);
        return new Rect(x, y, w, h);
    }

    private Rect button(SpriteBatch sb, String label, float x, float y, float w,
                        boolean active, boolean primary, Theme t, float s) {
        boolean hover = inRect(InputHelper.mX, InputHelper.mY, x, y, w, BTN_H * s);
        Color bg;
        Color textColor;
        Color border;
        if (active) {
            bg = hover ? t.positiveHover : t.positive;
            textColor = Color.WHITE;
            border = t.border;
        } else if (primary) {
            bg = hover ? t.accentHover : t.accent;
            textColor = Theme.c("201b13ff");
            border = t.accentHover;
        } else {
            bg = hover ? t.surfaceHover : t.surfaceRaised;
            textColor = t.textPrimary;
            border = t.borderSubtle;
        }
        float radius = primary ? 7f * s : 6f * s;
        box(sb, x, y, w, BTN_H * s, radius, bg, border);
        // 主按钮加一层顶部高光，增强视觉层级
        if (primary && !active) {
            fillRounded(sb, x + 2f * s, y - 1f * s, w - 4f * s, BTN_H * s * 0.45f,
                    Math.max(1f, radius - 2f), Color.WHITE, 0.06f);
        }
        String shown = ellipsize(captionFont, label, w - 10f * s);
        text(sb, captionFont, shown, x + w / 2f - textW(captionFont, shown) / 2f,
                y - (BTN_H * s - lineH(captionFont)) / 2f, textColor);
        return new Rect(x, y, w, BTN_H * s);
    }

    // ---------------------------------------------------------------- 交互

    public int handleInput(boolean visible) {
        if (!visible) {
            prevMouseDown = InputHelper.isMouseDown;
            dragging = false;
            resizing = false;
            return ACTION_NONE;
        }
        float mx = InputHelper.mX;
        float my = InputHelper.mY;
        boolean down = InputHelper.isMouseDown;

        int action = ACTION_NONE;
        if (down && !prevMouseDown) {
            if (!collapsed && settingsRect != null && settingsRect.contains(mx, my)) {
                settingsOpen = !settingsOpen;
                prevMouseDown = down;
                return ACTION_NONE;
            }
            if (!collapsed && previousPageRect != null && previousPageRect.contains(mx, my)) {
                routePage--; prevMouseDown = down; return ACTION_NONE;
            }
            if (!collapsed && nextPageRect != null && nextPageRect.contains(mx, my)) {
                routePage++; prevMouseDown = down; return ACTION_NONE;
            }
            if (collapsed) {
                if (expandRect != null && expandRect.contains(mx, my)) {
                    action = ACTION_EXPAND;
                } else if (hitHeaderChip(mx, my)) {
                    action = headerActionForChip(mx, my);
                } else if (hitButton(mx, my)) {
                    action = buttonAction(mx, my);
                } else {
                    beginTitleDrag(mx, my);
                }
            } else if (resizeRect != null && resizeRect.contains(mx, my)) {
                resizing = true;
                resizeStartW = panelW;
                resizeStartH = requestedHeight;
                dragOffX = mx;
                dragOffY = my;
            } else if (collapseRect != null && collapseRect.contains(mx, my)) {
                action = ACTION_COLLAPSE;
            } else {
                action = buttonAction(mx, my);
                if (action == ACTION_NONE) {
                    action = chipAction(mx, my);
                }
                if (action == ACTION_NONE && expandToggleRect != null
                        && expandToggleRect.contains(mx, my)) {
                    action = ACTION_TOGGLE_ROUTE_EXPAND;
                }
                if (action == ACTION_NONE) {
                    beginTitleDrag(mx, my);
                }
            }
        } else if (down && dragging) {
            panelX = mx - dragOffX;
            panelTop = my - dragOffY;
        } else if (down && resizing) {
            panelW = Math.max(MIN_W, Math.min(MAX_W, resizeStartW + (mx - dragOffX) / Settings.scale));
            requestedHeight = Math.max(420f,
                    Math.min(Settings.HEIGHT / Settings.scale * 0.46f,
                            resizeStartH - (my - dragOffY) / Settings.scale));
            sizeCustom = true;
        }

        if (!down) {
            if (dragging || resizing) {
                persistSize(safeSettings());
            }
            dragging = false;
            resizing = false;
        }
        prevMouseDown = down;
        return action;
    }

    public void setPausedState(boolean paused) {
        pausedStateForInput = paused;
    }

    private void beginTitleDrag(float mx, float my) {
        float s = Settings.scale;
        float headerBottom = panelTop - HEADER_H * s;
        if (mx >= panelX && mx <= panelX + panelW * s && my <= panelTop && my >= headerBottom) {
            dragging = true;
            dragOffX = mx - panelX;
            dragOffY = my - panelTop;
        }
    }

    private boolean hitButton(float mx, float my) {
        for (Rect r : footerHitboxes()) {
            if (r.contains(mx, my)) {
                return true;
            }
        }
        return false;
    }

    private boolean hitHeaderChip(float mx, float my) {
        for (Rect r : headerChips) {
            if (r.contains(mx, my)) {
                return true;
            }
        }
        return false;
    }

    private int headerActionForChip(float mx, float my) {
        for (int i = 0; i < headerChips.size(); i++) {
            if (headerChips.get(i).contains(mx, my)) {
                return headerAction(i);
            }
        }
        return ACTION_NONE;
    }

    private int buttonAction(float mx, float my) {
        Rect[] hits = footerHitboxes();
        for (int i = 0; i < hits.length; i++) if (hits[i].contains(mx, my)) {
            if (i == 3 && pausedStateForInput) return ACTION_RECALCULATE;
            return footerAction(i);
        }
        return ACTION_NONE;
    }

    private int footerAction(int index) {
        if (index == 0) return ACTION_TOGGLE_AUTO;
        if (index == 1) return ACTION_EXECUTE_TURN;
        if (index == 2) return ACTION_RECALCULATE;
        return ACTION_PAUSE;
    }

    /** Build click regions from the same current layout used for drawing, independent of render order. */
    private Rect[] footerHitboxes() {
        float s = Settings.scale;
        float left = panelX;
        float right = left + panelW * s;
        float bottom = panelTop - panelH * s;
        float yTop = bottom + PAD * s + BTN_H * s;
        float totalW = right - left - PAD * s * 2f;
        float gap = GAP * s;
        float execW = (totalW - gap * 3f) * 0.32f;
        float sideW = (totalW - gap * 3f - execW) / 3f;
        float x = left + PAD * s;
        Rect auto = new Rect(x, yTop, sideW, BTN_H * s);
        x += sideW + gap;
        Rect execute = new Rect(x, yTop, execW, BTN_H * s);
        x += execW + gap;
        Rect recalculate = new Rect(x, yTop, sideW, BTN_H * s);
        x += sideW + gap;
        Rect pause = new Rect(x, yTop, sideW, BTN_H * s);
        return new Rect[]{auto, execute, recalculate, pause};
    }

    private int chipAction(float mx, float my) {
        for (int i = 0; i < headerChips.size(); i++) {
            if (headerChips.get(i).contains(mx, my)) {
                return headerAction(i);
            }
        }
        return ACTION_NONE;
    }

    private int headerAction(int index) {
        if (collapsed) {
            // 顺序：全自动、执行、（展开单独处理）
            return index == 0 ? ACTION_TOGGLE_AUTO : ACTION_EXECUTE_TURN;
        }
        // headerChips 按从右到左加入：预算、透明度、主题
        int n = headerChips.size();
        if (index == n - 1) {
            return ACTION_CYCLE_BUDGET;
        }
        if (index == n - 2) {
            return ACTION_CYCLE_OPACITY;
        }
        return ACTION_TOGGLE_THEME;
    }

    private void persistSize(SolverSettings settings) {
        if (settings != null) {
            settings.panelX = panelX;
            settings.panelY = panelTop;
            settings.panelW = panelW;
            settings.panelH = requestedHeight;
            settings.save();
        }
    }

    private SolverSettings safeSettings() {
        return lastSettings;
    }

    // ---------------------------------------------------------------- 绘制原语

    private void text(SpriteBatch sb, BitmapFont font, String str, float x, float y, Color c) {
        FontHelper.renderFontLeftTopAligned(sb, font, str, x, y, c);
    }

    private void textRight(SpriteBatch sb, BitmapFont font, String str, float x, float y, Color c) {
        FontHelper.renderFontRightTopAligned(sb, font, str, x, y, c);
    }

    private float lineH(BitmapFont font) {
        return FontHelper.getHeight(font);
    }

    private float textW(BitmapFont font, String str) {
        measure.setText(font, str);
        return measure.width;
    }

    private String ellipsize(BitmapFont font, String str, float maxW) {
        if (maxW <= 0f || textW(font, "…") > maxW) return "";
        if (str == null || str.isEmpty() || textW(font, str) <= maxW) {
            return str;
        }
        String out = str;
        while (out.length() > 1 && textW(font, out + "…") > maxW) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }

    /** 圆角矩形：四角 + 四边 + 中心，用带 alpha 的圆角贴片拼接，任意尺寸都可用。 */
    private void fillRounded(SpriteBatch sb, float x, float yTop, float w, float h, float r, Color c) {
        if (w < 1f || h < 1f) {
            return;
        }
        if (r * 2f > w) {
            r = w / 2f;
        }
        if (r * 2f > h) {
            r = h / 2f;
        }
        r = Math.max(1f, r);
        TextureRegion[] regs = cornerRegionsFor((int) Math.ceil(r));
        sb.setColor(c);
        float yB = yTop - h;
        float innerW = w - 2f * r;
        float innerH = h - 2f * r;
        sb.draw(regs[4], x + r, yB + r, innerW, innerH);
        sb.draw(regs[4], x, yB + r, r, innerH);
        sb.draw(regs[4], x + w - r, yB + r, r, innerH);
        sb.draw(regs[4], x + r, yB, innerW, r);
        sb.draw(regs[4], x + r, yB + h - r, innerW, r);
        float d = r;
        sb.draw(regs[0], x, yB, d, d);
        sb.draw(regs[1], x + w - d, yB, d, d);
        sb.draw(regs[2], x, yB + h - d, d, d);
        sb.draw(regs[3], x + w - d, yB + h - d, d, d);
        sb.setColor(Color.WHITE);
    }

    /** 带边框的圆角框：先画边框色的大圆角，再贴背景色的小圆角。 */
    private void box(SpriteBatch sb, float x, float yTop, float w, float h, float r,
                     Color bg, Color border) {
        if (w < 2f || h < 2f) {
            fillRounded(sb, x, yTop, w, h, r, alpha(bg));
            return;
        }
        fillRounded(sb, x, yTop, w, h, r, alpha(border));
        fillRounded(sb, x + 1f, yTop - 1f, w - 2f, h - 2f, Math.max(1f, r - 1f), alpha(bg));
    }

    /** 半透明着色层（例如徽章的浅色底）。 */
    private void fillRounded(SpriteBatch sb, float x, float yTop, float w, float h, float r,
                             Color c, float alphaOverride) {
        Color b = alpha(new Color(c.r, c.g, c.b, alphaOverride));
        fillRounded(sb, x, yTop, w, h, r, b);
    }

    private void fillRect(SpriteBatch sb, float x, float yTop, float w, float h, Color c) {
        sb.setColor(c);
        sb.draw(cornerRegionsFor(4)[4], x, yTop - h, w, h);
        sb.setColor(Color.WHITE);
    }

    private TextureRegion[] cornerRegionsFor(int r) {
        TextureRegion[] cached = cornerRegions.get(r);
        if (cached != null) {
            return cached;
        }
        Texture tex = cornerTextures.get(r);
        if (tex == null) {
            int size = r * 2;
            Pixmap pix = new Pixmap(size, size, Pixmap.Format.RGBA8888);
            pix.setColor(Color.WHITE);
            pix.fillCircle(r, r, r);
            tex = new Texture(pix);
            tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            pix.dispose();
            cornerTextures.put(r, tex);
        }
        TextureRegion[] regs = new TextureRegion[5];
        // Four quarter circles and one opaque pixel; stretching a whole circle leaves holes.
        regs[0] = new TextureRegion(tex, 0, r, r, r);
        regs[1] = new TextureRegion(tex, r, r, r, r);
        regs[2] = new TextureRegion(tex, 0, 0, r, r);
        regs[3] = new TextureRegion(tex, r, 0, r, r);
        regs[4] = new TextureRegion(tex, r, r, 1, 1);
        cornerRegions.put(r, regs);
        return regs;
    }

    private final Color alphaTmp = new Color();

    private Color alpha(Color c) {
        alphaTmp.set(c);
        alphaTmp.a *= activeOpacity;
        return alphaTmp;
    }

    private float activeOpacity = 1f;

    private final Color bgAlphaTmp = new Color();

    /**
     * 面板底色专用：允许用户调透明度，但保底不透明度。
     *
     * 文字与图标本来就是主题色、不跟随透明度，所以真正让"背景干扰阅读"的是面板底色太透——
     * 游戏画面从面板里透出来把文字对比度压下去了。这里只给背景层设下限。
     */
    private Color bgAlpha(Color c) {
        bgAlphaTmp.set(c);
        bgAlphaTmp.a *= Math.max(activeOpacity, 0.88f);
        return bgAlphaTmp;
    }

    private Color color(String key, Theme t) {
        if ("muted".equals(key)) {
            return t.textMuted;
        }
        if ("secondary".equals(key)) {
            return t.textSecondary;
        }
        if ("accent".equals(key)) {
            return t.accent;
        }
        if ("warning".equals(key)) {
            return t.warning;
        }
        if ("danger".equals(key)) {
            return t.danger;
        }
        if ("success".equals(key)) {
            return t.success;
        }
        return t.textPrimary;
    }

    private static boolean inRect(float mx, float my, float x, float yTop, float w, float h) {
        return mx >= x && mx <= x + w && my >= yTop - h && my <= yTop;
    }

    private static final class Rect {
        final float x;
        final float yTop;
        final float w;
        final float h;

        Rect(float x, float yTop, float w, float h) {
            this.x = x;
            this.yTop = yTop;
            this.w = w;
            this.h = h;
        }

        boolean contains(float mx, float my) {
            return inRect(mx, my, x, yTop, w, h);
        }
    }

    // ---------------------------------------------------------------- 主题

    private static class Theme {
        final Color bg;
        final Color surface;
        final Color surfaceRaised;
        final Color surfaceHover;
        final Color border;
        final Color borderSubtle;
        final Color accent;
        final Color accentHover;
        final Color textPrimary;
        final Color textSecondary;
        final Color textMuted;
        final Color warning;
        final Color danger;
        final Color success;
        final Color positive;
        final Color positiveHover;
        final Color attack;
        final Color skill;
        final Color power;
        final Color negative;
        final Color potion;
        final Color choice;
        final Color progressBg;
        final Color shadow;
        float opacity = 1f;

        Theme(boolean light) {
            if (light) {
                bg = c("f3f3f3fb");
                surface = c("fffffffc");
                surfaceRaised = c("f7f9fbff");
                surfaceHover = c("efefefff");
                border = c("e0e0e0ff");
                borderSubtle = c("eaeaeaff");
                accent = c("0078d4ff");
                accentHover = c("106ebeff");
                textPrimary = c("1b1b1bff");
                textSecondary = c("5f5f5fff");
                textMuted = c("8a8a8aff");
                warning = c("9d5d00ff");
                danger = c("c42b1cff");
                success = c("0f7b0fff");
                positive = c("107c10ff");
                positiveHover = c("0e6e0eff");
                attack = c("c42b1cff");
                skill = c("0067c0ff");
                power = c("9d5d00ff");
                negative = c("6b4fa0ff");
                potion = c("00786cff");
                choice = c("d45500ff");
                progressBg = c("e8e8e8ff");
                shadow = c("00000014");
            } else {
                // 深色主题向游戏本体的视觉语言靠拢：石板底 + 暗铜描边 + 暖金强调，
                // 保留攻击红 / 技能蓝 / 能力金 / 药水青的动作分类色。
                bg = c("16151af7");
                surface = c("1c1a20fa");
                surfaceRaised = c("2a2620fb");
                surfaceHover = c("332d25ff");
                border = c("6b5540e6");
                borderSubtle = c("4a3d2ec4");
                accent = c("d9b25fff");
                accentHover = c("e8c877ff");
                textPrimary = c("eef1f6ff");
                textSecondary = c("b8c0ccff");
                textMuted = c("a5afbdff");
                warning = c("d6a34cff");
                danger = c("e26666ff");
                success = c("69b77dff");
                positive = c("34764fff");
                positiveHover = c("428d60ff");
                attack = c("d96363ff");
                skill = c("5b91d1ff");
                power = c("d7a84fff");
                negative = c("9b70c9ff");
                potion = c("55b9a5ff");
                choice = c("ff8533ff");
                progressBg = c("101216ff");
                shadow = c("00000061");
            }
        }

        static Theme dark() {
            return new Theme(false);
        }

        static Theme light() {
            return new Theme(true);
        }

        static Color c(String hex) {
            return new Color(Long.decode("0x" + hex.substring(0, 8)).intValue());
        }
    }

    // ---------------------------------------------------------------- 字体

    private static BitmapFont font(float size) {
        return FontHelper.prepFont(size, true);
    }

    static {
        titleFont = font(22f);
        bodyFont = font(19f);
        metricFont = font(22f);
        captionFont = font(17f);
    }
}
