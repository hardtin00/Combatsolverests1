package com.combatsolver1.search;

import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.SimState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 整场战斗的 Beam Search（可增量推进、可随时中断）。
 *
 * 与 CombatSolver 的划分一致：从稳定根出发，在独立分支上推演“本回合怎么打 → 结束回合 →
 * 怪物行动 → 抽新牌”，逐回合展开并保留一定宽度的候选，直到分出胜负或用完回合/节点预算。
 * 评估顺序为“生存 > 确认胜利 > 整场生命损失”，不只看当前回合伤害。
 */
public class CombatSearch {

    public static class Config {
        public int beamWidth = 6;
        public int withinTurnWidth = 8;
        public int maxTurns = 8;
        public int maxNodes = 4000;
        /** 墙钟时间预算（毫秒）；0 表示不限时，仅用节点/回合预算。 */
        public long timeBudgetMs = 0;
    }

    private Config cfg;
    private int startHp;
    private List<World> beam;
    private World best;
    private int depth;
    private int nodes;
    private boolean done;
    private Plan plan;
    /** 搜索开始时间与已访问状态签名：用于墙钟预算与循环检测。 */
    private long startedAtMs;
    private final java.util.HashSet<String> visited = new java.util.HashSet<String>();
    /** 搜索起点的副本：随机分支复推时从它再 fork，保证起点一致。 */
    private SimState rootState;

    public void begin(SimState root, Config cfg) {
        this.cfg = cfg;
        this.rootState = root.fork();
        this.startHp = root.hp;
        this.beam = new ArrayList<>();
        this.beam.add(new World(root.fork(), new ArrayList<Plan.Turn>()));
        this.best = null;
        this.depth = 0;
        this.nodes = 0;
        this.startedAtMs = System.currentTimeMillis();
        this.visited.clear();
        this.done = false;
        this.plan = null;
    }

    /** 推进一步（展开一个回合）。返回是否已经结束。 */
    public boolean step() {
        if (done) {
            return true;
        }
        if (depth >= cfg.maxTurns || nodes >= cfg.maxNodes) {
            finish();
            return true;
        }
        if (cfg.timeBudgetMs > 0 && System.currentTimeMillis() - startedAtMs >= cfg.timeBudgetMs) {
            finish(); // 时间预算耗尽：给出现在找到的最好路线
            return true;
        }
        List<World> next = new ArrayList<>();
        outer:
        for (World w : beam) {
            List<TurnEnd> ends = expandTurn(w.state);
            for (TurnEnd end : ends) {
                nodes++;
                if (nodes > cfg.maxNodes) {
                    break outer;
                }
                SimState after = end.preEnd.fork();
                CombatEngine.endTurn(after);

                Plan.Turn turn = new Plan.Turn();
                turn.turnNumber = w.state.turn;
                turn.stateSignature = w.state.signature();
                turn.actions.addAll(end.actions);
                turn.hpAfter = after.hp;

                List<Plan.Turn> turns = new ArrayList<>(w.turns);
                turns.add(turn);

                World nw = new World(after, turns);
                nw.score = evaluate(after, startHp);
                next.add(nw);
                if (after.won() || after.playerDead()) {
                    if (best == null || nw.score > best.score) {
                        best = nw;
                    }
                }
            }
        }
        if (next.isEmpty()) {
            finish();
            return true;
        }
        Collections.sort(next, DESC);
        // 状态合并：签名相同的世界只保留分数最高的一个
        List<World> merged = new ArrayList<>(Math.min(cfg.beamWidth, next.size()));
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (World w : next) {
            if (merged.size() >= cfg.beamWidth) {
                break;
            }
            String sig = w.state.signature() + "|e" + w.state.energy + "|t" + w.state.turn;
            // 循环检测：同一状态在更早的深度已经展开过，就不必再展开一次
            boolean revisited = !visited.add(sig);
            if (seen.add(sig) && !revisited) {
                merged.add(w);
            }
        }
        if (!merged.isEmpty()) {
            next = merged;
        }
        beam = next;
        depth++;
        if (depth >= cfg.maxTurns) {
            finish();
            return true;
        }
        return false;
    }

    public void abort() {
        done = true;
        plan = null;
    }

    public boolean isDone() {
        return done;
    }

    public Plan result() {
        return plan;
    }

    /** 当前已展开的回合数，用于面板显示搜索进度。 */
    public int progressDepth() {
        return depth;
    }

    public int progressMaxTurns() {
        return cfg == null ? 0 : cfg.maxTurns;
    }

    /** 已展开的搜索节点数，用于面板的搜索进度。 */
    public int progressNodes() {
        return nodes;
    }

    private void finish() {
        if (done) {
            return;
        }
        done = true;
        World chosen = best;
        if (chosen == null) {
            for (World w : beam) {
                if (chosen == null || w.score > chosen.score) {
                    chosen = w;
                }
            }
        }
        Plan p = new Plan();
        p.startHp = startHp;
        p.nodesSearched = nodes;
        if (chosen != null) {
            p.turns.addAll(chosen.turns);
            p.endHp = chosen.state.hp;
            p.predictedHpLoss = Math.max(0, startHp - chosen.state.hp);
            p.predictedWin = chosen.state.won();
            p.turnsToWin = p.predictedWin ? chosen.turns.size() : -1;
            p.score = chosen.score;
            measureRobustness(chosen.turns, p);
        } else {
            p.endHp = startHp;
        }
        plan = p;
    }

    /**
     * 随机分支复推：把选出的路线在多个不同的随机分支下重放一遍，
     * 给出扣血区间与是否存在失败分支。
     *
     * 洗牌与随机招式目前用的是模拟器内置 RNG（没有复制游戏真实序列），
     * 单次结果可能只是"恰好赌到一次好洗牌"。用区间表达不确定性，
     * 比把一个赌运气的数字当成确定预测要诚实。
     */
    private void measureRobustness(List<Plan.Turn> turns, Plan plan) {
        int samples = 4;
        if (rootState == null || turns.isEmpty()) {
            return;
        }
        int best = Integer.MAX_VALUE;
        int worst = 0;
        boolean allWin = true;
        for (int i = 0; i < samples; i++) {
            SimState s = rootState.fork();
            s.rngState = rootState.rngState + 0x9E3779B97F4A7C15L * (i + 1);
            for (Plan.Turn turn : turns) {
                for (Plan.Action action : turn.actions) {
                    if (action == null || action.cardId == null) {
                        continue;
                    }
                    int index = findInHand(s, action.cardId, action.handIndex);
                    if (index < 0) {
                        continue;
                    }
                    SimState.Card card = s.hand.remove(index);
                    CombatEngine.playCard(s, card, action.targetIndex);
                }
                CombatEngine.endTurn(s);
            }
            int loss = Math.max(0, rootState.hp - s.hp);
            best = Math.min(best, loss);
            worst = Math.max(worst, loss);
            if (!s.won()) {
                allWin = false;
            }
        }
        plan.samples = samples;
        plan.hpLossBest = best == Integer.MAX_VALUE ? 0 : best;
        plan.hpLossWorst = worst;
        plan.robustWin = allWin;
    }

    private static int findInHand(SimState s, String cardId, int hint) {
        if (hint >= 0 && hint < s.hand.size() && cardId.equals(s.hand.get(hint).id)) {
            return hint;
        }
        for (int i = 0; i < s.hand.size(); i++) {
            if (cardId.equals(s.hand.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    /** 展开当前玩家回合：返回若干“结束回合前的候选状态 + 动作序列”。 */
    private List<TurnEnd> expandTurn(SimState state) {
        List<TurnEnd> results = new ArrayList<>();
        results.add(new TurnEnd(state.fork(), new ArrayList<Plan.Action>()));

        int mobHpAtStart = totalMobHp(state);
        int incoming = incomingDamage(state);

        List<SimState> beam = new ArrayList<>();
        List<List<Plan.Action>> actionBeams = new ArrayList<>();
        beam.add(state);
        actionBeams.add(new ArrayList<Plan.Action>());

        // 上限用固定的动作步数，而不是初始手牌数：这样“抽牌后再打出抽到的牌”也能被展开
        for (int step = 0; step < 12; step++) {
            List<SimState> nextStates = new ArrayList<>();
            List<List<Plan.Action>> nextActions = new ArrayList<>();
            List<Double> nextScores = new ArrayList<>();

            for (int b = 0; b < beam.size(); b++) {
                SimState st = beam.get(b);
                List<Plan.Action> acts = actionBeams.get(b);
                for (int hi = 0; hi < st.hand.size(); hi++) {
                    SimState.Card card = st.hand.get(hi);
                    if (!CombatEngine.canPlay(st, card)) {
                        continue;
                    }
                    // 未建模且没有任何效果、还要花能量的牌不推荐；零费牌仍然可以打。
                    if (card.unmodeled && !card.hasEffect() && card.cost > 0) {
                        continue;
                    }
                    if (card.needsTarget && !card.aoe) {
                        for (int t = 0; t < st.mobs.size(); t++) {
                            if (!st.mobs.get(t).alive) {
                                continue;
                            }
                            SimState ns = st.fork();
                            SimState.Card c2 = ns.hand.remove(hi);
                            CombatEngine.playCard(ns, c2, t);
                            List<Plan.Action> na = new ArrayList<>(acts);
                            Plan.Action act = new Plan.Action(c2.id, c2.name, t, c2.cost)
                                    .target(st.mobs.get(t).name);
                            act.handIndex = hi; // 引擎语义：动作发生这一刻的手牌下标
                            na.add(act);
                            nextStates.add(ns);
                            nextActions.add(na);
                            nextScores.add(turnScore(state, ns, incoming, na.size()));
                        }
                    } else {
                        SimState ns = st.fork();
                        SimState.Card c2 = ns.hand.remove(hi);
                        CombatEngine.playCard(ns, c2, -1);
                        List<Plan.Action> na = new ArrayList<>(acts);
                        Plan.Action act = new Plan.Action(c2.id, c2.name, -1, c2.cost);
                        act.handIndex = hi;
                        na.add(act);
                        nextStates.add(ns);
                        nextActions.add(na);
                        nextScores.add(turnScore(state, ns, incoming, na.size()));
                    }
                }
                // 药水：只考虑已建模的药水，并计入机会成本（同分时优先不出药水）
                for (int pi = 0; pi < st.potionIds.size(); pi++) {
                    int[] spec = com.combatsolver1.engine.PotionLibrary.spec(st.potionIds.get(pi));
                    if (spec == null) {
                        continue;
                    }
                    String potionName = st.potionIds.get(pi);
                    if (spec[0] > 0 && spec[1] == 0) {
                        for (int t = 0; t < st.mobs.size(); t++) {
                            if (!st.mobs.get(t).alive) {
                                continue;
                            }
                            SimState ns = st.fork();
                            CombatEngine.usePotion(ns, pi, t);
                            List<Plan.Action> na = new ArrayList<>(acts);
                            Plan.Action pa = new Plan.Action("POTION:" + potionName,
                                    "药水:" + potionName, t, 0);
                            pa.potionIndex = pi;
                            na.add(pa);
                            nextStates.add(ns);
                            nextActions.add(na);
                            nextScores.add(turnScore(state, ns, incoming, na.size()) - 15.0);
                        }
                    } else {
                        SimState ns = st.fork();
                        CombatEngine.usePotion(ns, pi, -1);
                        List<Plan.Action> na = new ArrayList<>(acts);
                        Plan.Action pa = new Plan.Action("POTION:" + potionName,
                                "药水:" + potionName, -1, 0);
                        pa.potionIndex = pi;
                        na.add(pa);
                        nextStates.add(ns);
                        nextActions.add(na);
                        nextScores.add(turnScore(state, ns, incoming, na.size()) - 15.0);
                    }
                }
            }
            if (nextStates.isEmpty()) {
                break;
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < nextStates.size(); i++) {
                order.add(i);
            }
            final List<Double> scores = nextScores;
            Collections.sort(order, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return Double.compare(scores.get(b), scores.get(a));
                }
            });
            beam = new ArrayList<>();
            actionBeams = new ArrayList<>();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < order.size(); i++) {
                if (beam.size() >= cfg.withinTurnWidth) {
                    break;
                }
                int idx = order.get(i);
                // 同签名状态只保留分数最高的一个，避免整条宽度被等价状态占满
                String sig = nextStates.get(idx).signature() + "|e" + nextStates.get(idx).energy
                        + "|h" + nextStates.get(idx).hand.size();
                if (!seen.add(sig)) {
                    continue;
                }
                beam.add(nextStates.get(idx));
                actionBeams.add(nextActions.get(idx));
                results.add(new TurnEnd(nextStates.get(idx).fork(), nextActions.get(idx)));
            }
        }
        return results;
    }

    private double turnScore(SimState start, SimState st, int incoming, int cardsPlayed) {
        int mobHpAtStart = totalMobHp(start);
        int dealt = mobHpAtStart - totalMobHp(st);
        int kills = 0;
        for (SimState.Mob m : st.mobs) {
            if (!m.alive) {
                kills++;
            }
        }
        int effectiveBlock = Math.min(st.block, incoming);
        // 铺垫类动作的价值：力量、能力、过牌、留下的能量（否则搜索会只顾眼前伤害）
        int strengthGained = st.simStrengthBonus - start.simStrengthBonus;
        int powers = powerCount(st) - powerCount(start);
        int drawn = (st.hand.size() + cardsPlayed) - start.hand.size();
        int overkill = 0;
        for (SimState.Mob m : st.mobs) {
            if (m.hp < 0) {
                overkill += -m.hp;
            }
        }
        return dealt
                + kills * 30.0
                + effectiveBlock * 0.6
                + cardsPlayed * 1.0
                + strengthGained * 4.0
                + powers * 15.0
                + drawn * 3.0
                - overkill * 0.4;
    }

    private int powerCount(SimState s) {
        return s.pMetallicize + s.pFeelNoPain + s.pDarkEmbrace + s.pDemonForm + s.pJuggernaut
                + s.pCombust + s.pRupture + s.pThorns + s.pRage + s.pBerserk + s.pBrutality
                + (s.pBarricade ? 1 : 0) + (s.pCorruption ? 1 : 0);
    }

    /** 评估：生存 > 确认胜利 > 整场生命损失。 */
    private double evaluate(SimState st, int startHp) {
        int hpLoss = Math.max(0, startHp - st.hp);
        if (st.playerDead()) {
            return -1000000.0 - hpLoss;
        }
        if (st.won()) {
            return 1000000.0 - hpLoss * 1000.0 - st.turn * 10.0;
        }
        return -hpLoss * 1000.0 - totalMobHp(st) * 5.0 - st.turn * 1.0;
    }

    private int totalMobHp(SimState st) {
        int total = 0;
        for (SimState.Mob m : st.mobs) {
            if (m.alive) {
                total += Math.max(0, m.hp);
            }
        }
        return total;
    }

    private int incomingDamage(SimState st) {
        int total = 0;
        for (SimState.Mob m : st.mobs) {
            if (m.alive) {
                total += m.attackPerTurn(st.firstEnemyTurn);
            }
        }
        return total;
    }

    private static final Comparator<World> DESC = new Comparator<World>() {
        @Override
        public int compare(World a, World b) {
            return Double.compare(b.score, a.score);
        }
    };

    private static class World {
        final SimState state;
        final List<Plan.Turn> turns;
        double score;

        World(SimState state, List<Plan.Turn> turns) {
            this.state = state;
            this.turns = turns;
        }
    }

    private static class TurnEnd {
        final SimState preEnd;
        final List<Plan.Action> actions;

        TurnEnd(SimState preEnd, List<Plan.Action> actions) {
            this.preEnd = preEnd;
            this.actions = actions;
        }
    }
}

