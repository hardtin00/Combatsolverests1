package com.combatsolver1.runtime;

import com.combatsolver1.CombatLog;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.actions.common.DamageAction;
import com.megacrit.cardcrawl.actions.common.LoseHPAction;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.io.File;
import java.io.FileInputStream;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Properties;

/** Opt-in, read-only snapshots around native turn-transition resolution. */
final class TurnBoundaryDiagnostics {
    private static final int MAX_LINES_PER_TRANSITION = 160;
    private static final Map<DamageAction, DamageBefore> damageBefore = new IdentityHashMap<DamageAction, DamageBefore>();
    private static final Map<LoseHPAction, DamageBefore> hpLossBefore = new IdentityHashMap<LoseHPAction, DamageBefore>();
    private static boolean active;
    private static boolean truncated;
    private static int lines;
    private static String lastSnapshot;
    private static boolean failureReported;

    private TurnBoundaryDiagnostics() { }

    static boolean enabled() {
        String setting = System.getProperty("combatsolver1.turnDiagnostics");
        if (setting == null) setting = System.getenv("COMBATSOLVER1_TURN_DIAGNOSTICS");
        if (setting != null) return Boolean.parseBoolean(setting.trim());
        File settings = new File(new File(System.getProperty("user.home", "."), ".combatsolver1"),
                "settings.properties");
        if (!settings.isFile()) return false;
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(settings)) {
            properties.load(input);
            return Boolean.parseBoolean(properties.getProperty("diagnostics.turnBoundary", "false").trim());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void beforeEndTurn(GameActionManager manager) {
        try {
            if (!enabled() || AbstractDungeon.player == null) return;
            active = true;
            truncated = false;
            lines = 0;
            failureReported = false;
            damageBefore.clear();
            hpLossBefore.clear();
            lastSnapshot = snapshot(manager);
            emit("phase=player-end-turn-before " + lastSnapshot);
        } catch (Throwable t) { fail(t); }
    }

    static void endTurnTriggersEnqueued(GameActionManager manager) {
        try {
            if (!active) return;
            String state = snapshot(manager);
            emit("phase=end-turn-triggers-enqueued " + state);
            lastSnapshot = state;
        } catch (Throwable t) { fail(t); }
    }

    static void afterManagerUpdate(GameActionManager manager) {
        try {
            if (!active) return;
            String state = snapshot(manager);
            if (!state.equals(lastSnapshot)) {
                String current = manager.currentAction == null ? "none" : manager.currentAction.getClass().getSimpleName();
                emit("phase=transition-action-state-change action=" + current + " " + state);
                lastSnapshot = state;
            }
        } catch (Throwable t) { fail(t); }
    }

    static void onStablePlayerReady() {
        try {
            if (!active) return;
            emit("phase=new-player-turn-stable " + snapshot(AbstractDungeon.actionManager));
            active = false;
            damageBefore.clear();
            hpLossBefore.clear();
            lastSnapshot = null;
        } catch (Throwable t) { fail(t); }
    }

    static void beforeDamage(DamageAction action) {
        try {
            if (!active || action == null) return;
            damageBefore.put(action, new DamageBefore(targetBrief(action.target)));
        } catch (Throwable t) { fail(t); }
    }

    static void afterDamage(DamageAction action) {
        try {
            if (!active || action == null || !action.isDone) return;
            DamageBefore before = damageBefore.remove(action);
            if (before == null) return;
            AbstractCreature target = action.target;
            emit("phase=damage-action-settled source=DamageAction amount=" + action.amount
                    + " targetBefore=[" + before.target + "] targetAfter=[" + targetBrief(target) + "]");
        } catch (Throwable t) { fail(t); }
    }

    static void beforeHpLoss(LoseHPAction action) {
        try {
            if (!active || action == null) return;
            hpLossBefore.put(action, new DamageBefore(targetBrief(action.target)));
        } catch (Throwable t) { fail(t); }
    }

    static void afterHpLoss(LoseHPAction action) {
        try {
            if (!active || action == null || !action.isDone) return;
            DamageBefore before = hpLossBefore.remove(action);
            if (before == null) return;
            emit("phase=hp-loss-action-settled amount=" + action.amount
                    + " targetBefore=[" + before.target + "] targetAfter=[" + targetBrief(action.target) + "]");
        } catch (Throwable t) { fail(t); }
    }

    private static void fail(Throwable t) {
        active = false;
        damageBefore.clear();
        hpLossBefore.clear();
        if (!failureReported) {
            failureReported = true;
            CombatLog.info("TURN_DIAG disabled after diagnostic sampling error: " + t);
        }
    }

    private static void emit(String value) {
        if (!active && lines == 0) return;
        if (lines >= MAX_LINES_PER_TRANSITION) {
            if (!truncated) {
                truncated = true;
                CombatLog.info("TURN_DIAG phase=truncated maxLines=" + MAX_LINES_PER_TRANSITION);
            }
            return;
        }
        lines++;
        CombatLog.info("TURN_DIAG " + value);
    }

    private static String snapshot(GameActionManager manager) {
        if (AbstractDungeon.player == null) return "no-player";
        AbstractCreature player = AbstractDungeon.player;
        StringBuilder out = new StringBuilder(512);
        out.append("turn=").append(GameActionManager.turn)
                .append(" phase=").append(manager == null ? "none" : manager.phase)
                .append(" turnEnded=").append(manager != null && manager.turnHasEnded)
                .append(" queues=").append(manager == null ? "0/0/0"
                        : manager.actions.size() + "/" + manager.cardQueue.size() + "/" + manager.monsterQueue.size())
                .append(" player={hp:").append(player.currentHealth)
                .append("/").append(player.maxHealth)
                .append(",block:").append(player.currentBlock)
                .append(",energy:").append(EnergyPanel.getCurrentEnergy())
                .append(",str:").append(power(player, "Strength"))
                .append(",weak:").append(power(player, "Weakened"))
                .append(",vuln:").append(power(player, "Vulnerable"))
                .append(",hand:").append(cardIds(AbstractDungeon.player.hand.group))
                .append(",draw:").append(cardIds(AbstractDungeon.player.drawPile.group))
                .append(",discard:").append(cardIds(AbstractDungeon.player.discardPile.group))
                .append(",exhaust:").append(cardIds(AbstractDungeon.player.exhaustPile.group)).append('}');
        if (AbstractDungeon.getCurrRoom() != null && AbstractDungeon.getCurrRoom().monsters != null) {
            out.append(" monsters=[");
            for (int i = 0; i < AbstractDungeon.getCurrRoom().monsters.monsters.size(); i++) {
                AbstractMonster monster = AbstractDungeon.getCurrRoom().monsters.monsters.get(i);
                if (i > 0) out.append(';');
                if (monster == null) { out.append(i).append(":null"); continue; }
                out.append(i).append(':').append(clean(monster.id)).append("{hp:").append(monster.currentHealth)
                        .append('/').append(monster.maxHealth)
                        .append(",block:").append(monster.currentBlock)
                        .append(",str:").append(power(monster, "Strength"))
                        .append(",weak:").append(power(monster, "Weakened"))
                        .append(",vuln:").append(power(monster, "Vulnerable"))
                        .append(",intent:").append(monster.intent)
                        .append(",intentDmg:").append(Math.max(0, monster.getIntentDmg()))
                        .append(",alive:").append(!monster.isDeadOrEscaped()).append('}');
            }
            out.append(']');
        }
        return out.toString();
    }

    private static String targetBrief(AbstractCreature target) {
        if (target == null) return "none";
        String slot = "player";
        if (target instanceof AbstractMonster && AbstractDungeon.getCurrRoom() != null
                && AbstractDungeon.getCurrRoom().monsters != null) {
            slot = "slot" + AbstractDungeon.getCurrRoom().monsters.monsters.indexOf(target);
        }
        return slot + ':' + clean(target.name) + "{hp:" + target.currentHealth
                + ",block:" + target.currentBlock + '}';
    }

    private static int power(AbstractCreature creature, String id) {
        if (creature.powers == null) return 0;
        for (AbstractPower power : creature.powers) {
            if (power != null && id.equals(power.ID)) return power.amount;
        }
        return 0;
    }

    private static String cardIds(java.util.List<com.megacrit.cardcrawl.cards.AbstractCard> cards) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) out.append(',');
            out.append(clean(cards.get(i).cardID));
        }
        return out.append(']').toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }

    private static final class DamageBefore {
        final String target;
        DamageBefore(String target) { this.target = target; }
    }
}
