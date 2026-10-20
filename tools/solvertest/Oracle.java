/**
 * 参照求解器（离线评测专用；不进入 Mod，不在游戏内运行）。
 *
 * 目的：给内置 Beam Search 一个「同模拟器、同目标函数」的可穷举参照值，
 * 让“搜索漏掉了更好的合法路线”成为可客观判定的事实，而不是主观印象。
 *
 * 与 CombatSearch 刻意逐字对齐的部分（避免两套实现各打补丁）：
 *   模拟器          CombatEngine.canPlay / playCard / usePotion / endTurn
 *   合法动作集合     canPlay + 未建模耗能无效果牌跳过 + 逐活目标枚举 + 药水须有 PotionLibrary.spec
 *   单回合步数上限   12（与 CombatSearch.expandTurn 的硬截断相同）
 *   回合边界        每个可达的“结束回合前状态”都算一个候选（含一张不打）
 *   目标函数        CombatSearch.evaluate 的逐字镜像（生存 > 确认胜利 > 整场生命损失）
 *   叶子口径        候选集合 C(h) = {h 个回合边界内出现的终局状态（胜/败）}
 *                  ∪ {恰好用满 h 个回合边界的非终局状态}；其余中间状态不计分。
 *                  这与 CombatSearch 到达预算时取叶子的行为同构，因此用满 h 个回合边界的 beam
 *                  不可能超过参照值；beam 若被节点/墙钟提前截断（实际深度 d < h），
 *                  必须改与同深度参照 C(d) 比较——该处理由 SearchQuality 负责
 *   回合预算        同一 horizon
 * 唯一区别是搜索方式：
 *   CombatSearch  回合内按 turnScore 排序截断到 withinTurnWidth，跨回合截断到 beamWidth，
 *                 并用一个全局 visited 集合永久关闭状态；
 *   Oracle        回合内穷举全部合法出牌顺序（回合内转置表去重），
 *                 跨回合用 (剩余回合数, 状态) 记忆化取目标函数最大值。
 *
 * 因此参照值是「该模拟器模型 + 该目标函数 + 该回合预算」内所有完整合法路线的上界，
 * 不是现实游戏的全局最优。触顶（状态数或墙钟）后它只报已验证可达的下界：
 * beam 低于下界仍然证明 beam 漏路，但相等不能证明 beam 最优
 * （Outcome.complete=false 并附原因，报告里如实标注 CAPPED）。
 *
 * 已知不建模给任何一侧的东西（只在报告里如实标注，不算搜索质量问题）：
 *   军备 / 燃烧契约 / 双持 / 头槌 / 战吼 / 掘墓 / 发现 这类需要玩家选择的牌，
 *   模拟器用单一启发式代选，玩家真实可选项不在搜索空间里。
 */

import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.PotionLibrary;
import com.combatsolver1.engine.SimState;
import com.combatsolver1.search.Plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Oracle {

    /** WIN_MIN_LOSS 目标下“预算内无法取胜”的哨兵值，低于任何可达分数。 */
    public static final double NO_WIN = -1.0E9;
    /** 单回合出牌步数上限，与 CombatSearch.expandTurn 的硬截断一致。 */
    public static final int MAX_PLAYS_PER_TURN = 12;

    private Oracle() {
    }

    // ══ 目标函数 ══════════════════════════════════════════════════

    public abstract static class Objective {
        private final String name;

        Objective(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public abstract double value(SimState s, int startHp);
    }

    /** CombatSearch.evaluate 的逐字镜像。 */
    public static final Objective SCORE = new Objective("score") {
        @Override
        public double value(SimState s, int startHp) {
            int hpLoss = Math.max(0, startHp - s.hp);
            if (s.playerDead()) {
                return -1000000.0 - hpLoss;
            }
            if (s.won()) {
                return 1000000.0 - hpLoss * 1000.0 - s.turn * 10.0;
            }
            return -hpLoss * 1000.0 - totalMobHp(s) * 5.0 - s.turn * 1.0;
        }
    };

    /** 玩家可读的第二目标：预算内能赢时比“最少掉血”，赢不了记 NO_WIN。 */
    public static final Objective WIN_MIN_LOSS = new Objective("winMinLoss") {
        @Override
        public double value(SimState s, int startHp) {
            if (s.playerDead() || !s.won()) {
                return NO_WIN;
            }
            return -Math.max(0, startHp - s.hp);
        }
    };

    // ══ 合法动作（与 CombatSearch.expandTurn 的枚举规则一致）══════

    static final class Act {
        final int handIndex;
        final int target;
        final int potionIndex;
        final String label;

        Act(int handIndex, int target, int potionIndex, String label) {
            this.handIndex = handIndex;
            this.target = target;
            this.potionIndex = potionIndex;
            this.label = label;
        }

        boolean isPotion() {
            return potionIndex >= 0;
        }
    }

    /** 与 CombatSearch.expandTurn 相同的合法动作集合。 */
    static List<Act> legalActions(SimState st) {
        List<Act> out = new ArrayList<Act>();
        for (int hi = 0; hi < st.hand.size(); hi++) {
            SimState.Card card = st.hand.get(hi);
            if (!CombatEngine.canPlay(st, card)) {
                continue;
            }
            if (card.unmodeled && !card.hasEffect() && card.cost > 0) {
                continue;
            }
            if (card.needsTarget && !card.aoe) {
                for (int t = 0; t < st.mobs.size(); t++) {
                    if (st.mobs.get(t).alive) {
                        out.add(new Act(hi, t, -1, card.name + "->" + st.mobs.get(t).name));
                    }
                }
            } else {
                out.add(new Act(hi, -1, -1, card.name));
            }
        }
        for (int pi = 0; pi < st.potionIds.size(); pi++) {
            String pid = st.potionIds.get(pi);
            int[] spec = PotionLibrary.spec(pid);
            if (spec == null) {
                continue;
            }
            if (spec[0] > 0 && spec[1] == 0) {
                for (int t = 0; t < st.mobs.size(); t++) {
                    if (st.mobs.get(t).alive) {
                        out.add(new Act(-1, t, pi, "药水:" + pid + "->" + st.mobs.get(t).name));
                    }
                }
            } else {
                out.add(new Act(-1, -1, pi, "药水:" + pid));
            }
        }
        return out;
    }

    static SimState apply(SimState s, Act a) {
        SimState ns = s.fork();
        if (a.isPotion()) {
            CombatEngine.usePotion(ns, a.potionIndex, a.target);
            return ns;
        }
        SimState.Card c = ns.hand.remove(a.handIndex);
        CombatEngine.playCard(ns, c, a.target);
        return ns;
    }

    static SimState finishTurn(SimState st) {
        SimState after = st.fork();
        CombatEngine.endTurn(after);
        return after;
    }

    static int totalMobHp(SimState st) {
        int total = 0;
        for (SimState.Mob m : st.mobs) {
            if (m.alive) {
                total += Math.max(0, m.hp);
            }
        }
        return total;
    }

    // ══ 上下文与预算 ══════════════════════════════════════════════

    static final class Ctx {
        final int startHp;
        final Objective objective;
        final long stateCap;
        final boolean dedup;
        final Map<String, Double> memo = new HashMap<String, Double>();
        final Map<String, List<String>> bestPath = new HashMap<String, List<String>>();
        long turnStarts;
        long expansions;
        long dfsNodes;
        int maxPlaysUsed;
        boolean overCap;
        boolean playCapSeen;
        boolean timedOut;
        long deadlineNanos;

        Ctx(int startHp, Objective objective, long stateCap, boolean dedup) {
            this.startHp = startHp;
            this.objective = objective;
            this.stateCap = stateCap;
            this.dedup = dedup;
        }

        boolean outOfBudget() {
            if (expansions > stateCap || dfsNodes > stateCap) {
                overCap = true;
            }
            if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) {
                timedOut = true;
            }
            return overCap || timedOut;
        }

        double eval(SimState s) {
            return objective.value(s, startHp);
        }

        String key(SimState s, int h) {
            return objective.name() + "|" + h + "|" + s.searchKey();
        }
    }

    /** 一个玩家回合内可达的全部“结束回合前状态”，每个都带一条到达它的动作序列。 */
    static final class TurnReach {
        final Map<String, SimState> states = new HashMap<String, SimState>();
        final Map<String, List<String>> witnesses = new HashMap<String, List<String>>();
        boolean playCapHit;
        long expansions;

        List<String> witness(String key) {
            List<String> w = witnesses.get(key);
            return w == null ? new ArrayList<String>() : w;
        }
    }

    /**
     * 深度优先枚举回合内全部合法出牌顺序；每个访问到的状态都可作为“本回合就此结束”的候选。
     * 回合内转置表只跳过「同一状态被再次展开」，不跳过「同一状态作为候选」，
     * 所以去重不会丢候选（由 SearchQuality 的断言 A 独立验证）。
     */
    /** 供断言使用的独立上下文（不共享记忆化）。 */
    static Ctx newCtx(int startHp, Objective objective, long stateCap, boolean dedup) {
        return new Ctx(startHp, objective, stateCap, dedup);
    }

    static TurnReach enumerateTurn(SimState root, Ctx ctx) {
        TurnReach reach = new TurnReach();
        Set<String> expanded = new HashSet<String>();
        expanded.add(root.searchKey());
        dfsTurn(root, new ArrayList<String>(), 0, reach, expanded, ctx);
        reach.playCapHit = ctx.playCapSeen;
        return reach;
    }

    private static void dfsTurn(SimState st, List<String> path, int plays,
                                TurnReach reach, Set<String> expanded, Ctx ctx) {
        record(reach, st, path);
        if (st.playerDead() || st.won()) {
            return;
        }
        List<Act> acts = legalActions(st);
        if (acts.isEmpty()) {
            return;
        }
        if (plays >= MAX_PLAYS_PER_TURN) {
            ctx.playCapSeen = true;
            return;
        }
        ctx.dfsNodes++;
        if (ctx.outOfBudget()) {
            return;
        }
        for (Act a : acts) {
            SimState child = apply(st, a);
            reach.expansions++;
            List<String> childPath = new ArrayList<String>(path);
            childPath.add(a.label);
            if (expanded.add(child.searchKey())) {
                dfsTurn(child, childPath, plays + 1, reach, expanded, ctx);
            } else {
                record(reach, child, childPath);
            }
        }
    }

    private static void record(TurnReach reach, SimState st, List<String> path) {
        String key = st.searchKey();
        if (!reach.witnesses.containsKey(key)) {
            reach.states.put(key, st.fork());
            reach.witnesses.put(key, new ArrayList<String>(path));
        }
    }

    /**
     * 不做回合内去重的暴力枚举：按出牌序列展开，返回全部可达“结束回合前状态”键。
     * 只用于 SearchQuality 的断言 A2，验证回合内转置表没有丢掉候选状态。
     */
    static final class BruteResult {
        final java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<String>();
        long nodes;
        boolean playCapHit;
        boolean overCap;
    }

    static BruteResult bruteTurn(SimState root, long nodeCap) {
        BruteResult res = new BruteResult();
        brute(root, 0, res, nodeCap);
        return res;
    }

    private static void brute(SimState st, int plays, BruteResult res, long nodeCap) {
        res.nodes++;
        res.keys.add(st.searchKey());
        if (st.playerDead() || st.won()) {
            return;
        }
        if (plays >= MAX_PLAYS_PER_TURN) {
            res.playCapHit = true;
            return;
        }
        if (res.nodes > nodeCap) {
            res.overCap = true;
            return;
        }
        for (Act a : legalActions(st)) {
            brute(apply(st, a), plays + 1, res, nodeCap);
        }
    }

    // ══ 参照值 ════════════════════════════════════════════════════

    public static final class Outcome {
        public double value;
        public boolean complete;
        public String incompleteReason = "";
        public long turnStarts;
        public long turnEndStates;
        public long dfsExpansions;
        public int maxPlaysUsed;
        public List<String> line = new ArrayList<String>();

        public String status() {
            return complete ? "EXACT" : "CAPPED";
        }
    }

    /**
     * solve(s, h)：s 是某个玩家回合开始的状态，h 是还能推进的回合边界数。
     * 只有终局（胜/败）或 h 用尽时的状态才计分，因此参照值 = 恰好 h 个回合边界内
     * 全部合法路线的最大目标值，与用满 h 个回合边界的 beam 同深度可比。
     */
    private static double solve(Ctx ctx, SimState s, int h) {
        if (s.playerDead() || s.won()) {
            return ctx.eval(s);
        }
        String key = ctx.key(s, h);
        Double hit = ctx.memo.get(key);
        if (hit != null) {
            return hit.doubleValue();
        }
        ctx.turnStarts++;
        ctx.expansions++;
        if (ctx.outOfBudget()) {
            // 触顶：本状态无法完整求解，只能按当前状态给一个可达值并整体标为 CAPPED
            double leaf = ctx.eval(s);
            ctx.memo.put(key, Double.valueOf(leaf));
            return leaf;
        }
        if (h <= 0) {
            double leaf = ctx.eval(s);
            ctx.memo.put(key, Double.valueOf(leaf));
            return leaf;
        }
        if (!ctx.dedup) {
            return expandNoMemo(ctx, s, h);
        }
        TurnReach reach = enumerateTurn(s, ctx);
        // 叶子口径与 CombatSearch 严格一致：只有「终局」或「恰好用尽 h 个回合边界」的状态才计分。
        // 未用尽预算的中间状态不计分，否则参照会拿到更小的 -turn 惩罚而虚高（造成假漏优）。
        double best = Double.NEGATIVE_INFINITY;
        List<String> bestPath = null;
        for (Map.Entry<String, SimState> e : reach.states.entrySet()) {
            List<String> path = reach.witness(e.getKey());
            if (path.size() > ctx.maxPlaysUsed) {
                ctx.maxPlaysUsed = path.size();
            }
            SimState after = finishTurn(e.getValue());
            double v = solve(ctx, after, h - 1);
            if (v > best) {
                best = v;
                bestPath = path;
            }
        }
        ctx.memo.put(key, Double.valueOf(best));
        ctx.bestPath.put(key, bestPath);
        return best;
    }

    /** 验证模式：完全不使用跨回合记忆化，逐分支重新展开（只用于极小场景）。 */
    private static double expandNoMemo(Ctx ctx, SimState s, int h) {
        ctx.turnStarts++;
        ctx.expansions++;
        if (h <= 0 || s.playerDead() || s.won() || ctx.outOfBudget()) {
            return ctx.eval(s);
        }
        TurnReach reach = enumerateTurn(s, ctx);
        double best = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, SimState> e : reach.states.entrySet()) {
            double v = expandNoMemo(ctx, finishTurn(e.getValue()), h - 1);
            if (v > best) {
                best = v;
            }
        }
        return best;
    }

    /** 参照值：stateCap 是展开状态上限，触顶即结果降级为下界。 */
    public static Outcome reference(SimState root, int horizon, Objective objective, long stateCap) {
        return reference(root, horizon, objective, stateCap, true, 0L);
    }

    /** deadlineMs > 0 时超时即停止展开，结果降级为下界（CAPPED）。 */
    public static Outcome reference(SimState root, int horizon, Objective objective,
                                    long stateCap, long deadlineMs) {
        return reference(root, horizon, objective, stateCap, true, deadlineMs);
    }

    /** dedup=false 时关闭跨回合记忆化，用来独立验证去重没有丢状态。 */
    public static Outcome reference(SimState root, int horizon, Objective objective,
                                    long stateCap, boolean dedup) {
        return reference(root, horizon, objective, stateCap, dedup, 0L);
    }

    public static Outcome reference(SimState root, int horizon, Objective objective, long stateCap,
                                    boolean dedup, long deadlineMs) {
        Ctx ctx = new Ctx(root.hp, objective, stateCap, dedup);
        if (deadlineMs > 0) {
            ctx.deadlineNanos = System.nanoTime() + deadlineMs * 1000000L;
        }
        double v = solve(ctx, root, horizon);
        Outcome out = new Outcome();
        out.value = v;
        out.turnStarts = ctx.turnStarts;
        out.turnEndStates = ctx.expansions;
        out.dfsExpansions = ctx.dfsNodes;
        out.maxPlaysUsed = ctx.maxPlaysUsed;
        List<String> reasons = new ArrayList<String>();
        if (ctx.overCap) {
            reasons.add("状态数触顶" + stateCap);
        }
        if (ctx.timedOut) {
            reasons.add("墙钟触顶" + deadlineMs + "ms");
        }
        if (ctx.playCapSeen) {
            reasons.add("回合出牌步数触顶" + MAX_PLAYS_PER_TURN);
        }
        out.complete = reasons.isEmpty();
        out.incompleteReason = reasons.isEmpty() ? "" : join(reasons, "+");
        List<String> path = ctx.bestPath.get(ctx.key(root, horizon));
        if (path != null) {
            out.line.add("参照最优首回合出牌 " + path.size() + " 张: " + join(path, " "));
        }
        return out;
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    /** 内置搜索在同一口径下的分数（Plan.score / 胜利与掉血）。 */
    public static double planValue(Plan plan, Objective objective) {
        if (plan == null) {
            return Double.NEGATIVE_INFINITY;
        }
        if (objective == SCORE) {
            return plan.score;
        }
        return plan.predictedWin ? -plan.predictedHpLoss : NO_WIN;
    }

    public static String fmt(double v) {
        if (Double.isNaN(v)) {
            return "无";
        }
        if (v == Double.NEGATIVE_INFINITY) {
            return "无完整路线";
        }
        if (v <= NO_WIN / 2) {
            return "预算内无必胜";
        }
        if (v <= -100000.0) {
            return "判负";
        }
        return String.format("%.1f", v);
    }
}
