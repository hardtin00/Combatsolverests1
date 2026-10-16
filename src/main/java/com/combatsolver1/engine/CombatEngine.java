package com.combatsolver1.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 战斗规则结算：出牌、能力触发、消耗联动、结束回合、怪物行动、抽牌与洗牌。
 *
 * 已知近似：多段攻击按牌表声明；怪物后续回合按行动循环表；部分需要额外选牌或
 * 影响牌库构成的牌用 best-effort 近似（在 CardLibrary 中标记 approx）。
 */
public final class CombatEngine {
    private CombatEngine() {
    }

    /** 实际费用（腐化让技能变 0 费）。 */
    public static int costOf(SimState s, SimState.Card c) {
        if (c.bloodForBlood) {
            int lost = Math.max(0, s.maxHp - s.hp);
            return Math.max(0, c.cost - lost / 3);
        }
        if (c.xCost) {
            return -1; // X 费：费用在出牌时按全部剩余能量结算
        }
        if (s.pCorruption && !c.attack && c.power == null) {
            return 0;
        }
        return c.cost;
    }

    public static boolean canPlay(SimState s, SimState.Card c) {
        if (c.xCost) {
            return s.energy > 0 && !s.playerDead() && !s.won();
        }
        int cost = costOf(s, c);
        return cost >= 0 && cost <= s.energy && !s.playerDead() && !s.won();
    }

    /** 牌库里“打击”类牌的数量（完美打击的加伤依据）。 */
    private static int countStrikeCards(SimState s) {
        int count = 0;
        count += countStrikes(s.hand);
        count += countStrikes(s.drawPile);
        count += countStrikes(s.discardPile);
        count += countStrikes(s.exhaustPile);
        return count;
    }

    private static int countStrikes(List<SimState.Card> pile) {
        int count = 0;
        for (SimState.Card c : pile) {
            if (c.id != null && c.id.startsWith("Strike")) {
                count++;
            }
        }
        return count;
    }

    public static void playCard(SimState s, SimState.Card card, int targetIndex) {
        int spend = costOf(s, card);
        int xValue = 0;
        int dealtThisPlay = 0;
        if (card.xCost) {
            xValue = s.energy;
            s.energy = 0;
        } else if (spend >= 0) {
            s.energy -= spend;
        }

        if (card.attack) {
            s.attacksThisCombat++;
            boolean vulnOk = !card.requiresVulnerable
                    || (targetIndex >= 0 && targetIndex < s.mobs.size()
                        && s.mobs.get(targetIndex).vulnerable > 0);
            int hits = card.hits;
            if (s.pDoubleTap > 0) {
                hits *= 2;
                s.pDoubleTap--;
            }
            int dmg;
            if (card.xCost) {
                dmg = card.damagePerEnergy * xValue + s.simStrengthBonus;
            } else if (card.damageFromBlock) {
                dmg = s.block + s.simStrengthBonus;
            } else {
                dmg = card.damage + (card.strengthMult > 0
                        ? s.simStrengthBonus * card.strengthMult
                        : s.simStrengthBonus);
            }
            if (card.damagePerStrike > 0) {
                dmg += card.damagePerStrike * countStrikeCards(s);
            }
            if (card.growPerPlay > 0) {
                Integer used = s.growth.get(card.id);
                dmg += card.growPerPlay * (used == null ? 0 : used);
            }
            dmg += card.bonusDamage;
            if (s.hasRelic("Red Skull") && s.hp <= s.maxHp / 2) {
                dmg += 3; // 红骷髅：半血以下加力量
            }
            if (s.hasRelic("Pen Nib") && s.attacksThisCombat % 10 == 0) {
                dmg *= 2; // 钢笔尖：每第 10 次攻击伤害翻倍
            }
            if (dmg < 0) {
                dmg = 0;
            }
            if (!vulnOk) {
                dealtThisPlay += 0;
            } else if (card.aoe) {
                for (SimState.Mob m : s.mobs) {
                    if (m.alive) {
                        dealtThisPlay += hitMob(m, dmg, hits);
                    }
                }
            } else if (targetIndex >= 0 && targetIndex < s.mobs.size() && s.mobs.get(targetIndex).alive) {
                dealtThisPlay += hitMob(s.mobs.get(targetIndex), dmg, hits);
            }
            if (s.pRage > 0) {
                gainBlock(s, s.pRage);
            }
        }

        if (card.block > 0) {
            gainBlock(s, card.block);
        }
        if (card.bonusBlock > 0) {
            gainBlock(s, card.bonusBlock);
        }
        if (card.doubleBlock) {
            gainBlock(s, s.block);
        }

        if (card.vulnAll || card.weakAll) {
            for (SimState.Mob m : s.mobs) {
                if (!m.alive) {
                    continue;
                }
                if (card.vulnAll) m.vulnerable += card.vuln;
                if (card.weakAll) m.weak += card.weak;
            }
        } else if (targetIndex >= 0 && targetIndex < s.mobs.size()) {
            SimState.Mob m = s.mobs.get(targetIndex);
            if (m.alive) {
                if (card.vuln > 0) m.vulnerable += card.vuln;
                if (card.weak > 0) m.weak += card.weak;
            }
        }

        if (card.selfVuln && card.vuln > 0) {
            s.playerVulnerable += card.vuln;
        }
        if (card.selfDamage > 0) {
            s.hp -= card.selfDamage;
            if (s.pRupture > 0) {
                s.simStrengthBonus += s.pRupture;
            }
        }
        if (card.strGain > 0) {
            if (!card.requiresAttackIntent || anyEnemyAttacks(s)) {
                s.simStrengthBonus += card.strGain;
            }
        }
        if (card.doubleStrength) {
            s.simStrengthBonus *= 2;
        }
        if (card.healOnDamage && dealtThisPlay > 0) {
            s.hp = Math.min(s.maxHp, s.hp + dealtThisPlay);
        }
        if (card.energyGain > 0) {
            boolean ok = !card.requiresVulnerable
                    || (targetIndex >= 0 && targetIndex < s.mobs.size()
                        && s.mobs.get(targetIndex).vulnerable > 0);
            if (ok) {
                s.energy += card.energyGain;
            }
        }
        if (card.draw > 0) {
            boolean ok = !card.requiresVulnerable
                    || (targetIndex >= 0 && targetIndex < s.mobs.size()
                        && s.mobs.get(targetIndex).vulnerable > 0);
            if (ok) {
                drawCards(s, card.draw);
            }
        }

        if (card.enemyStrDown > 0 && targetIndex >= 0 && targetIndex < s.mobs.size()) {
            SimState.Mob m = s.mobs.get(targetIndex);
            m.strength = Math.max(0, m.strength - card.enemyStrDown);
        }

        // 牌库操作
        if (card.copyHandAttack) {
            for (SimState.Card c : s.hand) {
                if (c.attack) {
                    s.hand.add(c.copy());
                    break;
                }
            }
        }
        if (card.exhaustOneDrawTwo) {
            for (int i = 0; i < s.hand.size(); i++) {
                SimState.Card c = s.hand.remove(i);
                s.exhaustPile.add(c);
                onExhaustCard(s, c);
                break;
            }
            drawCards(s, 2);
        }
        if (card.discardToDraw && !s.discardPile.isEmpty()) {
            s.drawPile.add(s.discardPile.remove(0));
        }
        if (card.exhaustToHand && !s.exhaustPile.isEmpty()) {
            s.hand.add(s.exhaustPile.remove(0));
        }
        if (card.playTopExhaust && !s.drawPile.isEmpty()) {
            SimState.Card top = s.drawPile.remove(0);
            int t = -1;
            for (int i = 0; i < s.mobs.size(); i++) {
                if (s.mobs.get(i).alive) {
                    t = i;
                    break;
                }
            }
            playCard(s, top, t);
            s.exhaustPile.add(top);
            onExhaustCard(s, top);
        }
        if (card.generateStrike) {
            s.hand.add(new SimState.Card("Strike_R", "打击", 1, 6, 1, 0,
                    true, false, true, 0, 0, 0, 0, 0, false));
        }
        // 军备：升级手牌中的一张（模拟里用“+3 伤害 / +2 格挡”表示）
        if (card.upgradeHand) {
            for (SimState.Card c : s.hand) {
                if (c.attack) {
                    c.bonusDamage += 3;
                    break;
                } else if (c.block > 0) {
                    c.bonusBlock += 2;
                    break;
                }
            }
        }
        if (card.growPerPlay > 0) {
            Integer used = s.growth.get(card.id);
            s.growth.put(card.id, (used == null ? 0 : used) + 1);
        }

        int exhausted = 0;
        if (card.exhaustNonAttacks) {
            exhausted += exhaustNonAttacks(s);
        }
        if (card.exhaustHand) {
            exhausted += exhaustHand(s);
        }
        if (card.damagePerExhaust > 0 && exhausted > 0) {
            SimState.Mob first = firstAlive(s);
            if (first != null) {
                hitMob(first, card.damagePerExhaust * exhausted, 1);
            }
        }
        if (card.blockPerExhaust > 0 && exhausted > 0) {
            gainBlock(s, card.blockPerExhaust * exhausted);
        }

        // 塞牌：把状态牌放进弃牌堆，稀释后续抽牌
        if (card.addCardToDiscard != null && card.addCount > 0) {
            for (int i = 0; i < card.addCount; i++) {
                s.discardPile.add(statusCard(card.addCardToDiscard));
            }
        }

        if (card.power != null) {
            applyPower(s, card.power, card.powerAmount);
        }

        // 出牌后的去向：消耗或进入弃牌堆（腐化会让技能改成消耗）
        if (card.exhaustSelf || (s.pCorruption && !card.attack && card.power == null)) {
            s.exhaustPile.add(card);
            onExhaustCard(s, card);
        } else {
            s.discardPile.add(card);
        }

        for (SimState.Mob m : s.mobs) {
            if (m.alive && m.hp <= 0) {
                m.alive = false;
                m.hp = 0;
                if (card.gainMaxHpOnKill) {
                    s.maxHp += 3;
                    s.hp += 3;
                }
            }
        }
    }

    /** 生成一张状态牌（伤口 / 眩晕 / 灼伤），不可打出。 */
    private static SimState.Card statusCard(String id) {
        String name = id.equals("Wound") ? "伤口" : (id.equals("Dazed") ? "眩晕" : "灼伤");
        return new SimState.Card(id, name, -2, 0, 1, 0, false, false, false,
                0, 0, 0, 0, 0, true);
    }

    private static void applyPower(SimState s, String power, int amount) {
        if (power.equals("metallicize")) s.pMetallicize += amount;
        else if (power.equals("feelNoPain")) s.pFeelNoPain += amount;
        else if (power.equals("darkEmbrace")) s.pDarkEmbrace += amount;
        else if (power.equals("demonForm")) s.pDemonForm += amount;
        else if (power.equals("juggernaut")) s.pJuggernaut += amount;
        else if (power.equals("combust")) s.pCombust += amount;
        else if (power.equals("rupture")) s.pRupture += amount;
        else if (power.equals("thorns")) s.pThorns += amount;
        else if (power.equals("doubleTap")) s.pDoubleTap += amount;
        else if (power.equals("rage")) s.pRage += amount;
        else if (power.equals("berserk")) s.pBerserk += amount;
        else if (power.equals("brutality")) s.pBrutality += amount;
        else if (power.equals("barricade")) s.pBarricade = true;
        else if (power.equals("corruption")) s.pCorruption = true;
    }

    /** 使用一瓶药水：应用效果并移除该药水。 */
    public static void usePotion(SimState s, int index, int targetIndex) {
        if (index < 0 || index >= s.potionIds.size()) {
            return;
        }
        String id = s.potionIds.remove(index);
        int[] spec = PotionLibrary.spec(id);
        if (spec == null) {
            return;
        }
        if (spec[3] > 0) {
            s.simStrengthBonus += spec[3];
        }
        if (spec[4] > 0) {
            s.hp = Math.min(s.maxHp, s.hp + spec[4]);
        }
        if (spec[2] > 0) {
            gainBlock(s, spec[2]);
        }
        if (spec[0] > 0) {
            if (spec[1] == 1) {
                for (SimState.Mob m : s.mobs) {
                    if (m.alive) {
                        hitMob(m, spec[0], 1);
                    }
                }
            } else if (targetIndex >= 0 && targetIndex < s.mobs.size()
                    && s.mobs.get(targetIndex).alive) {
                hitMob(s.mobs.get(targetIndex), spec[0], 1);
            }
        }
        if (spec[5] > 0 && targetIndex >= 0 && targetIndex < s.mobs.size()
                && s.mobs.get(targetIndex).alive) {
            s.mobs.get(targetIndex).weak += spec[5];
        }
        if (spec[6] > 0 && targetIndex >= 0 && targetIndex < s.mobs.size()
                && s.mobs.get(targetIndex).alive) {
            s.mobs.get(targetIndex).vulnerable += spec[6];
        }
        if (spec[7] > 0) {
            s.energy += spec[7];
        }
        if (spec[8] > 0) {
            drawCards(s, spec[8]);
        }
        if (spec[9] > 0) {
            s.pThorns += spec[9];
        }
        for (SimState.Mob m : s.mobs) {
            if (m.alive && m.hp <= 0) {
                m.alive = false;
                m.hp = 0;
            }
        }
    }

    /** 获得格挡（会触发肉钩 / 铁蒺藜类能力）。 */
    private static void gainBlock(SimState s, int amount) {
        if (amount <= 0) {
            return;
        }
        s.block += amount;
        if (s.pJuggernaut > 0) {
            SimState.Mob m = firstAlive(s);
            if (m != null) {
                hitMob(m, s.pJuggernaut, 1);
            }
        }
    }

    /** 消耗一张牌带来的联动（无痛给格挡、黑暗拥抱抽牌）。 */
    private static void onExhaust(SimState s) {
    }

    /** 某张牌被消耗时的自身效果（哨卫给能量）+ 能力联动。 */
    private static void onExhaustCard(SimState s, SimState.Card c) {
        if (c.energyOnExhaust > 0) {
            s.energy += c.energyOnExhaust;
        }
        if (s.pFeelNoPain > 0) {
            gainBlock(s, s.pFeelNoPain);
        }
        if (s.pDarkEmbrace > 0) {
            drawCards(s, 1);
        }
    }

    private static int exhaustHand(SimState s) {
        int count = s.hand.size();
        for (SimState.Card c : s.hand) {
            s.exhaustPile.add(c);
            onExhaustCard(s, c);
        }
        s.hand.clear();
        return count;
    }

    private static int exhaustNonAttacks(SimState s) {
        List<SimState.Card> keep = new ArrayList<>();
        int count = 0;
        for (SimState.Card c : s.hand) {
            if (c.attack) {
                keep.add(c);
            } else {
                s.exhaustPile.add(c);
                onExhaustCard(s, c);
                count++;
            }
        }
        s.hand.clear();
        s.hand.addAll(keep);
        return count;
    }

    private static SimState.Mob firstAlive(SimState s) {
        for (SimState.Mob m : s.mobs) {
            if (m.alive) {
                return m;
            }
        }
        return null;
    }

    private static boolean anyEnemyAttacks(SimState s) {
        for (SimState.Mob m : s.mobs) {
            if (m.alive && m.attackPerTurn(s.firstEnemyTurn) > 0) {
                return true;
            }
        }
        return false;
    }

    /** 对怪物造成伤害，返回实际削减的生命值（用于收割回血）。 */
    private static int hitMob(SimState.Mob m, int baseDamage, int hits) {
        int dealt = 0;
        int dmg = baseDamage;
        if (m.vulnerable > 0) {
            dmg = dmg * 3 / 2;
        }
        for (int i = 0; i < Math.max(1, hits); i++) {
            int remaining = dmg;
            if (m.block > 0) {
                int absorbed = Math.min(m.block, remaining);
                m.block -= absorbed;
                remaining -= absorbed;
            }
            if (remaining > 0) {
                m.hp -= remaining;
                dealt += remaining;
            }
            if (m.hp <= 0) {
                break;
            }
        }
        return dealt;
    }

    public static void endTurn(SimState s) {
        monsterTurn(s);
        if (s.playerDead()) {
            return;
        }
        // 虚灵：回合结束若仍在手牌则被消耗（会触发消耗联动）
        List<SimState.Card> keep = new ArrayList<>();
        for (SimState.Card c : s.hand) {
            if (c.endTurnSelfDamage > 0) {
                s.hp -= c.endTurnSelfDamage;
            }
            if (c.regret) {
                s.hp -= s.hand.size();
            }
            if (c.ethereal) {
                s.exhaustPile.add(c);
                onExhaustCard(s, c);
            } else {
                keep.add(c);
            }
        }
        s.hand.clear();
        s.discardPile.addAll(keep);
        if (!s.pBarricade) {
            s.block = 0;
        }
        if (s.playerVulnerable > 0) s.playerVulnerable--;
        drawCards(s, s.handSize);
        if (s.hasRelic("Ice Cream")) {
            s.energy += s.energyPerTurn + s.pBerserk; // 冰淇淋：未用完的能量保留
        } else {
            s.energy = s.energyPerTurn + s.pBerserk;
        }
        s.turn++;
        applyStartOfTurnPowers(s);
        applyStartOfTurnRelics(s);
    }

    /** 玩家回合开始时触发的能力。 */
    private static void applyStartOfTurnPowers(SimState s) {
        if (s.pMetallicize > 0) {
            gainBlock(s, s.pMetallicize);
        }
        if (s.pDemonForm > 0) {
            s.simStrengthBonus += s.pDemonForm;
        }
        if (s.pCombust > 0) {
            for (SimState.Mob m : s.mobs) {
                if (m.alive) {
                    hitMob(m, s.pCombust, 1);
                }
            }
            s.hp -= 1;
        }
        if (s.pBrutality > 0) {
            s.hp -= 1;
            drawCards(s, 1);
        }
    }

    private static void applyStartOfTurnRelics(SimState s) {
        if (s.hasRelic("Mercury Hourglass")) {
            for (SimState.Mob m : s.mobs) {
                if (m.alive) {
                    hitMob(m, 3, 1);
                }
            }
        }
        if (s.hasRelic("Orichalcum") && s.block == 0) {
            s.block += 6;
        }
        if (s.hasRelic("Horn Cleat") && s.turn == 2) {
            s.block += 14;
        }
        if (s.hasRelic("Captain's Wheel") && s.turn == 3) {
            s.block += 18;
        }
    }

    private static void monsterTurn(SimState s) {
        int total = 0;
        boolean first = s.firstEnemyTurn;
        for (SimState.Mob m : s.mobs) {
            if (!m.alive) {
                continue;
            }
            if (m.block > 0) m.block = 0;
            int attack = m.attackPerTurn(first);
            total += attack;
            if (attack > 0) {
                int thorns = s.pThorns + (s.hasRelic("Bronze Scales") ? 3 : 0);
                if (thorns > 0) {
                    hitMob(m, thorns, 1);
                }
            }
            if (!first) {
                m.patternIndex++;
            }
            if (m.vulnerable > 0) m.vulnerable--;
            if (m.weak > 0) m.weak--;
        }
        s.firstEnemyTurn = false;
        if (total > 0) {
            if (s.playerVulnerable > 0) {
                total = total * 3 / 2;
            }
            int remaining = total;
            if (s.block > 0) {
                int absorbed = Math.min(s.block, remaining);
                s.block -= absorbed;
                remaining -= absorbed;
            }
            if (remaining > 0) {
                s.hp -= remaining;
            }
        }
    }

    public static void drawCards(SimState s, int count) {
        for (int i = 0; i < count; i++) {
            if (s.drawPile.isEmpty()) {
                if (s.discardPile.isEmpty()) {
                    return;
                }
                shuffleIntoDraw(s);
            }
            if (s.drawPile.isEmpty()) {
                return;
            }
            int idx = s.nextInt(s.drawPile.size());
            s.hand.add(s.drawPile.remove(idx));
        }
    }

    private static void shuffleIntoDraw(SimState s) {
        List<SimState.Card> pile = s.discardPile;
        while (!pile.isEmpty()) {
            int idx = s.nextInt(pile.size());
            s.drawPile.add(pile.remove(idx));
        }
    }
}
