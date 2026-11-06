package com.combatsolver1.search;

import java.util.List;

/**
 * W8-d：针对「喝药后低费牌连打被低估」的**可关闭评分调参实验**。
 *
 * <p>根因（见 docs/W8_Q44_ROOT_CAUSE.md）：Q44 异蛇之油局面里，穷举参照喝油后打
 * Cleave + Strike_R + Anger + Defend_R（4 张低费），beam 却先打 2 费 Heavy Blade、
 * 少打一张、多吃 4 伤害。原因之一是 {@code CombatSearch.expandTurn} 里药水动作有
 * 硬编码 {@code -15.0} 的机会成本惩罚，且 {@code turnScore} 没有对「喝药后手牌结构变化
 * → 低费连打」的组合价值给任何引导。
 *
 * <p>本类提供两个**可关闭**的调参旋钮：
 * <ul>
 *   <li>{@link #potionCostPenalty()}：药水动作的机会成本（默认仍 15.0，开启后降为 0）；</li>
 *   <li>{@link #lowCostChainBonus(List)}：含药水动作时，对喝药后打出的牌按
 *       {@code max(0, 2-cost)} 给小加分，量级远小于胜负项，只影响同层排序。</li>
 * </ul>
 * 默认 {@code enabled=false}，因此 {@code CombatSearch.turnScore} 与
 * {@code expandTurn} 在默认情况下逐位不变，W0 基线不受影响。
 *
 * <p><b>本单元是实验</b>：不声称已修复 W8 反超，是否把调参设为默认需用户决定
 * （会改变 W0 全局指标，需单独做前后对照）。
 */
public final class W8PotionPrior {

    private W8PotionPrior() { }

    private static boolean enabled = false;

    /** 单张牌的最大低费加分；与 CardOrderPrior.MAX_BONUS 同量级。 */
    public static final double MAX_BONUS = 0.5;

    public static void setEnabled(boolean on) { enabled = on; }
    public static boolean isEnabled() { return enabled; }

    /** 关闭时保持原硬编码 15.0；开启时取消该惩罚（让含药路线不被系统性压低）。 */
    public static double potionCostPenalty() {
        return enabled ? 0.0 : 15.0;
    }

    /**
     * 喝药后低费连打引导：只在动作序列里含药水动作时生效。
     * 对药水之后打出的每张牌累加 max(0, 2-cost)*MAX_BONUS，
     * 让「喝油 → 多张低费牌」的组合在 beam 早期排序里不被高费单牌压掉。
     */
    public static double lowCostChainBonus(List<Plan.Action> na) {
        if (!enabled || na == null || na.isEmpty()) return 0.0;
        boolean afterPotion = false;
        double sum = 0.0;
        for (Plan.Action a : na) {
            if (a == null) continue;
            boolean isPotion = a.kind == Plan.Action.KIND_POTION
                    || (a.cardId != null && a.cardId.startsWith("POTION:"));
            if (isPotion) {
                afterPotion = true;
                continue;
            }
            if (afterPotion && a.cost >= 0) {
                int lowCost = 2 - a.cost;
                if (lowCost > 0) sum += lowCost * MAX_BONUS;
            }
        }
        return sum;
    }
}
