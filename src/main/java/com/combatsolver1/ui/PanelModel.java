package com.combatsolver1.ui;

import com.combatsolver1.search.Plan;
import com.combatsolver1.settings.SolverSettings;

import java.util.ArrayList;
import java.util.List;

/** 面板要渲染的只读数据。 */
public class PanelModel {
    /** 摘要行里的一段文本：{文字, 颜色键}。颜色键见 OverlayRenderer 的 token。 */
    public static class Segment {
        public final String text;
        public final String color;

        public Segment(String text, String color) {
            this.text = text;
            this.color = color;
        }
    }

    public Plan plan;

    /** 状态徽章文字（搜索中 / 就绪 / 执行中 / 已暂停 / 等待战斗状态）。 */
    public String badge = "等待战斗状态";
    public String badgeColor = "muted";
    /** 徽章右侧的上下文说明。 */
    public String context = "";
    /** 摘要主行（分段着色）。 */
    public final List<Segment> summary = new ArrayList<>();
    /** 搜索进度文字与比例（0..1，-1 表示没有进度条）。 */
    public String progressText = "";
    public float progressRatio = -1f;

    /** 路线标题（推荐路线 / 正在执行的路线 / 已保存路线（已过期）…）。 */
    public String heading = "推荐路线";
    public String headingColor = "text";
    /** 标题右侧的结果标签：药水用量、整场扣血。 */
    public String potionOutcome = "";
    public String hpOutcome = "";
    public String hpOutcomeColor = "success";
    public boolean showDeathOutcome;

    public boolean fullAuto;
    public boolean paused;
    public boolean searching;
    public boolean executing;
    public boolean collapsed;
    public SolverSettings settings;

    public String potionLine = "";
    public String coverageLine = "";
    public String sourceLine = "";
    public final List<String> enemyLines = new ArrayList<>();

    /** Display-only projection: never merges actions or changes the executable plan. */
    public List<String> routeLines() {
        List<String> lines = new ArrayList<>();
        if (plan == null) return lines;
        for (int i = 0; i < plan.turns.size(); i++) {
            Plan.Turn turn = plan.turns.get(i);
            int number = turn.liveTurnNumber > 0 ? turn.liveTurnNumber
                    : turn.turnNumber > 0 ? turn.turnNumber : i + 1;
            String prefix = "T" + number;
            for (int j = 0; j < turn.actions.size(); j++) {
                Plan.Action action = turn.actions.get(j);
                String kind = action.isSelect() ? "选牌 · " : action.isPotion() ? "药水 · " : "";
                // Only resolved target-bearing actions have a name. Do not expose protocol
                // placeholder indices carried by untargeted cards such as Defend.
                String target = action.targetIndex >= 0 && action.targetName != null
                        && !action.targetName.isEmpty() ? " → " + action.targetName : "";
                lines.add(prefix + " · " + (j + 1) + "   " + kind + action.displayName() + target);
            }
            String end = turn.killsAll ? "预计击杀全部敌人" : turn.endsTurn ? "结束回合" : "本段结束";
            lines.add(prefix + " · " + end + "   HP " + turn.hpAfter
                    + (turn.endEnergy >= 0 ? "   能量 " + turn.endEnergy : ""));
        }
        return lines;
    }
}
