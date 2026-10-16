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
}
