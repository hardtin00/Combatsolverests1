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

    // 减益种类：神器（Artifact）会优先挡掉一次
    private static final int DEBUFF_VULN = 0;
    private static final int DEBUFF_WEAK = 1;
    private static final int DEBUFF_STR = 2;

    /** 对怪物施加减益：有神器时先消耗一层神器把它挡掉。 */
    private static void applyDebuff(SimState s, SimState.Mob mob, int amount, int kind) {
        if (amount <= 0) {
            return;
        }
        if (mob.artifact > 0) {
            mob.artifact--;
            return;
        }
        if (s.pSadistic > 0) {
            hitMob(s, mob, s.pSadistic, 1); // 残忍天性：上减益就追加伤害
        }
        switch (kind) {
            case DEBUFF_VULN:
                mob.vulnerable += amount;
                break;
            case DEBUFF_WEAK:
                mob.weak += amount;
                break;
            case DEBUFF_STR:
                mob.strength = Math.max(0, mob.strength - amount);
                break;
            default:
                break;
        }
    }

    // ══ 费用与可打性 ══════════════════════════════════════════════

    /** 实际费用（腐化让技能变 0 费；血怒按本场掉血次数递减）。 */
    public static int costOf(SimState s, SimState.Card c) {
        if (c.freeThisTurn) {
            return 0; // 木乃伊之手：本回合这张牌免费
        }
        if (c.bloodForBlood) {
            return Math.max(0, c.cost - s.hpLossEvents);
        }
        if (c.xCost) {
            return -1; // X 费：费用在出牌时按全部剩余能量结算
        }
        if (s.costOverrideThisTurn > 0 && c.cost > s.costOverrideThisTurn) {
            return s.costOverrideThisTurn; // 启蒙：本回合手牌费用上限
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
        if (s.turnLocked) {
            return false; // 时间扭曲触发：本回合已不能再出牌
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
            xValue = s.energy + (s.hasRelic("Chemical X") ? 2 : 0); // 化学 X：X 费牌多算 2
            s.energy = 0;
        } else if (spend > 0) {
            s.energy -= spend;
        }

        // 玩家侧力量 = 常驻增量 + 本回合临时力量
        int strength = s.simStrengthBonus + s.tempStrength;

        if (card.attack) {
            s.attacksThisCombat++;
            s.attacksThisTurn++;
            s.cardsPlayedThisCombat++;
            if (s.hasRelic("Ink Bottle") && s.cardsPlayedThisCombat % 10 == 0) {
                drawCards(s, 1); // 墨水瓶：每 10 张牌抽 1
            }
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
            if (card.damagePerDrawPileCard > 0) {
                dmg = card.damagePerDrawPileCard * s.drawPile.size() + strength; // 心灵爆震
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
            if (s.hasRelic("Akabeko") && !s.akabekoUsed) {
                dmg += 8; // 赤牛：本场第一次攻击 +8
                s.akabekoUsed = true;
            }
            if (s.hasRelic("Wrist Blade") && card.cost == 0) {
                dmg += 4; // 腕刃：0 费攻击 +4 伤害
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
        if (card.power != null && s.hasRelic("Mummified Hand") && !s.hand.isEmpty()) {
            SimState.Card pick = s.hand.get(s.nextInt(s.hand.size()));
            if (!pick.freeThisTurn) {
                pick.freeThisTurn = true;
                s.freeCardsThisTurn.add(pick); // 木乃伊之手：打能力牌后随机一张牌本回合 0 费
            }
        }
        if (card.power != null && s.hasRelic("Bird-Faced Urn")) {
            s.hp = Math.min(s.maxHp, s.hp + 2); // 鸟面瓮：打出能力牌回 2 血
        }
        if (s.hasRelic("Velvet Choker") && s.cardsPlayedThisTurn + 1 >= 6) {
            s.turnLocked = true; // 天鹅绒项圈：每回合最多 6 张牌
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
                if (card.vulnAll) applyDebuff(s, m, card.vuln, DEBUFF_VULN);
                if (card.weakAll) applyDebuff(s, m, card.weak, DEBUFF_WEAK);
            }
        } else if (targetIndex >= 0 && targetIndex < s.mobs.size()) {
            SimState.Mob m = s.mobs.get(targetIndex);
            if (m.alive) {
                if (card.vuln > 0) applyDebuff(s, m, card.vuln, DEBUFF_VULN);
                if (card.weak > 0) applyDebuff(s, m, card.weak, DEBUFF_WEAK);
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
        if (card.healFlat > 0) {
            s.hp = Math.min(s.maxHp, s.hp + card.healFlat); // 固定回血（吸血 / 绷带）
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
            applyDebuff(s, m, card.enemyStrDown, DEBUFF_STR);
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

        if (card.playerArtifact > 0) {
            s.playerArtifact += card.playerArtifact; // 万灵药
        }
        if (card.drawIfNoAttacks > 0) {
            boolean hasAttack = false;
            for (SimState.Card c : s.hand) {
                if (c.attack) {
                    hasAttack = true;
                    break;
                }
            }
            if (!hasAttack) {
                drawCards(s, card.drawIfNoAttacks); // 急躁
            }
        }
        if (card.setCostOverride) {
            s.costOverrideThisTurn = card.costOverrideValue; // 启蒙：影响之后打出的牌
        }
        if (card.grantIntangible > 0) {
            s.playerIntangible += card.grantIntangible; // 幻影：获得无形
        }
        if (card.blockLockTurns > 0) {
            s.blockLock = Math.max(s.blockLock, card.blockLockTurns);
        }
        if (card.bombDamage > 0) {
            s.bombTimer = card.bombTurns;
            s.bombDamage = card.bombDamage;
        }
        if (card.power != null) {
            applyPower(s, card.power, card.powerAmount);
        }

        // 死亡节拍：玩家每打出一张牌就受到伤害（腐化之心）
        for (SimState.Mob mob : s.mobs) {
            if (mob.alive && mob.beatOfDeath > 0) {
                loseHp(s, mob.beatOfDeath);
            }
        }

        // 时间扭曲：每打出 N 张牌就强制结束本回合，并让持有者获得 2 点力量
        s.cardsPlayedThisTurn++;
        int warp = 0;
        for (SimState.Mob mob : s.mobs) {
            if (mob.alive && mob.timeWarp > 0) {
                warp = Math.max(warp, mob.timeWarp);
            }
        }
        if (warp > 0 && s.cardsPlayedThisTurn >= warp) {
            s.turnLocked = true;
            for (SimState.Mob mob : s.mobs) {
                if (mob.alive && mob.timeWarp > 0) {
                    mob.strength += 2;
                }
            }
        }

        // 华丽：每回合打出第 5 张牌时对全体造成伤害
        if (s.pPanache > 0 && s.cardsPlayedThisTurn % 5 == 0) {
            for (SimState.Mob mob : s.mobs) {
                if (mob.alive) {
                    hitMob(s, mob, s.pPanache, 1);
                }
            }
        }
        // 随机生成牌：加入手牌，或按牌面要求洗入抽牌堆
        if (card.randomCardKind != null && card.randomCardCount > 0) {
            for (int i = 0; i < card.randomCardCount; i++) {
                SimState.Card generated = randomCardOf(s, card.randomCardKind);
                if (card.shuffleAddedToDraw) {
                    int at = s.drawPile.isEmpty() ? 0 : s.nextInt(s.drawPile.size() + 1);
                    s.drawPile.add(at, generated);
                } else {
                    s.hand.add(generated);
                }
            }
        }

        // 出牌后的去向：消耗或进入弃牌堆（腐化会让技能改成消耗）
        boolean spooned = s.hasRelic("Strange Spoon") && s.nextInt(100) < 50; // 奇异勺子：50% 不消耗
        if ((card.exhaustSelf && !spooned) || (s.pCorruption && !card.attack && card.power == null)) {
            s.exhaustPile.add(card);
            onExhaustCard(s, card);
        } else {
            s.discardPile.add(card);
        }

        // 不停之顶：回合中手牌为空时抽一张
        if (s.hasRelic("Unceasing Top") && s.hand.isEmpty() && !s.drawPile.isEmpty()) {
            drawCards(s, 1);
        }

        settleDeaths(s, card);
    }

    /** 结算死亡：标记死亡、触发死亡效果（孢子云）与喂食加血量。 */
    private static void settleDeaths(SimState s, SimState.Card source) {
        for (SimState.Mob m : s.mobs) {
            if (m.alive && m.hp <= 0) {
                if ("AwakenedOne".equals(m.id) && m.aiState == 0) {
                    // 觉醒者：第一阶段被打空后「重生」，满血进入第二阶段并换招式表
                    m.aiState = 1;
                    m.hp = m.maxHp;
                    m.block = 0;
                    m.intent = null;
                    m.aiCounter = m.aiCursor;
                    initMonsters(s);
                    continue;
                }
                if ("Darkling".equals(m.id) && hasLivingCompanion(s, m)) {
                    // 暗影：血量归零不会死，两回合后以半血复活
                    m.alive = false;
                    m.hp = 0;
                    m.reviveIn = 2;
                    continue;
                }
                m.alive = false;
                m.hp = 0;
                MonsterAi.onDeath(s, m);
                if (s.hasRelic("Gremlin Horn")) {
                    s.energy += 1; // 地精之角：敌人死亡 +1 能量并抽 1
                    drawCards(s, 1);
                }
                if (source != null && source.gainMaxHpOnKill) {
                    s.maxHp += 3;
                    s.hp += 3;
                }
            }
        }
    }

    /** 玩家神器：挡掉一次减益后返回 true。 */
    private static boolean consumePlayerArtifact(SimState s) {
        if (s.playerArtifact > 0) {
            s.playerArtifact--;
            return true;
        }
        return false;
    }

    private static boolean hasLivingCompanion(SimState s, SimState.Mob self) {
        for (SimState.Mob other : s.mobs) {
            if (other != self && other.alive && other.hp > 0) {
                return true;
            }
        }
        return false;
    }

    /** 复活的倒计时（暗影半血站起来）。 */
    private static void tickRevives(SimState s) {
        for (SimState.Mob m : s.mobs) {
            if (m.reviveIn <= 0) {
                continue;
            }
            m.reviveIn--;
            if (m.reviveIn == 0 && hasLivingCompanion(s, m)) {
                m.alive = true;
                m.hp = Math.max(1, m.maxHp / 2);
                m.block = 0;
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
        if ("Omega".equals(id)) {
            return CardLibrary.toSimCard("Omega", "欧米伽", 3, 0, 0, 1, false, false, false, false);
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
        else if (power.equals("panache")) s.pPanache += amount;
        else if (power.equals("sadistic")) s.pSadistic += amount;
        else if (power.equals("magnetism")) s.pMagnetism += amount;
        else if (power.equals("mayhem")) s.pMayhem += amount;
        else if (power.equals("omega")) s.pOmega += amount;
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
        if (spec[10] > 0) {
            s.simDexBonus += spec[10];      // 敏捷药水 / 迅捷药水
        }
        if (spec[11] > 0) {
            s.pMetallicize += spec[11];     // 铁心药水
        }
        if (spec[12] > 0) {
            s.playerIntangible += spec[12]; // 罐中幽灵
        }
        if (spec[13] > 0) {
            s.playerArtifact += spec[13];   // 古代药水
        }
        if (spec[14] > 0) {
            s.maxHp += spec[14];            // 果汁：最大生命
            s.hp += spec[14];
        }
        if (spec[15] > 0) {
            s.tempStrength += spec[15];     // 屈伸药水：本回合力量
        }
        if (spec[16] > 0) {
            s.pDoubleTap += spec[16];       // 复制药水：下一张牌打两次
        }
        if (s.hasRelic("Toy Ornithopter")) {
            s.hp = Math.min(s.maxHp, s.hp + 5); // 玩具管风琴：喝药水回 5 血
        }
        settleDeaths(s, null);
    }

    // ══ 格挡与掉血 ════════════════════════════════════════════════

    /** 直接失去生命（自我伤害 / 反伤），不吃格挡，计入掉血次数。 */
    public static void loseHp(SimState s, int amount) {
        if (amount <= 0) {
            return;
        }
        if (s.hasRelic("Tungsten Rod")) {
            amount = Math.max(0, amount - 1); // 钨钢杖：每次掉血少 1
        }
        if (amount <= 0) {
            return;
        }
        if (s.hasRelic("Fossilized Helix") && !s.fossilUsed) {
            s.fossilUsed = true; // 石纹护符：本场第一次掉血被完全挡掉
            return;
        }
        s.hp -= amount;
        s.hpLossEvents++;
        if (s.hasRelic("Centennial Puzzle") && s.hpLossEvents == 1) {
            drawCards(s, 3); // 百年拼图：本场第一次掉血抽 3
        }
        if (s.hasRelic("Runic Cube")) {
            drawCards(s, 1); // 符文方块：掉血就抽 1
        }
    }

    /** 受到攻击：先吃格挡，再扣生命。 */
    private static int takeAttack(SimState s, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int remaining = s.playerIntangible > 0 ? 1 : amount; // 玩家无形：受到伤害降为 1
        if (s.hasRelic("Torii") && remaining <= 5) {
            remaining = 1; // 鸟居：5 点及以下的未被格挡攻击伤害降为 1
        }
        if (s.block > 0) {
            int absorbed = Math.min(s.block, remaining);
            s.block -= absorbed;
            remaining -= absorbed;
        }
        if (remaining > 0) {
            if (s.hasRelic("Fossilized Helix") && !s.fossilUsed) {
                s.fossilUsed = true;
                remaining = 0; // 石纹护符：本场第一次掉血被挡掉
            } else {
                s.hp -= remaining;
                s.hpLossEvents++;
                if (s.playerPlatedArmor > 0) {
                    s.playerPlatedArmor--; // 针与线：受伤掉一层
                }
            }
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
        if (s.blockLock > 0) {
            return; // 紧急按钮：本回合不能用卡牌获得格挡
        }
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
        if (s.hasRelic("Dead Branch")) {
            // 死亡树枝：每消耗一张牌就往手里塞一张随机牌（牌池按攻击 / 防御近似）
            s.hand.add(randomCardOf(s, s.nextInt(2) == 0 ? "attack" : "skill"));
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
        if (s.attacksThisCombat % 3 == 0 && s.hasRelic("Ornamental Fan")) {
            gainBlock(s, 4); // 装饰扇：每 3 次攻击 4 点格挡
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
        boolean hadBlock = m.block > 0;
        int dmg = baseDamage;
        if (m.vulnerable > 0) {
            // 纸蛙：易伤加成从 50% 提升到 75%
            dmg = s.hasRelic("Paper Phrog") ? dmg * 7 / 4 : dmg * 3 / 2;
        }
        if (s.hasRelic("The Boot") && dmg > 0 && dmg < 5) {
            dmg = 5; // 靴子：未被格挡的攻击伤害至少 5
        }
        if (MonsterAi.intangibleNow(m)) {
            dmg = 1; // 无形：受到的伤害降为 1
        }
        for (int i = 0; i < Math.max(1, hits); i++) {
            int remaining = dmg;
            if (m.invincible > 0) {
                int left = m.invincible - m.invincibleTaken;
                if (left <= 0) {
                    break; // 无敌额度用尽，本回合不再掉血
                }
                if (remaining > left) {
                    remaining = left;
                }
            }
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
        if (dealt > 0 && hadBlock && m.block == 0 && s.hasRelic("Hand Drill")) {
            applyDebuff(s, m, 2, DEBUFF_VULN); // 手钻：击破格挡时施加 2 层易伤
        }
        if (dealt > 0) {
            m.invincibleTaken += dealt;
            if (m.malleable > 0) {
                // 可塑：受伤后获得格挡，且每次触发的量 +1，玩家回合开始重置
                if (m.malleableBlock <= 0) {
                    m.malleableBlock = m.malleable;
                }
                m.block += m.malleableBlock;
                m.malleableBlock++;
            }
            if (m.platedArmor > 0) {
                m.platedArmor--; // 板甲：每次受到攻击伤害掉一层
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
            if (m.intent != null) {
                continue; // 已经初始化过（也用于刚被召唤出来的怪只初始化一次）
            }
            MonsterAi.Move current = MonsterAi.current(s, m);
            // 当前招式的仪式由第一回合结算时补上；否则按“已经吟唱过”推断
            if (current == null || current.ritual <= 0) {
                m.ritual += MonsterAi.persistentRitual(m.id, m.aiCursor);
            }
            m.enrage = Math.max(m.enrage, MonsterAi.persistentEnrage(m.id));
            m.flight = MonsterAi.initialFlight(m.id);
            MonsterAi.applyInnatePowers(s, m);
            if ((current != null && current.sleep) || MonsterAi.sleepingNow(m.id, m.aiCursor)) {
                m.sleeping = true;
            }
            if (m.intent == null) {
                m.intent = MonsterAi.next(s, m);
            }
        }
    }

    public static void endTurn(SimState s) {
        // 缠绕：玩家回合结束先吃一次伤害（尖塔生长）
        if (s.constricted > 0) {
            loseHp(s, s.constricted);
        }
        // 战争艺术 / 怀表要在本回合数据被清零前先记下来
        for (SimState.Card free : s.freeCardsThisTurn) {
            free.freeThisTurn = false; // 木乃伊之手的免费只持续一回合
        }
        s.freeCardsThisTurn.clear();
        boolean noAttackThisTurn = s.attacksThisTurn == 0;
        int cardsThisTurn = s.cardsPlayedThisTurn;
        s.cardsPlayedThisTurn = 0;
        s.attacksThisTurn = 0;
        s.turnLocked = false;
        s.costOverrideThisTurn = 0;
        for (SimState.Mob mob : s.mobs) {
            mob.malleableBlock = 0; // 可塑的格挡量在玩家回合开始重置
        }
        tickRevives(s);
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
        if (s.hasRelic("Cloak Clasp")) {
            s.block += s.hand.size(); // 斗篷扣：回合结束每张手牌 1 格挡
        }
        if (s.hasRelic("Runic Pyramid")) {
            s.hand.addAll(keep); // 符文金字塔：手牌不再弃掉
        } else {
            s.hand.clear();
            s.discardPile.addAll(keep);
        }
        if (!s.pBarricade) {
            // 卡尺：只失去 15 点格挡而不是全部
            s.block = s.hasRelic("Calipers") ? Math.max(0, s.block - 15) : 0;
        }
        if (s.playerVulnerable > 0) s.playerVulnerable--;
        if (s.playerIntangible > 0) s.playerIntangible--;
        if (s.blockLock > 0) s.blockLock--;
        if (s.pOmega > 0) {
            for (SimState.Mob mob : s.mobs) {
                if (mob.alive) {
                    hitMob(s, mob, s.pOmega, 1);
                }
            }
        }
        if (s.bombTimer > 0) {
            s.bombTimer--;
            if (s.bombTimer == 0 && s.bombDamage > 0) {
                for (SimState.Mob mob : s.mobs) {
                    if (mob.alive) {
                        hitMob(s, mob, s.bombDamage, 1);
                    }
                }
            }
        }
        if (s.playerWeak > 0) s.playerWeak--;
        if (s.playerFrail > 0) s.playerFrail--;
        s.playerEntangled = 0;
        s.tempStrength = 0; // 活动肌肉的临时力量到期
        drawCards(s, s.handSize + (s.hasRelic("Snecko Eye") ? 2 : 0)); // 蛇眼：每回合多抽 2
        if (s.hasRelic("Pocket Watch") && cardsThisTurn <= 3) {
            drawCards(s, 3); // 怀表：本回合打出 3 张及以下，下回合多抽 3
        }
        if (s.hasRelic("Ice Cream")) {
            s.energy += s.energyPerTurn + s.pBerserk; // 冰淇淋：未用完的能量保留
        } else {
            s.energy = s.energyPerTurn + s.pBerserk;
        }
        if (s.hasRelic("Velvet Choker")) {
            s.energy += 1; // 天鹅绒项圈：每回合 +1 能量
        }
        if (s.hasRelic("Happy Flower") && s.turn % 3 == 0) {
            s.energy += 1; // 快乐花：每 3 回合 +1 能量
        }
        if (s.hasRelic("Brimstone")) {
            s.simStrengthBonus += 2; // 硫磺：自己 +2 力量
            for (SimState.Mob mob : s.mobs) {
                if (mob.alive) {
                    mob.strength += 1; // 代价：所有敌人 +1 力量
                }
            }
        }
        if (s.hasRelic("Art of War") && noAttackThisTurn) {
            s.energy += 1; // 战争艺术：本回合没打过攻击牌，下回合 +1 能量
        }
        // 能量类遗物（代价都在局外，对战斗只有正面效果）
        String[] energyRelics = {"Coffee Dripper", "Cursed Key", "Ectoplasm", "Fusion Hammer",
                "Philosopher\u0027s Stone", "Mark of Pain", "Busted Crown", "Runic Dome"};
        for (String relic : energyRelics) {
            if (s.hasRelic(relic)) {
                s.energy += 1;
            }
        }
        if (s.playerPlatedArmor > 0) {
            s.block += s.playerPlatedArmor; // 针与线：玩家板甲每回合给格挡
        }
        if (s.hasRelic("Meat on the Bone") && s.won() && s.hp <= s.maxHp / 2) {
            s.hp = Math.min(s.maxHp, s.hp + 12); // 骨上肉：战斗结束时残血回 12
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
        if (s.pMagnetism > 0) {
            s.hand.add(randomCardOf(s, "colorless")); // 磁力：每回合白拿一张无色牌
        }
        if (s.pMayhem > 0 && !s.drawPile.isEmpty()) {
            SimState.Card top = s.drawPile.remove(s.drawPile.size() - 1);
            playCardInternal(s, top, -1, true); // 混沌：每回合免费打出牌堆顶
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
        if (s.hasRelic("Incense Burner") && s.turn % 6 == 0) {
            s.playerIntangible += 1; // 香炉：每 6 回合 1 层无形
        }
    }

    private static void monsterTurn(SimState s) {
        boolean first = s.firstEnemyTurn;
        // 用下标遍历并固定初始数量：本回合召唤出来的小怪不会立刻行动
        int mobCountAtTurnStart = s.mobs.size();
        for (int mi = 0; mi < mobCountAtTurnStart; mi++) {
            SimState.Mob m = s.mobs.get(mi);
            if (!m.alive) {
                continue;
            }
            if (m.block > 0) {
                m.block = 0;
            }
            if (m.platedArmor > 0) {
                m.block += m.platedArmor; // 板甲：怪物回合开始获得格挡
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

            // 手写怪物的高进阶数值覆盖（普通怪 A2、精英 A3、首领 A4）
            if (mv != null) {
                int[] tier = MonsterAi.tierOverride(m.id, s.ascension, mv.id);
                if (tier != null) {
                    damage = tier[0];
                    hits = Math.max(1, tier[1]);
                }
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
            m.invincibleTaken = 0; // 无敌额度按回合重置
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
        if (mv.partyStr > 0) {
            for (SimState.Mob ally : s.mobs) {
                if (ally.alive) {
                    ally.strength += mv.partyStr;
                }
            }
        }
        if (mv.selfThorns > 0) {
            m.thorns += mv.selfThorns;
        }
        if (mv.constrict > 0) {
            s.constricted = Math.max(s.constricted, mv.constrict); // 缠绕：玩家回合结束受伤
        }
        if (mv.summonId != null && mv.summonCount > 0) {
            int existing = 0;
            for (SimState.Mob ally : s.mobs) {
                if (ally.alive && mv.summonId.equals(ally.id)) {
                    existing++;
                }
            }
            int room = Math.max(0, MonsterAi.summonCap(mv.summonId) - existing);
            int count = Math.min(mv.summonCount, room);
            for (int i = 0; i < count; i++) {
                s.mobs.add(MonsterAi.summon(mv.summonId));
            }
            if (count > 0) {
                initMonsters(s); // 给新召唤物算好下一招
            }
        }
        if (mv.escape) {
            m.alive = false; // 逃跑：脱离战斗，不再出手
            m.hp = Math.max(0, m.hp);
        }
        if (mv.allyHeal > 0) {
            SimState.Mob target = mostInjured(s, m);
            if (target != null) {
                target.hp = Math.min(target.maxHp, target.hp + mv.allyHeal);
            }
        }
        if (mv.allyBlock > 0) {
            SimState.Mob target = mostInjured(s, m);
            if (target != null) {
                target.block += mv.allyBlock;
            }
        }
        m.sleeping = mv.sleep;
    }

    /** 场上伤得最重的一员（优先除自己以外的队友，单独一人时返回自己）。 */
    private static SimState.Mob mostInjured(SimState s, SimState.Mob self) {
        SimState.Mob best = null;
        int bestMissing = 0;
        for (SimState.Mob ally : s.mobs) {
            if (!ally.alive || ally == self) {
                continue;
            }
            int missing = Math.max(0, ally.maxHp - ally.hp);
            if (missing > bestMissing) {
                bestMissing = missing;
                best = ally;
            }
        }
        if (best == null) {
            best = self != null && self.alive ? self : firstAlive(s);
        }
        return best;
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
        s.shuffles++;
        if (s.shuffles % 3 == 0 && s.hasRelic("Sundial")) {
            s.energy += 2; // 日晷：每 3 次洗牌 +2 能量
        }
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
    /** 生成一张随机牌：attack = 铁甲常见攻击牌池，skill = 常用格挡技能，colorless = 无色牌池。 */
    private static SimState.Card randomCardOf(SimState s, String kind) {
        if ("attack".equals(kind)) {
            return randomAttackCard(s);
        }
        if ("skill".equals(kind)) {
            return CardLibrary.toSimCard("Defend_R", "防御", 1, 0, 5, 1, false, false, false, false);
        }
        return s.nextInt(2) == 0
                ? CardLibrary.toSimCard("Swift Strike", "迅捷打击", 0, 7, 0, 1, true, false, true, false)
                : CardLibrary.toSimCard("Good Instincts", "良好直觉", 0, 0, 6, 1, false, false, false, false);
    }
}
