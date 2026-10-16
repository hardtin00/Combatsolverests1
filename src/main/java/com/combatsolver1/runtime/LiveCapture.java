package com.combatsolver1.runtime;

import com.combatsolver1.engine.CardLibrary;
import com.combatsolver1.engine.CombatEngine;
import com.combatsolver1.engine.SimState;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.lang.reflect.Field;

/**
 * 在主线程把实机战斗状态读成一份独立的模拟根状态。
 * 只读，不修改真实战斗，对应 CombatSolver 的 CombatRootSnapshot。
 */
public final class LiveCapture {
    private LiveCapture() {
    }

    public static SimState captureRoot() {
        if (AbstractDungeon.player == null || AbstractDungeon.getCurrRoom() == null) {
            return null;
        }
        if (AbstractDungeon.getCurrRoom().monsters == null || AbstractDungeon.player.hand == null) {
            return null;
        }
        SimState s = new SimState();
        s.hp = AbstractDungeon.player.currentHealth;
        s.maxHp = AbstractDungeon.player.maxHealth;
        s.block = AbstractDungeon.player.currentBlock;
        // 真正的当前能量在 EnergyPanel（HUD 显示的也是它）；player.energy.energy 不随出牌扣减。
        s.energy = EnergyPanel.getCurrentEnergy();
        s.energyPerTurn = Math.max(1, AbstractDungeon.player.energy.energyMaster);
        s.handSize = Math.max(1, AbstractDungeon.player.gameHandSize);
        s.turn = AbstractDungeon.actionManager.turn;
        s.rngState = 0x9E3779B97F4A7C15L + s.turn * 131L + AbstractDungeon.player.hand.size();

        for (AbstractCard c : AbstractDungeon.player.hand.group) {
            s.hand.add(simCard(c));
        }
        for (AbstractCard c : AbstractDungeon.player.drawPile.group) {
            s.drawPile.add(simCard(c));
        }
        for (AbstractCard c : AbstractDungeon.player.discardPile.group) {
            s.discardPile.add(simCard(c));
        }
        for (AbstractCard c : AbstractDungeon.player.exhaustPile.group) {
            s.exhaustPile.add(simCard(c));
        }
        for (AbstractRelic r : AbstractDungeon.player.relics) {
            s.relics.add(r.relicId);
        }
        for (AbstractPotion p : AbstractDungeon.player.potions) {
            if (p != null && p.ID != null) {
                s.potionIds.add(p.ID);
            }
        }
        for (AbstractMonster m : AbstractDungeon.getCurrRoom().monsters.monsters) {
            if (!m.isDeadOrEscaped()) {
                s.mobs.add(simMob(m));
            }
        }
        if (s.mobs.isEmpty()) {
            return null;
        }
        // 初始化怪物 AI：算出下一回合的招式、睡眠与永久强化（仪式 / 狂怒）
        CombatEngine.initMonsters(s);
        return s;
    }

    /** 面板用：当前药水列表。 */
    public static java.util.List<String> potionNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (AbstractDungeon.player == null) {
            return out;
        }
        for (AbstractPotion p : AbstractDungeon.player.potions) {
            if (p != null && p.name != null) {
                out.add(p.name);
            }
        }
        return out;
    }

    /** 统计当前牌组的建模情况，告诉玩家这套牌组的预测可信度。 */
    public static String deckCoverageText() {
        int exact = 0;
        int approx = 0;
        int unknown = 0;
        StringBuilder unknownNames = new StringBuilder();
        if (AbstractDungeon.player != null && AbstractDungeon.player.drawPile != null) {
            java.util.List<AbstractCard> all = new java.util.ArrayList<>();
            all.addAll(AbstractDungeon.player.drawPile.group);
            all.addAll(AbstractDungeon.player.hand.group);
            all.addAll(AbstractDungeon.player.discardPile.group);
            all.addAll(AbstractDungeon.player.exhaustPile.group);
            for (AbstractCard c : all) {
                if (!CardLibrary.isKnown(c.cardID)) {
                    unknown++;
                    if (unknownNames.indexOf(c.name) < 0) {
                        if (unknownNames.length() > 0) {
                            unknownNames.append("、");
                        }
                        unknownNames.append(c.name);
                    }
                } else if (CardLibrary.get(c.cardID).approx) {
                    approx++;
                } else {
                    exact++;
                }
            }
        }
        if (unknown > 0) {
            return "牌组覆盖：" + exact + " 完整 / " + approx + " 近似 / " + unknown
                    + " 未建模（" + unknownNames + "）";
        }
        return "牌组覆盖：" + exact + " 完整 / " + approx + " 近似";
    }

    private static SimState.Card simCard(AbstractCard c) {
        CardLibrary.Template t = CardLibrary.get(c.cardID);
        boolean attack = c.type == AbstractCard.CardType.ATTACK;
        boolean aoe = c.target == AbstractCard.CardTarget.ALL_ENEMY;
        boolean needsTarget = c.target == AbstractCard.CardTarget.ENEMY || aoe;
        int cost = c.freeToPlay() ? 0 : c.costForTurn;
        int damage = attack ? Math.max(c.damage, c.baseDamage) : 0;
        int block = c.block > 0 ? Math.max(c.block, c.baseBlock) : 0;
        boolean unmodeled = t.unmodeled || (!attack && block == 0 && !CardLibrary.isKnown(c.cardID));
        return CardLibrary.toSimCard(c.cardID, c.name, cost, damage, block, t.hits,
                attack, aoe, needsTarget, unmodeled);
    }

    private static SimState.Mob simMob(AbstractMonster m) {
        int[] at = moveAttack(m);
        int perHit;
        int hits;
        if (at[0] > 0) {
            perHit = at[0];
            hits = at[1];
        } else {
            perHit = fallbackAttack(m);
            hits = 1;
        }
        int turnDamage = perHit * Math.max(1, hits);
        SimState.Mob mob = new SimState.Mob(m.id, m.name, m.currentHealth, m.maxHealth,
                m.currentBlock, perHit, hits, 0, true);
        // 实机力量 / 易伤 / 虚弱：当前意图的基础伤害不含力量，这里补齐
        mob.strength = powerAmount(m, "Strength");
        mob.vulnerable = powerAmount(m, "Vulnerable");
        mob.weak = powerAmount(m, "Weak");
        mob.aiCursor = Math.max(0, m.moveHistory.size());
        // 记录本回合观察，用来估计这类怪物之后每回合的平均输出。
        int turn = AbstractDungeon.actionManager.turn;
        EnemyModel.observe(m.id, turn, turnDamage);
        mob.avgDamage = EnemyModel.average(m.id);
        // 优先使用从实战观察中识别出的真实周期；识别不出再退回出牌表。
        String detected = EnemyModel.detectCycle(m.id);
        if (detected != null) {
            mob.pattern = detected;
            mob.patternIndex = 0;
        } else {
            mob.pattern = com.combatsolver1.engine.MonsterPatterns.patternFor(m.id);
            // moveHistory 里已经记录了当前这一格的招式，下一回合落在它的后一位。
            mob.patternIndex = Math.max(0, m.moveHistory.size());
        }
        return mob;
    }

    /** 读取实机身上的某个 Power 层数（力量 / 易伤 / 虚弱等），没有返回 0。 */
    private static int powerAmount(AbstractCreature creature, String powerId) {
        if (creature == null || creature.powers == null) {
            return 0;
        }
        for (AbstractPower p : creature.powers) {
            if (p != null && powerId.equals(p.ID)) {
                return p.amount;
            }
        }
        return 0;
    }

    /** 面板用：怪物名 + 生命 + 当前意图（含多段次数）。 */
    public static String intentText(AbstractMonster m) {
        int[] at = moveAttack(m);
        String kind = intentKind(m);
        if (at[0] > 0) {
            return kind + " " + at[0] + (at[1] > 1 ? "×" + at[1] : "");
        }
        if (isAttacking(m)) {
            int d = Math.max(m.getIntentDmg(), 0);
            return d > 0 ? kind + " " + d : kind;
        }
        return kind;
    }

    private static String intentKind(AbstractMonster m) {
        if (m.intent == null) {
            return "未知";
        }
        switch (m.intent) {
            case ATTACK:
            case ATTACK_BUFF:
            case ATTACK_DEBUFF:
            case ATTACK_DEFEND:
                return "攻击";
            case DEFEND:
            case DEFEND_BUFF:
            case DEFEND_DEBUFF:
                return "防御";
            case BUFF:
                return "强化";
            case DEBUFF:
            case STRONG_DEBUFF:
                return "削弱";
            case SLEEP:
                return "睡眠";
            case STUN:
                return "眩晕";
            case ESCAPE:
                return "逃跑";
            default:
                return "未知";
        }
    }

    /** 用怪物伤害表的平均值估计它后续回合的攻击输出。 */
    private static int averageAttack(AbstractMonster m) {
        int sum = 0;
        int count = 0;
        for (Object o : m.damage) {
            if (o instanceof Integer) {
                int v = (Integer) o;
                if (v > 0) {
                    sum += v;
                    count++;
                }
            }
        }
        if (count == 0) {
            return 0;
        }
        return Math.max(1, sum / count);
    }

    /** 读取怪物当前招式：{单段伤害, 段数}；非攻击或读不到返回 {0,1}。 */
    private static int[] moveAttack(AbstractMonster m) {
        if (MOVE_FIELD == null) {
            return new int[]{0, 1};
        }
        try {
            Object move = MOVE_FIELD.get(m);
            if (move == null) {
                return new int[]{0, 1};
            }
            Class<?> cls = move.getClass();
            int base = cls.getField("baseDamage").getInt(move);
            int mult = cls.getField("multiplier").getInt(move);
            if (base > 0) {
                return new int[]{base, Math.max(1, mult)};
            }
        } catch (Throwable ignored) {
            // 读取失败时退回保守估计
        }
        return new int[]{0, 1};
    }

    /**
     * 招式读不到时的保守估计：当前意图是攻击就用意图伤害，
     * 否则退回它伤害表中最大的攻击值，避免预测成 0 伤害。
     */
    private static int fallbackAttack(AbstractMonster m) {
        if (isAttacking(m)) {
            int d = Math.max(m.getIntentDmg(), 0);
            if (d > 0) {
                return d;
            }
        }
        int best = 0;
        for (Object o : m.damage) {
            if (o instanceof Integer) {
                int v = (Integer) o;
                if (v > best) {
                    best = v;
                }
            }
        }
        return best;
    }

    private static final Field MOVE_FIELD = resolveMoveField();

    private static Field resolveMoveField() {
        try {
            Field f = AbstractMonster.class.getDeclaredField("move");
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isAttacking(AbstractMonster m) {
        return m.intent == AbstractMonster.Intent.ATTACK
                || m.intent == AbstractMonster.Intent.ATTACK_BUFF
                || m.intent == AbstractMonster.Intent.ATTACK_DEBUFF
                || m.intent == AbstractMonster.Intent.ATTACK_DEFEND;
    }
}
