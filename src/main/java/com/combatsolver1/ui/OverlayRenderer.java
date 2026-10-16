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

/**
 * 战斗路线窗口：按 CombatSolver 原项目的设计语言重做的覆盖层。
 *
 * - 深色半透明圆角面板，浅色主题可切换，带阴影与 1px 描边；
 * - 顶部标题栏（可拖动），右上角快捷设置与折叠；
 * - 状态徽章 + 摘要主行（预计整场扣血，按损失等级着色）+ 搜索进度条；
 * - “推荐路线 / 正在执行的路线”逐回合行：每回合一个圆角卡片，
 *   左边 3px 高亮条，中部彩色行动胶囊（攻击红 / 技能蓝 / 能力黄 /
 *   药水青 / 选牌橙 / 结束回合灰），右边“受击 / hp / 能量”三项指标；
 * - 底部动作条：全自动、执行本回合、重新计算（搜索中变停止）、暂停；
 * - 右下角拖拽手柄可调整尺寸；位置与尺寸持久化。
 */
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

    // 逻辑尺寸（Settings.scale = 1 时）。所有布局尺寸乘以 Settings.scale。
    private static final float MARGIN = 24f;
    private static final float HEADER_H = 34f;
    private static final float PAD = 12f;
    private static final float GAP = 8f;
    private static final float BADGE_H = 22f;
    private static final float ROUTE_ROW_H = 40f;
    private static final float PILL_H = 26f;
    private static final float PILL_RADIUS = 4f;
    private static final float TURN_COL_W = 82f;
    private static final float METRIC_DMG_W = 70f;
    private static final float METRIC_HP_W = 52f;
    private static final float METRIC_EN_W = 42f;
    private static final float BTN_H = 34f;
    private static final float CHIP_H = 24f;
    private static final float GRIP = 18f;
    private static final float PANEL_RADIUS = 10f;
    private static final float DEFAULT_W = 620f;
    private static final float MIN_W = 470f;
    private static final float MAX_W = 840f;
    private static final int MAX_ROUTE_ROWS = 5;

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

    private final List<Rect> headerChips = new ArrayList<>();
    private final List<Rect> settingChips = new ArrayList<>();
    private final List<Rect> buttons = new ArrayList<>();
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

    public void render(SpriteBatch sb, PanelModel model) {
        Color previous = sb.getColor();
        float s = Settings.scale;
        Theme t = model.settings.lightTheme ? Theme.light() : Theme.dark();
        lastSettings = model.settings;
        activeOpacity = model.settings.opacity();

        loadPersisted(model.settings);
        if (Float.isNaN(panelX)) {
            panelW = DEFAULT_W;
            panelX = Settings.WIDTH - panelW * s - MARGIN * s;
            panelTop = MARGIN * s + panelH * s;
        }

        float contentH = collapsed ? HEADER_H * s : measureContentHeight(sb, model, s);
        // 保存下来的自定义高度可能来自旧布局：明显大于当前内容时视为过期，回到自适应高度。
        // 否则面板里会留出一大片黑框（截图里最直观的问题）。
        if (sizeCustom && !collapsed && panelH * s > contentH * 1.6f + 40f * s) {
            sizeCustom = false;
        }
        if (!sizeCustom) {
            panelH = Math.min(contentH, Settings.HEIGHT - MARGIN * s * 2f);
        }
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
                (PANEL_RADIUS - 1f) * s, alpha(t.bg));

        float y = panelTop - HEADER_H * s;
        renderHeader(sb, model, t, s, left, y, right, bottom);
        if (!collapsed) {
            y = renderSummary(sb, model, t, s, left, y, right);
            y = renderRoute(sb, model, t, s, left, y, right, bottom);
            y = renderInfoCaptions(sb, model, t, s, left, y, right, bottom);
            renderFooter(sb, model, t, s, left, bottom, right);
            renderResizeGrip(sb, t, s, right, bottom);
        }
        sb.setColor(previous);
    }

    private float measureContentHeight(SpriteBatch sb, PanelModel model, float s) {
        // 与 render 中的分段一致，用于自动高度。
        float h = HEADER_H * s + GAP * s;
        h += summaryHeight(model, s);
        h += routeHeight(model, s);
        h += infoCaptionsHeight(model, s);
        h += footerHeight(model, s);
        return h + PAD * s;
    }

    private float summaryHeight(PanelModel model, float s) {
        float h = BADGE_H * s + GAP * s;
        if (!model.summary.isEmpty() || model.searching) {
            h += 22f * s;
        }
        if (model.searching) {
            h += 10f * s;
        }
        return h;
    }

    private float routeHeight(PanelModel model, float s) {
        Plan plan = model.plan;
        int rows = plan == null ? 1 : Math.min(MAX_ROUTE_ROWS, Math.max(1, plan.turns.size()));
        return 24f * s + rows * (ROUTE_ROW_H + GAP) * s;
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
        return n * 17f * s;
    }

    private float footerHeight(PanelModel model, float s) {
        return CHIP_H * s + GAP * s + BTN_H * s + PAD * s;
    }

    private void clampPanel(float s) {
        float w = Math.max(MIN_W * s, Math.min(MAX_W * s, panelW * s));
        float h = Math.max(HEADER_H * s + 40f * s,
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
            panelH = Math.max(HEADER_H + 40f, settings.panelH);
            sizeCustom = true;
        }
    }

    // ---------------------------------------------------------------- 各分区

    private void renderHeader(SpriteBatch sb, PanelModel model, Theme t, float s,
                              float left, float y, float right, float bottom) {
        text(sb, titleFont, "Combat Solver", left + PAD * s, y + 7f * s, t.accent);
        headerChips.clear();
        collapseRect = null;
        expandRect = null;

        if (collapsed) {
            String auto = "全自动：" + (model.fullAuto ? "开" : "关");
            float chipW = textW(captionFont, auto) + 20f * s;
            float cx = right - PAD * s - chipW;
            headerChips.add(chip(sb, captionFont, auto, cx, y + 5f * s, chipW, CHIP_H * s,
                    t, model.fullAuto ? t.positive : t.surfaceRaised, t.borderSubtle,
                    model.fullAuto ? Color.WHITE : t.textSecondary));
            String exec = "执行本回合";
            float exW = textW(captionFont, exec) + 20f * s;
            float exX = cx - GAP * s - exW;
            headerChips.add(chip(sb, captionFont, exec, exX, y + 5f * s, exW, CHIP_H * s,
                    t, t.accent, t.borderSubtle, Color.WHITE));
            expandRect = chip(sb, captionFont, "展开 +", exX - GAP * s - 48f * s,
                    y + 5f * s, 46f * s, CHIP_H * s, t, t.surface, t.border, t.textSecondary);
            return;
        }

        String[] labels = {"预算：" + model.settings.presetName(), "透明度", "主题"};
        float cursor = right - PAD * s;
        for (int i = 2; i >= 0; i--) {
            String label = i == 0 ? labels[0] : (i == 1 ? labels[1] + " " + Math.round(model.settings.opacity() * 100) + "%" : labels[2] + "：" + (model.settings.lightTheme ? "浅色" : "深色"));
            float w = textW(captionFont, label) + 16f * s;
            cursor -= w;
            headerChips.add(chip(sb, captionFont, label, cursor, y + 5f * s, w, CHIP_H * s,
                    t, t.surface, t.borderSubtle, t.textSecondary));
            cursor -= GAP * s;
        }
        collapseRect = chip(sb, captionFont, "折叠 -", cursor - 44f * s, y + 5f * s,
                44f * s, CHIP_H * s, t, t.surface, t.borderSubtle, t.textSecondary);
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
        text(sb, captionFont, badge, left + PAD * s + 9f * s, y + (BADGE_H * s - lineH(captionFont)) / 2f,
                badgeColor);
        if (!model.context.isEmpty()) {
            float cx = left + PAD * s + bw + 10f * s;
            String context = ellipsize(captionFont, model.context, right - PAD * s - cx);
            text(sb, captionFont, context, cx, y + (BADGE_H * s - lineH(captionFont)) / 2f,
                    t.textSecondary);
        }
        y -= BADGE_H * s + 8f * s;

        if (model.searching) {
            if (!model.progressText.isEmpty()) {
                text(sb, bodyFont, model.progressText, left + PAD * s, y + 2f * s, t.textPrimary);
                y -= 20f * s;
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
                text(sb, metricFont, seg.text, tx, ty, color(seg.color, t));
                tx += textW(metricFont, seg.text);
            }
            y -= 22f * s;
        }
        return y - GAP * s;
    }

    private float renderRoute(SpriteBatch sb, PanelModel model, Theme t, float s,
                              float left, float y, float right, float bottom) {
        y -= 4f * s;
        text(sb, bodyFont, model.heading, left + PAD * s, y, color(model.headingColor, t));
        float rx = right - PAD * s;
        if (!model.potionOutcome.isEmpty()) {
            textRight(sb, captionFont, model.potionOutcome, rx, y + 2f * s, t.warning);
            rx -= textW(captionFont, model.potionOutcome) + 12f * s;
        }
        if (!model.hpOutcome.isEmpty()) {
            textRight(sb, bodyFont, model.hpOutcome, rx, y, color(model.hpOutcomeColor, t));
            rx -= textW(bodyFont, model.hpOutcome) + 12f * s;
        }
        if (model.showDeathOutcome) {
            textRight(sb, bodyFont, "未找到生还路线", rx, y, t.danger);
        }
        y -= 18f * s;

        Plan plan = model.plan;
        if (plan == null || plan.turns.isEmpty()) {
            float rowTop = y;
            float rowBottom = y - ROUTE_ROW_H * s;
            if (rowBottom >= bottom) {
                box(sb, left + PAD * s, rowTop, right - left - PAD * s * 2f,
                        ROUTE_ROW_H * s, 6f * s, t.surface, t.borderSubtle);
                text(sb, bodyFont, model.searching ? "搜索进行中…" : "等待开始预测",
                        left + PAD * s + TURN_COL_W * s, rowTop + 12f * s, t.textMuted);
            }
            return rowBottom - GAP * s;
        }

        int prevHp = plan.startHp;
        int shown = Math.min(MAX_ROUTE_ROWS, plan.turns.size());
        for (int i = 0; i < shown; i++) {
            Plan.Turn turn = plan.turns.get(i);
            float rowTop = y;
            float rowBottom = y - ROUTE_ROW_H * s;
            if (rowBottom < bottom) {
                break;
            }
            boolean current = i == 0;
            renderRouteRow(sb, turn, i, current, prevHp, plan, model, t, s, left, right, rowTop);
            if (turn.hpAfter > 0) {
                prevHp = turn.hpAfter;
            }
            y = rowBottom - GAP * s;
        }
        if (plan.turns.size() > shown) {
            text(sb, captionFont, "… 其后还有 " + (plan.turns.size() - shown) + " 回合",
                    left + PAD * s, y + 4f * s, t.textMuted);
            y -= 18f * s;
        }
        return y;
    }

    private void renderRouteRow(SpriteBatch sb, Plan.Turn turn, int index, boolean current,
                                int hpBefore, Plan plan, PanelModel model, Theme t, float s,
                                float left, float right, float rowTop) {
        float x = left + PAD * s;
        float w = right - left - PAD * s * 2f;
        box(sb, x, rowTop, w, ROUTE_ROW_H * s, 6f * s,
                current ? t.surfaceRaised : t.surface, t.borderSubtle);
        if (current) {
            fillRect(sb, x, rowTop - 10f * s, 3f * s, ROUTE_ROW_H * s - 20f * s, alpha(t.accent));
        }
        String head = "第 " + (turn.turnNumber > 0 ? turn.turnNumber : index + 1) + " 回合";
        text(sb, bodyFont, head, x + 12f * s,
                rowTop + (ROUTE_ROW_H * s - lineH(bodyFont)) / 2f,
                current ? t.accent : t.textPrimary);

        // 中部胶囊流
        float pillX = x + TURN_COL_W * s;
        float pillRight = right - PAD * s - (METRIC_DMG_W + METRIC_HP_W + METRIC_EN_W) * s - 10f * s;
        pillX = renderActionPills(sb, turn, plan, model, t, s, pillX, pillRight, rowTop);

        // 右侧指标
        float rx = right - PAD * s;
        int damage = Math.max(0, hpBefore - Math.max(0, turn.hpAfter));
        if (damage > 0) {
            textRight(sb, bodyFont, "受击 " + damage, rx, rowTop + 12f * s, t.warning);
        }
        rx -= METRIC_DMG_W * s;
        if (turn.hpAfter > 0) {
            textRight(sb, bodyFont, "hp " + turn.hpAfter, rx + METRIC_HP_W * s / 2f,
                    rowTop + 12f * s, turn.killsAll ? t.success : t.textSecondary);
        }
        rx -= METRIC_HP_W * s;
        if (turn.endEnergy >= 0) {
            textRight(sb, bodyFont, "能 " + turn.endEnergy, rx + METRIC_EN_W * s / 2f,
                    rowTop + 12f * s, t.textSecondary);
        }
    }

    /** 胶囊流：同类连续动作合并成 ×N；放不下的截断。返回下一个可用 x。 */
    private float renderActionPills(SpriteBatch sb, Plan.Turn turn, Plan plan, PanelModel model,
                                    Theme t, float s, float x, float maxX, float rowTop) {
        float cursor = x;
        List<Plan.Action> actions = new ArrayList<>(turn.actions);
        int i = 0;
        while (i < actions.size()) {
            Plan.Action a = actions.get(i);
            int repeat = 1;
            while (i + repeat < actions.size()
                    && samePill(a, actions.get(i + repeat))) {
                repeat++;
            }
            float w = pillWidth(a, repeat, s);
            if (cursor + w > maxX) {
                if (cursor > x) {
                    break; // 后面的行动放不下：由调用方显示 +N
                }
                // 第一颗胶囊就放不下时压缩到可用宽度，保证绝不越出面板
                w = Math.max(40f * s, maxX - cursor);
            }
            drawPill(sb, a, repeat, t, s, cursor, rowTop + (ROUTE_ROW_H * s - PILL_H * s) / 2f, w);
            cursor += w + 6f * s;
            i += repeat;
        }
        boolean last = isLastTurn(plan, turn);
        int remaining = actions.size() - i;
        if (remaining > 0) {
            // 放不下的行动不能直接消失：给一颗 "+N 个行动" 的胶囊，玩家至少知道还剩多少
            Plan.Action more = new Plan.Action("MORE", "+" + remaining + " 个行动", -1, 0);
            float moreW = pillWidth(more, 1, s);
            if (cursor + moreW <= maxX) {
                drawPill(sb, more, 1, t, s, cursor,
                        rowTop + (ROUTE_ROW_H * s - PILL_H * s) / 2f, moreW);
                cursor += moreW + 6f * s;
            }
        }
        if (turn.endsTurn || last) {
            Plan.Action end = endTurnAction(plan, turn, last);
            float w = pillWidth(end, 1, s);
            if (cursor + w <= maxX || cursor == x) {
                drawPill(sb, end, 1, t, s, cursor,
                        rowTop + (ROUTE_ROW_H * s - PILL_H * s) / 2f, w);
            }
        }
        return cursor;
    }

    private boolean isLastTurn(Plan plan, Plan.Turn turn) {
        return !plan.turns.isEmpty() && plan.turns.get(plan.turns.size() - 1) == turn;
    }

    private Plan.Action endTurnAction(Plan plan, Plan.Turn turn, boolean last) {
        Plan.Action end = new Plan.Action("", "", -1, 0);
        if (turn.killsAll) {
            end.cardName = "结束回合 · 击杀";
            end.colorIndex = 6;
        } else if (!turn.endsTurn && plan.predictedWin) {
            end.cardName = "获胜";
            end.colorIndex = 7;
        } else if (!turn.endsTurn) {
            end.cardName = "…";
            end.colorIndex = 6;
        } else if (last && plan.predictedWin) {
            end.cardName = "结束回合 · 获胜";
            end.colorIndex = 7;
        } else {
            end.cardName = "结束回合";
            end.colorIndex = 6;
        }
        return end;
    }

    private boolean samePill(Plan.Action a, Plan.Action b) {
        return a.colorIndex == b.colorIndex
                && a.displayName().equals(b.displayName())
                && a.targetIndex == b.targetIndex;
    }

    private float pillWidth(Plan.Action a, int repeat, float s) {
        String name = a.displayName();
        float w = textW(bodyFont, name) + 14f * s;
        if (a.targetName != null && a.targetIndex >= 0) {
            w += textW(bodyFont, " → " + a.targetName) + 4f * s;
        }
        if (repeat > 1) {
            w += textW(captionFont, " ×" + repeat) + 2f * s;
        }
        return w + 10f * s;
    }

    private void drawPill(SpriteBatch sb, Plan.Action a, int repeat, Theme t, float s,
                          float x, float y, float w) {
        Color marker = pillColor(a, t);
        box(sb, x, y, w, PILL_H * s, PILL_RADIUS * s, t.surfaceRaised, t.borderSubtle);
        fillRect(sb, x + 3f * s, y + 6f * s, 3f * s, PILL_H * s - 12f * s, marker);
        float tx = x + 11f * s;
        text(sb, bodyFont, a.displayName(), tx, y + (PILL_H * s - lineH(bodyFont)) / 2f,
                t.textPrimary);
        tx += textW(bodyFont, a.displayName());
        if (a.targetName != null && a.targetIndex >= 0) {
            String arrow = " → " + a.targetName;
            text(sb, bodyFont, arrow, tx, y + (PILL_H * s - lineH(bodyFont)) / 2f, t.textSecondary);
            tx += textW(bodyFont, arrow);
        }
        if (repeat > 1) {
            text(sb, captionFont, " ×" + repeat, tx + 2f * s,
                    y + (PILL_H * s - lineH(captionFont)) / 2f, t.warning);
        }
    }

    private Color pillColor(Plan.Action a, Theme t) {
        if (a.isPotion() || a.colorIndex == 4) {
            return t.potion;
        }
        switch (a.colorIndex) {
            case 0:
                return t.attack;
            case 1:
                return t.skill;
            case 2:
                return t.power;
            case 3:
                return t.negative;
            case 5:
                return t.choice;
            case 7:
                return t.success;
            default:
                return t.textMuted;
        }
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
            if (y - 17f * s >= bottom) {
                text(sb, captionFont, text, left + PAD * s, y + 4f * s, t.textSecondary);
                y -= 17f * s;
            }
        }
        if (!model.sourceLine.isEmpty()) {
            String text = ellipsize(captionFont, model.sourceLine, right - left - PAD * s * 2f);
            if (y - 17f * s >= bottom) {
                text(sb, captionFont, text, left + PAD * s, y + 4f * s, t.textMuted);
                y -= 17f * s;
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
            if (y - 17f * s >= bottom) {
                text(sb, captionFont, text, left + PAD * s, y + 4f * s, t.textMuted);
                y -= 17f * s;
            }
        }
        return y - (y == yStart ? 0f : 4f * s);
    }

    private void renderFooter(SpriteBatch sb, PanelModel model, Theme t, float s,
                              float left, float bottom, float right) {
        settingChips.clear();
        buttons.clear();

        String[] labels = {
                "搜索预算：" + model.settings.presetName() + " >",
                "不透明度：" + Math.round(model.settings.opacity() * 100f) + "% >",
                "主题：" + (model.settings.lightTheme ? "浅色" : "深色") + " >",
        };
        float cx = left + PAD * s;
        float cy = bottom + PAD * s;
        for (int i = 0; i < labels.length; i++) {
            float w = textW(captionFont, labels[i]) + 18f * s;
            settingChips.add(chip(sb, captionFont, labels[i], cx, cy, w, CHIP_H * s,
                    t, t.surface, t.borderSubtle, t.textSecondary));
            cx += w + GAP * s;
        }

        float by = cy + CHIP_H * s + GAP * s;
        float bw = (right - left - PAD * s * 2f - GAP * s * 3f) / 4f;
        float bx = left + PAD * s;

        Rect autoBtn = button(sb, "全自动：" + (model.fullAuto ? "开" : "关"), bx, by, bw,
                model.fullAuto, false, t, s);
        buttons.add(autoBtn);
        bx += bw + GAP * s;

        Rect execBtn = button(sb, model.paused ? "执行（已暂停）" : "执行本回合", bx, by, bw,
                model.executing, true, t, s);
        buttons.add(execBtn);
        bx += bw + GAP * s;

        Rect recalcBtn;
        if (model.searching) {
            recalcBtn = button(sb, "停止搜索", bx, by, bw, false, false, t, s);
        } else {
            recalcBtn = button(sb, model.paused ? "重新计算（继续）" : "重新计算", bx, by, bw,
                    false, false, t, s);
        }
        buttons.add(recalcBtn);
        bx += bw + GAP * s;

        Rect pauseBtn = button(sb, model.paused ? "已暂停" : "暂停", bx, by, bw,
                model.paused, false, t, s);
        buttons.add(pauseBtn);
    }

    private void renderResizeGrip(SpriteBatch sb, Theme t, float s, float right, float bottom) {
        resizeRect = new Rect(right - GRIP * s, bottom, GRIP * s, GRIP * s);
        fillRect(sb, right - GRIP * s + 6f * s, bottom + 5f * s, 7f * s, 2f * s, t.textMuted);
        fillRect(sb, right - GRIP * s + 6f * s, bottom + 9f * s, 7f * s, 2f * s, t.textMuted);
        fillRect(sb, right - GRIP * s + 6f * s, bottom + 13f * s, 7f * s, 2f * s, t.textMuted);
    }

    private Rect chip(SpriteBatch sb, BitmapFont font, String label, float x, float y, float w,
                      float h, Theme t, Color bg, Color border, Color textColor) {
        boolean hover = inRect(InputHelper.mX, InputHelper.mY, x, y, w, h);
        box(sb, x, y, w, h, 4f, hover ? t.surfaceHover : bg, border);
        text(sb, font, label, x + w / 2f - textW(font, label) / 2f,
                y + (h - lineH(font)) / 2f, textColor);
        return new Rect(x, y, w, h);
    }

    private Rect button(SpriteBatch sb, String label, float x, float y, float w,
                        boolean active, boolean primary, Theme t, float s) {
        boolean hover = inRect(InputHelper.mX, InputHelper.mY, x, y, w, BTN_H * s);
        Color bg;
        Color textColor;
        if (active) {
            bg = hover ? t.positiveHover : t.positive;
            textColor = Color.WHITE;
        } else if (primary) {
            bg = hover ? t.accentHover : t.accent;
            textColor = Color.WHITE;
        } else {
            bg = hover ? t.surfaceHover : t.surfaceRaised;
            textColor = t.textPrimary;
        }
        box(sb, x, y, w, BTN_H * s, 6f * s, bg, t.borderSubtle);
        String shown = ellipsize(captionFont, label, w - 10f * s);
        text(sb, captionFont, shown, x + w / 2f - textW(captionFont, shown) / 2f,
                y + (BTN_H * s - lineH(captionFont)) / 2f, textColor);
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
                resizeStartH = panelH;
                dragOffX = mx;
                dragOffY = my;
            } else if (collapseRect != null && collapseRect.contains(mx, my)) {
                action = ACTION_COLLAPSE;
            } else {
                action = buttonAction(mx, my);
                if (action == ACTION_NONE) {
                    action = chipAction(mx, my);
                }
                if (action == ACTION_NONE) {
                    beginTitleDrag(mx, my);
                }
            }
        } else if (down && dragging) {
            panelX = mx - dragOffX;
            panelTop = my - dragOffY;
        } else if (down && resizing) {
            panelW = Math.max(MIN_W, Math.min(MAX_W, resizeStartW + (mx - dragOffX)));
            panelH = Math.max(HEADER_H + 60f,
                    Math.min(Settings.HEIGHT - MARGIN * 2f, resizeStartH + (my - dragOffY)));
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
        for (Rect r : buttons) {
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
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).contains(mx, my)) {
                // 顺序即布局顺序：全自动 / 执行 / 重新计算 / 暂停
                if (i == 0) {
                    return ACTION_TOGGLE_AUTO;
                }
                if (i == 1) {
                    return ACTION_EXECUTE_TURN;
                }
                if (i == 2) {
                    return ACTION_RECALCULATE;
                }
                return ACTION_PAUSE; // 暂停；已暂停时这个按钮不再触发（按钮文案显示已暂停）
            }
        }
        return ACTION_NONE;
    }

    private int chipAction(float mx, float my) {
        for (int i = 0; i < settingChips.size(); i++) {
            if (settingChips.get(i).contains(mx, my)) {
                return ACTION_CYCLE_BUDGET + i;
            }
        }
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
            settings.panelH = panelH;
            settings.save();
        }
    }

    private SolverSettings safeSettings() {
        return lastSettings;
    }

    // ---------------------------------------------------------------- 绘制原语

    private void text(SpriteBatch sb, BitmapFont font, String str, float x, float y, Color c) {
        FontHelper.renderFontLeftTopAligned(sb, font, str, x, y, alpha(c));
    }

    private void textRight(SpriteBatch sb, BitmapFont font, String str, float x, float y, Color c) {
        FontHelper.renderFontRightTopAligned(sb, font, str, x, y, alpha(c));
    }

    private float lineH(BitmapFont font) {
        return FontHelper.getHeight(font);
    }

    private float textW(BitmapFont font, String str) {
        measure.setText(font, str);
        return measure.width;
    }

    private String ellipsize(BitmapFont font, String str, float maxW) {
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
        sb.setColor(alpha(c));
        float yB = yTop - h;
        float innerW = w - 2f * r;
        float innerH = h - 2f * r;
        sb.draw(regs[4], x + r, yB + r, innerW, innerH);
        sb.draw(regs[4], x, yB + r, r, innerH);
        sb.draw(regs[4], x + w - r, yB + r, r, innerH);
        sb.draw(regs[4], x + r, yB, innerW, r);
        sb.draw(regs[4], x + r, yB + h - r, innerW, r);
        float d = 2f * r;
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
        TextureRegion bl = new TextureRegion(tex);
        regs[0] = bl;
        TextureRegion br = new TextureRegion(bl);
        br.flip(true, false);
        regs[1] = br;
        TextureRegion tl = new TextureRegion(bl);
        tl.flip(false, true);
        regs[2] = tl;
        TextureRegion tr = new TextureRegion(bl);
        tr.flip(true, true);
        regs[3] = tr;
        regs[4] = new TextureRegion(tex);
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
                bg = c("101216f7");
                surface = c("191c22fa");
                surfaceRaised = c("23272ffb");
                surfaceHover = c("2b3039ff");
                border = c("3a404aeb");
                borderSubtle = c("2a2f38cc");
                accent = c("5c9fc7ff");
                accentHover = c("73b4d8ff");
                textPrimary = c("eef1f6ff");
                textSecondary = c("b8c0ccff");
                textMuted = c("858f9fff");
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
        titleFont = font(16f);
        bodyFont = font(14f);
        metricFont = font(15f);
        captionFont = font(12f);
    }
}
