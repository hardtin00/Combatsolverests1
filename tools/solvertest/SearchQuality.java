import com.combatsolver1.engine.CardLibrary;
import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.SimState;
import com.combatsolver1.search.CombatSearch;
import com.combatsolver1.search.Plan;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P0 搜索质量基准（离线评测专用；不进入 Mod，不在游戏内运行）。
 *
 * 回答一个此前无法客观回答的问题：内置 Beam Search 在同一模拟器、同一目标函数、
 * 同一回合预算下，有没有错过可证更优的合法路线？比例多大？是哪一层截断造成的？
 *
 * 参照值来自 Oracle（同模型穷举，口径对齐见 Oracle.java 头部说明）：
 *   漏优 = 实测 Plan.score 低于同预算参照值。
 *   参照 EXACT 时漏优是确定的搜索缺陷；参照 CAPPED 时参照值只是下界，
 *   beam 低于下界仍属确定漏优，但“没低于下界”不能证明 beam 最优。
 *
 * 两个层级：
 *   PROVABLE 回合预算 2（当前回合 + 下一回合）：参照可在极小空间内穷举，给出可证的漏优率；
 *   BUDGET   回合预算 = 玩家设置（默认「中」档 maxTurns）：接近实战，参照可能 CAPPED。
 *
 * 归因（在 PROVABLE 层级逐项放宽，看漏优是否消失）：
 *   WITHIN 回合内宽度 withinTurnWidth / BEAM 跨回合宽度 beamWidth
 *   NODES 节点预算 maxNodes / TIME 墙钟预算 timeBudgetMs
 *   RESIDUAL 以上放宽都不变，指向全局 visited 永久关闭或打分口径差异
 *
 * 用法：
 *   java SearchQuality                    两个层级的漏优率与归因
 *   java SearchQuality --selftest         只跑参照求解器的完整性断言
 *   java SearchQuality --verbose          附带参照路线、beam 路线与耗时
 *   java SearchQuality --preset 0|1|2|3   按对应档位预算（默认 1「中」）
 *   java SearchQuality --out 文件路径     同时写出 UTF-8 报告
 *
 * 退出码：0 无漏优且自检通过；1 参照自检失败（数字不可信）；2 存在漏优。
 */
public final class SearchQuality {

    static final double EPS = 1e-6;

    /** PROVABLE 层级的回合预算：小空间内参照一定能穷举完。 */
    static final int PROVABLE_HORIZON = 2;

    /** 参照求解的状态上限与墙钟上限（触顶即降级为下界）。 */
    static long oracleStateCap = 300000L;
    static long oracleDeadlineMs = 12000L;
    /** 归因探测的参照预算：多次探测，单次给得更省，避免整轮基准耗时失控。 */
    static long attributeDeadlineMs = 5000L;

    /** 断言用的宽预算：beam 放宽到这里仍不能超过参照值，否则参照本身有问题。 */
    static final int CHECK_BEAM_WIDTH = 64;
    static final int CHECK_WITHIN_WIDTH = 64;
    static final int CHECK_MAX_NODES = 400000;

    // 玩家预算：一律由 applyPreset() 从 SolverSettings 取，初值无意义（main 里必先覆盖）
    static int beamWidth;
    static int withinTurnWidth;
    static int maxTurns;
    static int maxNodes;
    static long timeBudgetMs;
    static int presetIndex = 1;

    static boolean verbose = false;
    /** --sweep：宽度网格诊断，只看达到参照值所需的最小宽度与其时间代价。 */
    static boolean sweep = false;
    /** --wide：玩家回合预算下的宽度成本探测。 */
    static boolean wide = false;

    static final class Case {
        final String id;
        final String intent;
        final SimState root;

        Case(String id, String intent, SimState root) {
            this.id = id;
            this.intent = intent;
            this.root = root;
        }
    }

    static final class Level {
        String status = "-";
        /** 与 beam 实际深度同深度的可穷举参照 C(d)：漏优判定的基准。 */
        double ref = Double.NaN;
        /** 按玩家设定回合预算的参照 C(h)：仅用于说明“预算没用完”。 */
        double refFull = Double.NaN;
        double beam = Double.NaN;
        int beamDepth;
        int horizon;
        boolean budgetUsedUp;
        boolean miss;
        /** CAPPED 参照下的提示：不计入漏优率。 */
        boolean hint;
        String attribution = "";
        String reason = "";
        long refMs;
        long refExpansions;
        long refDfsNodes;
        long beamMs;
        int beamNodes;
        int beamTurns;
    }

    static final class Row {
        final Case caze;
        final Level provable = new Level();
        final Level budget = new Level();
        boolean winMiss;
        String winNote = "";

        Row(Case c) {
            this.caze = c;
        }
    }

    public static void main(String[] args) {
        boolean selftest = false;
        String out = null;
        for (int i = 0; i < args.length; i++) {
            if ("--verbose".equals(args[i])) {
                verbose = true;
            } else if ("--selftest".equals(args[i])) {
                selftest = true;
            } else if ("--sweep".equals(args[i])) {
                sweep = true;
            } else if ("--wide".equals(args[i])) {
                wide = true;
            } else if ("--preset".equals(args[i]) && i + 1 < args.length) {
                presetIndex = Integer.parseInt(args[++i]);
            } else if ("--out".equals(args[i]) && i + 1 < args.length) {
                out = args[++i];
            }
        }
        // 无条件按档位取预算：不传 --preset 也必须用运行时默认档，否则基准测的是本文件初值，
        // 与玩家实际配置无关（上一轮就是这么把旧宽度当成已加宽的配置测了一遍，数字毫无变化）。
        applyPreset(presetIndex);

        StringBuilder report = new StringBuilder();
        say(report, "CombatSolver1 搜索质量基准 P0（内置 Beam Search vs 可穷举参照）");
        say(report, "预算（取自运行时 SolverSettings.applyTo，档位 " + presetIndex + "）：beamWidth="
                + beamWidth + " withinTurnWidth=" + withinTurnWidth
                + " maxTurns=" + maxTurns + " maxNodes=" + maxNodes + " timeBudgetMs=" + timeBudgetMs);
        say(report, "参照预算：stateCap=" + oracleStateCap + " deadline=" + oracleDeadlineMs + "ms");
        say(report, "口径：双方共用 CombatEngine 结算与 CombatSearch.evaluate 打分；参照候选集 = 终局状态 加 恰好用满预算的回合边界状态，");
        say(report, "      与 beam 取叶子的规则同构，因此参照既是上界又一定是可达值；");
        say(report, "      唯一差别是搜索方式，所以漏优只能来自搜索（截断、去重、预算），不来自规则差异。");
        say(report, "");

        List<Case> cases = buildCorpus(report);

        if (wide) {
            int f = runWideProbe(cases, report);
            write(out, report);
            System.exit(f);
            return;
        }

        if (sweep) {
            int f = runSweep(cases, report);
            write(out, report);
            System.exit(f);
            return;
        }

        StringBuilder self = new StringBuilder();
        int oracleFail = runSelfTest(cases, self);
        if (report != null) {
            report.append(self);
        }

        if (selftest) {
            write(out, report);
            System.exit(oracleFail == 0 ? 0 : 1);
        }

        List<Row> rows = new ArrayList<Row>();
        for (Case c : cases) {
            rows.add(measure(c, report));
        }
        summarize(rows, report);
        write(out, report);

        if (oracleFail != 0) {
            System.out.println("参照求解器自检未通过（失败 " + oracleFail + " 项）：下面的漏优数字不可信。");
            System.exit(1);
        }
        boolean anyMiss = false;
        for (Row r : rows) {
            if (r.provable.miss || r.budget.miss || r.winMiss) {
                anyMiss = true;
            }
        }
        System.exit(anyMiss ? 2 : 0);
    }

    // ══ 单场景度量 ════════════════════════════════════════════════

    static Row measure(Case c, StringBuilder report) {
        Row row = new Row(c);
        say(report, "── " + c.id + "  " + c.intent);

        measureLevel(c, row.provable, PROVABLE_HORIZON);
        measureLevel(c, row.budget, maxTurns);

        // 玩家最在意的漏优：参照存在完整获胜路线，beam 却没给出获胜预测
        Oracle.Outcome refWin = Oracle.reference(c.root.fork(), maxTurns, Oracle.WIN_MIN_LOSS,
                oracleStateCap, oracleDeadlineMs);
        Plan p = runBeam(c.root.fork(), budget(maxTurns));
        // 参照值取自真实可达状态：即使 CAPPED，超过哨兵值也说明它已经找到一条可达的必胜路线
        if (refWin.value > Oracle.NO_WIN / 2 && (p == null || !p.predictedWin)) {
            row.winMiss = true;
            row.winNote = "参照已找到 " + maxTurns + " 回合内的可达必胜路线（" + refWin.status() + "），beam 未给出获胜预测";
        } else if (refWin.complete && refWin.value <= Oracle.NO_WIN / 2 && p != null && p.predictedWin) {
            row.winMiss = true;
            row.winNote = "beam 宣称可赢，但可穷举参照判定该预算内不存在必胜路线（预测过满）";
        }

        say(report, fmtLevel("PROVABLE(" + PROVABLE_HORIZON + "回合)", row.provable));
        say(report, fmtLevel("BUDGET(" + maxTurns + "回合)", row.budget));
        if (row.winMiss) {
            say(report, "  获胜性漏优：" + row.winNote);
        }
        if (refWin.complete && refWin.value <= Oracle.NO_WIN / 2) {
            say(report, "  参照判定：该场景在 " + maxTurns
                    + " 回合内不存在必胜路线（beam 若宣称能赢即为过满预测）");
        }
        if (row.provable.hint || row.budget.hint) {
            say(report, "  提示：存在 beam 低于未穷举完参照的情形，跨深度不可比，未计入漏优率。");
        }
        if (verbose) {
            for (String l : refWin.line) {
                say(report, "  参照路线(" + refWin.status() + ")：" + l);
            }
        }
        if (verbose) {
            Plan p2 = runBeam(c.root.fork(), budget(maxTurns));
            say(report, "  beam 路线：" + (p2 == null ? "无" : p2.turns.size() + " 回合 / "
                    + p2.nodesSearched + " 节点 / 预测扣血 " + p2.predictedHpLoss + " / "
                    + (p2.predictedWin ? "获胜" : "未获胜")));
            if (p2 != null) {
                for (Plan.Turn t : p2.turns) {
                    StringBuilder sb = new StringBuilder("    第" + t.turnNumber + "回合 "
                            + t.actions.size() + " 张 -> " + t.hpAfter + "hp:");
                    for (Plan.Action a : t.actions) {
                        sb.append(' ').append(a.cardName);
                    }
                    say(report, sb.toString());
                }
            }
            say(report, "  BUDGET 参照耗时 " + row.budget.refMs + "ms（回合状态 " + row.budget.refExpansions
                    + "，回合内展开 " + row.budget.refDfsNodes + "），beam 耗时 " + row.budget.beamMs
                    + "ms / " + row.budget.beamNodes + " 节点 / " + row.budget.beamTurns + " 回合");
            say(report, "  PROVABLE 参照耗时 " + row.provable.refMs + "ms（回合状态 " + row.provable.refExpansions
                    + "，回合内展开 " + row.provable.refDfsNodes + "）");
        }
        say(report, "");
        return row;
    }

    static String fmtLevel(String label, Level lv) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(pad(label, 18)).append(" ").append(pad(lv.status, 6))
          .append(" 参照C(").append(lv.beamDepth).append(")=").append(pad(Oracle.fmt(lv.ref), 11))
          .append(" beam=").append(pad(Oracle.fmt(lv.beam), 12))
          .append(" 深度").append(lv.beamDepth).append("/").append(lv.horizon).append(" 漏优=").append(yesNo(lv.miss));
        if (!lv.attribution.isEmpty()) {
            sb.append("  ").append(lv.attribution);
        }
        if (!lv.reason.isEmpty()) {
            sb.append("  [").append(lv.reason).append("]");
        }
        return sb.toString();
    }

    /**
     * 同深度对照：beam 常被节点/墙钟提前截断，因此漏优必须拿它「实际展开深度 d」的参照集合 C(d)
     * 来比。拿 C(h) 比只会报出「预算没用完」，那不是搜索缺陷。
     */
    static void measureLevel(Case c, Level lv, int horizon) {
        lv.horizon = horizon;
        long t1 = System.currentTimeMillis();
        CombatSearch search = newSearch(c.root.fork(), budget(horizon));
        Plan plan = drive(search);
        lv.beamMs = System.currentTimeMillis() - t1;
        lv.beam = Oracle.planValue(plan, Oracle.SCORE);
        lv.beamNodes = plan == null ? 0 : plan.nodesSearched;
        lv.beamTurns = plan == null ? 0 : plan.turns.size();
        lv.beamDepth = Math.max(0, search.progressDepth());
        lv.budgetUsedUp = lv.beamDepth >= horizon;

        long t0 = System.currentTimeMillis();
        Oracle.Outcome ref = Oracle.reference(c.root.fork(), lv.beamDepth, Oracle.SCORE,
                oracleStateCap, oracleDeadlineMs);
        lv.refMs = System.currentTimeMillis() - t0;
        lv.refExpansions = ref.turnEndStates;
        lv.refDfsNodes = ref.dfsExpansions;
        lv.ref = ref.value;
        lv.status = ref.complete ? "EXACT" : "CAPPED";
        lv.reason = ref.incompleteReason;
        if (!lv.budgetUsedUp) {
            lv.reason = "beam 只展开 " + lv.beamDepth + "/" + horizon + " 个回合边界（预算没用完），"
                    + "已与同深度参照比较" + (ref.incompleteReason.isEmpty() ? ""
                    : "；" + ref.incompleteReason);
            Oracle.Outcome full = Oracle.reference(c.root.fork(), horizon, Oracle.SCORE,
                    oracleStateCap, oracleDeadlineMs);
            lv.refFull = full.value;
            lv.reason = lv.reason + (full.complete
                    ? "；C(" + horizon + ")=" + Oracle.fmt(full.value)
                    : "；C(" + horizon + ") 未穷举完");
        }

        boolean below = !isNegInf(lv.beam) && lv.beam < lv.ref - EPS;
        // 只有参照穷举完成（EXACT）时差距才能判为漏优：CAPPED 时参照值来自某个可达中间状态，
        // 与 beam 的深度不一定同构，跨深度分数不可比，只能作为提示。
        lv.miss = ref.complete && below;
        lv.hint = !ref.complete && below;
        if (lv.miss) {
            String base = attribute(c, lv.beamDepth);
            boolean beamDead = plan != null && plan.endHp <= 0;
            boolean refAlive = lv.ref > -100000.0;
            if (beamDead && refAlive) {
                // 只是症状描述：finish() 已改为终局与存活叶子同集合取优，因此这里交出死亡路线
                // 说明存活叶子压根没进 beam 的最终集合（被宽度剪掉），真正原因看后面的 WITHIN/BEAM
                base = "DEAD_OUT（交出死亡路线，而同深度存在存活路线）"
                        + (base.isEmpty() ? "" : " / " + base);
            }
            lv.attribution = base;
        } else if (lv.hint) {
            lv.attribution = "提示：beam 低于未穷举完的参照，跨深度不可比，不计入漏优";
        } else if ("CAPPED".equals(lv.status)) {
            lv.attribution = "参照仅下界：不能据此判定 beam 最优";
        } else {
            lv.attribution = "与同深度可穷举参照同分";
        }
    }

    static CombatSearch newSearch(SimState root, CombatSearch.Config cfg) {
        CombatSearch search = new CombatSearch();
        search.begin(root, cfg);
        return search;
    }

    static Plan drive(CombatSearch search) {
        int guard = 0;
        while (!search.isDone() && guard++ < 4000) {
            search.step();
        }
        return search.result();
    }

    /** 归因：在同一深度下单独放宽各预算，看漏优是否消失。 */
    static String attribute(Case c, int depth) {
        List<String> hits = new ArrayList<String>();
        List<String> unknown = new ArrayList<String>();
        if (misses(c, budget(depth)) == NO_MISS) {
            return "基线不可复现（同参数重跑不再漏优，说明结果受墙钟影响而不稳定）";
        }
        probe(c, depth, hits, unknown, "WITHIN", 48, beamWidth, maxNodes, timeBudgetMs);
        probe(c, depth, hits, unknown, "BEAM", withinTurnWidth, 48, maxNodes, timeBudgetMs);
        probe(c, depth, hits, unknown, "BEAM+WITHIN", 48, 48, maxNodes, timeBudgetMs);
        probe(c, depth, hits, unknown, "NODES", withinTurnWidth, beamWidth, CHECK_MAX_NODES, timeBudgetMs);
        probe(c, depth, hits, unknown, "TIME", withinTurnWidth, beamWidth, maxNodes, 0L);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(hits.get(i));
        }
        if (hits.isEmpty()) {
            sb.append(unknown.isEmpty()
                    ? "RESIDUAL（同深度放宽宽度/节点/时间都不变：指向全局 visited 永久关闭或叶子选择口径）"
                    : "无法归因（放宽后参照穷举不完：" + String.join(",", unknown) + "）");
        }
        return sb.toString();
    }

    /** 单独放宽一个预算维度，看该层截断是否就是漏优的直接原因。 */
    static void probe(Case c, int depth, List<String> hits, List<String> unknown,
                      String tag, int within, int beam, int nodes, long time) {
        CombatSearch.Config cfg = new CombatSearch.Config();
        cfg.maxTurns = depth;
        cfg.withinTurnWidth = within;
        cfg.beamWidth = beam;
        cfg.maxNodes = nodes;
        cfg.timeBudgetMs = time;
        int r = misses(c, cfg);
        if (r == NO_MISS) {
            hits.add(tag);
        } else if (r == UNKNOWN) {
            unknown.add(tag);
        }
    }

    /** 判定三态：漏优 / 不漏 / 参照没穷举完因而无法判定。 */
    static final int MISS = 1;
    static final int NO_MISS = 0;
    static final int UNKNOWN = -1;

    /**
     * 该预算配置下 beam 是否仍漏优。必须按该配置自己的实际深度取参照：
     * 放宽预算会让 beam 走得更深，而分数跨深度不可直接比较。
     */
    static int misses(Case c, CombatSearch.Config cfg) {
        CombatSearch search = newSearch(c.root.fork(), cfg);
        Plan plan = drive(search);
        double score = Oracle.planValue(plan, Oracle.SCORE);
        int d = Math.max(0, search.progressDepth());
        Oracle.Outcome ref = Oracle.reference(c.root.fork(), d, Oracle.SCORE,
                oracleStateCap, attributeDeadlineMs);
        if (!ref.complete) {
            return UNKNOWN;
        }
        if (isNegInf(score)) {
            return MISS;
        }
        return score < ref.value - EPS ? MISS : NO_MISS;
    }

    static double runScore(Case c, CombatSearch.Config cfg) {
        return Oracle.planValue(runBeam(c.root.fork(), cfg), Oracle.SCORE);
    }

    static Plan runBeam(SimState root, CombatSearch.Config cfg) {
        CombatSearch search = new CombatSearch();
        search.begin(root, cfg);
        int guard = 0;
        while (!search.isDone() && guard++ < 500) {
            search.step();
        }
        return search.result();
    }

    static CombatSearch.Config budget(int horizon) {
        CombatSearch.Config cfg = new CombatSearch.Config();
        cfg.beamWidth = beamWidth;
        cfg.withinTurnWidth = withinTurnWidth;
        cfg.maxTurns = horizon;
        cfg.maxNodes = maxNodes;
        cfg.timeBudgetMs = timeBudgetMs;
        return cfg;
    }

    /**
     * 预算一律取自 SolverSettings.applyTo()，与游戏内实际用的档位同一权威实现；
     * 基准不得自带一份档位数字，否则测到的不是玩家真正会用到的配置。
     */
    static void applyPreset(int presetIndex) {
        com.combatsolver1.settings.SolverSettings s = new com.combatsolver1.settings.SolverSettings();
        s.preset = presetIndex;
        CombatSearch.Config cfg = new CombatSearch.Config();
        s.applyTo(cfg);
        beamWidth = cfg.beamWidth;
        withinTurnWidth = cfg.withinTurnWidth;
        maxTurns = cfg.maxTurns;
        maxNodes = cfg.maxNodes;
        timeBudgetMs = cfg.timeBudgetMs;
    }

    // ══ 参照求解器自检 ════════════════════════════════════════════

    /**
     * 参照必须先证明自己没错，漏优数字才有意义。
     * A1 跨回合记忆化不改变结果：同场景开/关记忆化取值一致，且都未触顶。
     * A2 回合内转置表不丢候选：去重枚举到的“回合结束前状态集合”是暴力按序枚举集合的子集且相等。
     * B  参照是上界：beam 在远宽预算下也不得超过参照值（EXACT 场景）。
     * C  可复现：同输入两次参照值一致。
     * D  语义一致：beam 路线能在同一模拟器上原样重放到同一终局分数。
     */
    static int runSelfTest(List<Case> cases, StringBuilder report) {
        say(report, "══ 参照求解器自检 ══");
        int pass = 0;
        int fail = 0;
        for (Case c : cases) {
            // A1
            Oracle.Outcome m1 = Oracle.reference(c.root.fork(), 2, Oracle.SCORE, oracleStateCap, true);
            Oracle.Outcome m2 = Oracle.reference(c.root.fork(), 2, Oracle.SCORE, oracleStateCap, false);
            boolean a1 = m1.complete && m2.complete && Math.abs(m1.value - m2.value) < EPS;
            if (record(report, a1, c.id + " A1 跨回合记忆化不改变参照值",
                    Oracle.fmt(m1.value) + " vs " + Oracle.fmt(m2.value))) {
                pass++;
            } else {
                fail++;
            }

            // A2
            Oracle.TurnReach dedup = Oracle.enumerateTurn(c.root.fork(), freshCtx(c));
            Oracle.BruteResult brute = Oracle.bruteTurn(c.root.fork(), oracleStateCap);
            Set<String> dedupKeys = new LinkedHashSet<String>(dedup.states.keySet());
            boolean a2 = !brute.overCap && !dedup.playCapHit && dedupKeys.equals(brute.keys);
            if (record(report, a2, c.id + " A2 回合内去重不丢候选状态",
                    "去重 " + dedupKeys.size() + " 个 vs 暴力 " + brute.keys.size() + " 个")) {
                pass++;
            } else {
                fail++;
            }

            // C
            Oracle.Outcome r1 = Oracle.reference(c.root.fork(), PROVABLE_HORIZON, Oracle.SCORE,
                    oracleStateCap, oracleDeadlineMs);
            Oracle.Outcome r2 = Oracle.reference(c.root.fork(), PROVABLE_HORIZON, Oracle.SCORE,
                    oracleStateCap, oracleDeadlineMs);
            boolean cc = r1.complete && r2.complete && Math.abs(r1.value - r2.value) < EPS;
            if (record(report, cc, c.id + " C 参照值可复现", "")) {
                pass++;
            } else {
                fail++;
            }

            // B
            CombatSearch.Config wide = new CombatSearch.Config();
            wide.beamWidth = CHECK_BEAM_WIDTH;
            wide.withinTurnWidth = CHECK_WITHIN_WIDTH;
            wide.maxTurns = PROVABLE_HORIZON;
            wide.maxNodes = CHECK_MAX_NODES;
            wide.timeBudgetMs = 0L;
            double wideScore = Oracle.planValue(runBeam(c.root.fork(), wide), Oracle.SCORE);
            boolean b = !r1.complete || isNegInf(wideScore) || wideScore <= r1.value + EPS;
            if (record(report, b, c.id + " B beam 宽预算不超过参照值",
                    "beamWide=" + Oracle.fmt(wideScore) + " ref=" + Oracle.fmt(r1.value))) {
                pass++;
            } else {
                fail++;
            }

            // D
            Plan plan = runBeam(c.root.fork(), budget(PROVABLE_HORIZON));
            SimState replayed = replay(c.root.fork(), plan);
            boolean d = replayed != null
                    && Math.abs(Oracle.SCORE.value(replayed, c.root.hp) - Oracle.planValue(plan, Oracle.SCORE)) < 1.0;
            if (record(report, d, c.id + " D beam 路线可原样重放且终局一致",
                    replayed == null ? "重放失败"
                            : "重放分 " + Oracle.fmt(Oracle.SCORE.value(replayed, c.root.hp))
                                    + " vs Plan.score " + Oracle.planValue(plan, Oracle.SCORE))) {
                pass++;
            } else {
                fail++;
            }
        }
        say(report, "自检汇总：通过 " + pass + " 项，失败 " + fail
                + (fail == 0 ? "（参照可信，漏优判定成立）" : "（参照不可信，先修 oracle，漏优数字作废）"));
        say(report, "");
        return fail;
    }

    /** 只为断言 A2 构造一个未触顶的回合内枚举上下文。 */
    static Oracle.Ctx freshCtx(Case c) {
        return Oracle.newCtx(c.root.hp, Oracle.SCORE, oracleStateCap, true);
    }

    /** 按 cardId/药水名逐动作重放；任一步在模拟器里打不出即视为语义不一致。 */
    static SimState replay(SimState s, Plan plan) {
        if (plan == null || plan.turns.isEmpty()) {
            return null;
        }
        for (Plan.Turn turn : plan.turns) {
            for (Plan.Action a : turn.actions) {
                if (a == null) {
                    return null;
                }
                if (a.potionIndex >= 0 || (a.cardId != null && a.cardId.startsWith("POTION:"))) {
                    String pid = a.cardId == null ? null : a.cardId.substring("POTION:".length());
                    int idx = pid == null ? -1 : s.potionIds.indexOf(pid);
                    if (idx < 0) {
                        return null;
                    }
                    CombatEngine.usePotion(s, idx, a.targetIndex);
                    continue;
                }
                int hi = -1;
                if (a.handIndex >= 0 && a.handIndex < s.hand.size()
                        && a.cardId.equals(s.hand.get(a.handIndex).id)) {
                    hi = a.handIndex;
                } else {
                    for (int i = 0; i < s.hand.size(); i++) {
                        if (a.cardId.equals(s.hand.get(i).id)) {
                            hi = i;
                            break;
                        }
                    }
                }
                if (hi < 0) {
                    return null;
                }
                SimState.Card card = s.hand.get(hi);
                if (!CombatEngine.canPlay(s, card)) {
                    return null;
                }
                s.hand.remove(hi);
                CombatEngine.playCard(s, card, a.targetIndex);
            }
            CombatEngine.endTurn(s);
        }
        return s;
    }

    /**
     * 宽度网格诊断：在 PROVABLE 回合预算下，对每个场景扫描 (withinTurnWidth, beamWidth)，
     * 记录是否与同深度参照同分、beam 耗时与节点数。用来回答「零漏优需要多宽、代价多少」，
     * 而不是凭感觉调参数。maxNodes 放开、墙钟关闭，确保测的是剪枝本身而不是预算耗尽。
     */
    /**
     * 深度成本测量：在玩家真实回合预算下跑候选宽度，回答「加宽要多花时间」，
     * 以及默认宽度下会误判的场景（Q9 报必败、Q8/Q7 未报胜）在加宽后是否改变。
     * maxNodes 放开、墙钟关闭：这里只测剪枝代价，不让预算耗尽混淆结论。
     */
    static int runWideProbe(List<Case> cases, StringBuilder report) {
        int[][] cands = {
                {12, 8}, {16, 12}, {24, 16}, {32, 24}, {48, 32}, {64, 48}
        };
        say(report, "══ 深度成本探测（回合预算 " + maxTurns + "，maxNodes 放开、墙钟关闭）══");
        say(report, "列含义：ms=beam 耗时，节点=progressNodes，回合=路线长度，分数=Plan.score，");
        say(report, "       HP=预测结束生命（<=0 即报必败），胜=beam 是否预测获胜");
        for (Case c : cases) {
            say(report, "");
            say(report, c.id + "  " + c.intent);
            for (int[] cand : cands) {
                CombatSearch.Config cfg = new CombatSearch.Config();
                cfg.withinTurnWidth = cand[0];
                cfg.beamWidth = cand[1];
                cfg.maxTurns = maxTurns;
                cfg.maxNodes = CHECK_MAX_NODES;
                cfg.timeBudgetMs = 0L;
                long t0 = System.currentTimeMillis();
                CombatSearch s = newSearch(c.root.fork(), cfg);
                Plan p = drive(s);
                long ms = System.currentTimeMillis() - t0;
                totalWideMs += ms;
                say(report, String.format("   within=%-3d beam=%-3d %6dms 节点=%-7d 路线=%-3d 分数=%-11s HP=%-5s 胜=%s 深度=%d/%d",
                        cand[0], cand[1], ms,
                        p == null ? 0 : p.nodesSearched,
                        p == null ? 0 : p.turns.size(),
                        p == null ? "-" : Oracle.fmt(p.score),
                        p == null ? "-" : Integer.toString(p.endHp),
                        p != null && p.predictedWin ? "是" : "否",
                        s.progressDepth(), maxTurns));
            }
        }
        say(report, "");
        say(report, "总探测耗时 " + totalWideMs + "ms（" + cases.size() + " 场景 × " + cands.length + " 组配置）");
        return 0;
    }

    static long totalWideMs;

    static int runSweep(List<Case> cases, StringBuilder report) {
        int[] withins = {4, 8, 12, 16, 24, 32, 48};
        int[] beams = {3, 6, 8, 12, 16, 24, 48};
        say(report, "══ 宽度网格诊断（回合预算 " + PROVABLE_HORIZON + "，maxNodes 放开、墙钟关闭）══");
        Map<String, Double> refByCase = new java.util.HashMap<String, Double>();
        for (Case c : cases) {
            // 深度按该场景 beam 实际到达的深度取参照；这里固定用满预算的深度
            Oracle.Outcome ref = Oracle.reference(c.root.fork(), PROVABLE_HORIZON, Oracle.SCORE,
                    oracleStateCap, oracleDeadlineMs);
            refByCase.put(c.id, ref.complete ? ref.value : Double.NaN);
            say(report, "");
            say(report, c.id + "  参照 C(" + PROVABLE_HORIZON + ")=" + Oracle.fmt(ref.value)
                    + (ref.complete ? "" : "（未穷举，跳过）"));
            if (!ref.complete) {
                continue;
            }
            StringBuilder head = new StringBuilder("   within\\beam");
            for (int bw : beams) {
                head.append(String.format("%9s", Integer.toString(bw)));
            }
            say(report, head.toString());
            boolean[][] okGrid = new boolean[withins.length][beams.length];
            for (int i = 0; i < withins.length; i++) {
                StringBuilder row = new StringBuilder(String.format("%10d", withins[i]));
                for (int j = 0; j < beams.length; j++) {
                    CombatSearch.Config cfg = new CombatSearch.Config();
                    cfg.withinTurnWidth = withins[i];
                    cfg.beamWidth = beams[j];
                    cfg.maxTurns = PROVABLE_HORIZON;
                    cfg.maxNodes = CHECK_MAX_NODES;
                    cfg.timeBudgetMs = 0L;
                    long t0 = System.currentTimeMillis();
                    CombatSearch s = newSearch(c.root.fork(), cfg);
                    Plan p = drive(s);
                    long ms = System.currentTimeMillis() - t0;
                    double score = Oracle.planValue(p, Oracle.SCORE);
                    boolean ok = !isNegInf(score) && Math.abs(score - refByCase.get(c.id)) < 1e-6;
                    okGrid[i][j] = ok;
                    row.append(String.format("%9s", (ok ? "=" : "!") + ms));
                    totalSweepMs += ms;
                }
                say(report, row.toString() + "   (ms；= 与参照同分，! 漏优)");
            }
            // 帕累托前沿：达标且不存在「within 更小且 beam 不更大」或反之的达标组合
            List<String> minimal = new ArrayList<String>();
            for (int i = 0; i < withins.length; i++) {
                for (int j = 0; j < beams.length; j++) {
                    if (!okGrid[i][j]) {
                        continue;
                    }
                    boolean dominated = false;
                    for (int a = 0; a < withins.length && !dominated; a++) {
                        for (int b = 0; b < beams.length; b++) {
                            if (!okGrid[a][b]) {
                                continue;
                            }
                            boolean strictlyBetter =
                                    (withins[a] < withins[i] && beams[b] <= beams[j])
                                    || (beams[b] < beams[j] && withins[a] <= withins[i]);
                            if (strictlyBetter) {
                                dominated = true;
                                break;
                            }
                        }
                    }
                    if (!dominated) {
                        minimal.add("within=" + withins[i] + "/beam=" + beams[j]);
                    }
                }
            }
            say(report, "   该场景达标的最小宽度组合：" + (minimal.isEmpty() ? "网格内无解"
                    : joinList(minimal)));
        }
        say(report, "");
        say(report, "总诊断耗时 " + totalSweepMs + "ms。读法：每格是该 (within,beam) 组合跑完的毫秒数，");
        say(report, "  「=」表示与同深度可穷举参照同分（不漏优），「!」表示漏优。");
        say(report, "  注：达标组合可能被同网格内更小宽度覆盖，只有列为「最小宽度组合」的才是帕累托前沿。");
        return 0;
    }

    static long totalSweepMs;

    static String joinList(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    // ══ 汇总 ═════════════════════════════════════════════════════

    static void summarize(List<Row> rows, StringBuilder report) {
        say(report, "══ 汇总 ══");
        int pExact = 0;
        int pMiss = 0;
        int bExact = 0;
        int bMiss = 0;
        int winMiss = 0;
        List<String> attrib = new ArrayList<String>();
        for (Row r : rows) {
            if ("EXACT".equals(r.provable.status)) {
                pExact++;
                if (r.provable.miss) {
                    pMiss++;
                    if (!r.provable.attribution.isEmpty()) {
                        attrib.add(r.caze.id + ":" + r.provable.attribution);
                    }
                }
            }
            if ("EXACT".equals(r.budget.status)) {
                bExact++;
                if (r.budget.miss) {
                    bMiss++;
                }
            }
            if (r.winMiss) {
                winMiss++;
            }
        }
        say(report, "判定口径：漏优 = beam 分数低于与它实际展开深度相同的可穷举参照 C(d)；");
        say(report, "          参照未穷举完 CAPPED 时只作提示、不计入漏优，避免跨深度误判。");
        say(report, "PROVABLE 层级（回合预算 " + PROVABLE_HORIZON + "，参照可穷举）：");
        say(report, "  场景 " + rows.size() + "，参照 EXACT " + pExact + "，漏优 " + pMiss
                + (pExact > 0 ? String.format("，漏优率 %.1f%%", 100.0 * pMiss / pExact) : "，无法计算比例"));
        say(report, "BUDGET 层级（回合预算 " + maxTurns + "，玩家实际设置）：");
        say(report, "  参照 EXACT " + bExact + " / CAPPED " + (rows.size() - bExact) + "，漏优 " + bMiss
                + (bExact > 0 ? String.format("，EXACT 子集漏优率 %.1f%%", 100.0 * bMiss / bExact) : ""));
        say(report, "参照存在必胜路线但 beam 未给出获胜预测：" + winMiss + " 个场景");
        if (!attrib.isEmpty()) {
            say(report, "漏优归因清单：");
            for (String a : attrib) {
                say(report, "  " + a);
            }
        }
        say(report, "");
        say(report, "怎么读这些数字：");
        say(report, "  漏优 = 同模型同目标下存在更好的合法路线而 beam 没找到，属搜索缺陷（不是规则错误）。");
        say(report, "  CAPPED = 参照触顶，其值只是下界；该场景“未漏优”不能作为最优证明。");
        say(report, "  WITHIN/BEAM/NODES/TIME = 同深度下放宽该预算后漏优消失，即该层截断是直接原因；");
        say(report, "  RESIDUAL = 同深度放宽全部预算仍漏，需查全局 visited 永久关闭或叶子选择口径；");
        say(report, "  DEAD_OUT = 交出了死亡路线而同深度存在存活路线；若同深度已无存活候选可取，则为宽度剪枝所致（见 WITHIN/BEAM）。");
        say(report, "  本基准不等于现实最优：模拟器 RNG 非真实 RNG；需要玩家选择的牌由引擎启发式代选。");
    }

    // ══ 固定题库 ═════════════════════════════════════════════════

    /**
     * 全部场景只用已登记、非 approx、不需要玩家选择的牌（军备、燃烧契约、双持、头槌、
     * 战吼、掘墓、浩劫、地狱之刃、发现等刻意排除），避免把“代选启发式差异”混进
     * “搜索质量差异”。每张牌显式写出费用/伤害/格挡/是否群体，构造过程可复核。
     */
    static List<Case> buildCorpus(StringBuilder report) {
        List<Case> out = new ArrayList<Case>();

        out.add(new Case("Q1-斩杀判定",
                "三张打击刚好打死低血敌人，多花一张防御就少杀人（回合内精确枚举的边界）",
                scene(80, 80, 3,
                        cards("Strike_R:1:6:0", "Strike_R:1:6:0", "Strike_R:1:6:0", "Defend_R:1:0:5"),
                        mob("Sentry", "哨卫", 18, 24, 9, 1),
                        draw("Bash:2:8:0", "Defend_R:1:0:5", "Strike_R:1:6:0"))));

        out.add(new Case("Q2-铺垫取舍",
                "强化本回合 0 伤害，力量要跨回合才回本：短视打分是否会牺牲它",
                scene(70, 70, 3,
                        cards("Inflame:1:0:0", "Strike_R:1:6:0", "Defend_R:1:0:5"),
                        mob("Cultist", "邪教徒", 52, 52, 0, 1),
                        draw("Strike_R:1:6:0", "Defend_R:1:0:5", "Strike_R:1:6:0"))));

        out.add(new Case("Q3-防御时机",
                "残血时先防还是先打：格挡只对本回合 incoming 有效",
                scene(30, 80, 3,
                        cards("Shrug It Off:1:0:5", "Strike_R:1:6:0", "Defend_R:1:0:5"),
                        mob("JawWorm", "大颚虫", 44, 44, 11, 1),
                        draw("Defend_R:1:0:5", "Strike_R:1:6:0", "Bash:2:8:0"))));

        out.add(new Case("Q4-连招顺序",
                "先重锤上易伤、再重刃吃 1.5 倍率；顺序颠倒就少伤害",
                scene(70, 70, 3,
                        cards("Bash:2:8:0", "Heavy Blade:2:14:0", "Strike_R:1:6:0"),
                        mob("Lagavulin", "拉格威林", 60, 110, 0, 1),
                        draw("Strike_R:1:6:0", "Defend_R:1:0:5", "Bash:2:8:0"))));

        out.add(new Case("Q5-目标分配",
                "两只血量不同的哨卫：群体与单体伤害如何分配才不漏更优解",
                twoMobScene(60, 60, 3,
                        cards("Cleave:1:8:0:aoe", "Strike_R:1:6:0", "Strike_R:1:6:0", "Defend_R:1:0:5"),
                        mob("Sentry", "哨卫", 24, 24, 9, 1),
                        mob("Sentry", "哨卫", 12, 24, 9, 1),
                        draw("Strike_R:1:6:0", "Defend_R:1:0:5", "Cleave:1:8:0:aoe"))));

        out.add(new Case("Q6-过牌价值",
                "剑柄打击抽到的可能是重锤：先抽后打的价值要跨过多目标序列才显现",
                scene(70, 70, 3,
                        cards("Pommel Strike:1:9:0", "Defend_R:1:0:5", "Strike_R:1:6:0"),
                        mob("AcidSlime_M", "酸液史莱姆（中）", 30, 30, 10, 1),
                        draw("Bash:2:8:0", "Cleave:1:8:0:aoe", "Defend_R:1:0:5"))));

        out.add(new Case("Q7-药水成本",
                "力量药水被固定扣 15 分机会成本：是否因此错过正确的开瓶时机",
                withPotion(scene(60, 60, 3,
                        cards("Strike_R:1:6:0", "Strike_R:1:6:0", "Defend_R:1:0:5"),
                        mob("GremlinNob", "地精大块头", 85, 85, 0, 1),
                        draw("Strike_R:1:6:0", "Defend_R:1:0:5", "Inflame:1:0:0")),
                        "Strength Potion")));

        out.add(new Case("Q8-长线消耗",
                "110 血敌人要打上十来个回合：回合内动作组合与防御节奏的长线权衡",
                scene(80, 80, 3,
                        cards("Strike_R:1:6:0", "Defend_R:1:0:5", "Defend_R:1:0:5", "Twin Strike:1:5:0"),
                        mob("Lagavulin", "拉格威林", 110, 110, 0, 1),
                        draw("Strike_R:1:6:0", "Defend_R:1:0:5", "Bash:2:8:0", "Twin Strike:1:5:0"))));

        out.add(new Case("Q9-生存下限",
                "两只哨卫齐射足以击杀：必须算清哪张防御值得先出", corneredCase()));

        out.add(new Case("Q10-零费循环",
                "迅捷打击（0 费）与铁斩波叠格挡的循环：回合内长度与顺序",
                scene(70, 70, 3,
                        cards("Swift Strike:0:7:0", "Shrug It Off:1:0:5", "Strike_R:1:6:0"),
                        mob("AcidSlime_M", "酸液史莱姆（中）", 26, 26, 10, 1),
                        draw("Swift Strike:0:7:0", "Defend_R:1:0:5", "Pommel Strike:1:9:0"))));

        out.add(new Case("Q11-能量效率",
                "2 费牌与多张 1 费牌在同一能量下的取舍（能量受限的组合搜索）",
                scene(60, 60, 2,
                        cards("Bash:2:8:0", "Strike_R:1:6:0", "Defend_R:1:0:5", "Clothesline:2:12:0"),
                        mob("Cultist", "邪教徒", 40, 40, 0, 1),
                        draw("Strike_R:1:6:0", "Defend_R:1:0:5", "Clothesline:2:12:0"))));

        out.add(new Case("Q12-易伤链",
                "上群体易伤后低伤高频牌更划算（段数与倍率的组合价值）",
                twoMobScene(60, 60, 3,
                        cards("Thunderclap:1:4:0:aoe", "Sword Boomerang:1:5:0", "Defend_R:1:0:5"),
                        mob("GremlinNob", "地精大块头", 48, 48, 0, 1),
                        mob("Sentry", "哨卫", 24, 24, 9, 1),
                        draw("Strike_R:1:6:0", "Defend_R:1:0:5", "Thunderclap:1:4:0:aoe"))));

        // 题库自身必须先合法：所有牌已登记、非 approx、不需要玩家选择
        List<String> bad = new ArrayList<String>();
        for (Case c : out) {
            for (SimState.Card card : allCards(c.root)) {
                if (!CardLibrary.isKnown(card.id)) {
                    bad.add(c.id + " 未登记牌 " + card.id);
                } else if (card.approx) {
                    bad.add(c.id + " 近似牌 " + card.id);
                }
            }
        }
        if (bad.isEmpty()) {
            say(report, "题库合法性检查通过：" + out.size() + " 个场景，全部为已登记、非 approx、无玩家选择的牌");
        } else {
            for (String b : bad) {
                say(report, "题库存在不合格牌：" + b);
            }
        }
        say(report, "");
        return out;
    }

    static List<SimState.Card> allCards(SimState s) {
        List<SimState.Card> all = new ArrayList<SimState.Card>();
        all.addAll(s.hand);
        all.addAll(s.drawPile);
        all.addAll(s.discardPile);
        all.addAll(s.exhaustPile);
        return all;
    }

    /**
     * Q9「生存下限」场景的唯一定义：14 血、两只 40 血哨卫（各 9 点单段攻击），3 能量，
     * 手牌一打击两防御，抽牌堆防御/打击/铁斩波。离线基准与 Checks 的收束口径回归断言
     * 共用这一构造，避免同一场景出现两份实现。该场景在 2 回合预算内所有分支都杀不完，
     * 但既存在「少掉血的存活叶子」，也存在「被打死的终局分支」。
     */
    static SimState corneredCase() {
        return twoMobScene(14, 80, 3,
                cards("Strike_R:1:6:0", "Defend_R:1:0:5", "Defend_R:1:0:5"),
                mob("Sentry", "哨卫", 40, 40, 9, 1),
                mob("Sentry", "哨卫", 40, 40, 9, 1),
                draw("Defend_R:1:0:5", "Strike_R:1:6:0", "Shrug It Off:1:0:5"));
    }

    static SimState withPotion(SimState s, String potionId) {
        s.potionIds.add(potionId);
        return s;
    }

    static List<String> cards(String... specs) {
        List<String> list = new ArrayList<String>();
        for (String s : specs) {
            list.add(s);
        }
        return list;
    }

    static List<String> draw(String... specs) {
        return cards(specs);
    }

    /** 牌规格 {id:cost:damage:block[:aoe]}，与题库书写一致。 */
    static SimState.Card card(String spec) {
        String[] parts = spec.split(":");
        String id = parts[0];
        int cost = Integer.parseInt(parts[1]);
        int damage = Integer.parseInt(parts[2]);
        int block = Integer.parseInt(parts[3]);
        boolean aoe = parts.length > 4 && "aoe".equals(parts[4]);
        boolean attack = damage > 0;
        return CardLibrary.toSimCard(id, id, cost, damage, block, 0,
                attack, aoe, attack && !aoe, !CardLibrary.isKnown(id));
    }

    /** 怪物：与 Checks.monster 同构造（aiCursor=1 表示模拟「第 1 回合已捕捉」）。 */
    static SimState.Mob mob(String id, String name, int hp, int maxHp, int atkDamage, int atkHits) {
        SimState.Mob m = new SimState.Mob(id, name, hp, maxHp, 0, atkDamage, atkHits, 0, true);
        m.aiCursor = 1; // 与 Checks.monster 一致：模拟「第 1 回合已捕捉」
        return m;
    }

    static SimState scene(int hp, int maxHp, int energy, List<String> hand,
                          SimState.Mob mob, List<String> drawPile) {
        SimState s = new SimState();
        s.hp = hp;
        s.maxHp = maxHp;
        s.energy = energy;
        s.energyPerTurn = energy;
        s.handSize = 5;
        s.turn = 1;
        s.rngState = 0x2545F4914F6CDD1DL;
        for (String spec : hand) {
            s.hand.add(card(spec));
        }
        for (String spec : drawPile) {
            s.drawPile.add(card(spec));
        }
        s.mobs.add(mob);
        CombatEngine.initMonsters(s);
        return s;
    }

    static SimState twoMobScene(int hp, int maxHp, int energy, List<String> hand,
                                SimState.Mob a, SimState.Mob b, List<String> drawPile) {
        SimState s = scene(hp, maxHp, energy, hand, a, drawPile);
        s.mobs.add(b);
        return s;
    }

    // ══ 输出助手 ═════════════════════════════════════════════════

    static void say(StringBuilder sb, String line) {
        System.out.println(line);
        if (sb != null) {
            sb.append(line).append('\n');
        }
    }

    static boolean record(StringBuilder sb, boolean ok, String what, String detail) {
        say(sb, (ok ? "  [通过] " : "  [失败] ") + what + (detail == null || detail.isEmpty() ? "" : "  " + detail));
        return ok;
    }

    static String yesNo(boolean b) {
        return b ? "是" : "否";
    }

    static boolean isNegInf(double v) {
        return Double.isNaN(v) || v == Double.NEGATIVE_INFINITY;
    }

    static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) {
            sb.append(' ');
        }
        return sb.toString();
    }

    static void write(String path, StringBuilder sb) {
        if (path == null) {
            return;
        }
        try {
            BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8));
            w.write(sb.toString());
            w.close();
            System.out.println("报告已写出：" + path);
        } catch (Exception e) {
            System.out.println("报告写出失败：" + e);
        }
    }
}
