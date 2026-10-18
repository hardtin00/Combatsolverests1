package com.combatsolver1.runtime;

import com.combatsolver1.CombatLog;
import com.combatsolver1.engine.CardLibrary;
import com.combatsolver1.search.Plan;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.actions.unique.ExhumeAction;
import com.megacrit.cardcrawl.actions.utility.NewQueueCardAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
 * 4. 任何一个动作无法忠实执行（牌不在、能量不够、目标不可信或不支持的选择），立即停下
 *    本回合剩余动作；唯一支持的 Exhume 选择会经过独立身份校验和原生界面状态机。
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
    private ExecutionSafety.TargetSlots<AbstractMonster> targetSlots;
    private int cursor;
    private boolean endTurnAfter;
    private boolean endRequested;
    private int idleFrames;
    private int readyFrames;
    private int played;
    private final List<AbstractCard> virtualHand = new ArrayList<>();
    private Result result;
    private final Set<AbstractPotion> usedPotions = new HashSet<>();
    private final Set<NewQueueCardAction> queuedActions = Collections.newSetFromMap(new IdentityHashMap<NewQueueCardAction, Boolean>());
    private final Set<AbstractCard> queuedCards = Collections.newSetFromMap(new IdentityHashMap<AbstractCard, Boolean>());
    private final List<ExpectedUse> expectedUses = new ArrayList<>();
    private String planSource;
    private Plan.Action pendingChoice;
    private ExhumeChoiceSafety.Gate choiceGate;
    private AbstractCard choiceTargetCard;
    private AbstractCard playedExhumeCard;
    private AbstractCard plannedExhumeCard;
    private List<AbstractCard> choiceRootHand = Collections.emptyList();
    private List<AbstractCard> choiceRootExhaust = Collections.emptyList();
    private int choiceEnergyAfterUse = -1;
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
        targetSlots = null;
        virtualHand.clear();
        usedPotions.clear();
        clearChoiceState();
        deferrals = 0;
        idleFrames = 0;
        readyFrames = 0;
    }

    /** Called at the native useCard boundary to distinguish a planned use from player intervention. */
    public boolean acceptCardUsed(AbstractCard card, CardTargetRef target) {
        ExpectedUse expected = null;
        for (ExpectedUse use : expectedUses) {
            if (use.card == card) { expected = use; break; }
        }
        if (expected == null || target == null || !target.trusted
                || !expected.targetSignature.equals(targetSignature(target))) {
            String actualCard = card == null ? "<null>" : card.cardID;
            String actualTarget = target == null ? "<null>" : targetSignature(target)
                    + " trusted=" + target.trusted;
            finish(false, "执行期间检测到未计划的出牌或目标变化，card="
                    + actualCard + " target=" + actualTarget + "，已取消剩余路线");
            return false;
        }
        expectedUses.remove(expected);
        if (pendingChoice != null && card == plannedExhumeCard && !captureExhumeChoiceRoot(card)) {
            finish(false, "Exhume 出牌前后的消耗堆与计划候选不一致");
            return false;
        }
        CombatLog.info("deploy useCard confirmed: card=" + card.cardID
                + " target=" + targetSignature(target));
        return true;
    }

    public void execute(Plan.Turn turn, boolean endTurnAfter, ActionJournal actionJournal,
                        ExecutionSafety.TargetSlots<AbstractMonster> targets, String source) {
        if (turn == null) {
            return;
        }
        pending = turn;
        targetSlots = targets;
        journal = actionJournal;
        cursor = 0;
        played = 0;
        endRequested = false;
        idleFrames = 0;
        readyFrames = 0;
        virtualHand.clear();
        usedPotions.clear();
        clearChoiceState();
        deferrals = 0;
        planSource = source;
        this.endTurnAfter = endTurnAfter;
        if (targets == null) {
            finish(false, "计划目标索引来源或根状态无法确认");
            return;
        }
        CombatLog.info("deploy turn " + turn.turnNumber + ": " + turn.actions.size()
                + " action(s), endTurn=" + endTurnAfter);
    }

    public void update() {
        if (pending == null) {
            return;
        }
        try {
            if (pendingChoice != null) {
                updateExhumeChoice();
                return;
            }
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

            if (!expectedUses.isEmpty()) {
                finish(false, "排队的牌未在原生 useCard 边界确认，已停止后续动作");
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
                targetSlots = null;
                virtualHand.clear();
                usedPotions.clear();
                clearQueuedTracking();
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
                if (!ExhumeChoiceSafety.supports(action, planSource)) {
                    if (journal != null) journal.markDirty("拒绝执行缺少 Exhume 候选身份的选牌路线");
                    finish(false, "无法安全执行该选牌动作：仅支持带候选身份的 Exhume 消耗堆选择");
                    if (result != null) result.unsupported = true;
                    return;
                }
                if (plannedExhumeCard == null) {
                    finish(false, "Exhume 选择动作前没有对应的原生 Exhume 出牌");
                    return;
                }
                pendingChoice = action;
                choiceGate = new ExhumeChoiceSafety.Gate(System.currentTimeMillis(),
                        action.selectCandidates.size() == 1);
                choiceTargetCard = null;
                playedExhumeCard = null;
                choiceEnergyAfterUse = -1;
                CombatLog.info("deploy Exhume choice waiting: candidates=" + action.selectCandidates.size()
                        + " selected=" + action.selectCards.get(0).id);
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
            if ("Exhume".equals(card.cardID)) {
                if (!ExhumeChoiceSafety.canPlayExhume(pending, cursor, planSource)) {
                    // Preserve already queued earlier actions, then reject Exhume before spending energy.
                    if (queuedThisSegment > 0) return;
                    finish(false, "Exhume 路线缺少可验证的原生消耗堆选择，已在出牌前停止");
                    if (result != null) result.unsupported = true;
                    return;
                }
            }
            int cost = card.freeToPlay() ? 0 : card.costForTurn;
            if ("Exhume".equals(card.cardID) && (card.costForTurn == -9
                    || (AbstractDungeon.player.hasPower("Corruption")
                    && card.type == AbstractCard.CardType.SKILL))) {
                cost = 0;
            } else if (cost < 0) {
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
            CardTargetRef targetRef = CardTargetRef.capture(card, target);
            if (!targetRef.trusted) {
                if (journal != null) journal.markDirty("planned card target is untrusted: " + targetRef.error);
                finish(false, "无法确认出牌目标：" + card.name + "（" + targetRef.error + "）");
                return;
            }
            if (!card.canUse(AbstractDungeon.player, target)) {
                finish(false, "当前不能打出：" + card.name);
                return;
            }

            energy -= cost;
            int engineIdx = resolved.index;
            if (journal != null) {
                journal.recordCard(engineIdx, card.cardID, targetRef);
            }
            NewQueueCardAction queued = new NewQueueCardAction(card, target);
            queuedActions.add(queued);
            queuedCards.add(card);
            expectedUses.add(new ExpectedUse(card, targetSignature(targetRef)));
            if ("Exhume".equals(card.cardID)) plannedExhumeCard = card;
            AbstractDungeon.actionManager.addToBottom(queued);
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
            AbstractMonster target = potionTarget(action, potion);
            if (potion.targetRequired && target == null) {
                CombatLog.info("deploy potion target is missing or stale: " + potionId);
                return false;
            }
            int nativeTargetSlot = target == null ? 0
                    : ExecutionSafety.TargetSlots.fixedIndex(nativeMonsters(), target);
            if (target != null && nativeTargetSlot < 0) return false;
            potion.use(target);
            AbstractDungeon.player.removePotion(potion);
            usedPotions.add(potion);
            if (journal != null) {
                journal.recordPotion(Math.max(0, slot), nativeTargetSlot);
            }
            CombatLog.info("deploy potion: " + potionId
                    + (target != null ? " -> " + target.name : ""));
            return true;
        } catch (Throwable t) {
            CombatLog.error("potion use failed: " + potionId, t);
            return false;
        }
    }

    /** Called by the narrow GridCardSelectScreen prefix; it synthesizes a dynamic hitbox click only. */
    public void beforeGridSelectUpdate(GridCardSelectScreen grid) {
        if (pendingChoice == null || choiceGate == null || grid == null) return;
        boolean context = isExpectedExhumeGrid(grid);
        boolean poolMatches = context && matchesNativePool(cardsIn(grid.targetGroup));
        boolean externalInput = InputHelper.isMouseDown || InputHelper.justClickedLeft
                || InputHelper.justReleasedClickLeft;
        ExhumeChoiceSafety.Pulse pulse = choiceGate.beforeGridUpdate(context, poolMatches,
                externalInput, Gdx.input.isKeyJustPressed(Input.Keys.J), System.currentTimeMillis());
        if (choiceGate.phase() == ExhumeChoiceSafety.Phase.FAILED
                || choiceGate.phase() == ExhumeChoiceSafety.Phase.CANCELLED) return;
        if (pulse == ExhumeChoiceSafety.Pulse.NONE) return;
        if (choiceTargetCard == null || choiceTargetCard.hb == null
                || !containsIdentity(cardsIn(grid.targetGroup), choiceTargetCard)) {
            // Gate has not emitted a click yet in this case; abort rather than touching another card.
            finish(false, "Exhume 目标实例已不在原生选择界面");
            return;
        }
        // InputHelper uses logical game coordinates. The point comes from the live card hitbox,
        // so resolution changes and scrolling do not rely on fixed screen coordinates.
        InputHelper.mX = Math.round(choiceTargetCard.hb.x + choiceTargetCard.hb.width / 2.0f);
        InputHelper.mY = Math.round(choiceTargetCard.hb.y + choiceTargetCard.hb.height / 2.0f);
        if (InputHelper.mX < 0 || InputHelper.mX > Settings.WIDTH
                || InputHelper.mY < 0 || InputHelper.mY > Settings.HEIGHT) {
            finish(false, "Exhume 目标卡牌当前不在可见屏幕范围，已拒绝自动点击");
            return;
        }
        if (!isUniqueCandidateHitboxAt(grid.targetGroup.group, choiceTargetCard, InputHelper.mX, InputHelper.mY)) {
            finish(false, "Exhume 目标位置无法唯一命中预期卡牌，已拒绝自动点击");
            return;
        }
        if (pulse == ExhumeChoiceSafety.Pulse.PRESS) {
            InputHelper.justClickedLeft = true;
            CombatLog.info("Exhume native input: card hitbox press");
        } else {
            InputHelper.justReleasedClickLeft = true;
            CombatLog.info("Exhume native input: card hitbox release");
        }
    }

    private void updateExhumeChoice() {
        if (choiceGate == null || pendingChoice == null) {
            finish(false, "Exhume 选择状态丢失，已停止自动路线");
            return;
        }
        boolean screenUp = AbstractDungeon.isScreenUp;
        GridCardSelectScreen grid = AbstractDungeon.gridSelectScreen;
        boolean expectedGrid = screenUp && grid != null && isExpectedExhumeGrid(grid);
        List<AbstractCard> gridCards = expectedGrid ? cardsIn(grid.targetGroup) : Collections.<AbstractCard>emptyList();
        boolean poolMatches = expectedGrid && matchesNativePool(gridCards)
                && containsIdentity(gridCards, choiceTargetCard);
        boolean selectedUnexpected = expectedGrid && hasUnexpectedGridSelection(grid);
        choiceGate.observeScreen(screenUp, expectedGrid, poolMatches, selectedUnexpected,
                System.currentTimeMillis());

        boolean stable = !screenUp && isIdle();
        boolean resultMatches = selectionResultMatches();
        boolean gameAlive = AbstractDungeon.player != null && !AbstractDungeon.player.isDead
                && currentCombatRoom();
        choiceGate.observeResult(screenUp, gameAlive, stable, resultMatches, System.currentTimeMillis());

        if (choiceGate.phase() == ExhumeChoiceSafety.Phase.COMPLETE) {
            if (journal != null) journal.recordSelection(pendingChoice);
            CombatLog.info("Exhume native choice verified: card=" + choiceTargetCard.cardID
                    + " upgrades=" + choiceTargetCard.timesUpgraded
                    + " energy=" + EnergyPanel.getCurrentEnergy());
            cursor++;
            pendingChoice = null;
            choiceGate = null;
            choiceTargetCard = null;
            playedExhumeCard = null;
            plannedExhumeCard = null;
            choiceRootHand = Collections.emptyList();
            choiceRootExhaust = Collections.emptyList();
            choiceEnergyAfterUse = -1;
            readyFrames = 0;
            idleFrames = 0;
            return;
        }
        if (choiceGate.phase() == ExhumeChoiceSafety.Phase.FAILED
                || choiceGate.phase() == ExhumeChoiceSafety.Phase.CANCELLED) {
            finish(false, choiceGate.failure() == null ? "Exhume 选择已取消" : choiceGate.failure());
        }
    }

    private boolean captureExhumeChoiceRoot(AbstractCard card) {
        if (pendingChoice == null || card == null || !"Exhume".equals(card.cardID)) return false;
        List<AbstractCard> exhaust = AbstractDungeon.player.exhaustPile.group;
        List<AbstractCard> candidates = legalExhumeCandidates(exhaust);
        List<Plan.ChoiceCard> descriptions = describe(candidates);
        ExhumeChoiceSafety.Match match = ExhumeChoiceSafety.match(pendingChoice, descriptions);
        if (!match.matched()) {
            CombatLog.info("Exhume candidate validation failed: " + match.failure);
            return false;
        }
        if (match.liveIndex < 0 || match.liveIndex >= candidates.size()) return false;
        choiceTargetCard = candidates.get(match.liveIndex);
        playedExhumeCard = card;
        choiceRootHand = new ArrayList<AbstractCard>(AbstractDungeon.player.hand.group);
        choiceRootExhaust = new ArrayList<AbstractCard>(exhaust);
        int currentEnergy = EnergyPanel.getCurrentEnergy();
        boolean corruptionSkill = AbstractDungeon.player.hasPower("Corruption")
                && card.type == AbstractCard.CardType.SKILL;
        int nativeCost = ExhumeChoiceSafety.nativeEnergyCost(card.costForTurn,
                card.freeToPlay(), corruptionSkill, currentEnergy);
        choiceEnergyAfterUse = currentEnergy - nativeCost;
        choiceGate.allowNativeAutoResolution(candidates.size() == 1 && exhaust.size() == 1);
        CombatLog.info("Exhume pre-choice identity matched: pool=" + candidates.size()
                + " sourceOrderMayDiffer=true");
        return true;
    }

    private boolean isExpectedExhumeGrid(GridCardSelectScreen grid) {
        return grid != null && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.GRID
                && AbstractDungeon.actionManager != null
                && AbstractDungeon.actionManager.currentAction instanceof ExhumeAction
                && AbstractDungeon.player != null && grid.targetGroup == AbstractDungeon.player.exhaustPile;
    }

    private boolean matchesNativePool(List<AbstractCard> nativeCandidates) {
        if (pendingChoice == null || choiceTargetCard == null || nativeCandidates == null) return false;
        ExhumeChoiceSafety.Match match = ExhumeChoiceSafety.match(pendingChoice, describe(nativeCandidates));
        return match.matched() && containsIdentity(nativeCandidates, choiceTargetCard);
    }

    private boolean hasUnexpectedGridSelection(GridCardSelectScreen grid) {
        if (grid.selectedCards == null || grid.selectedCards.isEmpty()) return false;
        return grid.selectedCards.size() != 1 || grid.selectedCards.get(0) != choiceTargetCard;
    }

    private boolean selectionResultMatches() {
        if (choiceTargetCard == null || playedExhumeCard == null || choiceEnergyAfterUse < 0
                || AbstractDungeon.player == null) return false;
        List<AbstractCard> hand = AbstractDungeon.player.hand.group;
        List<AbstractCard> exhaust = AbstractDungeon.player.exhaustPile.group;
        if (countIdentity(hand, choiceTargetCard) != 1
                || countIdentity(exhaust, choiceTargetCard) != 0
                || countIdentity(exhaust, playedExhumeCard) != 1
                || hand.size() < choiceRootHand.size()
                || exhaust.size() != choiceRootExhaust.size()
                || EnergyPanel.getCurrentEnergy() != choiceEnergyAfterUse) return false;
        for (AbstractCard rootCard : choiceRootHand) {
            if (rootCard != playedExhumeCard && countIdentity(hand, rootCard) != 1) return false;
        }
        for (AbstractCard rootCard : choiceRootExhaust) {
            int expected = rootCard == choiceTargetCard ? 0 : 1;
            if (countIdentity(exhaust, rootCard) != expected) return false;
        }
        return true;
    }

    private boolean currentCombatRoom() {
        try {
            return AbstractDungeon.getCurrRoom() != null
                    && AbstractDungeon.getCurrRoom().phase == com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT;
        } catch (Throwable ignored) { return false; }
    }

    private static List<AbstractCard> cardsIn(com.megacrit.cardcrawl.cards.CardGroup group) {
        return group == null ? Collections.<AbstractCard>emptyList() : group.group;
    }

    private static List<AbstractCard> legalExhumeCandidates(List<AbstractCard> cards) {
        List<AbstractCard> result = new ArrayList<AbstractCard>();
        for (AbstractCard card : cards) if (card != null && !"Exhume".equals(card.cardID)) result.add(card);
        return result;
    }

    private static List<Plan.ChoiceCard> describe(List<AbstractCard> cards) {
        List<Plan.ChoiceCard> result = new ArrayList<Plan.ChoiceCard>();
        for (AbstractCard card : cards) {
            result.add(ExhumeChoiceSafety.descriptor(card.cardID, card.timesUpgraded, card.costForTurn));
        }
        return result;
    }

    private static boolean containsIdentity(List<AbstractCard> cards, AbstractCard wanted) {
        return countIdentity(cards, wanted) > 0;
    }

    private static boolean isUniqueCandidateHitboxAt(List<AbstractCard> cards, AbstractCard target, int x, int y) {
        int hits = 0;
        for (AbstractCard card : cards) {
            if (card == null || card.hb == null) continue;
            boolean contains = x >= card.hb.x && x <= card.hb.x + card.hb.width
                    && y >= card.hb.y && y <= card.hb.y + card.hb.height;
            if (!contains) continue;
            if (card != target) return false;
            hits++;
        }
        return hits == 1;
    }

    private static int countIdentity(List<AbstractCard> cards, AbstractCard wanted) {
        int count = 0;
        if (cards != null && wanted != null) for (AbstractCard card : cards) if (card == wanted) count++;
        return count;
    }

    private void clearChoiceState() {
        if (choiceGate != null) choiceGate.cancel();
        choiceGate = null;
        pendingChoice = null;
        choiceTargetCard = null;
        playedExhumeCard = null;
        plannedExhumeCard = null;
        choiceRootHand = Collections.emptyList();
        choiceRootExhaust = Collections.emptyList();
        choiceEnergyAfterUse = -1;
        planSource = null;
    }

    private AbstractMonster potionTarget(Plan.Action action, AbstractPotion potion) {
        if (!potion.targetRequired) return null;
        if (action.targetIndex < 0) return null;
        AbstractMonster target = monsterAt(action.targetIndex);
        if (target == null) return null;
        if (action.targetName != null && !action.targetName.isEmpty()
                && !action.targetName.equals(target.name)) return null;
        return target;
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
        if (!completed && ExhumeChoiceSafety.requiresDirtyJournalOnFailure(
                pendingChoice != null, plannedExhumeCard != null) && journal != null) {
            journal.markDirty("Exhume 选牌未能忠实完成：" + (reason == null ? "未知原因" : reason));
        }
        if (!completed) cancelQueuedOperations();
        else clearQueuedTracking();
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
        targetSlots = null;
        virtualHand.clear();
        usedPotions.clear();
        clearChoiceState();
    }

    private AbstractPotion findPotion(String potionId) {
        for (AbstractPotion p : AbstractDungeon.player.potions) {
            if (p != null && p.ID != null && p.ID.equals(potionId) && !usedPotions.contains(p)) {
                return p;
            }
        }
        return null;
    }

    private List<AbstractMonster> nativeMonsters() {
        return AbstractDungeon.getCurrRoom().monsters.monsters;
    }

    /** 本地路线按根状态存活顺序映射对象；引擎路线按原生固定槽位。 */
    private AbstractMonster monsterAt(int index) {
        return targetSlots == null ? null
                : targetSlots.resolve(nativeMonsters(), index, m -> !m.isDeadOrEscaped());
    }

    private AbstractMonster targetFor(Plan.Action action, AbstractCard card) {
        if (!needsEnemy(card)) {
            return null;
        }
        AbstractMonster target = monsterAt(action.targetIndex);
        if (target == null) return null;
        if (action.targetName != null && !action.targetName.isEmpty()
                && !action.targetName.equals(target.name)) return null;
        return target;
    }

    private void cancelQueuedOperations() {
        GameActionManager manager = AbstractDungeon.actionManager;
        if (manager != null) {
            // J can be pressed while one of our queue wrappers is already currentAction.
            // It has left manager.actions, so cancel it explicitly before it can enqueue a card.
            boolean cancelledCurrent = ExecutionSafety.isOwnedByIdentity(queuedActions, manager.currentAction);
            if (cancelledCurrent) {
                manager.currentAction.isDone = true;
            }
            int cancelledActions = ExecutionSafety.removeOwnedByIdentity(manager.actions, queuedActions);
            int cancelledCards = ExecutionSafety.removeMatching(manager.cardQueue,
                    item -> containsIdentity(queuedCards, item.card));
            CombatLog.info("deploy queue cancellation: currentAction=" + cancelledCurrent
                    + " pendingActions=" + cancelledActions + " cardQueue=" + cancelledCards);
        }
        clearQueuedTracking();
    }

    private void clearQueuedTracking() {
        queuedActions.clear();
        queuedCards.clear();
        expectedUses.clear();
    }

    private static boolean containsIdentity(Set<AbstractCard> cards, AbstractCard target) {
        for (AbstractCard card : cards) if (card == target) return true;
        return false;
    }

    private static String targetSignature(CardTargetRef target) {
        return target.mode + ":" + target.identity + ":" + target.engineIndex;
    }

    private static final class ExpectedUse {
        final AbstractCard card;
        final String targetSignature;
        ExpectedUse(AbstractCard card, String targetSignature) {
            this.card = card;
            this.targetSignature = targetSignature;
        }
    }

    private boolean needsEnemy(AbstractCard card) {
        return card.target == AbstractCard.CardTarget.ENEMY
                || card.target == AbstractCard.CardTarget.SELF_AND_ENEMY;
    }
}
