package com.combatsolver1.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * 铁甲战士全卡池的效果规格表。
 *
 * damage / block 这类随力量、遗物实时变化的数值由实机读取后填入；这里描述的是
 * “这张牌做什么”：打几下、施加什么状态、抽几张、给不给能量、有没有消耗联动、获得什么能力。
 * approx = 该牌的部分语义仍为近似（例如按手牌张数成长、需要额外选牌等）。
 */
public final class CardLibrary {
    private CardLibrary() {
    }

    public static final class Template {
        public int hits = 1;
        public int draw;
        public int energy;
        public int vuln;
        public int weak;
        public int str;
        public int dex;
        public int selfDamage;
        public int blockPerExhaust;      // 第二阵风：每消耗一张给格挡
        public int damagePerExhaust;     // 恶魔火焰：每消耗一张造成伤害
        public boolean exhaustHand;      // 消耗整手牌
        public boolean damageFromBlock;  // 泰山压顶：伤害 = 当前格挡
        public boolean exhaustNonAttacks; // 断魂斩：消耗所有非攻击牌
        public boolean xCost;            // 旋风斩：X 费
        public int strengthMult;         // 重刃：每点力量额外结算的倍数
        public int blockMult;            // 壁垒冲撞：格挡倍数
        public boolean vulnAll;          // 对全体施加剧伤
        public boolean weakAll;          // 对全体施加虚弱
        public boolean selfVuln;         // 狂暴：自身获得易伤
        public String power;             // 获得的能力
        public int powerAmount;
        public boolean approx;
        public boolean unmodeled;
        public boolean doubleBlock;
        public boolean doubleStrength;
        public boolean healOnDamage;
        public boolean gainMaxHpOnKill;
        public int energyOnExhaust;
        public String addCardToDiscard;
        public int addCount;
        public boolean exhaustSelf;
        public boolean ethereal;
        public int enemyStrDown;
        public boolean requiresVulnerable;
        public boolean requiresAttackIntent;
        public int damagePerStrike;
        public boolean copyHandAttack;
        public boolean playTopExhaust;
        public boolean discardToDraw;
        public boolean exhaustToHand;
        public boolean exhaustOneDrawTwo;
        public boolean bloodForBlood;
        public int growPerPlay;
        public boolean generateStrike;
        public boolean upgradeHand;
        public int endTurnSelfDamage;
        public boolean regret;
        public boolean tempStrength;        // 活动肌肉：力量只在当前回合有效
        public boolean requiresAllAttacks;  // 斗殴：手牌必须全是攻击牌
        public boolean shuffleAddedToDraw;  // 塞入的状态牌洗进抽牌堆而不是弃牌堆
        public boolean putBackWorst;        // 战吼：抽牌后把最差的一张放回牌堆顶

        Template hits(int v) { hits = v; return this; }
        Template draw(int v) { draw = v; return this; }
        Template energy(int v) { energy = v; return this; }
        Template vuln(int v) { vuln = v; return this; }
        Template weak(int v) { weak = v; return this; }
        Template str(int v) { str = v; return this; }
        Template dex(int v) { dex = v; return this; }
        Template selfDamage(int v) { selfDamage = v; return this; }
        Template blockPerExhaust(int v) { blockPerExhaust = v; return this; }
        Template damagePerExhaust(int v) { damagePerExhaust = v; return this; }
        Template exhaustHand() { exhaustHand = true; return this; }
        Template fromBlock() { damageFromBlock = true; return this; }
        Template exhaustNonAttacks() { exhaustNonAttacks = true; return this; }
        Template x() { xCost = true; return this; }
        Template strengthMult(int v) { strengthMult = v; return this; }
        Template vulnAll(int v) { vulnAll = true; vuln = v; return this; }
        Template weakAll(int v) { weakAll = true; weak = v; return this; }
        Template selfVuln(int v) { selfVuln = true; vuln = v; return this; }
        Template power(String p, int amount) { power = p; powerAmount = amount; return this; }
        Template approx() { approx = true; return this; }
        Template unmodeled() { unmodeled = true; return this; }
        Template doubleBlock() { doubleBlock = true; approx = false; return this; }
        Template doubleStrength() { doubleStrength = true; approx = false; return this; }
        Template healOnDamage() { healOnDamage = true; approx = false; return this; }
        Template gainMaxHpOnKill() { gainMaxHpOnKill = true; approx = false; return this; }
        Template energyOnExhaust(int v) { energyOnExhaust = v; approx = false; return this; }
        Template addCard(String id, int count) {
            addCardToDiscard = id;
            addCount = count;
            return this;
        }
        Template exhaustSelf() { exhaustSelf = true; approx = false; return this; }
        Template ethereal() { ethereal = true; approx = false; return this; }
        Template enemyStrDown(int v) { enemyStrDown = v; approx = false; return this; }
        Template reqVulnerable() { requiresVulnerable = true; return this; }
        Template reqAttackIntent() { requiresAttackIntent = true; approx = false; return this; }
        Template perStrike(int v) { damagePerStrike = v; approx = false; return this; }
        Template copyHandAttack() { copyHandAttack = true; approx = false; return this; }
        Template playTopExhaust() { playTopExhaust = true; approx = false; return this; }
        Template discardToDraw() { discardToDraw = true; approx = false; return this; }
        Template exhaustToHand() { exhaustToHand = true; approx = false; return this; }
        Template exhaustOneDrawTwo() { exhaustOneDrawTwo = true; approx = false; return this; }
        Template bloodForBlood() { bloodForBlood = true; approx = false; return this; }
        Template growPerPlay(int v) { growPerPlay = v; approx = false; return this; }
        Template generateStrike() { generateStrike = true; approx = false; return this; }
        Template upgradeHand() { upgradeHand = true; approx = false; return this; }
        Template endTurnDamage(int v) { endTurnSelfDamage = v; approx = false; return this; }
        Template regret() { regret = true; approx = false; return this; }
        Template tempStrength() { tempStrength = true; approx = false; return this; }
        Template requiresAllAttacks() { requiresAllAttacks = true; approx = false; return this; }
        Template shuffleAddedToDraw() { shuffleAddedToDraw = true; approx = false; return this; }
        Template putBackWorst() { putBackWorst = true; approx = false; return this; }
    }

    private static final Map<String, Template> TABLE = new HashMap<>();

    private static Template card(String id) {
        Template t = TABLE.get(id);
        if (t == null) {
            t = new Template();
            TABLE.put(id, t);
        }
        return t;
    }

    static {
        // ── 基础与常见 ──────────────────────────────────────────────
        card("Strike_R");
        card("Defend_R");
        card("Bash").vuln(2);
        card("Anger").addCard("Anger", 1);      // 复制一张自身进入弃牌堆
        card("Armaments").upgradeHand();        // 升级手牌中最好的一张
        card("Body Slam").fromBlock();
        card("Clash").requiresAllAttacks();     // 仅当手牌全是攻击牌可打出
        card("Cleave");
        card("Clothesline").weak(2);
        card("Flex").str(2).tempStrength();     // 临时力量（回合结束失效）
        card("Havoc").playTopExhaust();         // 打出牌堆顶牌并消耗“那张牌”（自身不消耗）
        card("Headbutt").discardToDraw();       // 从弃牌堆取回一张放到牌堆顶
        card("Heavy Blade").strengthMult(3);
        card("Iron Wave");
        card("Perfected Strike").perStrike(2);  // 每张“打击”额外加伤
        card("Pommel Strike").draw(1);
        card("Shrug It Off").draw(1);
        card("Sword Boomerang").hits(3);
        card("Thunderclap").vulnAll(1);
        card("True Grit");
        card("Twin Strike").hits(2);
        card("Warcry").draw(1).putBackWorst().exhaustSelf(); // 抽牌后放回一张，自身消耗
        card("Wild Strike").addCard("Wound", 1).shuffleAddedToDraw(); // 洗入一张伤口

        // ── 少见 ───────────────────────────────────────────────────
        card("Battle Trance").draw(3);
        card("Blood for Blood").bloodForBlood(); // 费用随本场掉血次数降低
        card("Bloodletting").energy(2).selfDamage(3);
        card("Burning Pact").exhaustOneDrawTwo(); // 消耗最差的一张牌并抽两张（自身不消耗）
        card("Carnage").ethereal();             // 虚灵（回合结束消耗）
        card("Combust").power("combust", 5);
        card("Dark Embrace").power("darkEmbrace", 1);
        card("Disarm").enemyStrDown(2).exhaustSelf(); // 降低敌人力量，自身消耗
        card("Dropkick").draw(1).energy(1).reqVulnerable(); // 需目标处于易伤
        card("Dual Wield").copyHandAttack();    // 复制手牌里最好的一张攻击牌
        card("Entrench").doubleBlock();         // 格挡翻倍
        card("Evolve").power("evolve", 1);
        card("Feel No Pain").power("feelNoPain", 3);
        card("Fire Breathing").power("fireBreathing", 6);
        card("Flame Barrier").power("thorns", 4);
        card("Ghostly Armor").ethereal();       // 虚灵
        card("Hemokinesis").selfDamage(2);
        card("Infernal Blade").generateStrike().exhaustSelf(); // 生成一张随机攻击牌，自身消耗
        card("Inflame").str(2);
        card("Intimidate").weakAll(1).exhaustSelf(); // 消耗
        card("Metallicize").power("metallicize", 3);
        card("Power Through").addCard("Wound", 2); // 加入两张伤口
        card("Pummel").hits(4).exhaustSelf();   // 消耗
        card("Rage").power("rage", 3);
        card("Rampage").growPerPlay(5);         // 每次使用永久加伤
        card("Reckless Charge").addCard("Dazed", 1).shuffleAddedToDraw(); // 洗入一张眩晕
        card("Rupture").power("rupture", 1);
        card("Searing Blow").approx();          // 可反复升级加伤
        card("Second Wind").blockPerExhaust(5).exhaustHand();
        card("Seeing Red").energy(2).exhaustSelf(); // 消耗
        card("Sentinel").energyOnExhaust(2);    // 被消耗时给能量（自身不消耗）
        card("Sever Soul").exhaustNonAttacks();
        card("Shockwave").vulnAll(3).weakAll(3).exhaustSelf(); // 消耗
        card("Spot Weakness").str(3).reqAttackIntent(); // 需目标意图为攻击
        card("Uppercut").weak(2);
        card("Whirlwind").x();

        // ── 稀有 ───────────────────────────────────────────────────
        card("Barricade").power("barricade", 1);
        card("Berserk").power("berserk", 1).selfVuln(2);
        card("Bludgeon");
        card("Brutality").power("brutality", 1);
        card("Corruption").power("corruption", 1);
        card("Demon Form").power("demonForm", 2);
        card("Double Tap").power("doubleTap", 1);
        card("Exhume").exhaustToHand();         // 取回一张已消耗的牌
        card("Feed").gainMaxHpOnKill();         // 击杀时提升最大生命
        card("Fiend Fire").damagePerExhaust(7).exhaustHand().exhaustSelf();
        card("Immolate").approx();              // 全体伤害并把一张灼伤洗入弃牌堆
        card("Impervious");
        card("Juggernaut").power("juggernaut", 5);
        card("Limit Break").doubleStrength().exhaustSelf(); // 力量翻倍，自身消耗
        card("Offering").energy(2).draw(3).selfDamage(6).exhaustSelf(); // 消耗
        card("Reaper").healOnDamage();          // 全体伤害并回复未格挡伤害

        // 未登记的牌按类型保守推断（攻击=伤害，技能有格挡=格挡）

        // ── 精确化覆盖：把上面标为近似的牌补成完整实现 ──
        card("Entrench").doubleBlock();
        card("Limit Break").doubleStrength();
        card("Reaper").healOnDamage();
        card("Feed").gainMaxHpOnKill();
        card("Sentinel").energyOnExhaust(2);
        card("Wild Strike").addCard("Wound", 1);
        card("Reckless Charge").addCard("Dazed", 1);
        card("Immolate").addCard("Burn", 1);
        // 打完即消耗的牌（影响牌库循环）
        card("Pummel").exhaustSelf();
        card("Shockwave").exhaustSelf();
        card("Intimidate").exhaustSelf();
        card("Seeing Red").exhaustSelf();
        card("Offering").exhaustSelf();
        card("Exhume").exhaustSelf();
        // 虚灵：回合结束若仍在手牌则消耗
        card("Carnage").ethereal();
        card("Ghostly Armor").ethereal();
        // 精确化：敌人力量削减 / 条件牌 / 按打击数加伤 / 塞两张伤口
        card("Disarm").enemyStrDown(2);
        card("Dropkick").reqVulnerable();
        card("Spot Weakness").reqAttackIntent();
        card("Perfected Strike").perStrike(2);
        card("Power Through").addCard("Wound", 2);
        // 牌库操作类
        card("Anger").addCard("Anger", 1);
        card("Dual Wield").copyHandAttack();
        card("Havoc").playTopExhaust();
        card("Headbutt").discardToDraw();
        card("Exhume").exhaustToHand();
        card("Burning Pact").exhaustOneDrawTwo();
        card("Blood for Blood").bloodForBlood();
        card("Rampage").growPerPlay(5);
        // 灼热打击的成长只来自升级（尚未建模），保持 approx 标记
        card("Infernal Blade").generateStrike();
        card("Armaments").upgradeHand();
        // 依据 sts_lightspeed 的权威“打出即消耗”表补正：喂食 / 收割 / 铜墙铁壁同样消耗
        card("Feed").exhaustSelf();
        card("Reaper").exhaustSelf();
        card("Impervious").exhaustSelf();
        // 诅咒与状态：在手牌中的回合结束结算（不可打出，语义上按状态牌处理）
        card("Burn").endTurnDamage(2);
        card("Decay").endTurnDamage(2);
        card("Regret").regret();
    }

    public static Template get(String id) {
        Template t = TABLE.get(id);
        return t != null ? t : new Template();
    }

    public static boolean isKnown(String id) {
        return TABLE.containsKey(id);
    }

    /** 已登记的全部卡牌 id（离线自检用，可用于与游戏自带文本对账）。 */
    public static java.util.List<String> ids() {
        java.util.List<String> list = new java.util.ArrayList<String>(TABLE.keySet());
        java.util.Collections.sort(list);
        return list;
    }

    /**
     * 按数据表生成一张模拟用卡牌。实机捕捉（LiveCapture）与离线自检共用这一条路径，
     * 保证「同一张牌」在两条链路上得到完全相同的效果标记。
     *
     * @param hits 段数；传 &lt;= 0 表示使用数据表里的段数
     */
    public static SimState.Card toSimCard(String id, String name, int cost, int damage, int block,
                                          int hits, boolean attack, boolean aoe, boolean needsTarget,
                                          boolean unmodeled) {
        Template t = get(id);
        int realHits = hits > 0 ? hits : t.hits;
        SimState.Card sim = new SimState.Card(id, name, cost, damage, realHits, block,
                attack, aoe, needsTarget, t.draw, t.energy, t.vuln, t.weak, t.str, unmodeled,
                t.selfDamage, t.str, t.dex, t.blockPerExhaust, t.damagePerExhaust,
                t.exhaustHand, t.damageFromBlock, t.exhaustNonAttacks,
                t.vulnAll, t.weakAll, t.selfVuln, t.strengthMult, t.power, t.powerAmount, t.approx);
        sim.xCost = t.xCost;
        sim.damagePerEnergy = damage;
        sim.doubleBlock = t.doubleBlock;
        sim.doubleStrength = t.doubleStrength;
        sim.healOnDamage = t.healOnDamage;
        sim.gainMaxHpOnKill = t.gainMaxHpOnKill;
        sim.energyOnExhaust = t.energyOnExhaust;
        sim.addCardToDiscard = t.addCardToDiscard;
        sim.addCount = t.addCount;
        sim.exhaustSelf = t.exhaustSelf;
        sim.ethereal = t.ethereal;
        sim.enemyStrDown = t.enemyStrDown;
        sim.requiresVulnerable = t.requiresVulnerable;
        sim.requiresAttackIntent = t.requiresAttackIntent;
        sim.damagePerStrike = t.damagePerStrike;
        sim.copyHandAttack = t.copyHandAttack;
        sim.playTopExhaust = t.playTopExhaust;
        sim.discardToDraw = t.discardToDraw;
        sim.exhaustToHand = t.exhaustToHand;
        sim.exhaustOneDrawTwo = t.exhaustOneDrawTwo;
        sim.bloodForBlood = t.bloodForBlood;
        sim.growPerPlay = t.growPerPlay;
        sim.generateStrike = t.generateStrike;
        sim.upgradeHand = t.upgradeHand;
        sim.endTurnSelfDamage = t.endTurnSelfDamage;
        sim.regret = t.regret;
        sim.tempStrength = t.tempStrength;
        sim.requiresAllAttacks = t.requiresAllAttacks;
        sim.shuffleAddedToDraw = t.shuffleAddedToDraw;
        sim.putBackWorst = t.putBackWorst;
        return sim;
    }
}
