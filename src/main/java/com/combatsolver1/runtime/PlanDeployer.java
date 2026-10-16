package com.combatsolver1.runtime;

import com.combatsolver1.CombatLog;
import com.combatsolver1.engine.CardLibrary;
import com.combatsolver1.search.Plan;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.actions.utility.NewQueueCardAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把预测路线中的“一个回合”部署到实机。
 *
 * 关键点（踩过的坑，别回退）：
 *
 * 1. 引擎给出的手牌下标是“动作发生那一刻的手牌下标”——每打出一张牌，后面的下标都会前移。
 *    以前按“回合开始时的手牌下标”取牌，第二张同名牌就会取不到（日志里的 deploy skip (gone)），
 *    于是回合结束时还剩 1 点能量。现在用一份“虚拟手牌”逐步消费下标，与引擎语义一致。
 *
 * 2. 游戏里 GameActionManager.isEmpty() 只检查待执行动作，不检查“正在执行的动作”，
 *    逐张出牌会在动作交接的间隙误判为空闲，进而提前结束回合、清空出牌队列。
 *    因此一段连续动作（段与段之间没有药水/选牌）要一次性排入队列。
 *
 * 3. 结束回合前要求连续多帧完全空闲：动作列表空 + 无正在执行动作 + 出牌队列空 + 未在出牌中。
 *
 * 4. 任何一个动作无法忠实执行（牌不在、能量不够、需要选牌），立即停下本回合剩余动作并回报
 *    “计划已失效”，交给控制器重新规划——而不是默默少打一张牌。
 */
public class PlanDeployer {
    private static final int IDLE_FRAMES_REQUIRED = 3;
    /** 计划里的牌暂时不在手里（多半是本回合内刚抽到的）时，最多等几次空闲帧再判失效。 */
    private static final int MAX_DEFERRALS = 8;

    /** 一次部署的结果。 */
    public static class Result {
        public boolean completed;
        public boolean invalidated;
        public String invalidReason = "";
        /** 计划里出现了暂不支持的动作（选牌屏幕）。 */
        public boolean unsupported;
        public int planned;
        public int played;
        public int endEnergy = -1;
        public boolean endedTurn;
    }

    private Plan.Turn pending;
    private ActionJournal journal;
    private int cursor;
    private boolean endTurnAfter;
    private boolean endRequested;
    private int idleFrames;
    private int readyFrames;
    private int played;
    private final List<AbstractCard> virtualHand = new ArrayList<>();
    private Result result;
    private final Set<AbstractPotion> usedPotions = new HashSet<>();
    /** 计划里的牌暂时不在手里（多半是本回合内刚抽到的）时，允许等待的帧数。 */
    private int deferrals;

    public boolean isActive() {
        return pending != null;
    }

    public Result consumeResult() {
        Result r = result;
        result = null;
        return r;
    }

    public void cancel() {
        if (pending != null) {
            finish(false, "deploy cancelled");
        }
        pending = null;
        virtualHand.clear();
        usedPotions.clear();
        deferrals = 0;
        idleFrames = 0;
        readyFrames = 0;
    }

    public void execute(Plan.Turn turn, boolean endTurnAfter, ActionJournal actionJournal) {
        if (turn == null) {
            return;
        }
        pending = turn;
        journal = actionJournal;
        cursor = 0;
        played = 0;
        endRequested = false;
        idleFrames = 0;
        readyFrames = 0;
        virtualHand.clear();
        usedPotions.clear();
        deferrals = 0;
        this.endTurnAfter = endTurnAfter;
        CombatLog.info("deploy turn " + turn.turnNumber + ": " + turn.actions.size()
                + " action(s), endTurn=" + endTurnAfter);
    }

    public void update() {
        if (pending == null) {
            return;
        }
        try {
            // 游戏弹出了选牌界面（武装 / 双持 / 头槌 / 发掘 / 燃烧契约…）：
            // 这些选择我暂时无法自动完成，此时**必须停手**，把控制权交还玩家；
            // 否则下面的"动作已排完 + 空闲"判断会直接把回合结束掉。
            if (AbstractDungeon.isScreenUp) {
                finish(false, "游戏弹出了选牌界面：已停下本回合的自动执行，等你选完后重新规划");
                return;
            }
            if (cursor < pending.actions.size()) {
                if (!isIdle()) {
                    readyFrames = 0;
                    return;
                }
                readyFrames++;
                if (readyFrames < IDLE_FRAMES_REQUIRED) {
                    return;
                }
                readyFrames = 0;
                queueSegment();
                return;
            }

            // 计划动作都排入了，等游戏真正安静下来再收尾。
            if (!isIdle()) {
                idleFrames = 0;
                return;
            }
            idleFrames++;
            if (idleFrames < IDLE_FRAMES_REQUIRED) {
                return;
            }

            if (endTurnAfter && !endRequested) {
                endRequested = true;
                CombatLog.info("deploy: ending turn");
                if (journal != null) {
                    journal.recordEndTurn();
                }
                Result r = new Result();
                r.completed = true;
                r.planned = pending.actions.size();
                r.played = played;
                r.endedTurn = true;
                r.endEnergy = EnergyPanel.getCurrentEnergy();
                result = r;
                pending = null;
                virtualHand.clear();
                usedPotions.clear();
                AbstractDungeon.getCurrRoom().endTurn();
                return;
            }
            CombatLog.info("deploy done: played=" + played + "/" + pending.actions.size()
                    + " energy=" + EnergyPanel.getCurrentEnergy()
                    + " hand=" + AbstractDungeon.player.hand.size());
            finish(true, "");
        } catch (Throwable t) {
            CombatLog.error("deploy error", t);
            finish(false, "deploy error: " + t);
        }
    }

    /**
     * 排入一段连续动作：遇到药水就停下（药水要等前面排入的牌结算完再喝），
     * 遇到暂不支持的选牌动作直接判定计划失效。
     */
    private void queueSegment() {
        // 每一段都从实机手牌重新开始：药水/抽牌效果结算过之后，实机手牌才是“引擎此刻的手牌”。
        virtualHand.clear();
        virtualHand.addAll(AbstractDungeon.player.hand.group);
        int energy = EnergyPanel.getCurrentEnergy();
        int queuedThisSegment = 0;
        while (cursor < pending.actions.size()) {
            Plan.Action action = pending.actions.get(cursor);

            if (action.isSelect()) {
                finish(false, "计划包含选牌动作（暂不支持自动选择）");
                if (result != null) {
                    result.unsupported = true;
                }
                return;
            }
            if (action.isPotion()) {
                if (queuedThisSegment > 0) {
                    // 先让这一段排入的牌结算完，下一段再喝药水（顺序与引擎一致）。
                    return;
                }
                if (!usePotion(action)) {
                    finish(false, "药水动作无法执行：" + action.potionName());
                    return;
                }
                cursor++;
                return;
            }

            Resolved resolved = resolveCard(action);
            if (resolved.card == null) {
                if (deferrals < MAX_DEFERRALS) {
                    // 计划里可能是“本回合内刚抽到”的牌：等这一段结算完，再对着实机手牌重新解析。
                    deferrals++;
                    CombatLog.info("deploy: waiting for " + action.displayName()
                            + "（下标 " + action.handIndex + "），可能是本回合内刚抽到的牌");
                    return;
                }
                finish(false, "计划里的牌取不到：" + action.displayName()
                        + "（下标 " + action.handIndex + "）");
                return;
            }
            deferrals = 0;
            AbstractCard card = resolved.card;
            int cost = card.freeToPlay() ? 0 : card.costForTurn;
            if (cost < 0) {
                cost = Math.max(0, energy);
            }
            if (cost > energy) {
                finish(false, "能量不足：" + card.name + " 需要 " + cost + "，只剩 " + energy);
                return;
            }
            AbstractMonster target = targetFor(action, card);
            if (needsEnemy(card) && target == null) {
                finish(false, "没有可选目标：" + card.name);
                return;
            }
            if (!card.canUse(AbstractDungeon.player, target)) {
                finish(false, "当前不能打出：" + card.name);
                return;
            }

            energy -= cost;
            int engineIdx = resolved.index;
            int engineTarget = action.targetIndex >= 0 ? action.targetIndex : 0;
            if (journal != null) {
                journal.recordCard(engineIdx, engineTarget);
            }
            AbstractDungeon.actionManager.addToBottom(new NewQueueCardAction(card, target));
            played++;
            queuedThisSegment++;
            cursor++;
            CombatLog.info("deploy play: " + card.name
                    + (target != null ? " -> " + target.name : "")
                    + "（下标 " + engineIdx + "）");
        }
        CombatLog.info("deploy segment queued: " + queuedThisSegment
                + " card(s), energy left=" + energy);
    }

    private static class Resolved {
        AbstractCard card;
        int index;
    }

    /**
     * 按引擎语义取牌：下标是“这一刻的手牌下标”，取走后后面的牌前移。
     *
     * 名字只在“可信”时才用于校验：引擎（sts_lightspeed）给出的可能是截断的英文名
     * （例如 Shrug）或占位符 [MISSING_TITLE]，这时位置才是权威信息，按位置取牌。
     */
    private Resolved resolveCard(Plan.Action action) {
        Resolved r = new Resolved();
        int idx = action.handIndex;
        boolean nameTrustworthy = action.cardId != null && CardLibrary.isKnown(action.cardId);
        if (idx >= 0 && idx < virtualHand.size()) {
            AbstractCard candidate = virtualHand.get(idx);
            if (action.cardId == null || action.cardId.equals(candidate.cardID)) {
                virtualHand.remove(idx);
                r.card = candidate;
                r.index = idx;
                return r;
            }
            if (!nameTrustworthy) {
                CombatLog.info("deploy: 计划里的牌名不可信（" + action.cardId
                        + "），按位置取 hand[" + idx + "]=" + candidate.cardID);
                virtualHand.remove(idx);
                r.card = candidate;
                r.index = idx;
                return r;
            }
            CombatLog.info("deploy index mismatch: plan " + action.cardId
                    + " vs hand[" + idx + "]=" + candidate.cardID);
        }
        for (int i = 0; i < virtualHand.size(); i++) {
            AbstractCard candidate = virtualHand.get(i);
            if (action.cardId != null && action.cardId.equals(candidate.cardID)) {
                virtualHand.remove(i);
                r.card = candidate;
                r.index = i;
                return r;
            }
        }
        return r;
    }

    private boolean usePotion(Plan.Action action) {
        String potionId = action.potionId();
        AbstractPotion potion = findPotion(potionId);
        if (potion == null) {
            CombatLog.info("deploy potion not found: " + potionId);
            return false;
        }
        int slot = AbstractDungeon.player.potions.indexOf(potion);
        try {
            if (action.discardPotion) {
                AbstractDungeon.player.removePotion(potion);
                usedPotions.add(potion);
                if (journal != null) {
                    journal.recordPotion(Math.max(0, slot), ActionJournal.DISCARD_POTION_TARGET);
                }
                CombatLog.info("deploy discard potion: " + potionId);
                return true;
            }
            AbstractMonster target = potionTarget(action);
            potion.use(target);
            AbstractDungeon.player.removePotion(potion);
            usedPotions.add(potion);
            if (journal != null) {
                journal.recordPotion(Math.max(0, slot),
                        action.targetIndex >= 0 ? action.targetIndex : 0);
            }
            CombatLog.info("deploy potion: " + potionId
                    + (target != null ? " -> " + target.name : ""));
            return true;
        } catch (Throwable t) {
            CombatLog.error("potion use failed: " + potionId, t);
            return false;
        }
    }

    private AbstractMonster potionTarget(Plan.Action action) {
        if (action.targetIndex >= 0) {
            AbstractMonster m = monsterAt(action.targetIndex);
            if (m != null && !m.isDeadOrEscaped()) {
                return m;
            }
        }
        for (AbstractMonster m : AbstractDungeon.getCurrRoom().monsters.monsters) {
            if (!m.isDeadOrEscaped()) {
                return m;
            }
        }
        return null;
    }

    /** 完全空闲：待执行动作为空、没有正在执行的动作、出牌队列为空、未在出牌中。 */
    private boolean isIdle() {
        GameActionManager manager = AbstractDungeon.actionManager;
        return !AbstractDungeon.isScreenUp
                && manager.isEmpty()
                && manager.currentAction == null
                && manager.cardQueue.isEmpty()
                && !manager.usingCard;
    }

    private void finish(boolean completed, String reason) {
        Result r = new Result();
        r.completed = completed;
        r.invalidated = !completed;
        r.invalidReason = reason == null ? "" : reason;
        r.planned = pending == null ? 0 : pending.actions.size();
        r.played = played;
        r.endEnergy = EnergyPanel.getCurrentEnergy();
        result = r;
        if (!completed) {
            CombatLog.info("deploy aborted: " + r.invalidReason
                    + "（已打出 " + played + "/" + r.planned + "）");
        }
        pending = null;
        virtualHand.clear();
        usedPotions.clear();
    }

    private AbstractPotion findPotion(String potionId) {
        for (AbstractPotion p : AbstractDungeon.player.potions) {
            if (p != null && p.ID != null && p.ID.equals(potionId) && !usedPotions.contains(p)) {
                return p;
            }
        }
        return null;
    }

    /** 引擎的目标下标是“怪物列表下标”（含已死怪物），与实机 monsters 列表一致。 */
    private AbstractMonster monsterAt(int index) {
        List<AbstractMonster> monsters = AbstractDungeon.getCurrRoom().monsters.monsters;
        if (index >= 0 && index < monsters.size()) {
            return monsters.get(index);
        }
        return null;
    }

    private AbstractMonster targetFor(Plan.Action action, AbstractCard card) {
        if (!needsEnemy(card)) {
            return null;
        }
        AbstractMonster target = monsterAt(action.targetIndex);
        if (target != null && !target.isDeadOrEscaped()) {
            return target;
        }
        for (AbstractMonster m : AbstractDungeon.getCurrRoom().monsters.monsters) {
            if (!m.isDeadOrEscaped()) {
                return m;
            }
        }
        return null;
    }

    private boolean needsEnemy(AbstractCard card) {
        return card.target == AbstractCard.CardTarget.ENEMY
                || card.target == AbstractCard.CardTarget.ALL_ENEMY;
    }
}
