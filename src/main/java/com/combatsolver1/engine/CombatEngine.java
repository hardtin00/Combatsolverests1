package com.combatsolver1.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 战斗规则结算：出牌、能力触发、消耗联动、结束回合、怪物行动、抽牌与洗牌。
 *
 * 与旧版相比的关键变化：
 * - 怪物行动改由 MonsterAi 的逐招式表驱动（仪式、睡眠、狂怒、蓄力、模式切换、死亡触发）；
 * - 玩家侧的虚弱 / 残废 / 缠身会真实影响伤害、格挡与可打性；
 * - 抽牌堆按“堆顶 = 列表末尾”结算（与游戏 CardGroup.getTopCard 一致），
 *   洗牌改成对整堆做 Fisher–Yates；
 * - 选牌类卡牌（双持 / 头槌 / 掘墓 / 燃烧契约 / 军备）按档位价值选最合适的目标。
 */
public final class CombatEngine {
    private CombatEngine() {
    }

    // ══ 费用与可打性 ══════════════════════════════════════════════

    /** 实际费用（腐化让技能变 0 费；血怒按本场掉血次数递减）。 */
    public static int costOf(SimState s, SimState.Card c) {
        if (c.bloodForBlood) {
            return Math.max(0, c.cost - s.hpLossEvents);
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
        if (s.playerDead() || s.won()) {
            return false;
        }
        if (c.cost < 0 && !c.xCost) {
            return false; // 状态牌与诅咒
        }
        if (s.playerEntangled > 0 && c.attack) {
            return false; // 缠身：本回合不能打出攻击牌
        }
        if (c.requiresAllAttacks && !allCardsAreAttacks(s)) {
            return false; // 斗殴：仅当手牌全是攻击牌时可打出
        }
        if (c.requiresVulnerable && !anyVulnerable(s)) {
            return false; // 回旋踢：需要有处于易伤的敌人
        }
        if (c.xCost) {
            return s.energy > 0;
        }
        int cost = costOf(s, c);
        return cost >= 0 && cost <= s.energy;
    }

    private static boolean allCardsAreAttacks(SimState s) {
        for (SimState.Card c : s.hand) {
            if (!c.attack) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyVulnerable(SimState s) {
        for (SimState.Mob m : s.mobs) {
            if (m.alive && m.vulnerable > 0) {
                return true;
            }
        }
        return false;
    }

    /** 牌库里含“打击”的牌数量（完美打击的加伤依据：按牌名统计）。 */
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
            if (c.id != null && c.id.contains("Strike")) {
                count++;
            }
        }
        return count;
    }

    // ══ 出牌 ══════════════════════════════════════════════════════

    public static void playCard(SimState s, SimState.Card card, int targetIndex) {
        playCardInternal(s, card, targetIndex, false);
    }

    private static void playCardInternal(SimState s, SimState.Card card, int targetIndex, boolean free) {
        int spend = free ? 0 : costOf(s, card);
        int xValue = 0;
        int dealtThisPlay = 0;
        if (card.xCost && !free) {
            xValue = s.energy;
            s.energy = 0;
        } else if (spend > 0) {
            s.energy -= spend;
        }

        // 玩家侧力量 = 常驻增量 + 本回合临时力量
        int strength = s.simStrengthBonus + s.tempStrength;

        if (card.attack) {
            s.attacksThisCombat++;
            applyAttackRelics(s);
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
                dmg = card.damagePerEnergy * xValue + strength;
            } else if (card.damageFromBlock) {
                dmg = s.block + strength;
            } else {
                dmg = card.damage + (card.strengthMult > 0
                        ? strength * card.strengthMult
                        : strength);
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
            if (s.playerWeak > 0) {
                dmg = dmg * 3 / 4; // 玩家虚弱：攻击伤害降低 25%
            }
            if (dmg < 0) {
                dmg = 0;
            }
            if (vulnOk) {
                if (card.aoe) {
                    for (SimState.Mob m : s.mobs) {
                        if (m.alive) {
                            dealtThisPlay += hitMob(s, m, dmg, hits);
                        }
                    }
                } else if (targetIndex >= 0 && targetIndex < s.mobs.size()
                        && s.mobs.get(targetIndex).alive) {
                    dealtThisPlay += hitMob(s, s.mobs.get(targetIndex), dmg, hits);
                }
            }
            if (s.pRage > 0) {
                gainBlock(s, s.pRage);
            }
            // 尖刺类怪物（守护者的尖锐外甲）：玩家每打出一张攻击牌受到反伤
            if (targetIndex >= 0 && targetIndex < s.mobs.size()) {
                SimState.Mob target = s.mobs.get(targetIndex);
                if (target.alive && target.thorns > 0) {
                    loseHp(s, target.thorns);
                }
            }
        } else if (card.power == null) {
            // 技能牌：计数（开信刀）并触发狂怒（地精大块头）
            s.skillsThisCombat++;
            applySkillRelics(s);
            for (SimState.Mob m : s.mobs) {
                if (m.alive && m.enrage > 0) {
                    m.strength += m.enrage;
                }
            }
        }

        if (card.block > 0) {
            gainCardBlock(s, card.block);
        }
        if (card.bonusBlock > 0) {
            gainCardBlock(s, card.bonusBlock);
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
            loseHp(s, card.selfDamage);
            if (s.pRupture > 0) {
                s.simStrengthBonus += s.pRupture;
            }
        }
        if (card.strGain > 0) {
            if (!card.requiresAttackIntent || anyEnemyAttacks(s)) {
                if (card.tempStrength) {
                    s.tempStrength += card.strGain;
                } else {
                    s.simStrengthBonus += card.strGain;
                }
            }
        }
        if (card.doubleStrength) {
            s.simStrengthBonus *= 2;
            s.tempStrength *= 2;
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
        if (card.putBackWorst && !s.hand.isEmpty()) {
            // 战吼：抽一张后把手里最没价值的一张放回牌堆顶
            int idx = worstIndex(s.hand);
            if (idx >= 0) {
                s.drawPile.add(s.hand.remove(idx));
            }
        }

        if (card.enemyStrDown > 0 && targetIndex >= 0 && targetIndex < s.mobs.size()) {
            SimState.Mob m = s.mobs.get(targetIndex);
            m.strength = Math.max(0, m.strength - card.enemyStrDown);
        }

        // ── 牌库操作：按“最优目标”选择，而不是永远取第一张 ──
        if (card.copyHandAttack) {
            int idx = bestAttackIndex(s.hand);
            if (idx >= 0) {
                s.hand.add(s.hand.get(idx).copy());
            }
        }
        if (card.exhaustOneDrawTwo) {
            int idx = worstIndex(s.hand);
            if (idx >= 0) {
                SimState.Card c = s.hand.remove(idx);
                s.exhaustPile.add(c);
                onExhaustCard(s, c);
            }
            drawCards(s, 2);
        }
        if (card.discardToDraw && !s.discardPile.isEmpty()) {
            int idx = bestIndex(s.discardPile);
            s.drawPile.add(s.discardPile.remove(idx)); // 追加到末尾 = 牌堆顶
        }
        if (card.exhaustToHand && !s.exhaustPile.isEmpty()) {
            int idx = bestIndex(s.exhaustPile);
            s.hand.add(s.exhaustPile.remove(idx));
        }
        if (card.playTopExhaust && !s.drawPile.isEmpty()) {
            SimState.Card top = s.drawPile.remove(s.drawPile.size() - 1); // 牌堆顶 = 末尾
            int t = -1;
            for (int i = 0; i < s.mobs.size(); i++) {
                if (s.mobs.get(i).alive) {
                    t = i;
                    break;
                }
            }
            playCardInternal(s, top, t, true); // 浩劫：免费打出并消耗
            s.exhaustPile.add(top);
            onExhaustCard(s, top);
        }
        if (card.generateStrike) {
            s.hand.add(randomAttackCard(s));
        }
        if (card.upgradeHand) {
            upgradeBestCard(s);
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
                hitMob(s, first, card.damagePerExhaust * exhausted, 1);
            }
        }
        if (card.blockPerExhaust > 0 && exhausted > 0) {
            gainBlock(s, card.blockPerExhaust * exhausted);
        }

        // 塞牌：把状态牌放进弃牌堆或洗入抽牌堆，稀释后续抽牌
        if (card.addCardToDiscard != null && card.addCount > 0) {
            for (int i = 0; i < card.addCount; i++) {
                SimState.Card status = addedCard(card.addCardToDiscard);
                if (card.shuffleAddedToDraw) {
                    int at = s.drawPile.isEmpty() ? 0 : s.nextInt(s.drawPile.size() + 1);
                    s.drawPile.add(at, status);
                } else {
                    s.discardPile.add(status);
                }
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

        settleDeaths(s, card);
    }

    /** 结算死亡：标记死亡、触发死亡效果（孢子云）与喂食加血量。 */
    private static void settleDeaths(SimState s, SimState.Card source) {
        for (SimState.Mob m : s.mobs) {
            if (m.alive && m.hp <= 0) {
                m.alive = false;
                m.hp = 0;
                MonsterAi.onDeath(s, m);
                if (source != null && source.gainMaxHpOnKill) {
                    s.maxHp += 3;
                    s.hp += 3;
                }
            }
        }
    }

    // ── 价值档位：给选牌类卡牌挑目标 ──────────────────────────

    private static double cardValue(SimState.Card c) {
        if (c.cost < 0 && !c.xCost) {
            return -1; // 状态牌 / 诅咒
        }
        double v = c.damage * Math.max(1, c.hits) + c.block + c.draw * 3.0
                + c.energyGain * 5.0 + c.strGain * 6.0;
        if (c.power != null) {
            v += 6.0 + c.powerAmount * 2.0;
        }
        return v + c.bonusDamage + c.bonusBlock + c.vuln * 2.0 + c.weak * 2.0;
    }

    private static int bestIndex(List<SimState.Card> pile) {
        int best = -1;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < pile.size(); i++) {
            double v = cardValue(pile.get(i));
            if (v > bestValue) {
                bestValue = v;
                best = i;
            }
        }
        return best;
    }

    private static int bestAttackIndex(List<SimState.Card> pile) {
        int best = -1;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < pile.size(); i++) {
            SimState.Card c = pile.get(i);
            if (!c.attack) {
                continue;
            }
            double v = cardValue(c);
            if (v > bestValue) {
                bestValue = v;
                best = i;
            }
        }
        return best;
    }

    private static int worstIndex(List<SimState.Card> pile) {
        int worst = -1;
        double worstValue = Double.POSITIVE_INFINITY;
        for (int i = 0; i < pile.size(); i++) {
            double v = cardValue(pile.get(i));
            if (v < worstValue) {
                worstValue = v;
                worst = i;
            }
        }
        return worst;
    }

    /** 军备：升级手里最有价值的一张牌（攻击 +3 伤害 / 格挡牌 +3 格挡，通用升级数值）。 */
    private static void upgradeBestCard(SimState s) {
        int best = -1;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < s.hand.size(); i++) {
            SimState.Card c = s.hand.get(i);
            if (c.upgraded || (!c.attack && c.block <= 0) || c.cost < 0) {
                continue;
            }
            double v = cardValue(c);
            if (v > bestValue) {
                bestValue = v;
                best = i;
            }
        }
        if (best < 0) {
            return;
        }
        SimState.Card c = s.hand.get(best);
        c.upgraded = true;
        if (c.attack) {
            c.bonusDamage += 3;
        } else {
            c.bonusBlock += 3;
        }
    }

    /** 地狱之刃：生成一张随机攻击牌（简化自原版未升级卡池）。 */
    private static final String[][] RANDOM_ATTACKS = {
            {"Strike_R", "6"}, {"Twin Strike", "5"}, {"Cleave", "8"}, {"Clothesline", "12"},
            {"Heavy Blade", "14"}, {"Pommel Strike", "9"}, {"Anger", "6"}, {"Iron Wave", "5"},
            {"Headbutt", "9"}, {"Sword Boomerang", "3"},
    };

    private static SimState.Card randomAttackCard(SimState s) {
        String[] pick = RANDOM_ATTACKS[s.nextInt(RANDOM_ATTACKS.length)];
        String id = pick[0];
        int damage = Integer.parseInt(pick[1]);
        CardLibrary.Template t = CardLibrary.get(id);
        SimState.Card c = new SimState.Card(id, id, 1, damage, t.hits, 0,
                true, false, true, t.draw, t.energy, t.vuln, t.weak, t.str, false);
        c.exhaustSelf = t.exhaustSelf;
        return c;
    }

    /** 生成一张状态牌（伤口 / 眩晕 / 灼伤 / 黏液），按原版语义结算。 */
    /** 生成被「塞入」的牌：状态牌不可打出，复制自身的（愤怒）则是可打出的普通牌。 */
    private static SimState.Card addedCard(String id) {
        if ("Anger".equals(id)) {
            SimState.Card c = new SimState.Card("Anger", "愤怒", 0, 6, 1, 0,
                    true, false, true, 0, 0, 0, 0, 0, false);
            c.addCardToDiscard = "Anger";
            c.addCount = 1;
            return c;
        }
        if ("Strike_R".equals(id)) {
            return new SimState.Card("Strike_R", "打击", 1, 6, 1, 0,
                    true, false, true, 0, 0, 0, 0, 0, false);
        }
        return statusCard(id);
    }

    private static SimState.Card statusCard(String id) {
        if ("Dazed".equals(id)) {
            SimState.Card c = new SimState.Card(id, "眩晕", -2, 0, 1, 0, false, false, false,
                    0, 0, 0, 0, 0, true);
            c.ethereal = true;
            return c;
        }
        if ("Burn".equals(id)) {
            SimState.Card c = new SimState.Card(id, "灼伤", -2, 0, 1, 0, false, false, false,
                    0, 0, 0, 0, 0, true);
            c.endTurnSelfDamage = 2;
            return c;
        }
        if ("Slimed".equals(id)) {
            return new SimState.Card(id, "黏液", -2, 0, 1, 0, false, false, false,
                    0, 0, 0, 0, 0, true);
        }
        return new SimState.Card(id, "伤口", -2, 0, 1, 0, false, false, false,
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

    // ══ 药水 ══════════════════════════════════════════════════════

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
                        hitMob(s, m, spec[0], 1);
                    }
                }
            } else if (targetIndex >= 0 && targetIndex < s.mobs.size()
                    && s.mobs.get(targetIndex).alive) {
                hitMob(s, s.mobs.get(targetIndex), spec[0], 1);
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
        settleDeaths(s, null);
    }

    // ══ 格挡与掉血 ════════════════════════════════════════════════

    /** 直接失去生命（自我伤害 / 反伤），不吃格挡，计入掉血次数。 */
    public static void loseHp(SimState s, int amount) {
        if (amount <= 0) {
            return;
        }
        s.hp -= amount;
        s.hpLossEvents++;
    }

    /** 受到攻击：先吃格挡，再扣生命。 */
    private static int takeAttack(SimState s, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int remaining = amount;
        if (s.block > 0) {
            int absorbed = Math.min(s.block, remaining);
            s.block -= absorbed;
            remaining -= absorbed;
        }
        if (remaining > 0) {
            s.hp -= remaining;
            s.hpLossEvents++;
        }
        return remaining;
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
                hitMob(s, m, s.pJuggernaut, 1);
            }
        }
    }

    /** 卡牌提供的格挡：吃敏捷增量与残废减益。 */
    private static void gainCardBlock(SimState s, int amount) {
        int value = amount + s.simDexBonus;
        if (s.playerFrail > 0) {
            value = value * 3 / 4;
        }
        gainBlock(s, value);
    }

    // ══ 消耗与遗物触发 ════════════════════════════════════════════

    /** 某张牌被消耗时的自身效果（哨卫给能量）+ 能力 / 遗物联动。 */
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
        if (s.hasRelic("Charon's Ashes")) {
            for (SimState.Mob m : s.mobs) {
                if (m.alive) {
                    hitMob(s, m, 3, 1);
                }
            }
        }
    }

    private static void applyAttackRelics(SimState s) {
        if (s.attacksThisCombat % 3 == 0) {
            if (s.hasRelic("Shuriken")) {
                s.simStrengthBonus += 1;
            }
            if (s.hasRelic("Kunai")) {
                s.simDexBonus += 1;
            }
        }
        if (s.attacksThisCombat % 10 == 0 && s.hasRelic("Nunchaku")) {
            s.energy += 1;
        }
    }

    private static void applySkillRelics(SimState s) {
        if (s.skillsThisCombat % 3 == 0 && s.hasRelic("Letter Opener")) {
            for (SimState.Mob m : s.mobs) {
                if (m.alive) {
                    hitMob(s, m, 5, 1);
                }
            }
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
    private static int hitMob(SimState s, SimState.Mob m, int baseDamage, int hits) {
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
        MonsterAi.onDamaged(s, m, dealt);
        return dealt;
    }

    // ══ 回合推进 ══════════════════════════════════════════════════

    /** 捕捉实机状态后初始化怪物 AI：算出“下一回合”的招式、睡眠与永久强化状态。 */
    public static void initMonsters(SimState s) {
        for (SimState.Mob m : s.mobs) {
            if (!MonsterAi.isModeled(m.id)) {
                continue;
            }
            MonsterAi.Move current = MonsterAi.current(s, m);
            // 当前招式的仪式由第一回合结算时补上；否则按“已经吟唱过”推断
            if (current == null || current.ritual <= 0) {
                m.ritual += MonsterAi.persistentRitual(m.id, m.aiCursor);
            }
            m.enrage = Math.max(m.enrage, MonsterAi.persistentEnrage(m.id));
            if ((current != null && current.sleep) || MonsterAi.sleepingNow(m.id, m.aiCursor)) {
                m.sleeping = true;
            }
            if (m.intent == null) {
                m.intent = MonsterAi.next(s, m);
            }
        }
    }

    public static void endTurn(SimState s) {
        monsterTurn(s);
        if (s.playerDead()) {
            return;
        }
        // 虚灵 / 灼伤 / 悔恨：回合结束时在手牌里结算
        List<SimState.Card> keep = new ArrayList<>();
        int handCount = s.hand.size();
        for (SimState.Card c : s.hand) {
            if (c.endTurnSelfDamage > 0) {
                loseHp(s, c.endTurnSelfDamage);
            }
            if (c.regret) {
                loseHp(s, handCount);
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
        if (s.playerWeak > 0) s.playerWeak--;
        if (s.playerFrail > 0) s.playerFrail--;
        s.playerEntangled = 0;
        s.tempStrength = 0; // 活动肌肉的临时力量到期
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
                    hitMob(s, m, s.pCombust, 1);
                }
            }
            loseHp(s, 1);
        }
        if (s.pBrutality > 0) {
            loseHp(s, 1);
            drawCards(s, 1);
        }
        settleDeaths(s, null);
    }

    private static void applyStartOfTurnRelics(SimState s) {
        if (s.hasRelic("Mercury Hourglass")) {
            for (SimState.Mob m : s.mobs) {
                if (m.alive) {
                    hitMob(s, m, 3, 1);
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
        boolean first = s.firstEnemyTurn;
        for (SimState.Mob m : s.mobs) {
            if (!m.alive) {
                continue;
            }
            if (m.block > 0) {
                m.block = 0;
            }

            MonsterAi.Move mv = m.intent;
            MonsterAi.Move effects = null;
            int damage;
            int hits;
            if (first) {
                // 第一回合用实机捕捉到的当前意图数值，效果取自招式表中对应的当前招式
                damage = m.atkDamage;
                hits = Math.max(1, m.atkHits);
                effects = MonsterAi.currentMatching(s, m, m.atkDamage);
            } else if (mv != null) {
                damage = rollDamage(s, mv);
                hits = Math.max(1, mv.hits);
                effects = mv;
            } else {
                // 未建模的怪物：退回节奏表 / 经验平均
                if (m.pattern != null && !m.pattern.isEmpty()) {
                    int idx = Math.floorMod(m.patternIndex, m.pattern.length());
                    m.patternIndex++;
                    damage = m.pattern.charAt(idx) == 'A' ? m.atkDamage : 0;
                } else {
                    damage = m.avgDamage > 0 ? m.avgDamage : m.atkDamage;
                }
                hits = 1;
            }

            int per = Math.max(0, damage + m.strength);
            if (per > 0 && m.weak > 0) {
                per = per * 3 / 4; // 怪物虚弱：攻击伤害降低 25%
            }
            int total = per * hits;

            if (effects != null) {
                applyMoveEffects(s, m, effects);
            }

            if (total > 0) {
                int thorns = s.pThorns + (s.hasRelic("Bronze Scales") ? 3 : 0);
                if (thorns > 0) {
                    hitMob(s, m, thorns, 1);
                }
                if (s.playerVulnerable > 0) {
                    total = total * 3 / 2;
                }
                takeAttack(s, total);
            }

            // 仪式：该怪物回合结束时获得力量（邪教徒越打越疼的来源）
            if (m.ritual > 0) {
                m.strength += m.ritual;
            }

            if (!first) {
                MonsterAi.advance(m);
            }
            m.intent = MonsterAi.next(s, m);
            if (m.intent != null) {
                m.sleeping = m.intent.sleep;
            }
            if (m.vulnerable > 0) m.vulnerable--;
            if (m.weak > 0) m.weak--;
        }
        s.firstEnemyTurn = false;
        settleDeaths(s, null);
    }

    /** 招式附带的非伤害效果：自身增益、玩家减益、塞状态牌。 */
    private static void applyMoveEffects(SimState s, SimState.Mob m, MonsterAi.Move mv) {
        if (mv.block > 0) {
            m.block += mv.block;
        }
        if (mv.strGain > 0) {
            m.strength += mv.strGain;
        }
        if (mv.ritual > 0) {
            m.ritual += mv.ritual;
        }
        if (mv.playerWeak > 0) {
            s.playerWeak += mv.playerWeak;
        }
        if (mv.playerVuln > 0) {
            s.playerVulnerable += mv.playerVuln;
        }
        if (mv.playerFrail > 0) {
            s.playerFrail += mv.playerFrail;
        }
        if (mv.playerStrDown > 0) {
            s.simStrengthBonus -= mv.playerStrDown;
        }
        if (mv.playerDexDown > 0) {
            s.simDexBonus -= mv.playerDexDown;
        }
        if (mv.entangle) {
            s.playerEntangled = 1;
        }
        if (mv.addCard != null && mv.addCount > 0) {
            for (int i = 0; i < mv.addCount; i++) {
                SimState.Card status = addedCard(mv.addCard);
                if (mv.addToDraw) {
                    int at = s.drawPile.isEmpty() ? 0 : s.nextInt(s.drawPile.size() + 1);
                    s.drawPile.add(at, status);
                } else {
                    s.discardPile.add(status);
                }
            }
        }
        m.sleeping = mv.sleep;
    }

    private static int rollDamage(SimState s, MonsterAi.Move mv) {
        if (mv.damage <= 0) {
            return 0;
        }
        if (mv.damageMax > mv.damage) {
            return mv.damage + s.nextInt(mv.damageMax - mv.damage + 1);
        }
        return mv.damage;
    }

    // ══ 抽牌与洗牌 ════════════════════════════════════════════════

    /**
     * 抽牌：从牌堆顶（列表末尾）取牌，与游戏 CardGroup.getTopCard 语义一致。
     * 实机捕捉的抽牌堆顺序就是真实顺序，所以这里不再随机取下标。
     */
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
            s.hand.add(s.drawPile.remove(s.drawPile.size() - 1));
        }
    }

    /** 洗牌：把弃牌堆整堆 Fisher–Yates 后变成新的抽牌堆。 */
    private static void shuffleIntoDraw(SimState s) {
        List<SimState.Card> pile = s.discardPile;
        for (int i = pile.size() - 1; i > 0; i--) {
            int j = s.nextInt(i + 1);
            SimState.Card tmp = pile.get(i);
            pile.set(i, pile.get(j));
            pile.set(j, tmp);
        }
        s.drawPile.addAll(pile);
        pile.clear();
    }
}
