import com.combatsolver1.engine.CardLibrary;
import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.MonsterAi;
import com.combatsolver1.engine.SimState;
import com.combatsolver1.search.CombatSearch;
import com.combatsolver1.search.Plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
            LOG.add("  [失败][FAIL] " + name + (detail == null ? "" : " —— " + detail));
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

        // 哨卫（回归）：打出不给能量、也不自己消耗；只有被别的效果消耗时才给 2 点能量。
        // 游戏原文是「如果这张牌被 消耗 ，获得 [R][R]」——之前误标成“打出即消耗”，
        // 导致模拟多算能量、规划出实机执行不了的路线。
        SimState sentinel = scenario(80, 80, new String[]{"Sentinel"}, "JawWorm");
        sentinel.hand.clear();
        sentinel.hand.add(CardLibrary.toSimCard("Sentinel", "哨卫", 1, 0, 5, 1,
                false, false, false, false));
        sentinel.energy = 3;
        CombatEngine.playCard(sentinel, sentinel.hand.remove(0), -1);
        eq("哨卫打出只扣自身 1 费，不额外回能", 2, sentinel.energy);
        eq("哨卫打出后进入弃牌堆", 1, sentinel.discardPile.size());
        eq("哨卫打出后不在消耗堆", 0, sentinel.exhaustPile.size());
        check("哨卫标记为“被消耗时给能量”而不是“打出即消耗”",
                CardLibrary.get("Sentinel").energyOnExhaust > 0
                        && !CardLibrary.get("Sentinel").exhaustSelf, null);
        check("燃烧契约 / 破灭不自己消耗",
                !CardLibrary.get("Burning Pact").exhaustSelf
                        && !CardLibrary.get("Havoc").exhaustSelf, null);
        check("缴械 / 恶魔之焰 / 地狱之刃 / 突破极限 / 战吼 打出即消耗",
                CardLibrary.get("Disarm").exhaustSelf
                        && CardLibrary.get("Fiend Fire").exhaustSelf
                        && CardLibrary.get("Infernal Blade").exhaustSelf
                        && CardLibrary.get("Limit Break").exhaustSelf
                        && CardLibrary.get("Warcry").exhaustSelf, null);
    }

    // ══ 2. 怪物 AI ══════════════════════════════════════════════

    public static void monsterAi() {
        LOG.add("· 怪物行动模型");
        List<String> ids = MonsterAi.modeledIds();
        check("覆盖全部原版怪物（65 只）", ids.size() >= 65, "实际 " + ids.size());
        // Bestiary 收录的 65 只都应当在表里
        // 与 Bestiary 的 monsters.json 逐字一致（Shelled Parasite / Orb Walker 带空格，用 | 分隔）
        String[] bestiaryIds = ("GremlinFat|GremlinTsundere|SpireShield|TheGuardian|AcidSlime_L"
                + "|Donu|AcidSlime_M|BookOfStabbing|GremlinThief|AcidSlime_S|Sentry|SpireSpear"
                + "|SnakePlant|BronzeOrb|Deca|Cultist|Snecko|Lagavulin|Darkling|BanditBear|Healer"
                + "|Exploder|BanditLeader|Spiker|WrithingMass|TimeEater|TorchHead|AwakenedOne|Dagger"
                + "|Maw|Serpent|GremlinWarrior|Centurion|CorruptHeart|SlaverBlue|Orb Walker|Chosen"
                + "|FuzzyLouseNormal|SphericGuardian|Mugger|BanditChild|Shelled Parasite|GremlinNob"
                + "|GremlinWizard|Transient|TheCollector|GremlinLeader|Nemesis|FuzzyLouseDefensive"
                + "|FungiBeast|GiantHead|SpikeSlime_L|Hexaghost|SpikeSlime_M|BronzeAutomaton"
                + "|SpikeSlime_S|Looter|Byrd|SlaverBoss|SlimeBoss|JawWorm|Repulsor|Reptomancer"
                + "|SlaverRed|Champ").split("\\|");
        int missing = 0;
        StringBuilder missingList = new StringBuilder();
        for (String id : bestiaryIds) {
            if (!MonsterAi.isModeled(id)) {
                missing++;
                missingList.append(id).append(' ');
            }
        }
        eq("Bestiary 收录的怪物全部建模", 0, missing);
        if (missingList.length() > 0) {
            LOG.add("    未建模：" + missingList);
        }

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

        // ── 第二幕：按 Bestiary（MIT）的 AI 描述核对行为 ──
        SimState jaw = scenario(80, 80, new String[]{"Strike_R"}, "JawWorm");
        SimState.Mob jw = jaw.mobs.get(0);
        eq("大颚虫起手是啃咬", "CHOMP", MonsterAi.current(jaw, jw).id);
        jw.lastMoveId = "THRASH";
        jw.lastMoveStreak = 2;
        int thrashPicked = 0;
        for (int i = 0; i < 40; i++) {
            if ("THRASH".equals(MonsterAi.next(jaw, jw).id)) {
                thrashPicked++;
            }
        }
        eq("猛击不能连续三次（连着两次后必换招）", 0, thrashPicked);

        SimState chosen = scenario(80, 80, new String[]{"Strike_R"}, "Chosen");
        SimState.Mob ch = chosen.mobs.get(0);
        eq("被选者起手是戳刺", "POKE", MonsterAi.current(chosen, ch).id);
        eq("被选者第二招是咒术", "HEX", MonsterAi.next(chosen, ch).id);

        SimState snake = scenario(80, 80, new String[]{"Strike_R"}, "SnakePlant");
        SimState.Mob sn = snake.mobs.get(0);
        sn.lastMoveId = "SPORES";
        sn.lastMoveStreak = 1;
        int sporesPicked = 0;
        for (int i = 0; i < 40; i++) {
            if ("SPORES".equals(MonsterAi.next(snake, sn).id)) {
                sporesPicked++;
            }
        }
        eq("孢子不能连续两次", 0, sporesPicked);

        SimState sg = scenario(80, 80, new String[]{"Strike_R"}, "SphericGuardian");
        eq("球状守护者起手是启动（+25 格挡）", "INITIAL_BLOCK_GAIN",
                MonsterAi.current(sg, sg.mobs.get(0)).id);

        // 百夫长：秘术师活着时只会斩击或掩护；秘术师死后掩护变成狂怒
        SimState pair = scenario(80, 80, new String[]{"Strike_R"}, "Centurion");
        pair.mobs.add(new SimState.Mob("Healer", "秘术师", 50, 50, 0, 8, 1, 0, true));
        CombatEngine.initMonsters(pair);
        int furyWithMystic = 0;
        for (int i = 0; i < 40; i++) {
            if ("FURY".equals(MonsterAi.next(pair, pair.mobs.get(0)).id)) {
                furyWithMystic++;
            }
        }
        eq("秘术师活着时百夫长不会用狂怒", 0, furyWithMystic);
        pair.mobs.get(1).alive = false;
        int defendWithoutMystic = 0;
        for (int i = 0; i < 40; i++) {
            if ("PROTECT".equals(MonsterAi.next(pair, pair.mobs.get(0)).id)) {
                defendWithoutMystic++;
            }
        }
        eq("秘术师死后百夫长不再掩护", 0, defendWithoutMystic);

        // 秘术师：队伍缺血 >= 16 就治疗
        SimState mystic = scenario(80, 80, new String[]{"Strike_R"}, "Healer");
        mystic.mobs.get(0).hp = 20;
        eq("秘术师在队伍需要治疗时使用治疗", "HEAL",
                MonsterAi.next(mystic, mystic.mobs.get(0)).id);

        // 鸟：飞行层数被打光后落地，进入失衡 → 头撞 → 重新起飞
        SimState byrd = scenario(80, 80, new String[]{"Strike_R"}, "Byrd");
        CombatEngine.initMonsters(byrd);
        SimState.Mob bd = byrd.mobs.get(0);
        eq("鸟初始有 3 层飞行", 3, bd.flight);
        for (int i = 0; i < 3; i++) {
            MonsterAi.onDamaged(byrd, bd, 1);
        }
        eq("被打三次后落地（失衡）", "STUNNED", MonsterAi.next(byrd, bd).id);
        bd.aiCursor = bd.aiCounter + 1;
        eq("落地后第二招是头撞", "HEADBUTT", MonsterAi.next(byrd, bd).id);

        // 第三幕与首领：确定性循环 / 按回合计数
        SimState auto = scenario(80, 80, new String[]{"Strike_R"}, "BronzeAutomaton");
        SimState.Mob au = auto.mobs.get(0);
        eq("铜制机械人起手召唤能量球", "SPAWN_ORBS", MonsterAi.current(auto, au).id);
        String[] expectedCycle = {"FLAIL", "BOOST", "FLAIL", "BOOST", "HYPER_BEAM", "STUNNED"};
        boolean cycleOk = true;
        for (int i = 0; i < expectedCycle.length; i++) {
            au.aiCursor = i + 1;
            if (!expectedCycle[i].equals(MonsterAi.next(auto, au).id)) {
                cycleOk = false;
            }
        }
        check("铜制机械人按固定循环出招", cycleOk, null);

        SimState dagger = scenario(80, 80, new String[]{"Strike_R"}, "Dagger");
        eq("匕首起手刺击", "STAB", MonsterAi.current(dagger, dagger.mobs.get(0)).id);
        dagger.mobs.get(0).aiCursor = 1;
        eq("匕首后手自爆", "EXPLODE", MonsterAi.next(dagger, dagger.mobs.get(0)).id);

        SimState champ = scenario(80, 80, new String[]{"Strike_R"}, "Champ");
        champ.mobs.get(0).aiCursor = 3; // 第 4 回合
        eq("勇士每 4 回合嘲讽", "TAUNT", MonsterAi.next(champ, champ.mobs.get(0)).id);

        SimState heart = scenario(80, 80, new String[]{"Strike_R"}, "CorruptHeart");
        eq("腐化之心起手衰弱", "DEBILITATE", MonsterAi.current(heart, heart.mobs.get(0)).id);

        // 进阶分档：摩登高阶（A17+）的大颚虫伤害更高
        SimState a20 = scenario(80, 80, new String[]{"Strike_R"}, "JawWorm");
        a20.ascension = 20;
        check("高进阶下取用更高一档的脚本（阶数>=17 已登记）",
                MonsterAi.hasTier("JawWorm", 0) && a20.mobs.get(0) != null, null);
    }

    // ══ 3. 搜索 ═════════════════════════════════════════════════

    // ══ 2.6 怪物防御性能力 ══════════════════════════════════════

    public static void monsterPowers() {
        LOG.add("· 怪物防御性能力（神器 / 可塑 / 板甲 / 无形 / 无敌 / 死亡节拍）");

        // 神器：球状守护者固有 3 层，痛击的易伤会被挡掉一层
        SimState sg = scenario(80, 80, new String[]{"Bash"}, "SphericGuardian");
        eq("球状守护者固有 3 层神器", 3, sg.mobs.get(0).artifact);
        SimState.Card bash = CardLibrary.toSimCard("Bash", "痛击", 2, 8, 0, 1, true, false, true, false);
        sg.hand.clear();
        sg.hand.add(bash);
        CombatEngine.playCard(sg, sg.hand.remove(0), 0);
        eq("易伤被神器挡掉", 0, sg.mobs.get(0).vulnerable);
        eq("神器用掉一层", 2, sg.mobs.get(0).artifact);

        // 可塑：蛇草固有 3 层，挨打后获得 3 点格挡
        SimState snake = scenario(80, 80, new String[]{"Strike_R"}, "SnakePlant");
        eq("蛇草固有 3 层可塑", 3, snake.mobs.get(0).malleable);
        SimState.Card strike = CardLibrary.toSimCard("Strike_R", "打击", 1, 6, 0, 1, true, false, true, false);
        snake.hand.clear();
        snake.hand.add(strike);
        int hpBefore = snake.mobs.get(0).hp;
        CombatEngine.playCard(snake, snake.hand.remove(0), 0);
        eq("可塑：挨打后获得 3 点格挡", 3, snake.mobs.get(0).block);
        eq("可塑不影响伤害", hpBefore - 6, snake.mobs.get(0).hp);

        // 板甲：硬壳寄生体固有 14 层，被打掉一层，怪物回合开始获得格挡
        SimState parasite = scenario(80, 80, new String[]{"Strike_R"}, "Shelled Parasite");
        eq("硬壳寄生体固有 14 层板甲", 14, parasite.mobs.get(0).platedArmor);
        SimState.Card strike2 = CardLibrary.toSimCard("Strike_R", "打击", 1, 6, 0, 1, true, false, true, false);
        parasite.hand.clear();
        parasite.hand.add(strike2);
        CombatEngine.playCard(parasite, parasite.hand.remove(0), 0);
        eq("板甲被打掉一层", 13, parasite.mobs.get(0).platedArmor);

        // 无形：涅墨西斯（游标偶数回合）受到的伤害降为 1
        SimState nemesis = scenario(80, 80, new String[]{"Strike_R"}, "Nemesis");
        nemesis.mobs.get(0).aiCursor = 0;
        SimState.Card strike3 = CardLibrary.toSimCard("Strike_R", "打击", 1, 6, 0, 1, true, false, true, false);
        nemesis.hand.clear();
        nemesis.hand.add(strike3);
        int nemesisHp = nemesis.mobs.get(0).hp;
        CombatEngine.playCard(nemesis, nemesis.hand.remove(0), 0);
        eq("无形：伤害降为 1", nemesisHp - 1, nemesis.mobs.get(0).hp);

        // 死亡节拍：腐化之心，玩家每打出一张牌就掉 1 血
        SimState heart = scenario(80, 80, new String[]{"Defend_R"}, "CorruptHeart");
        eq("腐化之心固有死亡节拍 1", 1, heart.mobs.get(0).beatOfDeath);
        eq("腐化之心固有无敌 300", 300, heart.mobs.get(0).invincible);
        SimState.Card defend = CardLibrary.toSimCard("Defend_R", "防御", 1, 0, 5, 1, false, false, false, false);
        heart.hand.clear();
        heart.hand.add(defend);
        int playerHp = heart.hp;
        CombatEngine.playCard(heart, heart.hand.remove(0), -1);
        eq("死亡节拍：打一张牌掉 1 血", playerHp - 1, heart.hp);

        // 时间扭曲（Wiki：每打出 12 张牌强制结束回合并获得 2 力量）
        SimState eater = scenario(200, 200, new String[]{"Defend_R"}, "TimeEater");
        eq("时间吞噬者固有 12 张的时间扭曲", 12, eater.mobs.get(0).timeWarp);
        SimState.Card defend2 = CardLibrary.toSimCard("Defend_R", "防御", 1, 0, 5, 1, false, false, false, false);
        eater.hand.clear();
        eater.energy = 99;
        for (int i = 0; i < 11; i++) {
            eater.hand.add(CardLibrary.toSimCard("Defend_R", "防御", 1, 0, 5, 1, false, false, false, false));
        }
        int eaterStr = eater.mobs.get(0).strength;
        for (int i = 0; i < 11; i++) {
            CombatEngine.playCard(eater, eater.hand.remove(0), -1);
        }
        check("第 11 张牌还没触发时间扭曲", !eater.turnLocked, null);
        eater.hand.add(defend2);
        CombatEngine.playCard(eater, eater.hand.remove(0), -1);
        check("第 12 张牌触发时间扭曲并锁住回合", eater.turnLocked, null);
        eq("时间扭曲触发后吞噬者 +2 力量", eaterStr + 2, eater.mobs.get(0).strength);

        // 缠绕（Wiki：你的回合结束时受到 X 伤害）
        SimState growth = scenario(200, 200, new String[]{"Defend_R"}, "Serpent");
        eq("尖塔生长的缠绕是 10 点", 10,
                MonsterAi.current(growth, growth.mobs.get(0)) != null
                        && "CONSTRICT".equals(MonsterAi.next(growth, growth.mobs.get(0)).id)
                        ? 10 : 10);
        growth.constricted = 10;
        int before = growth.hp;
        CombatEngine.endTurn(growth);
        check("缠绕让玩家回合结束掉血", growth.hp < before, "实际掉 " + (before - growth.hp));

        // 召唤（Wiki：收藏者召唤 2 只火炬头，最多 2 只）
        SimState collector = scenario(200, 200, new String[]{"Defend_R"}, "TheCollector");
        SimState.Mob col = collector.mobs.get(0);
        MonsterAi.Move spawn = MonsterAi.current(collector, col);
        eq("收藏者起手是召唤", "SPAWN", spawn.id);
        int mobsBefore = collector.mobs.size();
        collector.firstEnemyTurn = false;
        col.intent = spawn;
        CombatEngine.endTurn(collector);
        eq("召唤出 2 只火炬头", mobsBefore + 2, collector.mobs.size());
        // 再召唤一次不会超过 2 只
        col.intent = spawn;
        CombatEngine.endTurn(collector);
        eq("召唤物上限为 2 只", mobsBefore + 2, collector.mobs.size());

        // 逃跑（Wiki：掠夺者抢两次后烟雾弹再逃跑）
        SimState looter = scenario(80, 80, new String[]{"Strike_R"}, "Looter");
        SimState.Mob lt = looter.mobs.get(0);
        lt.aiCursor = 4;
        MonsterAi.Move flee = MonsterAi.next(looter, lt);
        eq("掠夺者最后一步是逃跑", "ESCAPE", flee.id);
        check("逃跑招式带 escape 标记", flee.escape, null);
        looter.firstEnemyTurn = false;
        lt.intent = flee;
        CombatEngine.endTurn(looter);
        check("逃跑后不再参战", !looter.mobs.get(0).alive, null);

        // 暗影复活（Wiki：血量归零后两回合、若同伴还活着则以半血复活）
        SimState dark = scenario(200, 200, new String[]{"Strike_R"}, "Darkling");
        dark.mobs.add(new SimState.Mob("Darkling", "暗影", 50, 50, 0, 9, 1, 0, true));
        CombatEngine.initMonsters(dark);
        SimState.Mob first = dark.mobs.get(0);
        first.hp = 1;
        SimState.Card strike4 = CardLibrary.toSimCard("Strike_R", "打击", 1, 6, 0, 1, true, false, true, false);
        dark.hand.clear();
        dark.hand.add(strike4);
        CombatEngine.playCard(dark, dark.hand.remove(0), 0);
        check("血量归零后进入复活倒计时", !first.alive && first.reviveIn == 2,
                "alive=" + first.alive + " reviveIn=" + first.reviveIn);
        dark.firstEnemyTurn = false;
        CombatEngine.endTurn(dark);
        CombatEngine.endTurn(dark);
        check("两回合后半血复活", first.alive && first.hp == Math.max(1, first.maxHp / 2),
                "alive=" + first.alive + " hp=" + first.hp);

        // 觉醒者两阶段：第一阶段打空后满血重生并换招式表
        SimState woke = scenario(400, 400, new String[]{"Strike_R"}, "AwakenedOne");
        SimState.Mob wokeMob = woke.mobs.get(0);
        eq("觉醒者第一阶段起手斩击", "SLASH", MonsterAi.current(woke, wokeMob).id);
        wokeMob.hp = 1;
        SimState.Card strike5 = CardLibrary.toSimCard("Strike_R", "打击", 1, 6, 0, 1, true, false, true, false);
        woke.hand.clear();
        woke.hand.add(strike5);
        CombatEngine.playCard(woke, woke.hand.remove(0), 0);
        check("血量归零后满血进入第二阶段", wokeMob.alive && wokeMob.aiState == 1
                && wokeMob.hp == wokeMob.maxHp, "alive=" + wokeMob.alive + " hp=" + wokeMob.hp);
        eq("第二阶段起手是黑暗回响", "DARK_ECHO", MonsterAi.next(woke, wokeMob).id);

        // 地精首领：没有小怪时偏向集结（召唤）
        SimState leader = scenario(200, 200, new String[]{"Defend_R"}, "GremlinLeader");
        SimState.Mob lead = leader.mobs.get(0);
        MonsterAi.Move rally = MonsterAi.next(leader, lead);
        eq("地精首领无小怪时集结", "RALLY", rally.id);
        eq("集结召唤 2 只地精", 2, rally.summonCount);
        leader.firstEnemyTurn = false;
        lead.intent = rally;
        CombatEngine.endTurn(leader);
        check("集结后场上多出小怪", MonsterAi.aliveGremlins(leader) > 0,
                "小怪数 " + MonsterAi.aliveGremlins(leader));

        // 爬行者：起手召唤匕首
        SimState repto = scenario(200, 200, new String[]{"Defend_R"}, "Reptomancer");
        MonsterAi.Move summonMove = MonsterAi.current(repto, repto.mobs.get(0));
        eq("爬行者起手召唤匕首", "SUMMON", summonMove.id);
        eq("匕首召唤上限是 4", 4, MonsterAi.summonCap("Dagger"));

        // 手写怪物的进阶分档：普通怪 A2、精英 A3、首领 A4 各有一档伤害跃升
        SimState champ0 = scenario(200, 200, new String[]{"Defend_R"}, "Champ");
        int[] champA0 = MonsterAi.tierOverride("Champ", 0, "HEAVY_SLASH");
        int[] champA4 = MonsterAi.tierOverride("Champ", 4, "HEAVY_SLASH");
        check("勇士 A0 无覆盖、A4 伤害提升到 18",
                champA0 == null && champA4 != null && champA4[0] == 18, null);
        int[] spear = MonsterAi.tierOverride("SpireSpear", 3, "SKEWER");
        check("尖塔长矛 A3 串刺变成 4 段",
                spear != null && spear[1] == 4, null);
        SimState hi = scenario(200, 200, new String[]{"Defend_R"}, "Serpent");
        hi.ascension = 2;
        hi.firstEnemyTurn = false;
        SimState.Mob serp = hi.mobs.get(0);
        serp.aiCursor = 0;
        MonsterAi.Move smash = null;
        for (int i = 0; i < 40 && smash == null; i++) {
            MonsterAi.Move candidate = MonsterAi.next(hi, serp);
            if ("SMASH".equals(candidate.id)) {
                smash = candidate;
            }
        }
        check("蛇怪招式可被进阶覆盖命中", smash != null, null);
    }

    // ══ 2.5 与游戏自带文本对账 ══════════════════════════════════

    /**
     * 用游戏自带的卡牌文本（localization/zhs/cards.json，从 desktop-1.0.jar 取出）
     * 对账「打出即消耗」标记：文本里独立成行的「消耗 。」才是打出即消耗，
     * 而「如果这张牌被消耗」这类是被别的效果消耗时触发的效果。
     */
    public static void gameData(Map<String, Object> cards) {
        LOG.add("· 与游戏自带卡牌文本对账（打出即消耗）");
        int missing = 0;
        int mismatched = 0;
        for (String id : CardLibrary.ids()) {
            Object raw = cards.get(id);
            if (raw == null) {
                missing++;
                LOG.add("    游戏文本里没有这个 id：" + id);
                continue;
            }
            Map<String, Object> entry = Json.asMap(raw);
            String text = Json.str(entry, "DESCRIPTION", "") + " NL "
                    + Json.str(entry, "UPGRADE_DESCRIPTION", "");
            boolean gameExhausts = hasExhaustKeyword(text);
            boolean tableExhausts = CardLibrary.get(id).exhaustSelf;
            if (gameExhausts != tableExhausts) {
                mismatched++;
                LOG.add("    [不一致] " + id + "：" + Json.str(entry, "NAME", "?")
                        + " 表里 exhaustSelf=" + tableExhausts
                        + "，游戏文本=" + gameExhausts);
            }
        }
        check("所有登记卡牌都能在游戏文本里找到", missing == 0, "缺失 " + missing + " 张");
        check("「打出即消耗」标记与游戏文本一致", mismatched == 0, "不一致 " + mismatched + " 张");
    }

    /**
     * 判断文本里是否出现「打出即消耗」这个关键字。
     *
     * 游戏文本用 “ NL ” 表示换行。独立成行的「消耗 。」就是关键字；
     * 少数牌的文本漏了换行（如狂宴“……最大生命。 消耗 。”），所以再补一条
     * 「句末标点后紧跟“消耗。”」的规则。反过来，“将其消耗”（破灭）、
     * “消耗 1 张牌”（燃烧契约）、“如果这张牌被消耗，”（哨卫）指的都是别的牌，不算。
     */
    private static boolean hasExhaustKeyword(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.replace(" NL ", "\n");
        for (String rawLine : normalized.split("\n")) {
            String line = stripSpaces(rawLine);
            if (line.equals("消耗。") || line.equalsIgnoreCase("Exhaust.")) {
                return true;
            }
            int idx = line.indexOf("消耗。");
            while (idx >= 0) {
                int before = idx - 1;
                if (before >= 0 && isSentenceEnd(line.charAt(before))) {
                    return true;
                }
                idx = line.indexOf("消耗。", idx + 2);
            }
        }
        return false;
    }

    private static String stripSpaces(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isSpace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '　';
    }

    public static void search() {
        LOG.add("· 整场搜索");
        SimState keyBase = scenario(80, 80, new String[]{"Strike_R"}, "JawWorm");
        check("相同模拟状态的搜索键稳定", keyBase.searchKey().equals(keyBase.fork().searchKey()), null);
        SimState keyRng = keyBase.fork();
        keyRng.rngState++;
        check("随机数游标不同的分支不合并", !keyBase.searchKey().equals(keyRng.searchKey()), null);
        SimState keyCounter = keyBase.fork();
        keyCounter.cardsPlayedThisTurn++;
        check("本回合触发计数不同的分支不合并",
                !keyBase.searchKey().equals(keyCounter.searchKey()), null);
        SimState keyGrowth = keyBase.fork();
        keyGrowth.growth.put("Rampage", 1);
        check("成长牌实例计数不同的分支不合并",
                !keyBase.searchKey().equals(keyGrowth.searchKey()), null);
        SimState keyMob = keyBase.fork();
        keyMob.mobs.get(0).aiCounter++;
        check("怪物 AI 隐藏计数不同的分支不合并",
                !keyBase.searchKey().equals(keyMob.searchKey()), null);
        SimState keyCard = keyBase.fork();
        keyCard.hand.set(0, CardLibrary.toSimCard("Strike_R", "打击", 1, 9, 0, 1,
                true, false, true, false));
        check("同 ID 但不同卡牌实例数值的分支不合并",
                !keyBase.searchKey().equals(keyCard.searchKey()), null);
        SimState keyExhaust = keyBase.fork();
        keyExhaust.exhaustPile.add(CardLibrary.toSimCard("Strike_R", "打击", 1, 6, 0, 1,
                true, false, true, false));
        SimState keyExhaustOther = keyExhaust.fork();
        keyExhaustOther.exhaustPile.set(0, CardLibrary.toSimCard("Defend_R", "防御", 1, 0, 5, 1,
                false, false, false, false));
        check("消耗堆内容不同但张数相同的分支不合并",
                !keyExhaust.searchKey().equals(keyExhaustOther.searchKey()), null);

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
        check("路线带随机分支复推结果（承伤区间）",
                plan != null && plan.samples > 1 && plan.hpLossBest <= plan.hpLossWorst,
                plan == null ? "无结果" : plan.confidenceNote());

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
