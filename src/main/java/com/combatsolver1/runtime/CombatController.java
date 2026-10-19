package com.combatsolver1.runtime;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.combatsolver1.CombatLog;
import com.combatsolver1.engine.SimState;
import com.combatsolver1.search.CombatSearch;
import com.combatsolver1.search.Plan;
import com.combatsolver1.settings.SolverSettings;
import com.combatsolver1.ui.OverlayRenderer;
import com.combatsolver1.ui.PanelModel;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.potions.PotionSlot;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 战斗控制器。三种使用方式：
 * - 仅查看路线：默认状态，只预测并展示，不执行。
 * - 执行本回合：把预测路线当前回合的牌打完，不替你结束回合。
 * - 连续全自动：打完牌后自动结束回合并继续。
 *
 * 路线来源（优先级从高到低）：
 * 1. 引擎路线：sts_lightspeed 精确引擎，战斗开始时从存档推演整场；
 * 2. 引擎回放路线：把本场已经发生的动作喂回引擎，从“当前真实状态”重新推演
 *    （每个回合开始、每次点重新计算都会重规划）；
 * 3. 内置求解器：引擎不可用或路线被判定失真时的近似兜底。
 *
 * 每回合开始都会拿实机状态和引擎路线预测的结束状态逐项核对，
 * 对不上就丢弃引擎路线（并在面板上说明原因），避免拿错误路线指挥战斗。
 */
public class CombatController {
    private static CombatController instance;

    private final CombatSearch search = new CombatSearch();
    private final PlanDeployer deployer = new PlanDeployer();
    private final OverlayRenderer overlay = new OverlayRenderer();
    private final CombatSearch.Config config = new CombatSearch.Config();
    private final SolverSettings settings = SolverSettings.load();
    private final ActionJournal journal = new ActionJournal();
    private final Object engineFetchLock = new Object();

    private boolean inCombat;
    private Plan plan;
    private volatile Plan enginePlan;
    private volatile EngineBridge.Fetch pendingEngineFetch;
    private volatile long pendingEngineFetchAt;
    private volatile int pendingEngineFetchJournalSize = -1;
    private volatile boolean engineFetching;
    private int engineFetchJournalSize = -1;
    private int replayRequestedForSize = -1;
    private int battleId;
    private String engineNote = "";
    private String engineDriftReason = "";
    private String battleStartSave;
    private String battleRoomClassName = "";
    private NativeSaveLoadCapture.Resolution battleSaveResolution;
    private String expectedEndState;
    private int expectedEndTurn = -1;
    /** 待核对的“上一回合结束时引擎预测状态”：等到真正稳定的那一帧再比。 */
    private String pendingDriftExpected;
    private long pendingDriftDeadline;
    /** 本场战斗里出现过引擎路线无法自动执行的动作（选牌），之后只用内置求解器。 */
    private boolean engineActionsUnsupported;

    private boolean planValidated;
    private boolean planStale;
    /**
     * 本回合已放弃自动执行：计划反复校验失败、且牌与实机手牌对不上时置位，
     * 避免每帧重复搜索却每次都在打到一半时失效。状态一变（出牌 / 抽牌 / 换回合 / 手动重算）就复位。
     */
    private boolean suppressed;
    private String validatedSignature;
    private int searchAttempts;
    private boolean searching;
    private boolean paused;
    private int lastTurn = -1;
    private boolean gKeyDown;
    private boolean hKeyDown;
    private boolean jKeyDown;
    private boolean rKeyDown;
    private boolean executeRequested;
    private boolean fullAuto;
    private final ExecutionSafety.FailureLatch executionFailure = new ExecutionSafety.FailureLatch();
    private String activeExecutionSignature;
    private boolean failureStateChangeReported;

    private List<AbstractCard> lastIdleHand = new ArrayList<>();
    private List<AbstractPotion> lastIdlePotions = new ArrayList<>();

    public static CombatController instance() {
        if (instance == null) {
            instance = new CombatController();
        }
        return instance;
    }

    public void onBattleStart(AbstractRoom room) {
        inCombat = true;
        battleId++;
        battleSaveResolution = NativeSaveLoadCapture.consumeForBattle(AbstractDungeon.player, room);
        EnemyModel.reset();
        journal.clear();
        CombatLog.info(LiveCapture.deckCoverageText());
        plan = null;
        enginePlan = null;
        engineFetchJournalSize = -1;
        replayRequestedForSize = -1;
        engineDriftReason = "";
        engineNote = "";
        synchronized (engineFetchLock) {
            pendingEngineFetch = null;
            pendingEngineFetchJournalSize = -1;
            engineFetching = false;
            battleStartSave = null;
        }
        battleRoomClassName = room == null ? "" : room.getClass().getName();
        expectedEndState = null;
        expectedEndTurn = -1;
        pendingDriftExpected = null;
        engineActionsUnsupported = false;
        planValidated = false;
        planStale = false;
        executionFailure.clear();
        activeExecutionSignature = null;
        failureStateChangeReported = false;
        validatedSignature = null;
        searching = false;
        paused = false;
        lastTurn = -1;
        lastIdleHand = new ArrayList<>();
        lastIdlePotions = null;
        requestInitialEnginePlan(battleId);
        CombatLog.info("battle start: " + room.getClass().getSimpleName());
    }

    public void onBattleEnd() {
        reset();
        CombatLog.info("battle end");
    }

    public void onPlayerTurnStart() {
        planValidated = false;
        suppressed = false;
    }

    public void onCardDrawn(AbstractCard card) {
        planValidated = false;
        suppressed = false;
    }

    /** Narrow game-thread hook used only while a validated Exhume choice is pending. */
    public void beforeGridSelectUpdate(GridCardSelectScreen screen) {
        if (inCombat && deployer.isActive()) deployer.beforeGridSelectUpdate(screen);
    }

    /** 玩家（或模组）打出一张牌。用于把“手动出牌”也记录进动作日志。 */
    /** Records the same queue-selected target reference used by the card recorder. */
    public void onCardUsed(AbstractCard card, CardTargetRef target) {
        if (!inCombat) {
            return;
        }
        if (deployer.isActive()) {
            // At native useCard time, reject any unexpected player action/target and cancel the queued remainder.
            if (!deployer.acceptCardUsed(card, target)) {
                onDeployFinished(deployer.consumeResult());
            }
            return;
        }
        if (!journal.isUsable()) {
            return;
        }
        int index = indexOfCardIdentity(card);
        if (index < 0) {
            journal.markDirty("读不出出牌位置：" + card.name);
            return;
        }
        CardTargetRef ref = target == null ? CardTargetRef.missing() : target;
        journal.recordCard(index, card.cardID, ref);
        CombatLog.info("journal: manual play " + card.name + " idx=" + index
                + " targetMode=" + ref.mode + " target=" + ref.engineIndex
                + " trusted=" + ref.trusted);
    }

    public void update() {
        if (!inCombat) {
            clearKeys();
            return;
        }
        try {
            if (!AbstractDungeon.isPlayerInDungeon()) {
                reset();
                return;
            }
            AbstractRoom room = currentRoom();
            if (room == null || room.phase != AbstractRoom.RoomPhase.COMBAT) {
                clearKeys();
                return;
            }
            if (edge(Input.Keys.G)) {
                if (authorizeAfterFailure("G")) {
                    executeRequested = true;
                    suppressed = false;
                    CombatLog.info("G requested: current turn only");
                }
            }
            if (edge(Input.Keys.H)) {
                fullAuto = !fullAuto;
                CombatLog.info("full auto: " + fullAuto);
                if (fullAuto) authorizeAfterFailure("H");
                if (!fullAuto && deployer.isActive()) {
                    CombatLog.info("H disabled: current deployment is not cancelled; J pauses and cancels pending actions");
                }
            }
            if (edge(Input.Keys.J)) {
                stopAndPause();
            }
            if (edge(Input.Keys.R)) {
                recalculate();
            }

            int uiAction = overlay.handleInput(!AbstractDungeon.isScreenUp);
            if (uiAction == OverlayRenderer.ACTION_RECALCULATE) {
                recalculate();
            } else if (uiAction == OverlayRenderer.ACTION_EXECUTE_TURN) {
                if (authorizeAfterFailure("G-overlay")) {
                    executeRequested = true;
                    suppressed = false;
                    CombatLog.info("G requested from overlay: current turn only");
                }
            } else if (uiAction == OverlayRenderer.ACTION_TOGGLE_AUTO) {
                fullAuto = !fullAuto;
                CombatLog.info("full auto: " + fullAuto);
                if (fullAuto) authorizeAfterFailure("H-overlay");
                if (!fullAuto && deployer.isActive()) {
                    CombatLog.info("H disabled: current deployment is not cancelled; J pauses and cancels pending actions");
                }
            } else if (uiAction == OverlayRenderer.ACTION_CYCLE_BUDGET) {
                settings.cyclePreset();
                settings.save();
                CombatLog.info("search budget: " + settings.presetName());
            } else if (uiAction == OverlayRenderer.ACTION_CYCLE_OPACITY) {
                settings.cycleOpacity();
                settings.save();
            } else if (uiAction == OverlayRenderer.ACTION_TOGGLE_THEME) {
                settings.cycleTheme();
                settings.save();
            } else if (uiAction == OverlayRenderer.ACTION_COLLAPSE) {
                overlay.setCollapsed(true);
            } else if (uiAction == OverlayRenderer.ACTION_EXPAND) {
                overlay.setCollapsed(false);
            } else if (uiAction == OverlayRenderer.ACTION_PAUSE) {
                if (!paused) {
                    stopAndPause();
                }
            } else if (uiAction == OverlayRenderer.ACTION_TOGGLE_ROUTE_EXPAND) {
                overlay.toggleRouteExpanded();
            }

            adoptEngineFetchIfReady();
            int turn = AbstractDungeon.actionManager.turn;
            if (turn != lastTurn) {
                if (lastTurn > 0 && journal.isUsable() && journal.size() > 0
                        && !journal.endsWithEndTurn()) {
                    // 上一回合结束（我们或玩家点的），补记一次结束回合
                    journal.recordEndTurn();
                }
                lastTurn = turn;
                searchAttempts = 0;
                if (!executionFailure.isBlocked()) suppressed = false;
                planValidated = false;
                planStale = false;
                validatedSignature = null;
                searching = false;
                search.abort();
                CombatLog.info("new turn: " + turn);
                if (fullAuto && enginePlan != null) {
                    CombatLog.info("H continuation candidate: turn=" + turn
                            + " remainingSegments=" + enginePlan.turns.size()
                            + " pendingStabilityCheck=" + (expectedEndState != null));
                }
                verifyEngineRouteAtTurnStart(turn);
            }
            checkPendingDrift();

            if (searching) {
                if (paused) {
                    search.abort();
                    searching = false;
                } else if (search.step()) {
                    searching = false;
                    plan = search.result();
                    planValidated = false;
                    if (plan != null) {
                        plan.source = "local";
                        localizePlanNames(plan);
                        CombatLog.info("plan: turns=" + plan.turns.size()
                                + " hpLoss=" + plan.predictedHpLoss
                                + " win=" + plan.predictedWin
                                + " nodes=" + plan.nodesSearched
                                + " | " + plan.confidenceNote());
                        CombatLog.info("提示：这是当前预算内找到的最好路线，不保证全局最优。");
                    }
                }
            }

            if (deployer.isActive()) {
                deployer.update();
                PlanDeployer.Result result = deployer.consumeResult();
                if (result != null) {
                    onDeployFinished(result);
                }
                return;
            }
            if (!AbstractDungeon.actionManager.isEmpty()) {
                return;
            }
            boolean canAct = AbstractDungeon.actionManager.phase == GameActionManager.Phase.WAITING_ON_USER
                    && !AbstractDungeon.actionManager.turnHasEnded
                    && !AbstractDungeon.isScreenUp;
            // Root and action snapshots are eligible only after setup has settled and the player can act.
            boolean stable = AbstractDungeon.actionManager.isEmpty()
                    && AbstractDungeon.actionManager.currentAction == null
                    && AbstractDungeon.actionManager.cardQueue.isEmpty()
                    && !AbstractDungeon.actionManager.usingCard
                    && !AbstractDungeon.isScreenUp;
            if (stable && canAct) {
                TurnBoundaryDiagnostics.onStablePlayerReady();
            }
            CombatRecorder.onIdle(stable, canAct);
            recordPassiveChanges(canAct);

            if (!canAct || paused) {
                if (executeRequested) {
                    executeRequested = false;
                }
                return;
            }

            SimState root = null;
            if (!searching) {
                root = LiveCapture.captureRoot();
            }

            // Compare signatures from the same full-state representation. The old lightweight string
            // omitted hand/card fields and could never equal SimState.signature().
            if (root != null && currentPlan() != null && !planStale && validatedSignature != null
                    && !validatedSignature.equals(root.signature())) {
                planStale = true;
                planValidated = false;
                suppressed = false;
                plan = null;
                enginePlan = null;
                validatedSignature = null;
                expectedEndState = null;
                CombatLog.info("live state changed; plan invalidated");
            }

            if (root != null && executionFailure.isBlocked()) {
                boolean wasChanged = executionFailure.hasStateChange();
                executionFailure.observe(executionStateKey(root, turn));
                if (!wasChanged && executionFailure.hasStateChange() && !failureStateChangeReported) {
                    failureStateChangeReported = true;
                    CombatLog.info("failed execution state changed; waiting for fresh G/H authorization");
                }
            }

            if (!searching && !planValidated) {
                if (root != null && canReusePlan(plan, root)) {
                    planValidated = true;
                    planStale = false;
                    validatedSignature = root.signature();
                    CombatLog.info("local route reused for turn " + turn);
                } else if (executionFailure.isBlocked()) {
                    suppressed = true;
                } else if (suppressed) {
                    // 本回合已经放弃自动执行，不再反复搜索
                } else if (plan != null && searchAttempts >= 3) {
                    // 重复验证失败不能因为牌名还在手里就降级为“强行执行”。
                    planValidated = false;
                    planStale = true;
                    validatedSignature = null;
                    suppressed = true;
                    CombatLog.info("路线连续验证失败，本回合停止自动执行"
                            + "（计划 " + planActionSummary(plan) + "）");
                } else if (enginePlanAvailable()) {
                    // 引擎路线在手，不必再跑本地搜索。
                    if (root != null) {
                        planValidated = true;
                        planStale = false;
                        validatedSignature = root.signature();
                    }
                } else {
                    maybeRequestReplayEnginePlan();
                    plan = null;
                    planStale = false;
                    if (root != null) {
                        settings.applyTo(config);
                        searchAttempts++;
                        search.begin(root, config);
                        searching = true;
                        validatedSignature = root.signature();
                        CombatLog.info("search root: energy=" + root.energy
                                + " hp=" + root.hp + " hand=[" + handSummary(root) + "]");
                    }
                }
            }

            Plan active = currentPlan();
            if (active != null && active.turns.size() > 0 && (executeRequested || fullAuto)
                    && ExecutionSafety.mayExecute(true, planValidated, planStale, suppressed,
                    paused, executeRequested, fullAuto, validatedSignature,
                    root == null ? null : root.signature())
                    && !executionFailure.isBlocked()
                    && engineTurnUsable(active)) {
                boolean autoTurn = fullAuto;
                executeRequested = false;
                activeExecutionSignature = executionStateKey(root, turn);
                Plan.Turn firstTurn = active.turns.get(0);
                ExecutionSafety.TargetSlots<AbstractMonster> targets = targetSlotsFor(active, root);
                if (active == enginePlan) {
                    // 引擎路线是整场推演：一回合用掉一节，下一回合继续核对。
                    active.turns.remove(0);
                    expectedEndState = firstTurn.endState;
                    expectedEndTurn = firstTurn.turnNumber;
                    planValidated = false;
                    validatedSignature = null;
                } else {
                    // 本地路线：手动执行本回合后，用剩余手牌与能量重新预测。
                    plan = null;
                    planValidated = false;
                    planStale = false;
                    validatedSignature = null;
                }
                lastIdlePotions = null;
                deployer.execute(firstTurn, autoTurn, journal, targets, active.source);
                onDeployFinished(deployer.consumeResult());
            } else if (active != null && active == enginePlan && (executeRequested || fullAuto)
                    && active.turns.size() > 0 && !engineTurnUsable(active)) {
                executeRequested = false;
            } else if (executeRequested) {
                executeRequested = false;
            }
        } catch (Throwable t) {
            CombatLog.error("update error", t);
        }
    }

    public void render(SpriteBatch sb) {
        if (!inCombat) {
            return;
        }
        PanelModel model = new PanelModel();
        model.plan = currentPlan();
        model.fullAuto = fullAuto;
        model.paused = paused;
        model.searching = searching || engineFetching;
        model.executing = deployer.isActive();
        model.settings = settings;
        model.collapsed = overlay.isCollapsed();

        // 状态徽章与上下文
        if (executionFailure.isBlocked()) {
            model.badge = "执行已停止";
            model.badgeColor = "danger";
            model.context = engineDriftReason.isEmpty() ? "状态校验未通过，请检查路线" : engineDriftReason;
        } else if (paused) {
            model.badge = "已暂停";
            model.badgeColor = "warning";
            model.context = "点“重新计算”继续";
        } else if (searching || engineFetching) {
            model.badge = "搜索中";
            model.badgeColor = "accent";
            model.context = shortSourceLine();
        } else if (deployer.isActive()) {
            model.badge = "执行中";
            model.badgeColor = "accent";
            model.context = shortSourceLine();
        } else if (planStale) {
            model.badge = "路线已过期";
            model.badgeColor = "warning";
            model.context = shortSourceLine();
        } else if (model.plan == null) {
            model.badge = "暂无可用路线";
            model.badgeColor = "warning";
            model.context = engineNote.isEmpty() ? shortSourceLine() : engineNote;
        } else {
            model.badge = "就绪";
            model.badgeColor = "success";
            model.context = shortSourceLine();
        }

        // 摘要行与搜索进度
        Plan plan = model.plan;
        if (engineFetching) {
            model.progressText = "sts_lightspeed 正在求解…";
            model.progressRatio = -1f;
        } else if (searching) {
            int depth = search.progressDepth();
            int max = Math.max(1, search.progressMaxTurns());
            model.progressText = "搜索中… 已展开 " + search.progressNodes() + " 个节点（第 "
                    + depth + "/" + max + " 回合）";
            model.progressRatio = Math.max(0.03f, Math.min(0.97f, (float) depth / max));
        } else if (plan == null) {
            model.summary.add(new PanelModel.Segment("等待预测", "muted"));
        } else if (!plan.predictedWin) {
            model.summary.add(new PanelModel.Segment("预算内未找到存活路线", "danger"));
        } else {
            int loss = Math.max(0, plan.predictedHpLoss);
            String lossColor = loss <= 0 ? "success" : (loss >= 8 ? "warning" : "secondary");
            model.summary.add(new PanelModel.Segment("预计整场扣血 ", "text"));
            model.summary.add(new PanelModel.Segment(loss + " HP", lossColor));
            model.summary.add(new PanelModel.Segment("　剩 ", "muted"));
            model.summary.add(new PanelModel.Segment(Math.max(0, plan.endHp) + " / " + plan.startHp,
                    "text"));
        }

        // 路线标题与结果标签
        if (planStale) {
            model.heading = "已保存路线（已过期）";
            model.headingColor = "warning";
        } else if (deployer.isActive()) {
            model.heading = "正在执行的路线";
            model.headingColor = "accent";
        } else if (searching) {
            model.heading = "求解器当前考虑（尚未验证）";
            model.headingColor = "muted";
        } else {
            model.heading = "推荐路线";
            model.headingColor = "text";
        }
        if (plan != null) {
            int potions = countPotionActions(plan);
            if (potions > 0) {
                model.potionOutcome = "预计用 " + potions + " 瓶药";
            }
            if (plan.predictedWin) {
                model.hpOutcome = "本局扣血 " + Math.max(0, plan.predictedHpLoss) + " HP";
                model.hpOutcomeColor = plan.predictedHpLoss <= 0 ? "success" : "warning";
            } else {
                model.showDeathOutcome = true;
            }
        }

        model.sourceLine = sourceLine();
        appendEnemyLines(model.enemyLines);
        model.coverageLine = LiveCapture.deckCoverageText();
        List<String> potions = LiveCapture.potionNames();
        if (!potions.isEmpty()) {
            StringBuilder line = new StringBuilder("药水：");
            for (int i = 0; i < potions.size(); i++) {
                if (i > 0) {
                    line.append("、");
                }
                line.append(potions.get(i));
            }
            model.potionLine = line.toString();
        }
        overlay.render(sb, model);
    }

    private String shortSourceLine() {
        StringBuilder sb = new StringBuilder();
        if (enginePlanAvailable()) {
            sb.append("引擎路线");
        } else if (plan != null) {
            sb.append("内置求解器");
        } else {
            sb.append("尚未生成");
        }
        sb.append(" · 日志 ").append(journal.size()).append(" 动作");
        if (engineFetching) {
            sb.append(" · 引擎计算中…");
        }
        if (!engineDriftReason.isEmpty()) {
            sb.append(" · ").append(engineDriftReason);
        }
        return sb.toString();
    }

    private int countPotionActions(Plan p) {
        int n = 0;
        for (Plan.Turn t : p.turns) {
            for (Plan.Action a : t.actions) {
                if (a.isPotion()) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 面板文案

    private String sourceLine() {
        StringBuilder sb = new StringBuilder("路线来源：");
        if (enginePlanAvailable()) {
            sb.append("外部引擎（sts_lightspeed）");
        } else if (plan != null) {
            sb.append("内置求解器（近似）");
        } else {
            sb.append("尚未生成");
        }
        sb.append("　｜　动作日志：").append(journal.summary());
        if (engineFetching) {
            sb.append("　｜　引擎计算中…");
        }
        if (!engineDriftReason.isEmpty()) {
            sb.append("　｜　").append(engineDriftReason);
        } else if (!engineNote.isEmpty()) {
            sb.append("　｜　").append(engineNote);
        }
        return sb.toString();
    }

    private void appendEnemyLines(List<String> out) {
        AbstractRoom room = currentRoom();
        if (room == null || room.monsters == null) {
            return;
        }
        for (AbstractMonster m : room.monsters.monsters) {
            if (m.isDeadOrEscaped()) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            line.append(m.name).append("　").append(m.currentHealth).append("/").append(m.maxHealth);
            line.append("　").append(LiveCapture.intentText(m));
            if (m.currentBlock > 0) {
                line.append("　格挡").append(m.currentBlock);
            }
            out.add(line.toString());
            if (out.size() >= 4) {
                return;
            }
        }
    }

    // ------------------------------------------------------------ 引擎路线管理

    private boolean enginePlanAvailable() {
        return enginePlan != null && !enginePlan.turns.isEmpty();
    }

    private void requestInitialEnginePlan(final int id) {
        final boolean engineConfigured = EngineBridge.isConfigured();
        if (engineConfigured && engineFetching) return;
        if (engineConfigured) engineFetching = true;
        final long since = System.currentTimeMillis() - 3000L;
        final String roomClassName = battleRoomClassName;
        final String artifactId = CombatRecorder.currentBattleId();
        final NativeSaveLoadCapture.Resolution saveResolution = battleSaveResolution;
        if (!BattleArtifacts.initialCaptureStarted(artifactId)) {
            engineNote = "本场证据归档不可用，未启动存档采集";
            if (engineConfigured) engineFetching = false;
            return;
        }
        if (engineConfigured && (AbstractDungeon.player == null
                || AbstractDungeon.player.chosenClass != AbstractPlayer.PlayerClass.IRONCLAD)) {
            String reason = "sts_lightspeed currently supports IRONCLAD saves only";
            engineNote = "当前外部引擎只支持铁甲战士，已拒绝其他角色存档";
            BattleArtifacts.enginePreparationFailed(artifactId, reason);
            BattleArtifacts.initialCaptureFinished(artifactId);
            if (engineConfigured) engineFetching = false;
            CombatLog.info("engine save rejected: " + reason);
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!BattleArtifacts.canCapture(artifactId)) {
                        engineNote = "本场证据归档不可用，拒绝使用未配对存档";
                        return;
                    }
                    File archivedSave;
                    if (saveResolution != null && saveResolution.nativeLoadObserved) {
                        NativeSaveLoadCapture.LoadedSource loaded = saveResolution.source;
                        if (loaded == null) {
                            BattleArtifacts.nativeLoadedSaveRejected(artifactId, saveResolution.reason);
                            String reason = "Native Continue save rejected: " + saveResolution.reason;
                            BattleArtifacts.enginePreparationFailed(artifactId, reason);
                            engineNote = "读档存档与当前战斗未能可信绑定：" + saveResolution.reason;
                            CombatLog.info("resume save rejected: " + saveResolution.reason);
                            return;
                        }
                        archivedSave = BattleArtifacts.captureNativeLoadedSave(artifactId, loaded.bytes,
                                loaded.sourcePath, loaded.sourceModifiedAt, loaded.evidenceId);
                        if (archivedSave != null) {
                            CombatLog.info("resume save bound to battle: evidence=" + loaded.evidenceId
                                    + " sourceSha256=" + loaded.sourceSha256);
                        }
                    } else {
                        File save = EngineBridge.freshAutosave(since);
                        if (save == null) {
                            BattleArtifacts.captureAutosave(artifactId, null, since);
                            BattleArtifacts.enginePreparationFailed(artifactId, "No fresh autosave found; stale autosave was not used.");
                            engineNote = "未找到本场新自动存档，已拒绝旧存档";
                            return;
                        }
                        archivedSave = BattleArtifacts.captureAutosave(artifactId, save, since);
                    }
                    if (archivedSave == null) {
                        BattleArtifacts.enginePreparationFailed(artifactId, "Could not archive the verified battle save source.");
                        engineNote = "本场已验证存档归档失败，已停止外部引擎调用";
                        return;
                    }
                    if (!engineConfigured) {
                        BattleArtifacts.engineNotConfigured(artifactId);
                        return;
                    }
                    File engineDir = BattleArtifacts.engineDirectory(artifactId);
                    if (engineDir == null) {
                        BattleArtifacts.enginePreparationFailed(artifactId, "Could not create per-battle engine directory.");
                        engineNote = "本场引擎工作目录创建失败";
                        return;
                    }
                    // Build the engine input from the archived byte-for-byte source copy so the
                    // manifest hash always identifies the exact save used for initialization.
                    String staged = EngineBridge.prepareCombatSave(archivedSave, roomClassName, engineDir);
                    if (staged == null) {
                        BattleArtifacts.enginePreparationFailed(artifactId, "Engine combat save preparation failed.");
                        engineNote = "本场存档无法转换为引擎战斗起点";
                        return;
                    }
                    BattleArtifacts.recordEnginePreparation(artifactId, new File(staged), EngineBridge.describe(),
                            EngineBridge.engineExecutable());
                    BattleArtifacts.initialCaptureFinished(artifactId);
                    if (!BattleArtifacts.isActive(artifactId) || id != battleId) return;
                    EngineBridge.Fetch fetch = EngineBridge.fetchPlan(staged,
                            settings.engineSimulations());
                    synchronized (engineFetchLock) {
                        if (!BattleArtifacts.isActive(artifactId) || id != battleId) return;
                        battleStartSave = staged;
                        if (fetch != null && fetch.plan != null) {
                            fetch.plan.source = "engine";
                            pendingEngineFetch = fetch;
                            pendingEngineFetchAt = System.currentTimeMillis();
                            pendingEngineFetchJournalSize = 0;
                            engineFetchJournalSize = 0;
                        } else {
                            engineNote = "引擎没有给出计划";
                        }
                    }
                } catch (Throwable t) {
                    String reason = "Unexpected battle artifact or engine preparation failure: " + t;
                    try {
                        BattleArtifacts.enginePreparationFailed(artifactId, reason);
                    } catch (Throwable reportFailure) {
                        t.addSuppressed(reportFailure);
                    }
                    if (id == battleId) {
                        engineNote = "本场采集或引擎准备异常：" + t;
                    }
                    CombatLog.error("battle artifact or engine preparation failed", t);
                } finally {
                    BattleArtifacts.initialCaptureFinished(artifactId);
                    if (engineConfigured && id == battleId) {
                        synchronized (engineFetchLock) { if (id == battleId) engineFetching = false; }
                    }
                }
            }
        }, "CombatSolver1-EngineInit");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (Throwable t) {
            BattleArtifacts.enginePreparationFailed(artifactId, "Could not start battle artifact capture: " + t);
            BattleArtifacts.initialCaptureFinished(artifactId);
            if (engineConfigured && id == battleId) {
                synchronized (engineFetchLock) { if (id == battleId) engineFetching = false; }
            }
            CombatLog.error("battle artifact capture thread could not start", t);
        }
    }

    /** 日志干净且引擎空闲时，用动作日志回放到当前状态再重算。 */
    private void maybeRequestReplayEnginePlan() {
        if (!EngineBridge.isConfigured() || engineFetching || !journal.isUsable()) {
            return;
        }
        if (engineActionsUnsupported) {
            return;
        }
        if (battleStartSave == null) {
            return;
        }
        if (replayRequestedForSize == journal.size()) {
            return;
        }
        replayRequestedForSize = journal.size();
        final int id = battleId;
        final int journalSize = journal.size();
        final String artifactId = CombatRecorder.currentBattleId();
        final String journalPath = EngineBridge.writeJournal(journal.snapshotWithMetadata());
        if (journalPath == null) {
            String reason = BattleArtifacts.journalFailureReason();
            journal.markDirty("引擎回放 journal 替换失败："
                    + (reason == null || reason.isEmpty() ? "未捕获底层错误" : reason));
            // Only discard an unadopted asynchronous result. A currently adopted route is left
            // intact; its execution remains guarded by its live-state signature and drift checks.
            pendingEngineFetch = null;
            return;
        }
        engineFetching = true;
        final String save = battleStartSave;
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    EngineBridge.Fetch fetch = EngineBridge.fetchReplayPlan(save, journalPath,
                            settings.engineSimulations());
                    synchronized (engineFetchLock) {
                        if (id != battleId || !BattleArtifacts.isActive(artifactId)) return;
                        if (fetch != null && fetch.plan != null && !fetch.replayInvalid) {
                            fetch.plan.source = "engine";
                            pendingEngineFetch = fetch;
                            pendingEngineFetchAt = System.currentTimeMillis();
                            pendingEngineFetchJournalSize = journalSize;
                            engineFetchJournalSize = journalSize;
                        } else if (fetch != null && fetch.replayInvalid) {
                            engineNote = "引擎回放中断（日志与实机不同步）";
                        } else {
                            engineNote = "引擎重算失败";
                        }
                    }
                } finally {
                    if (id == battleId) {
                        synchronized (engineFetchLock) { if (id == battleId) engineFetching = false; }
                    }
                }
            }
        }, "CombatSolver1-EngineReplay");
        worker.setDaemon(true);
        worker.start();
    }

    /** 引擎结果到主线程后：校验与实机一致再采用。 */
    private void adoptEngineFetchIfReady() {
        EngineBridge.Fetch fetch = pendingEngineFetch;
        if (fetch == null) {
            return;
        }
        if (!ExecutionSafety.canAdoptReplayFetch(journal.isUsable(),
                pendingEngineFetchJournalSize, journal.size())) {
            boolean dirty = !journal.isUsable();
            pendingEngineFetch = null;
            if (dirty) {
                engineNote = "journal 已标脏，丢弃未采纳的引擎回放结果";
                CombatLog.info("engine fetch rejected: action journal is dirty");
            } else {
                replayRequestedForSize = -1;
                engineNote = "引擎结果基于旧动作日志，已丢弃并等待重算";
                CombatLog.info("engine fetch stale: journal " + pendingEngineFetchJournalSize
                        + " -> " + journal.size());
            }
            return;
        }
        if (deployer.isActive()) {
            // 正在按计划出牌：等这一回合忙完再核对，否则会把“打了一半的状态”当成偏差。
            if (System.currentTimeMillis() - pendingEngineFetchAt > 20000L) {
                pendingEngineFetch = null;
            }
            return;
        }
        if (!ExecutionSafety.fetchMatchesJournal(pendingEngineFetchJournalSize, journal.size())) {
            pendingEngineFetch = null;
            replayRequestedForSize = -1;
            engineNote = "引擎结果基于旧动作日志，已丢弃并等待重算";
            CombatLog.info("engine fetch stale: journal " + pendingEngineFetchJournalSize
                    + " -> " + journal.size());
            return;
        }
        final boolean initialBattleRootFetch = pendingEngineFetchJournalSize == 0 && journal.size() == 0;
        // 只在“稳定的一帧”上核对：回合切换、动画中、看牌时手牌可能正在变化，核对会误判。
        if (!isSettled()) {
            if (System.currentTimeMillis() - pendingEngineFetchAt > 8000L) {
                pendingEngineFetch = null;
                engineNote = "引擎结果未能在稳定状态核对";
                CombatLog.info("engine fetch dropped: game never settled");
            }
            return;
        }
        pendingEngineFetch = null;
        if (fetch.plan == null) {
            return;
        }
        fillMissingLiveTurns(fetch.plan);
        String live = liveStateLine();
        if (fetch.state != null && !statesMatch(fetch.state, live)) {
            if (pendingEngineFetchJournalSize >= 0
                    && journal.size() != pendingEngineFetchJournalSize) {
                // 结果是过期快照（这段期间我们又打了牌）：丢掉重算，而不是判定日志失真。
                engineNote = "引擎结果已过期，稍后重算";
                replayRequestedForSize = -1;
                CombatLog.info("engine fetch stale: journal " + pendingEngineFetchJournalSize
                        + " -> " + journal.size());
                return;
            }
            engineDriftReason = "引擎回放结果与实机不一致，改用内置求解器";
            journal.markDirty("回放状态与实机不一致");
            enginePlan = null;
            CombatLog.info("engine state mismatch: engine=[" + fetch.state + "] live=[" + live + "]");
            return;
        }
        if (fetch.state == null && fetch.state0 != null && journal.size() == 0) {
            // 战斗起点计划：顺手核对起点是否就是这一场战斗（防止读到上一张地图的旧存档）。
            if (!startStatesMatch(fetch.state0, live)) {
                engineNote = "起点状态与实机不一致（存档可能过期）";
                if (initialBattleRootFetch) {
                    BattleArtifacts.engineRootStateResult(CombatRecorder.currentBattleId(), false,
                            "engine STATE0 differs from the stable live root");
                }
                CombatLog.info("engine start state mismatch: engine=[" + fetch.state0
                        + "] live=[" + live + "]");
                return;
            }
        }
        if (initialBattleRootFetch && fetch.state == null) {
            if (fetch.state0 == null) {
                engineNote = "外部引擎未返回根状态，拒绝采用路线";
                BattleArtifacts.engineRootStateResult(CombatRecorder.currentBattleId(), false,
                        "engine did not provide STATE0 for initial plan");
                CombatLog.info("engine initial plan rejected: STATE0 missing");
                return;
            }
            BattleArtifacts.engineRootStateResult(CombatRecorder.currentBattleId(), true,
                    "engine STATE0 matched live hp/block/energy/hand/monster state");
        }
        localizePlanNames(fetch.plan);
        enginePlan = fetch.plan;
        SimState validatedRoot = LiveCapture.captureRoot();
        if (validatedRoot == null) {
            enginePlan = null;
            engineNote = "无法在稳定帧确认引擎路线起点";
            return;
        }
        validatedSignature = validatedRoot.signature();
        planValidated = true;
        planStale = false;
        engineDriftReason = "";
        engineNote = "";
        CombatLog.info("engine plan ready: turns=" + fetch.plan.turns.size()
                + " endHp=" + fetch.plan.endHp + " source=" + fetch.plan.source
                + " replay=" + fetch.replayActions);
    }

    /** 引擎输出里缺回合号的段（例如整场只有一回合），按实机当前回合顺序补齐。 */
    private void fillMissingLiveTurns(Plan p) {
        int next = AbstractDungeon.actionManager.turn;
        for (Plan.Turn t : p.turns) {
            if (t.liveTurnNumber <= 0) {
                t.liveTurnNumber = next;
            }
            next = t.liveTurnNumber + 1;
        }
    }

    /** 稳定帧：玩家待操作、动作队列安静、没有开牌面/选牌界面。 */
    private boolean isSettled() {
        GameActionManager m = AbstractDungeon.actionManager;
        return m.phase == GameActionManager.Phase.WAITING_ON_USER
                && !m.turnHasEnded
                && m.isEmpty()
                && m.currentAction == null
                && m.cardQueue.isEmpty()
                && !m.usingCard
                && !AbstractDungeon.isScreenUp;
    }

    /** 安全取当前房间：地图节点还没初始化时 getCurrRoom() 会抛空指针。 */
    private AbstractRoom currentRoom() {
        try {
            if (AbstractDungeon.currMapNode == null) {
                return null;
            }
            return AbstractDungeon.getCurrRoom();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 回合开始时核对：上一回合打完后的实机状态是否与引擎预测一致。 */
    private void verifyEngineRouteAtTurnStart(int turn) {
        if (expectedEndState == null || enginePlan == null) {
            return;
        }
        // 回合切换的一瞬间手牌还没抽、动作还在跑，这里只登记，等稳定帧再比。
        pendingDriftExpected = expectedEndState;
        pendingDriftDeadline = System.currentTimeMillis() + 10000L;
        expectedEndState = null;
    }

    /** 在真正稳定的一帧上核对引擎预测；对不上才丢弃引擎路线。 */
    private void checkPendingDrift() {
        if (pendingDriftExpected == null || deployer.isActive()) {
            return;
        }
        if (!isSettled()) {
            if (System.currentTimeMillis() > pendingDriftDeadline) {
                CombatLog.info("drift check skipped: game never settled");
                pendingDriftExpected = null;
            }
            return;
        }
        String expected = pendingDriftExpected;
        pendingDriftExpected = null;
        if (enginePlan == null) {
            return;
        }
        String live = liveStateLine();
        if (!statesMatch(expected, live)) {
            engineDriftReason = "实机与引擎预测出现偏差，已丢弃该路线";
            CombatLog.info("engine drift: expected=[" + expected + "] live=[" + live + "]");
            enginePlan = null;
            expectedEndTurn = -1;
        } else {
            CombatLog.info("engine drift check matched: remainingSegments=" + enginePlan.turns.size());
        }
    }

    private void onDeployFinished(PlanDeployer.Result result) {
        if (result == null) {
            return;
        }
        // 我们自己部署里可能喝了药水：清空快照，下一帧以当前药水为准重新基线。
        lastIdlePotions = null;
        if (result.invalidated) {
            engineDriftReason = "计划执行失败：" + result.invalidReason;
            CombatLog.info("deploy invalidated: " + result.invalidReason);
            if (result.unsupported) {
                engineActionsUnsupported = true;
                engineDriftReason = "路线里有需要手动选牌的牌，本场改用内置求解器";
            }
            enginePlan = null;
            plan = null;
            planValidated = false;
            validatedSignature = null;
            searchAttempts = 0;
            expectedEndState = null;
            executionFailure.block(activeExecutionSignature);
            failureStateChangeReported = false;
            suppressed = true;
            fullAuto = false;
            executeRequested = false;
            CombatLog.info("automatic retry blocked until stable state changes and a fresh G/H authorization is given");
        } else if (result.completed) {
            activeExecutionSignature = null;
        }
    }

    private boolean authorizeAfterFailure(String source) {
        if (!executionFailure.isBlocked()) return true;
        executionFailure.authorize();
        if (executionFailure.isBlocked()) {
            CombatLog.info(source + " ignored: failed state has not changed; automatic retry remains blocked");
            return false;
        }
        suppressed = false;
        searchAttempts = 0;
        CombatLog.info(source + " authorized after verified state change");
        return true;
    }

    private static String executionStateKey(SimState state, int turn) {
        return turn + "|" + state.signature();
    }

    /**
     * 引擎路线的一段动作只能用在它对应的实机回合：
     * 早一回合（比如本回合已经手打完，却把下一回合的牌打出去）或晚一回合都不允许。
     */
    private boolean engineTurnUsable(Plan active) {
        if (active != enginePlan) {
            return true;
        }
        Plan.Turn first = active.turns.get(0);
        int liveTurn = AbstractDungeon.actionManager.turn;
        if (!ExecutionSafety.turnMatches(first.liveTurnNumber, liveTurn)
                && first.liveTurnNumber > liveTurn) {
            return false;
        }
        if (!ExecutionSafety.turnMatches(first.liveTurnNumber, liveTurn)
                && first.liveTurnNumber < liveTurn) {
            // 计划落后于实机：这段路线已经过期，直接丢弃重新规划。
            engineDriftReason = "引擎路线落后于实机回合，已重新规划";
            CombatLog.info("engine plan turn " + first.liveTurnNumber
                    + " is behind live turn " + liveTurn + "; dropping");
            enginePlan = null;
            engineFetchJournalSize = -1;
            expectedEndState = null;
            return false;
        }
        return true;
    }

    private void localizePlanNames(Plan p) {
        AbstractRoom room = currentRoom();
        List<AbstractMonster> monsters = room != null && room.monsters != null
                ? room.monsters.monsters
                : new ArrayList<AbstractMonster>();
        ExecutionSafety.TargetSlots<AbstractMonster> targets = ExecutionSafety.TargetSlots.capture(
                p.source, monsters, m -> !m.isDeadOrEscaped());
        for (int ti = 0; ti < p.turns.size(); ti++) {
            Plan.Turn t = p.turns.get(ti);
            boolean last = ti == p.turns.size() - 1;
            for (Plan.Action a : t.actions) {
                if (a.kind == Plan.Action.KIND_CARD && a.cardId != null) {
                    a.cardName = CardNames.zh(a.cardId);
                    a.colorIndex = colorIndexFor(a.cardId);
                }
                if (a.isPotion()) {
                    a.cardName = "用药水：" + CardNames.zhPotion(a.potionId());
                    a.colorIndex = 4;
                    AbstractMonster target = targets == null || ("local".equals(p.source) && ti > 0)
                            ? null : targets.resolve(monsters, a.targetIndex, m -> !m.isDeadOrEscaped());
                    a.targetName = target == null ? null : target.name;
                    continue;
                }
                if (a.isSelect()) {
                    a.colorIndex = 5;
                    continue;
                }
                // 只有需要指定目标的牌才显示“→某怪物”；防御这类牌引擎也会带 0 号下标。
                if (a.kind == Plan.Action.KIND_CARD && needsTarget(a.cardId)) {
                    AbstractMonster target = targets == null || ("local".equals(p.source) && ti > 0)
                            ? null : targets.resolve(monsters, a.targetIndex, m -> !m.isDeadOrEscaped());
                    a.targetName = target == null ? null : target.name;
                }
            }
            // 内置求解器：每一回合都以结束回合收尾；击杀信息只在最后一回合可判定。
            if ("local".equals(p.source)) {
                t.endsTurn = true;
                t.killsAll = last && p.predictedWin;
            }
        }
    }

    private ExecutionSafety.TargetSlots<AbstractMonster> targetSlotsFor(Plan p, SimState root) {
        AbstractRoom room = currentRoom();
        if (room == null || room.monsters == null) return null;
        List<AbstractMonster> roster = room.monsters.monsters;
        ExecutionSafety.TargetSlots<AbstractMonster> targets = ExecutionSafety.TargetSlots.capture(
                p.source, roster, m -> !m.isDeadOrEscaped());
        if (targets == null || !"local".equals(p.source)) return targets;
        if (root == null || targets.initialLiving().size() != root.mobs.size()) return null;
        for (int i = 0; i < root.mobs.size(); i++) {
            AbstractMonster live = targets.initialLiving().get(i);
            SimState.Mob planned = root.mobs.get(i);
            if (!sameId(live.id, planned.id) || live.currentHealth != planned.hp
                    || live.maxHealth != planned.maxHp || live.currentBlock != planned.block) return null;
        }
        return targets;
    }

    private static boolean sameId(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 牌的类型 → 胶囊颜色：0 攻击、1 技能、2 能力、3 状态/诅咒。 */
    private int colorIndexFor(String cardId) {
        AbstractCard sample = sampleCard(cardId);
        if (sample == null) {
            return 0;
        }
        switch (sample.type) {
            case ATTACK:
                return 0;
            case SKILL:
                return 1;
            case POWER:
                return 2;
            case STATUS:
            case CURSE:
                return 3;
            default:
                return 0;
        }
    }

    private boolean needsTarget(String cardId) {
        AbstractCard sample = sampleCard(cardId);
        if (sample == null) {
            return true;
        }
        return sample.target == AbstractCard.CardTarget.ENEMY
                || sample.target == AbstractCard.CardTarget.ALL_ENEMY;
    }

    private AbstractCard sampleCard(String cardId) {
        for (AbstractCard c : AbstractDungeon.player.hand.group) {
            if (cardId.equals(c.cardID)) {
                return c;
            }
        }
        for (AbstractCard c : AbstractDungeon.player.drawPile.group) {
            if (cardId.equals(c.cardID)) {
                return c;
            }
        }
        for (AbstractCard c : AbstractDungeon.player.discardPile.group) {
            if (cardId.equals(c.cardID)) {
                return c;
            }
        }
        try {
            return com.megacrit.cardcrawl.helpers.CardLibrary.getCard(cardId);
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------------------------------------------------- 状态对比

    /** 实机状态行：字段与引擎 STATE 行一致，用于逐项核对。 */
    private String liveStateLine() {
        StringBuilder sb = new StringBuilder("STATE hp=");
        sb.append(AbstractDungeon.player.currentHealth)
          .append(" blk=").append(AbstractDungeon.player.currentBlock)
          .append(" en=").append(EnergyPanel.getCurrentEnergy())
          .append(" turn=").append(AbstractDungeon.actionManager.turn)
          .append(" hand=[");
        List<AbstractCard> hand = AbstractDungeon.player.hand.group;
        for (int i = 0; i < hand.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(hand.get(i).cardID);
        }
        sb.append("] mons=");
        AbstractRoom room = currentRoom();
        if (room != null && room.monsters != null) {
            List<AbstractMonster> monsters = room.monsters.monsters;
            for (int i = 0; i < monsters.size(); i++) {
                AbstractMonster m = monsters.get(i);
                if (i > 0) {
                    sb.append('|');
                }
                sb.append(Math.max(0, m.currentHealth)).append(':').append(m.currentBlock);
            }
        }
        return sb.toString();
    }

    /** 只比较会决定走法是否仍然成立的字段。 */
    private boolean statesMatch(String engineState, String liveState) {
        return EngineRootStateBinding.matches(engineState, liveState);
    }

    /** 起点核对也使用相同的字段与 engine-turn + 1 映射，不因存档来源降低门槛。 */
    private boolean startStatesMatch(String engineState, String liveState) {
        return EngineRootStateBinding.matches(engineState, liveState);
    }

    private String handSummary(SimState root) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < root.hand.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(root.hand.get(i).name).append('(').append(root.hand.get(i).cost).append(')');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 手动动作记录

    /** 每帧在空闲状态下记录手牌/药水快照，用于给手动出牌定位下标、发现未记录的药水。 */
    private void recordPassiveChanges(boolean playerReady) {
        lastIdleHand = new ArrayList<>(AbstractDungeon.player.hand.group);

        if (deployer.isActive()) {
            lastIdlePotions = null;
            return;
        }
        List<AbstractPotion> potions = new ArrayList<>();
        for (AbstractPotion p : AbstractDungeon.player.potions) {
            potions.add(p);
        }
        PotionJournalSnapshot.Observation<AbstractPotion> observation =
                PotionJournalSnapshot.observe(lastIdlePotions, potions, playerReady,
                        p -> !(p instanceof PotionSlot));
        lastIdlePotions = observation.snapshot;
        if (observation.missing != null) {
            journal.markDirty("有未记录的用药水动作：" + observation.missing.name);
        }
    }

    private int indexOfCardIdentity(AbstractCard card) {
        List<AbstractCard> hand = AbstractDungeon.player.hand.group;
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i) == card) {
                return i;
            }
        }
        for (int i = 0; i < lastIdleHand.size(); i++) {
            if (lastIdleHand.get(i) == card) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ 按键

    private boolean canReusePlan(Plan p, SimState live) {
        if (p == null || p.turns.isEmpty()) {
            return false;
        }
        Plan.Turn t = p.turns.get(0);
        if (t.stateSignature == null || !t.stateSignature.equals(live.signature())) {
            CombatLog.info("reuse rejected by state: plan[" + t.stateSignature
                    + "] live[" + live.signature() + "]");
            return false;
        }
        if (!planActionsResolvable(p, live)) {
            CombatLog.info("reuse rejected by hand: plan " + planActionSummary(p)
                    + " hand " + handSummary(live));
            return false;
        }
        return true;
    }

    /** 计划当前回合的牌是否都能在实机手牌里找到（药水 / 选牌动作不参与判断）。 */
    private boolean planActionsResolvable(Plan p, SimState live) {
        if (p == null || p.turns.isEmpty() || live == null) {
            return false;
        }
        Plan.Turn t = p.turns.get(0);
        for (Plan.Action a : t.actions) {
            if (a.isPotion() || a.isSelect()) {
                continue;
            }
            boolean found = false;
            for (SimState.Card c : live.hand) {
                if (c.id.equals(a.cardId)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private String planActionSummary(Plan p) {
        if (p == null || p.turns.isEmpty()) {
            return "(空)";
        }
        StringBuilder sb = new StringBuilder("[");
        for (Plan.Action a : p.turns.get(0).actions) {
            if (sb.length() > 1) {
                sb.append(' ');
            }
            sb.append(a.cardId).append('@').append(a.handIndex);
        }
        return sb.append(']').toString();
    }

    private void stopAndPause() {
        paused = true;
        executeRequested = false;
        fullAuto = false;
        if (searching) {
            search.abort();
            searching = false;
        }
        if (deployer.isActive()) {
            deployer.cancel();
            onDeployFinished(deployer.consumeResult());
        }
        CombatLog.info("paused: auto search & execution disabled until recalculation");
    }

    private void recalculate() {
        paused = false;
        plan = null;
        enginePlan = null;
        engineDriftReason = "";
        engineFetchJournalSize = -1;
        replayRequestedForSize = -1;
        expectedEndState = null;
        planValidated = false;
        planStale = false;
        suppressed = false;
        validatedSignature = null;
        searchAttempts = 0;
        if (searching) {
            search.abort();
            searching = false;
        }
        CombatLog.info("recalculate requested");
    }

    private boolean edge(int key) {
        boolean down = Gdx.input.isKeyPressed(key) || Gdx.input.isKeyJustPressed(key);
        boolean prev;
        if (key == Input.Keys.G) {
            prev = gKeyDown;
            gKeyDown = down;
        } else if (key == Input.Keys.H) {
            prev = hKeyDown;
            hKeyDown = down;
        } else if (key == Input.Keys.J) {
            prev = jKeyDown;
            jKeyDown = down;
        } else {
            prev = rKeyDown;
            rKeyDown = down;
        }
        return ExecutionSafety.risingEdge(prev, down);
    }

    private void clearKeys() {
        gKeyDown = false;
        hKeyDown = false;
        jKeyDown = false;
        rKeyDown = false;
    }

    private void reset() {
        inCombat = false;
        executionFailure.clear();
        activeExecutionSignature = null;
        failureStateChangeReported = false;
        plan = null;
        enginePlan = null;
        synchronized (engineFetchLock) {
            pendingEngineFetch = null;
            pendingEngineFetchJournalSize = -1;
            engineFetching = false;
            battleStartSave = null;
            battleSaveResolution = null;
        }
        battleSaveResolution = null;
        planValidated = false;
        planStale = false;
        validatedSignature = null;
        searching = false;
        paused = false;
        lastTurn = -1;
        executeRequested = false;
        fullAuto = false;
        expectedEndState = null;
        pendingDriftExpected = null;
        lastIdlePotions = null;
        clearKeys();
        deployer.cancel();
    }

    public Plan currentPlan() {
        if (enginePlan != null && !enginePlan.turns.isEmpty()) {
            return enginePlan;
        }
        return plan;
    }
}
