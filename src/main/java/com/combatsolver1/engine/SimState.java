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
    /** 玩家虚弱：造成的攻击伤害 ×3/4（由怪物施加）。 */
    public int playerWeak;
    /** 玩家残废：获得的格挡 ×3/4（由怪物施加）。 */
    public int playerFrail;
    /** 玩家缠身：本回合不能打出攻击牌（红奴隶主）。 */
    public int playerEntangled;
    public int turn;
    /** 模拟过程中获得的力量加成，用于同一回合内后续攻击。 */
    public int simStrengthBonus;
    /** 模拟过程中的敏捷变化（被吸魂等效果削减，影响后续格挡牌）。 */
    public int simDexBonus;
    /** 本回合临时力量（活动肌肉），回合结束失效。 */
    public int tempStrength;
    /** 是否还没打过第一次怪物回合（第一次用当前意图，之后用平均估计）。 */
    public boolean firstEnemyTurn = true;

    /** 本场战斗玩家累计失去的生命次数（血怒费用按次数递减）。 */
    public int hpLossEvents;
    /** 本局进阶等级（影响怪物的伤害与 AI 分档）。 */
    public int ascension;
    /** 缠绕（尖塔生长）：玩家回合结束时受到的伤害。 */
    public int constricted;
    /** 本回合已打出的牌数（时间扭曲按 12 张触发）。 */
    public int cardsPlayedThisTurn;
    /** 时间扭曲触发后本回合不能再出牌。 */
    public boolean turnLocked;
    /** 玩家无形剩余回合：受到的伤害降为 1（幻影）。 */
    public int playerIntangible;
    /** 玩家神器层数：挡掉怪物施加的减益。 */
    public int playerArtifact;
    /** 玩家本轮不能用卡牌获得格挡的剩余回合（紧急按钮）。 */
    public int blockLock;
    /** 能力：每回合打出 5 张牌就对全体造成伤害（华丽）。 */
    public int pPanache;
    /** 能力：每次给敌人上减益就追加伤害（残忍天性）。 */
    public int pSadistic;
    /** 能力：每回合开始抽到一张随机无色牌（磁力）。 */
    public int pMagnetism;
    /** 能力：每回合开始免费打出牌堆顶（混沌）。 */
    public int pMayhem;
    /** 能力：回合结束对全体造成伤害（欧米伽）。 */
    public int pOmega;
    /** 炸弹倒计时与爆炸伤害。 */
    /** 本回合手牌费用上限（启蒙：全部降到 1）。0 表示不生效。 */
    public int costOverrideThisTurn;
    public int bombTimer;
    public int bombDamage;

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
    /** 本场战斗已打出的技能牌数量（开信刀按次数触发）。 */
    public int skillsThisCombat;

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
        s.playerWeak = playerWeak;
        s.playerFrail = playerFrail;
        s.playerEntangled = playerEntangled;
        s.turn = turn;
        s.simStrengthBonus = simStrengthBonus;
        s.simDexBonus = simDexBonus;
        s.tempStrength = tempStrength;
        s.hpLossEvents = hpLossEvents;
        s.ascension = ascension;
        s.constricted = constricted;
        s.cardsPlayedThisTurn = cardsPlayedThisTurn;
        s.turnLocked = turnLocked;
        s.playerIntangible = playerIntangible;
        s.playerArtifact = playerArtifact;
        s.blockLock = blockLock;
        s.pPanache = pPanache;
        s.pSadistic = pSadistic;
        s.pMagnetism = pMagnetism;
        s.pMayhem = pMayhem;
        s.pOmega = pOmega;
        s.costOverrideThisTurn = costOverrideThisTurn;
        s.bombTimer = bombTimer;
        s.bombDamage = bombDamage;
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
        s.skillsThisCombat = skillsThisCombat;
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
        public boolean tempStrength;     // 活动肌肉：力量只在当前回合有效
        public boolean upgraded;         // 模拟中已被升级（军备），避免重复升级
        public boolean requiresAllAttacks; // 斗殴：仅当手牌全是攻击牌时可打出
        public boolean shuffleAddedToDraw; // 洗入抽牌堆而不是进入弃牌堆
        public boolean putBackWorst;       // 战吼：抽牌后把一张牌放回牌堆顶
        public int healFlat;               // 固定回血（吸血 / 绷带）
        /** 打出时生成若干张随机牌："colorless" / "attack" / "skill"。 */
        public String randomCardKind;
        public int randomCardCount;
        public int grantIntangible;
        public int blockLockTurns;
        public int bombDamage;
        public int bombTurns;
        public boolean setCostOverride;    // 启蒙：本回合手牌费用降到 N
        public int costOverrideValue;
        public int damagePerDrawPileCard;  // 心灵爆震：伤害 = 牌堆张数 × N
        public int playerArtifact;         // 万灵药：玩家获得神器
        public int drawIfNoAttacks;        // 急躁：手牌无攻击牌才抽

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
            c.tempStrength = tempStrength;
            c.upgraded = upgraded;
            c.requiresAllAttacks = requiresAllAttacks;
            c.shuffleAddedToDraw = shuffleAddedToDraw;
            c.putBackWorst = putBackWorst;
            c.healFlat = healFlat;
            c.randomCardKind = randomCardKind;
            c.randomCardCount = randomCardCount;
            c.grantIntangible = grantIntangible;
            c.blockLockTurns = blockLockTurns;
            c.bombDamage = bombDamage;
            c.bombTurns = bombTurns;
            c.setCostOverride = setCostOverride;
            c.costOverrideValue = costOverrideValue;
            c.damagePerDrawPileCard = damagePerDrawPileCard;
            c.playerArtifact = playerArtifact;
            c.drawIfNoAttacks = drawIfNoAttacks;
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

        // ── 招式表（MonsterAi）──
        /** 下一回合要执行的招式；null 表示该怪物未建模或尚未推进。 */
        public MonsterAi.Move intent;
        /** AI 游标：已经执行过的招式数（捕捉时取自实机 moveHistory 的大小）。 */
        public int aiCursor;
        /** AI 内部计数器（蓄力回合数、防御模式剩余回合等，由具体脚本使用）。 */
        public int aiCounter;
        /** 模式标记（0 进攻 / 1 防御 等，由具体脚本定义）。 */
        public int aiState;
        /** 最近一次执行的招式 id 与连续次数（随机怪物的“不能连续三次”约束）。 */
        public String lastMoveId = "";
        public int lastMoveStreak;
        /** 睡眠中（拉格威林）：受到伤害会被打醒。 */
        public boolean sleeping;
        /** 仪式：每回合结束获得的力量（邪教徒）。 */
        public int ritual;
        /** 狂怒：玩家每打出一张技能牌时获得的力量（地精大块头）。 */
        public int enrage;
        /** 尖刺外甲：玩家攻击它时受到的反伤（守护者）。 */
        public int thorns;
        /** 本场累计承受的伤害（守护者的 30 点阈值）。 */
        public int damageTakenTotal;
        /** 飞行层数（鸟）：被打掉后落地。 */
        public int flight;
        /** 神器：每次挡住一个减益就少一层。 */
        public int artifact;
        /** 可塑：每次受到伤害获得等量格挡（蛇草）。 */
        public int malleable;
        /** 板甲：每回合结束获得等量格挡，受到攻击伤害时少一层（硬壳寄生体）。 */
        public int platedArmor;
        /** 无形：受到的伤害降为 1（涅墨西斯）。 */
        public int intangible;
        /** 无敌：每回合最多承受这么多伤害（腐化之心）。 */
        public int invincible;
        /** 本回合已经吃掉的“无敌”额度。 */
        public int invincibleTaken;
        /** 死亡节拍：玩家每打出一张牌就受到的伤害（腐化之心）。 */
        public int beatOfDeath;
        /** 时间扭曲计数：玩家每打出这么多张牌，强制结束回合并获得力量（时间吞噬者）。 */
        public int timeWarp;
        /** 可塑当前能给的格挡量（每触发一次 +1，玩家回合开始重置）。 */
        public int malleableBlock;
        /** 复活倒计时（暗影：血量归零后隔两回合半血复活）。 */
        public int reviveIn;

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
            m.intent = intent;
            m.aiCursor = aiCursor;
            m.aiCounter = aiCounter;
            m.aiState = aiState;
            m.lastMoveId = lastMoveId;
            m.lastMoveStreak = lastMoveStreak;
            m.sleeping = sleeping;
            m.ritual = ritual;
            m.enrage = enrage;
            m.thorns = thorns;
            m.damageTakenTotal = damageTakenTotal;
            m.flight = flight;
            m.artifact = artifact;
            m.malleable = malleable;
            m.platedArmor = platedArmor;
            m.intangible = intangible;
            m.invincible = invincible;
            m.invincibleTaken = invincibleTaken;
            m.beatOfDeath = beatOfDeath;
            m.timeWarp = timeWarp;
            m.malleableBlock = malleableBlock;
            m.reviveIn = reviveIn;
            return m;
        }

        public int attackPerTurn(boolean firstAttack) {
            int per;
            int hits;
            if (firstAttack) {
                per = atkDamage;
                hits = Math.max(1, atkHits);
            } else if (intent != null) {
                if (intent.damage <= 0) {
                    return 0;
                }
                per = intent.damage;
                hits = Math.max(1, intent.hits);
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
