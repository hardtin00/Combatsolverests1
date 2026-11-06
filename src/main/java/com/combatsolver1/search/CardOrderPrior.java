package com.combatsolver1.search;

import java.util.HashMap;
import java.util.Map;

/**
 * W7 第 5 项：出牌排序先验（只引导，不剪枝）。
 *
 * <p>任务书 W7.5：「按 W0 逐遭遇数据为常用牌加排序先验（参考项目 ExpertKnowledge 的思路），
 * 先验只引导不剪枝」。本类提供一个**小的、可关闭的加分**，作用于
 * {@code CombatSearch.turnScore} 的排序，不删除任何候选、不改 withinTurnWidth/beamWidth 截断、
 * 不改模拟器或目标函数本身。
 *
 * <p>先验值的来源：W0 冻结的 200 局离线批量对局（`tools/solvertest/BatchPlay.java`，
 * 档位 1）里起手池/抽牌池的常用牌。它们是**排序提示**不是规则：分数量级远小于
 * turnScore 的生存/胜负项（见 MAX_BONUS），且默认 {@code enabled=false}，
 * 因此 W0 基线在默认配置下逐位不变。
 *
 * <p>本单元只提供先验表与自检；**接入 CombatSearch.turnScore 的开关留下一单元**
 * （那会改变搜索行为，需单独做 W0 前后对照）。
 */
public final class CardOrderPrior {

    private CardOrderPrior() { }

    /** 单个动作允许的最大先验加分；远小于 turnScore 的胜负量级，保证只影响同分排序。 */
    public static final double MAX_BONUS = 0.5;

    private static boolean enabled = false;

    /** 常用牌 → 先验权重（0..1）。来源：W0 BatchPlay 起手/抽牌池中出现频率。 */
    private static final Map<String, Double> WEIGHTS = new HashMap<String, Double>();

    static {
        // 高频基础牌（W0 200 局起手池里出现最多）
        WEIGHTS.put("Strike_R", 1.0);
        WEIGHTS.put("Defend_R", 1.0);
        WEIGHTS.put("Bash", 0.9);
        WEIGHTS.put("Pommel Strike", 0.8);       // 过牌
        WEIGHTS.put("Shrug It Off", 0.8);        // 格挡+过牌
        WEIGHTS.put("Twin Strike", 0.7);
        WEIGHTS.put("Cleave", 0.7);
        WEIGHTS.put("Clothesline", 0.7);
        WEIGHTS.put("Iron Wave", 0.7);
        WEIGHTS.put("Heavy Blade", 0.6);         // 易伤后高价值，但依赖铺垫
        WEIGHTS.put("Thunderclap", 0.6);
        WEIGHTS.put("Swift Strike", 0.5);
        WEIGHTS.put("Anger", 0.5);
        WEIGHTS.put("Body Slam", 0.4);
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 关闭时恒返回 0（W0 基线不变）；开启时返回 0..MAX_BONUS 的小加分。 */
    public static double bonus(String cardId) {
        if (!enabled || cardId == null) return 0.0;
        Double w = WEIGHTS.get(cardId);
        return w == null ? 0.0 : w.doubleValue() * MAX_BONUS;
    }

    /** 只读快照，供自检核对表规模与量级。 */
    public static Map<String, Double> weights() {
        return new HashMap<String, Double>(WEIGHTS);
    }
}
