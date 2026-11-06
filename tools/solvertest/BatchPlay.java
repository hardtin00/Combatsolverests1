import com.combatsolver1.engine.CardLibrary;
import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.SimState;
import com.combatsolver1.search.CombatSearch;
import com.combatsolver1.search.Plan;
import com.combatsolver1.settings.SolverSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * W0 ② 离线批量对局 runner（原型）。
 *
 * <p>口径声明（必须随任何引用本工具的数字一起声明）：这是「同模拟器自打」——
 * 用 CombatSearch(beam) 在 CombatEngine 模拟器里搜索整场计划，再在同一模拟器上原样重放。
 * 它既不是真机 auto-play（L4），也不是与参考项目对等的实机胜率；只是 W0 在拿到真机
 * harness 之前，一把可复现、可进 CI 的离线尺子。任务书要求的「真机 ≥200 局 auto-play」
 * 仍由 W0 后续任务完成，本工具不替代、不声称等价。
 *
 * <p>设计约束：
 * - 固定 200 个种子：seed i 决定敌人组合/血量/攻击、起手手牌与抽牌堆，全部由
 *   java.util.Random(seed) 派生，输入可复现。
 * - 只用 CardLibrary 已登记、非 approx 的牌（与 Oracle 题库同约束）。
 * - 搜索预算取运行时 SolverSettings 档位 1，与 W0 Oracle 基线同口径。
 * - 重放严格逐动作：任一步 canPlay 失败即记「执行失败」，不计入胜率分子。
 * - 不修改任何求解/评估/RNG 行为，纯观测。
 */
public final class BatchPlay {

    private static final int RUNS = 200;
    private static final long SEED0 = 20261106L;
    private static final int MAX_ACTION_GUARD = 2000;
    /** W7 预算分档诊断：默认 1「中」= W0 冻结基线；--preset 可切换。 */
    private static int preset = 1;
    /** W8-e 诊断：默认 -1 = 不覆盖，与 W0 冻结基线逐位一致；--within/--beam 可覆盖。 */
    private static int overrideWithin = -1;
    private static int overrideBeam = -1;

    private BatchPlay() {
    }

    /** 一局的观测结果。 */
    static final class Result {
        int seedIndex;
        String encounter;
        boolean searched;
        boolean replayed;
        boolean win;
        int startHp;
        int endHp;
        int hpLoss;
        int turns;
        long searchMs;
        int nodes;
        String note = "";
    }

    public static void main(String[] args) {
        int runs = RUNS;
        boolean prior = false;
        for (int i = 0; i < args.length; i++) {
            if ("--runs".equals(args[i]) && i + 1 < args.length) runs = Integer.parseInt(args[++i]);
            else if ("--prior".equals(args[i])) prior = true;
            else if ("--preset".equals(args[i]) && i + 1 < args.length) preset = Integer.parseInt(args[++i]);
            else if ("--within".equals(args[i]) && i + 1 < args.length) overrideWithin = Integer.parseInt(args[++i]);
            else if ("--beam".equals(args[i]) && i + 1 < args.length) overrideBeam = Integer.parseInt(args[++i]);
        }
        // W7-b: 先验默认关闭，--prior 用于「改动前后指标对照」。纯报告选项，
        // 不改评测/搜索逻辑本身（只是切换 CardOrderPrior 的开关）。
        com.combatsolver1.search.CardOrderPrior.setEnabled(prior);
        SolverSettings s = new SolverSettings();
        s.preset = preset;

        List<Result> results = new ArrayList<Result>();
        for (int i = 0; i < runs; i++) {
            results.add(runOne(i));
        }

        int wins = 0;
        int replayed = 0;
        int searched = 0;
        long totalLoss = 0L;
        long totalSearchMs = 0L;
        long maxSearchMs = 0L;
        Map<String, int[]> byEncounter = new LinkedHashMap<String, int[]>();
        for (Result r : results) {
            if (r.searched) searched++;
            if (r.replayed) {
                replayed++;
                if (r.win) wins++;
                totalLoss += r.hpLoss;
            }
            totalSearchMs += r.searchMs;
            if (r.searchMs > maxSearchMs) maxSearchMs = r.searchMs;
            int[] agg = byEncounter.get(r.encounter);
            if (agg == null) {
                agg = new int[4]; // 局数, 胜, 总失血, 重放成功数
                byEncounter.put(r.encounter, agg);
            }
            agg[0]++;
            if (r.replayed) {
                agg[3]++;
                if (r.win) agg[1]++;
                agg[2] += r.hpLoss;
            }
        }

        System.out.println("=== W0 离线批量对局（同模拟器自打，非真机 L4）===");
        System.out.println("口径: CombatSearch(beam, 档位1) 搜索整场计划 -> 同一 CombatEngine 原样重放");
        System.out.println("局数=" + runs + " 种子=" + SEED0 + "+i 预算=档位" + preset + "(within=" + within()
                + " beam=" + beam() + " maxTurns=" + maxTurns() + " maxNodes=" + maxNodes() + ")"
                + " 先验=" + (prior ? "on" : "off"));
        System.out.println("搜索出计划 " + searched + " / 重放成功 " + replayed);
        System.out.println("胜率 " + (replayed > 0 ? String.format("%.1f%%", 100.0 * wins / replayed) : "N/A")
                + " (" + wins + "/" + replayed + ")");
        System.out.println("平均每场 HP 损失 " + (replayed > 0 ? String.format("%.1f", (double) totalLoss / replayed) : "N/A"));
        System.out.println("每场搜索耗时 平均 " + (runs > 0 ? (totalSearchMs / runs) : 0) + "ms 最大 " + maxSearchMs + "ms");
        System.out.println("逐遭遇分布（局数/胜/平均失血/重放成功）:");
        for (Map.Entry<String, int[]> e : byEncounter.entrySet()) {
            int[] a = e.getValue();
            System.out.println("  " + e.getKey() + "  " + a[0] + "/" + a[1] + "/"
                    + (a[3] > 0 ? String.format("%.1f", (double) a[2] / a[3]) : "N/A") + "/" + a[3]);
        }
        System.out.println("注意: 本数字不可与真机胜率、参考项目实机表现或 Oracle 漏优率混同。");
    }

    private static int within() {
        return config().withinTurnWidth;
    }

    private static int beam() {
        return config().beamWidth;
    }

    private static int maxTurns() {
        return config().maxTurns;
    }

    private static int maxNodes() {
        return config().maxNodes;
    }

    /** 与 SearchQuality.applyPreset 同一权威来源：当前档位的运行时配置。 */
    private static CombatSearch.Config config() {
        SolverSettings s = new SolverSettings();
        s.preset = preset;
        CombatSearch.Config cfg = new CombatSearch.Config();
        s.applyTo(cfg);
        if (overrideWithin > 0) cfg.withinTurnWidth = overrideWithin;
        if (overrideBeam > 0) cfg.beamWidth = overrideBeam;
        return cfg;
    }

    private static Result runOne(int idx) {
        Result r = new Result();
        r.seedIndex = idx;
        java.util.Random rnd = new java.util.Random(SEED0 + idx);
        SimState root = buildEncounter(idx, rnd, r);
        r.startHp = root.hp;
        r.endHp = root.hp;

        CombatSearch.Config cfg = new CombatSearch.Config();
        cfg.beamWidth = beam();
        cfg.withinTurnWidth = within();
        cfg.maxTurns = maxTurns();
        cfg.maxNodes = maxNodes();
        cfg.timeBudgetMs = 0L; // 离线批量：只用节点/回合预算，避免墙钟抖动污染对照

        long t0 = System.currentTimeMillis();
        try {
            CombatSearch search = new CombatSearch();
            search.begin(root.fork(), cfg);
            int guard = 0;
            while (!search.isDone() && guard++ < MAX_ACTION_GUARD) search.step();
            Plan plan = search.result();
            r.searchMs = System.currentTimeMillis() - t0;
            r.nodes = plan == null ? 0 : plan.nodesSearched;
            r.searched = plan != null && !plan.turns.isEmpty();
            if (!r.searched) {
                r.note = "无计划";
                return r;
            }
            SimState played = replay(root.fork(), plan);
            if (played == null) {
                r.note = "重放失败";
                return r;
            }
            r.replayed = true;
            r.win = played.won() && !played.playerDead();
            r.endHp = Math.max(0, played.hp);
            r.hpLoss = Math.max(0, r.startHp - r.endHp);
            r.turns = plan.turns.size();
        } catch (Throwable t) {
            r.note = "异常 " + t.getClass().getSimpleName();
        }
        return r;
    }

    /** 与 SearchQuality.replay 同口径：逐动作合法性检查，失败返回 null。 */
    private static SimState replay(SimState s, Plan plan) {
        for (Plan.Turn turn : plan.turns) {
            for (Plan.Action a : turn.actions) {
                if (a == null) return null;
                if (a.potionIndex >= 0 || (a.cardId != null && a.cardId.startsWith("POTION:"))) {
                    String pid = a.cardId == null ? null : a.cardId.substring("POTION:".length());
                    int pi = pid == null ? -1 : s.potionIds.indexOf(pid);
                    if (pi < 0) return null;
                    CombatEngine.usePotion(s, pi, a.targetIndex);
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
                if (hi < 0) return null;
                SimState.Card card = s.hand.get(hi);
                if (!CombatEngine.canPlay(s, card)) return null;
                s.hand.remove(hi);
                CombatEngine.playCard(s, card, a.targetIndex);
            }
            CombatEngine.endTurn(s);
        }
        return s;
    }

    /** 固定种子生成 A20 风格起始局面；只用已登记、非 approx 牌。 */
    private static SimState buildEncounter(int idx, java.util.Random rnd, Result r) {
        String[] encounters = {
                "Jaw Worm", "Cultist", "2 Louse", "Small Slimes", "Gremlin Gang",
                "Blue Slaver", "Red Slaver", "Exordium Thugs", "Exordium Wildlife",
                "Gremlin Nob", "Lagavulin", "3 Sentries", "Hexaghost", "Slime Boss", "The Guardian"
        };
        String enc = encounters[idx % encounters.length];
        r.encounter = enc;

        int hp = 55 + rnd.nextInt(16);            // 55-70 起手血（A20 前几层常见残血）
        int energy = 3;
        SimState s = new SimState();
        s.hp = hp;
        s.maxHp = 80;
        s.energy = energy;
        s.energyPerTurn = energy;
        s.handSize = 5;
        s.turn = 1;
        s.rngState = SEED0 + idx;                 // 模拟器内部 RNG 由种子派生
        s.ascension = 20;

        for (String spec : startingHand(rnd)) s.hand.add(card(spec));
        for (String spec : drawPile(rnd)) s.drawPile.add(card(spec));

        int mobCount = 1 + rnd.nextInt(2);
        for (int m = 0; m < mobCount; m++) {
            s.mobs.add(mob(enc, 45 + rnd.nextInt(50), 9 + rnd.nextInt(10)));
        }
        CombatEngine.initMonsters(s);
        return s;
    }

    private static List<String> startingHand(java.util.Random rnd) {
        String[] pool = {
                "Strike_R:1:6:0", "Defend_R:1:0:5", "Bash:2:8:0", "Cleave:1:8:0:aoe",
                "Pommel Strike:1:9:0", "Shrug It Off:1:0:5", "Twin Strike:1:5:0",
                "Clothesline:2:12:0", "Iron Wave:1:5:5", "Thunderclap:1:4:0:aoe"
        };
        List<String> out = new ArrayList<String>();
        int n = 4 + rnd.nextInt(2);
        for (int i = 0; i < n; i++) out.add(pool[rnd.nextInt(pool.length)]);
        return out;
    }

    private static List<String> drawPile(java.util.Random rnd) {
        String[] pool = {
                "Strike_R:1:6:0", "Defend_R:1:0:5", "Bash:2:8:0", "Cleave:1:8:0:aoe",
                "Pommel Strike:1:9:0", "Shrug It Off:1:0:5", "Heavy Blade:2:14:0",
                "Swift Strike:0:7:0", "Anger:0:6:0", "Body Slam:1:0:0"
        };
        List<String> out = new ArrayList<String>();
        int n = 10 + rnd.nextInt(6);
        for (int i = 0; i < n; i++) out.add(pool[rnd.nextInt(pool.length)]);
        return out;
    }

    private static SimState.Card card(String spec) {
        String[] p = spec.split(":");
        String id = p[0];
        int cost = Integer.parseInt(p[1]);
        int dmg = Integer.parseInt(p[2]);
        int block = Integer.parseInt(p[3]);
        boolean aoe = p.length > 4 && "aoe".equals(p[4]);
        boolean attack = dmg > 0;
        return CardLibrary.toSimCard(id, id, cost, dmg, block, 0,
                attack, aoe, attack && !aoe, !CardLibrary.isKnown(id));
    }

    private static SimState.Mob mob(String id, int hp, int atk) {
        SimState.Mob m = new SimState.Mob(id, id, hp, hp, 0, atk, 1, 0, true);
        m.aiCursor = 1;
        return m;
    }
}
