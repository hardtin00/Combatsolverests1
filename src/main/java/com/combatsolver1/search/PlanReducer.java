package com.combatsolver1.search;

import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.SimState;

import java.util.ArrayList;
import java.util.List;

/**
 * W7 第 4 项（计划精简）的离线实现：搜索后的**有界冗余动作删除**。
 *
 * <p>借鉴参考项目 {@code overlay/plan_reduction.py} 的思路（MIT，仅读思路、不移植代码）：
 * 逐个尝试删掉一张牌，把剩下的动作按**原回合调度**在 {@link CombatEngine} 上原样重放；
 * 只有当「终局仍然获胜、且回合数不增加、且终局 HP 不低于原路线」时才接受删除，
 * 否则回滚该次尝试。任何一次尝试都有上限（尝试次数 / 重放次数），避免长循环路线卡死。
 *
 * <p><b>边界与诚实标注</b>：
 * <ul>
 *   <li>这是<b>离线自检单元</b>，{@code PlanReducer} <b>默认不接入</b> CombatSearch 或
 *       PlanDeployer 的交付路径；本单元不改变任何搜索输出、不改 W0 指标。</li>
 *   <li>等价判据只用「胜负 + 回合数 + 终局 HP」，不比较遗物计数/药水/牌堆等资源
 *       （参考项目用 resources() 更全）；因此是<b>保守近似</b>，不能当作实机执行安全证明。</li>
 *   <li>重放必须与原计划同一 {@link SimState} 起点；调用方负责提供未注入真实六流时
 *       仍是「近似」口径的起点，本类不声称真机 L4。</li>
 * </ul>
 */
public final class PlanReducer {

    private PlanReducer() { }

    /** 单次精简的硬上限：与参考项目 caps() 同量级思路，防止长路线卡死。 */
    public static final int MAX_ATTEMPTS = 128;
    public static final int MAX_REPLAYS = 20000;

    public static final class Result {
        public final List<Plan.Turn> turns;
        public final int originalActions;
        public final int reducedActions;
        public final int attempts;
        public final int replays;
        public final boolean capReached;

        Result(List<Plan.Turn> turns, int original, int reduced,
               int attempts, int replays, boolean capReached) {
            this.turns = turns;
            this.originalActions = original;
            this.reducedActions = reduced;
            this.attempts = attempts;
            this.replays = replays;
            this.capReached = capReached;
        }

        public int removed() { return originalActions - reducedActions; }
    }

    /**
     * 对 root 起点重放一份计划，返回终局状态；任一出牌动作找不到手牌即视为
     * <b>候选无效</b>（返回 null），不静默跳过——否则「删一张」会隐式变成「删多张」，
     * 破坏等价判据（AGENTS.md 4.3 不允许跳过动作后仍算通过）。
     */
    private static SimState replay(SimState root, List<Plan.Turn> turns, int[] replayCounter) {
        SimState s = root.fork();
        for (Plan.Turn turn : turns) {
            for (Plan.Action action : turn.actions) {
                if (action == null || action.cardId == null) continue;
                int index = findInHand(s, action.cardId, action.handIndex);
                if (index < 0) return null;
                SimState.Card card = s.hand.remove(index);
                CombatEngine.playCard(s, card, action.targetIndex);
                if (replayCounter != null) replayCounter[0]++;
            }
            CombatEngine.endTurn(s);
        }
        return s;
    }

    private static int findInHand(SimState s, String cardId, int hint) {
        if (hint >= 0 && hint < s.hand.size() && cardId.equals(s.hand.get(hint).id)) return hint;
        for (int i = 0; i < s.hand.size(); i++) {
            if (cardId.equals(s.hand.get(i).id)) return i;
        }
        return -1;
    }

    private static int countActions(List<Plan.Turn> turns) {
        int n = 0;
        for (Plan.Turn t : turns) n += t.actions.size();
        return n;
    }

    private static List<Plan.Turn> deepCopy(List<Plan.Turn> src) {
        // 只复制精简所需的动作引用（Action 本身在重放阶段只读）；回合壳新建，避免改动原计划。
        List<Plan.Turn> out = new ArrayList<Plan.Turn>();
        for (Plan.Turn t : src) {
            Plan.Turn c = new Plan.Turn();
            c.turnNumber = t.turnNumber;
            c.actions.addAll(t.actions);
            out.add(c);
        }
        return out;
    }

    /**
     * 有界精简。只删「单个出牌动作」；删药水/结束回合等不在本单元范围。
     * 任一候选删除后重放若不能保持「同样获胜 + 回合数不增 + 终局 HP 不降」，立即回滚。
     */
    public static Result reduce(SimState root, List<Plan.Turn> source) {
        List<Plan.Turn> best = deepCopy(source);
        int original = countActions(source);
        int[] counter = new int[1];
        SimState baseline = replay(root, best, counter);
        // 原计划自身无法原样重放时不精简（避免用无效基线做等价判断）。
        if (baseline == null) {
            return new Result(deepCopy(source), original, original, 0, counter[0], false);
        }
        int attempts = 0;
        boolean capReached = false;

        outer:
        for (int ti = 0; ti < best.size(); ti++) {
            Plan.Turn turn = best.get(ti);
            for (int ai = 0; ai < turn.actions.size(); ai++) {
                if (attempts >= MAX_ATTEMPTS || counter[0] >= MAX_REPLAYS) {
                    capReached = true;
                    break outer;
                }
                Plan.Action a = turn.actions.get(ai);
                if (a == null || a.cardId == null || a.kind != Plan.Action.KIND_CARD) continue;
                attempts++;
                Plan.Action removed = turn.actions.remove(ai);
                SimState candidate = replay(root, best, counter);
                // 等价判据（与参考项目 _same_victory/turn 思路一致）：仍获胜、回合数不增、终局 HP 不降。
                // 删除已在 best 上执行，通过即保留（更新 baseline 为更短路线），否则回滚。
                boolean keep = candidate != null && candidate.won()
                        && baseline.won()
                        && candidate.turn <= baseline.turn
                        && candidate.hp >= baseline.hp;
                if (keep) {
                    baseline = candidate;
                } else {
                    turn.actions.add(ai, removed);
                }
            }
        }
        return new Result(best, original, countActions(best), attempts, counter[0], capReached);
    }
}
