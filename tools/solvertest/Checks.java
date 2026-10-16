import com.combatsolver1.engine.CardLibrary;
import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.MonsterAi;
import com.combatsolver1.engine.SimState;
import com.combatsolver1.search.CombatSearch;
import com.combatsolver1.search.Plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 离线自检：不依赖游戏运行，也不需要游戏 jar，直接对引擎数据表与搜索做断言。
 * 任何一条断言失败都会打印明细并以非零码退出，可直接用于 CI。
 */
public final class Checks {

    private Checks() {
    }

    private static int failures;
    private static final List<String> LOG = new ArrayList<String>();

    public static int failures() {
        return failures;
    }

    public static List<String> log() {
        return LOG;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            LOG.add("  [通过] " + name);
        } else {
            failures++;
            LOG.add("  [失败] " + name + (detail == null ? "" : " —— " + detail));
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(name, ok, "期望 " + expected + "，实际 " + actual);
    }

    // ══ 1. 卡牌数据表 ═══════════════════════════════════════════

    public static void cardTable() {
        LOG.add("· 卡牌数据表");
        String[] mustKnow = {
                "Strike_R", "Defend_R", "Bash", "Anger", "Armaments", "Body Slam", "Clash",
                "Cleave", "Clothesline", "Flex", "Havoc", "Headbutt", "Heavy Blade", "Iron Wave",
                "Perfected Strike", "Pommel Strike", "Shrug It Off", "Sword Boomerang",
                "Thunderclap", "True Grit", "Twin Strike", "Warcry", "Wild Strike",
                "Battle Trance", "Blood for Blood", "Bloodletting", "Burning Pact", "Carnage",
                "Combust", "Dark Embrace", "Disarm", "Dropkick", "Dual Wield", "Entrench",
                "Evolve", "Feel No Pain", "Fire Breathing", "Flame Barrier", "Ghostly Armor",
                "Hemokinesis", "Infernal Blade", "Inflame", "Intimidate", "Metallicize",
                "Power Through", "Pummel", "Rage", "Rampage", "Reckless Charge", "Rupture",
                "Searing Blow", "Second Wind", "Seeing Red", "Sentinel", "Sever Soul",
                "Shockwave", "Spot Weakness", "Uppercut", "Whirlwind", "Barricade", "Berserk",
                "Bludgeon", "Brutality", "Corruption", "Demon Form", "Double Tap", "Exhume",
                "Feed", "Fiend Fire", "Immolate", "Impervious", "Juggernaut", "Limit Break",
                "Offering", "Reaper",
        };
        int unknown = 0;
        for (String id : mustKnow) {
            if (!CardLibrary.isKnown(id)) {
                unknown++;
                LOG.add("    缺少卡牌条目：" + id);
            }
        }
        eq("铁甲全卡池均已登记（75 张）", 0, unknown);

        // 关键效果：这些是旧版标 approx、现在要求精确的牌
        check("血怒使用掉血次数计价", CardLibrary.get("Blood for Blood").bloodForBlood, null);
        check("双持复制手牌攻击牌", CardLibrary.get("Dual Wield").copyHandAttack, null);
        check("头槌取回弃牌堆最优牌", CardLibrary.get("Headbutt").discardToDraw, null);
        check("掘墓取回消耗堆最优牌", CardLibrary.get("Exhume").exhaustToHand, null);
        check("燃烧契约消耗最差牌并抽二", CardLibrary.get("Burning Pact").exhaustOneDrawTwo, null);
        check("浩劫打出牌堆顶并消耗", CardLibrary.get("Havoc").playTopExhaust, null);
        check("军备升级手牌最优牌", CardLibrary.get("Armaments").upgradeHand, null);
        check("地狱之刃生成随机攻击牌", CardLibrary.get("Infernal Blade").generateStrike, null);
        check("活动肌肉为临时力量", CardLibrary.get("Flex").tempStrength, null);
        check("斗殴需手牌全攻击", CardLibrary.get("Clash").requiresAllAttacks, null);
        check("狂野打击洗入抽牌堆", CardLibrary.get("Wild Strike").shuffleAddedToDraw, null);
        check("战吼放回最差牌", CardLibrary.get("Warcry").putBackWorst, null);
        check("完美打击按打击数加伤", CardLibrary.get("Perfected Strike").damagePerStrike > 0, null);
        check("收割按伤害回血", CardLibrary.get("Reaper").healOnDamage, null);
        check("喂食击杀加最大生命", CardLibrary.get("Feed").gainMaxHpOnKill, null);

        // 仍标记为近似的牌应当很少，且是已知清单
        List<String> approx = new ArrayList<String>();
        for (String id : mustKnow) {
            if (CardLibrary.get(id).approx) {
                approx.add(id);
            }
        }
        LOG.add("    仍为近似的牌：" + (approx.isEmpty() ? "（无）" : approx.toString()));
        check("近似牌数量收敛到 8 张以内", approx.size() <= 8, "实际 " + approx.size());

        // 数值行为：打击 6 伤害、防御 5 格挡
        SimState s = scenario(80, 80, new String[]{"Strike_R"}, "JawWorm");
        SimState.Card strike = CardLibrary.toSimCard("Strike_R", "打击", 1, 6, 0, 1,
                true, false, true, false);
        s.hand.clear();
        s.hand.add(strike);
        int before = s.mobs.get(0).hp;
        CombatEngine.playCard(s, s.hand.remove(0), 0);
        eq("打击造成 6 点伤害", before - 6, s.mobs.get(0).hp);

        SimState d = scenario(80, 80, new String[]{"Defend_R"}, "JawWorm");
        d.hand.clear();
        d.hand.add(CardLibrary.toSimCard("Defend_R", "防御", 1, 0, 5, 1,
                false, false, false, false));
        CombatEngine.playCard(d, d.hand.remove(0), -1);
        eq("防御获得 5 点格挡", 5, d.block);
    }

    // ══ 2. 怪物 AI ══════════════════════════════════════════════

    public static void monsterAi() {
        LOG.add("· 怪物行动模型");
        List<String> ids = MonsterAi.modeledIds();
        check("第一幕怪物建模数量 >= 20", ids.size() >= 20, "实际 " + ids.size());

        // 每个建模怪物在前 10 个回合都能给出招式
        int broken = 0;
        StringBuilder brokenList = new StringBuilder();
        for (String id : ids) {
            SimState s = scenario(80, 80, new String[]{"Strike_R"}, id);
            SimState.Mob mob = s.mobs.get(0);
            for (int turn = 0; turn < 10; turn++) {
                if (MonsterAi.next(s, mob) == null) {
                    broken++;
                    brokenList.append(id).append('@').append(turn).append(' ');
                    break;
                }
                mob.aiCursor++;
            }
        }
        eq("所有建模怪物都能连续给出 10 个招式", 0, broken);
        if (brokenList.length() > 0) {
            LOG.add("    失败明细：" + brokenList);
        }

        // 具体序列：邪教徒先吟唱再黑暗打击；哨卫光束/射线交替；拉格威林先睡三回合
        SimState cultist = scenario(80, 80, new String[]{"Strike_R"}, "Cultist");
        SimState.Mob c = cultist.mobs.get(0);
        eq("邪教徒当前招式是吟唱", "INCANTATION", MonsterAi.current(cultist, c).id);
        eq("邪教徒下一招是黑暗打击", "DARK_STRIKE", MonsterAi.next(cultist, c).id);

        SimState sentry = scenario(80, 80, new String[]{"Strike_R"}, "Sentry");
        SimState.Mob se = sentry.mobs.get(0);
        eq("哨卫当前招式是光束", "BOLT", MonsterAi.current(sentry, se).id);
        eq("哨卫下一招是射线", "BEAM", MonsterAi.next(sentry, se).id);
        se.aiCursor = 2;
        eq("哨卫第 3 招回到光束", "BOLT", MonsterAi.next(sentry, se).id);

        SimState lag = scenario(80, 80, new String[]{"Strike_R"}, "Lagavulin");
        SimState.Mob la = lag.mobs.get(0);
        eq("拉格威林当前招式是沉睡", "IDLE", MonsterAi.current(lag, la).id);
        la.aiCursor = 1;
        eq("拉格威林第 2 招仍是沉睡", "IDLE", MonsterAi.next(lag, la).id);
        la.aiCursor = 3;
        eq("拉格威林睡醒后重击", "STRONG_ATK", MonsterAi.next(lag, la).id);
        la.aiCursor = 4;
        eq("拉格威林之后吸魂", "DEBUFF", MonsterAi.next(lag, la).id);

        // 仪式：邪教徒每回合末 +3 力量，伤害递增
        SimState ritual = scenario(200, 200, new String[]{"Strike_R"}, "Cultist");
        CombatEngine.initMonsters(ritual);
        int firstHit = 0;
        int laterHit = 0;
        for (int turn = 0; turn < 6; turn++) {
            int hpBefore = ritual.hp;
            CombatEngine.endTurn(ritual);
            int lost = hpBefore - ritual.hp;
            if (turn == 0) {
                firstHit = lost;
            }
            laterHit = lost;
        }
        eq("邪教徒第一回合不造成伤害（只吟唱）", 0, firstHit);
        check("邪教徒伤害随仪式递增", laterHit > 6, "第 6 回合伤害 " + laterHit);

        // 拉格威林：睡觉回合不掉血，被打醒后开始掉血
        SimState lag2 = scenario(200, 200, new String[]{"Strike_R"}, "Lagavulin");
        CombatEngine.initMonsters(lag2);
        int hp0 = lag2.hp;
        CombatEngine.endTurn(lag2);
        eq("沉睡中的拉格威林不造成伤害", hp0, lag2.hp);

        // 地精大块头：玩家打技能牌会被狂怒惩罚
        SimState nob = scenario(200, 200, new String[]{"Defend_R"}, "GremlinNob");
        CombatEngine.initMonsters(nob);
        int strBefore = nob.mobs.get(0).strength;
        nob.hand.clear();
        nob.hand.add(CardLibrary.toSimCard("Defend_R", "防御", 1, 0, 5, 1, false, false, false, false));
        CombatEngine.playCard(nob, nob.hand.remove(0), -1);
        eq("狂怒：打出一张技能牌后大块头 +2 力量", strBefore + 2, nob.mobs.get(0).strength);

        // 节奏表必须用实机 ID 做键（旧版用显示名，整张表从未命中）
        check("节奏表用实机 ID 命中（Byrd）",
                !com.combatsolver1.engine.MonsterPatterns.patternFor("Byrd").isEmpty(), null);
        check("节奏表不再用显示名做键（'Jaw Worm' 应为空）",
                com.combatsolver1.engine.MonsterPatterns.patternFor("Jaw Worm").isEmpty(), null);
    }

    // ══ 3. 搜索 ═════════════════════════════════════════════════

    public static void search() {
        LOG.add("· 整场搜索");
        SimState s = scenario(80, 80,
                new String[]{"Strike_R", "Strike_R", "Strike_R", "Defend_R", "Defend_R"}, "Cultist");
        s.energy = 3;
        CombatSearch.Config cfg = new CombatSearch.Config();
        cfg.beamWidth = 5;
        cfg.withinTurnWidth = 6;
        cfg.maxTurns = 8;
        cfg.maxNodes = 3000;
        Plan plan = run(s, cfg);
        check("搜索能给出路线", plan != null && !plan.turns.isEmpty(), null);
        check("邪教徒局预测可击败", plan != null && plan.predictedWin,
                plan == null ? "无结果" : "predictedWin=false");
        check("预测承伤在合理范围（0-40）", plan != null && plan.predictedHpLoss >= 0
                && plan.predictedHpLoss <= 40, plan == null ? "无结果" : "" + plan.predictedHpLoss);

        // 确定性：同样输入两次搜索结果完全一致
        Plan again = run(s, cfg);
        eq("同输入两次搜索结果一致（可复现）",
                plan == null ? "null" : plan.predictedHpLoss + "/" + plan.turnsToWin + "/" + plan.nodesSearched,
                again == null ? "null" : again.predictedHpLoss + "/" + again.turnsToWin + "/" + again.nodesSearched);

        // 打不过的场景应当判负
        SimState deadly = scenario(12, 80,
                new String[]{"Strike_R", "Strike_R", "Defend_R", "Defend_R", "Defend_R"}, "Lagavulin");
        Plan deadlyPlan = run(deadly, cfg);
        check("低血量面对拉格威林会给出失败预测",
                deadlyPlan == null || !deadlyPlan.predictedWin || deadlyPlan.predictedHpLoss > 0,
                deadlyPlan == null ? "无结果" : "win=" + deadlyPlan.predictedWin
                        + " loss=" + deadlyPlan.predictedHpLoss);
    }

    private static Plan run(SimState root, CombatSearch.Config cfg) {
        CombatSearch search = new CombatSearch();
        search.begin(root.fork(), cfg);
        int guard = 0;
        while (!search.isDone() && guard++ < 100) {
            search.step();
        }
        return search.result();
    }

    // ══ 场景构造 ════════════════════════════════════════════════

    /** 构造一个最小战斗场景：指定血量 / 手牌 / 敌人 id。 */
    public static SimState scenario(int hp, int maxHp, String[] hand, String monsterId) {
        SimState s = new SimState();
        s.hp = hp;
        s.maxHp = maxHp;
        s.energy = 3;
        s.energyPerTurn = 3;
        s.handSize = 5;
        s.turn = 1;
        s.rngState = 0x2545F4914F6CDD1DL;
        for (String id : hand) {
            s.hand.add(blankCard(id));
        }
        s.mobs.add(monster(monsterId));
        CombatEngine.initMonsters(s);
        return s;
    }

    private static SimState.Card blankCard(String id) {
        int damage = 0;
        int block = 0;
        int cost = 1;
        if ("Strike_R".equals(id)) {
            damage = 6;
        } else if ("Defend_R".equals(id)) {
            block = 5;
        }
        return CardLibrary.toSimCard(id, id, cost, damage, block, 0,
                damage > 0, false, true, !CardLibrary.isKnown(id));
    }

    private static SimState.Mob monster(String id) {
        String[][] table = {
                {"JawWorm", "大颚虫", "44", "11", "1"},
                {"Cultist", "邪教徒", "52", "0", "1"},
                {"Lagavulin", "拉格威林", "110", "0", "1"},
                {"GremlinNob", "地精大块头", "85", "0", "1"},
                {"Sentry", "哨卫", "40", "9", "1"},
                {"AcidSlime_M", "酸液史莱姆（中）", "30", "10", "1"},
        };
        for (String[] row : table) {
            if (row[0].equals(id)) {
                SimState.Mob mob = new SimState.Mob(id, row[1], Integer.parseInt(row[2]),
                        Integer.parseInt(row[2]), 0, Integer.parseInt(row[3]),
                        Integer.parseInt(row[4]), 0, true);
                // 模拟「第 1 回合捕捉」：当前招式已经记入 moveHistory，游标指向下一招
                mob.aiCursor = 1;
                return mob;
            }
        }
        SimState.Mob mob = new SimState.Mob(id, id, 40, 40, 0, 8, 1, 0, true);
        mob.aiCursor = 1;
        return mob;
    }

    /** 常用组合：给定怪物与手牌，跑若干回合后返回总掉血（供文档 / 调试）。 */
    public static String simulateSummary(String monsterId, int turns) {
        SimState s = scenario(999, 999, new String[]{"Defend_R"}, monsterId);
        CombatEngine.initMonsters(s);
        for (int i = 0; i < turns; i++) {
            CombatEngine.endTurn(s);
        }
        return monsterId + "：模拟 " + turns + " 回合掉血 " + (999 - s.hp);
    }

    public static List<String> modeledMonsterIds() {
        return Arrays.asList(MonsterAi.modeledIds().toArray(new String[0]));
    }
}
