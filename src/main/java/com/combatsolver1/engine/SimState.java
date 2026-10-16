package com.combatsolver1.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 可 fork 的战斗模拟状态。纯数据，不引用任何游戏对象，
 * 对应 CombatSolver 里“从稳定根派生出的独立模拟分支”。
 */
public class SimState {
    public int hp;
    public int maxHp;
    public int block;
    public int energy;
    public int energyPerTurn;
    public int handSize;
    public int playerVulnerable;
    public int turn;
    /** 模拟过程中获得的力量加成，用于同一回合内后续攻击。 */
    public int simStrengthBonus;
    /** 是否还没打过第一次怪物回合（第一次用当前意图，之后用平均估计）。 */
    public boolean firstEnemyTurn = true;

    // ── 能力牌（铁甲核心机制）──
    public int pMetallicize;
    public int pFeelNoPain;
    public int pDarkEmbrace;
    public int pDemonForm;
    public int pJuggernaut;
    public int pCombust;
    public int pRupture;
    public int pThorns;
    public int pDoubleTap;
    public int pRage;
    public int pBerserk;
    public int pBrutality;
    public boolean pBarricade;
    public boolean pCorruption;
    /** 本场战斗已打出的攻击牌数量（钢笔尖 / 苦无类遗物按次数触发）。 */
    public int attacksThisCombat;

    public final List<Card> hand = new ArrayList<>();
    public final List<Card> drawPile = new ArrayList<>();
    public final List<Card> discardPile = new ArrayList<>();
    public final List<Card> exhaustPile = new ArrayList<>();
    public final List<Mob> mobs = new ArrayList<>();
    /** 玩家持有的遗物 id，用于结算每回合触发的战斗遗物。 */
    public final List<String> relics = new ArrayList<>();
    /** 本场战斗内的牌实例成长计数（按牌 id 累计使用次数）。 */
    public final java.util.Map<String, Integer> growth = new java.util.HashMap<>();
    /** 携带的药水 id（按药水栏顺序）。 */
    public final java.util.List<String> potionIds = new java.util.ArrayList<>();

    public long rngState;

    public SimState fork() {
        SimState s = new SimState();
        s.hp = hp;
        s.maxHp = maxHp;
        s.block = block;
        s.energy = energy;
        s.energyPerTurn = energyPerTurn;
        s.handSize = handSize;
        s.playerVulnerable = playerVulnerable;
        s.turn = turn;
        s.simStrengthBonus = simStrengthBonus;
        s.firstEnemyTurn = firstEnemyTurn;
        s.pMetallicize = pMetallicize;
        s.pFeelNoPain = pFeelNoPain;
        s.pDarkEmbrace = pDarkEmbrace;
        s.pDemonForm = pDemonForm;
        s.pJuggernaut = pJuggernaut;
        s.pCombust = pCombust;
        s.pRupture = pRupture;
        s.pThorns = pThorns;
        s.pDoubleTap = pDoubleTap;
        s.pRage = pRage;
        s.pBerserk = pBerserk;
        s.pBrutality = pBrutality;
        s.pBarricade = pBarricade;
        s.pCorruption = pCorruption;
        s.attacksThisCombat = attacksThisCombat;
        s.rngState = rngState;
        for (Card c : hand) s.hand.add(c.copy());
        for (Card c : drawPile) s.drawPile.add(c.copy());
        for (Card c : discardPile) s.discardPile.add(c.copy());
        for (Card c : exhaustPile) s.exhaustPile.add(c.copy());
        for (Mob m : mobs) s.mobs.add(m.copy());
        s.relics.addAll(relics);
        s.growth.putAll(growth);
        s.potionIds.addAll(potionIds);
        return s;
    }

    public boolean hasRelic(String id) {
        return relics.contains(id);
    }

    public boolean playerDead() {
        return hp <= 0;
    }

    public boolean won() {
        for (Mob m : mobs) {
            if (m.alive) return false;
        }
        return true;
    }

    public int aliveMobs() {
        int n = 0;
        for (Mob m : mobs) if (m.alive) n++;
        return n;
    }

    /** 稳定状态签名：用于“实机与预测一致则复用路线”的核对。 */
    public String signature() {
        StringBuilder sb = new StringBuilder();
        sb.append(hp).append('|').append(block).append('|').append(energy).append('|');
        for (Mob m : mobs) {
            if (m.alive) {
                sb.append(m.hp).append(',').append(m.block).append(';');
            }
        }
        return sb.toString();
    }

    /** 确定性随机数（xorshift），fork 后可复现。 */
    public int nextInt(int bound) {
        if (bound <= 1) return 0;
        long x = rngState;
        x ^= (x << 13);
        x ^= (x >>> 7);
        x ^= (x << 17);
        rngState = x;
        return (int) Math.floorMod(x, bound);
    }

    /** 模拟用卡牌：只保留模拟需要的字段。 */
    public static class Card {
        public final String id;
        public final String name;
        public final int cost;
        public final int damage;
        public final int hits;
        public final int block;
        public final boolean attack;
        public final boolean aoe;
        public final boolean needsTarget;
        public final int draw;
        public final int energyGain;
        public final int vuln;
        public final int weak;
        public final int strength;
        public final boolean unmodeled;
        public final int selfDamage;
        public final int strGain;
        public final int dexGain;
        public final int blockPerExhaust;
        public final int damagePerExhaust;
        public final boolean exhaustHand;
        public final boolean damageFromBlock;
        public final boolean exhaustNonAttacks;
        public final boolean vulnAll;
        public final boolean weakAll;
        public final boolean selfVuln;
        public final int strengthMult;
        public final String power;
        public final int powerAmount;
        public final boolean approx;

        /** X 费：费用为全部剩余能量，伤害按每点能量伤害 × X 计算。 */
        public boolean xCost;
        public int damagePerEnergy;
        public boolean doubleBlock;      // 壁垒冲撞：格挡翻倍
        public boolean doubleStrength;   // 突破极限：力量翻倍
        public boolean healOnDamage;     // 收割：按造成的伤害回血
        public boolean gainMaxHpOnKill;  // 喂食：击杀提升最大生命
        public int energyOnExhaust;      // 哨卫：被消耗时给能量
        public String addCardToDiscard;  // 往弃牌堆塞牌（伤口/眩晕/灼伤）
        public int addCount;
        public boolean exhaustSelf;      // 打完即消耗（不进入弃牌堆）
        public boolean ethereal;         // 虚灵：回合结束若仍在手牌则消耗
        public int enemyStrDown;         // 降低敌人力量
        public boolean requiresVulnerable;   // 仅当目标处于易伤时生效（回旋踢）
        public boolean requiresAttackIntent; // 仅当敌人意图为攻击时生效（窥破弱点）
        public int damagePerStrike;      // 每张“打击”牌额外加伤（完美打击）
        public boolean copyHandAttack;   // 双持：复制手牌中的一张攻击牌
        public boolean playTopExhaust;   // 浩劫：打出抽牌堆顶并消耗
        public boolean discardToDraw;    // 头槌：从弃牌堆取回一张
        public boolean exhaustToHand;    // 掘墓：从消耗堆取回一张
        public boolean exhaustOneDrawTwo; // 燃烧契约：消耗一张并抽两张
        public boolean bloodForBlood;    // 血怒：费用随本场已损失生命降低
        public int growPerPlay;          // 每次使用后永久加伤（横冲直撞 / 灼热打击）
        public boolean generateStrike;   // 地狱之刃：生成一张攻击牌
        public int bonusDamage;          // 本场内被升级带来的额外伤害
        public int bonusBlock;           // 本场内被升级带来的额外格挡
        public boolean upgradeHand;      // 军备：升级手牌中的一张
        public int endTurnSelfDamage;    // 灼伤 / 腐朽：回合结束若在手牌则受伤
        public boolean regret;           // 悔恨：回合结束按手牌张数受伤

        public Card(String id, String name, int cost, int damage, int hits, int block,
                    boolean attack, boolean aoe, boolean needsTarget,
                    int draw, int energyGain, int vuln, int weak, int strength, boolean unmodeled) {
            this(id, name, cost, damage, hits, block, attack, aoe, needsTarget,
                    draw, energyGain, vuln, weak, strength, unmodeled,
                    0, 0, 0, 0, 0, false, false, false, false, false, false, 0, null, 0, false);
        }

        public Card(String id, String name, int cost, int damage, int hits, int block,
                    boolean attack, boolean aoe, boolean needsTarget,
                    int draw, int energyGain, int vuln, int weak, int strength, boolean unmodeled,
                    int selfDamage, int strGain, int dexGain, int blockPerExhaust, int damagePerExhaust,
                    boolean exhaustHand, boolean damageFromBlock, boolean exhaustNonAttacks,
                    boolean vulnAll, boolean weakAll, boolean selfVuln, int strengthMult,
                    String power, int powerAmount, boolean approx) {
            this.id = id;
            this.name = name;
            this.cost = cost;
            this.damage = damage;
            this.hits = hits;
            this.block = block;
            this.attack = attack;
            this.aoe = aoe;
            this.needsTarget = needsTarget;
            this.draw = draw;
            this.energyGain = energyGain;
            this.vuln = vuln;
            this.weak = weak;
            this.strength = strength;
            this.unmodeled = unmodeled;
            this.selfDamage = selfDamage;
            this.strGain = strGain;
            this.dexGain = dexGain;
            this.blockPerExhaust = blockPerExhaust;
            this.damagePerExhaust = damagePerExhaust;
            this.exhaustHand = exhaustHand;
            this.damageFromBlock = damageFromBlock;
            this.exhaustNonAttacks = exhaustNonAttacks;
            this.vulnAll = vulnAll;
            this.weakAll = weakAll;
            this.selfVuln = selfVuln;
            this.strengthMult = strengthMult;
            this.power = power;
            this.powerAmount = powerAmount;
            this.approx = approx;
        }

        public Card copy() {
            Card c = new Card(id, name, cost, damage, hits, block, attack, aoe, needsTarget,
                    draw, energyGain, vuln, weak, strength, unmodeled,
                    selfDamage, strGain, dexGain, blockPerExhaust, damagePerExhaust,
                    exhaustHand, damageFromBlock, exhaustNonAttacks,
                    vulnAll, weakAll, selfVuln, strengthMult, power, powerAmount, approx);
            c.xCost = xCost;
            c.damagePerEnergy = damagePerEnergy;
            c.doubleBlock = doubleBlock;
            c.doubleStrength = doubleStrength;
            c.healOnDamage = healOnDamage;
            c.gainMaxHpOnKill = gainMaxHpOnKill;
            c.energyOnExhaust = energyOnExhaust;
            c.addCardToDiscard = addCardToDiscard;
            c.addCount = addCount;
            c.exhaustSelf = exhaustSelf;
            c.ethereal = ethereal;
            c.enemyStrDown = enemyStrDown;
            c.requiresVulnerable = requiresVulnerable;
            c.requiresAttackIntent = requiresAttackIntent;
            c.damagePerStrike = damagePerStrike;
            c.copyHandAttack = copyHandAttack;
            c.playTopExhaust = playTopExhaust;
            c.discardToDraw = discardToDraw;
            c.exhaustToHand = exhaustToHand;
            c.exhaustOneDrawTwo = exhaustOneDrawTwo;
            c.bloodForBlood = bloodForBlood;
            c.growPerPlay = growPerPlay;
            c.generateStrike = generateStrike;
            c.bonusDamage = bonusDamage;
            c.bonusBlock = bonusBlock;
            c.upgradeHand = upgradeHand;
            c.endTurnSelfDamage = endTurnSelfDamage;
            c.regret = regret;
            return c;
        }

        public boolean hasEffect() {
            return damage > 0 || block > 0 || draw > 0 || energyGain > 0
                    || vuln > 0 || weak > 0 || strength > 0 || selfDamage > 0 || strGain > 0
                    || dexGain > 0 || blockPerExhaust > 0 || damagePerExhaust > 0 || exhaustHand
                    || damageFromBlock || exhaustNonAttacks || strengthMult > 0 || power != null;
        }
    }

    /** 模拟用怪物。 */
    public static class Mob {
        public final String id;
        public final String name;
        public int hp;
        public int maxHp;
        public int block;
        public int atkDamage;
        public int atkHits;
        public int strength;
        public int vulnerable;
        public int weak;
        public boolean alive;
        /** 后续回合的攻击估计（取伤害表平均值）。 */
        public int avgDamage;
        /** 行动节奏：A=攻击，N=不攻击；为空表示未登记，退回平均值。 */
        public String pattern = "";
        /** 下一回合在 pattern 中的下标。 */
        public int patternIndex;

        public Mob(String id, String name, int hp, int maxHp, int block,
                   int atkDamage, int atkHits, int strength, boolean alive) {
            this.id = id;
            this.name = name;
            this.hp = hp;
            this.maxHp = maxHp;
            this.block = block;
            this.atkDamage = atkDamage;
            this.atkHits = atkHits;
            this.strength = strength;
            this.alive = alive;
        }

        public Mob copy() {
            Mob m = new Mob(id, name, hp, maxHp, block, atkDamage, atkHits, strength, alive);
            m.vulnerable = vulnerable;
            m.weak = weak;
            m.avgDamage = avgDamage;
            m.pattern = pattern;
            m.patternIndex = patternIndex;
            return m;
        }

        public int attackPerTurn(boolean firstAttack) {
            int per;
            int hits;
            if (firstAttack) {
                per = atkDamage;
                hits = Math.max(1, atkHits);
            } else if (pattern != null && !pattern.isEmpty()) {
                int idx = Math.floorMod(patternIndex, pattern.length());
                if (pattern.charAt(idx) != 'A') {
                    return 0;
                }
                per = atkDamage;
                hits = Math.max(1, atkHits);
            } else {
                per = avgDamage > 0 ? avgDamage : atkDamage;
                hits = 1;
            }
            int base = per + strength;
            if (base < 0) base = 0;
            if (weak > 0) base = base * 3 / 4;
            return base * hits;
        }
    }
}
