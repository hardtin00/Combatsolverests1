package com.combatsolver1.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * M4 player-end-only simulator.
 *
 * It deliberately does NOT call CombatEngine.endTurn(): that method advances
 * through the monster turn, block loss, next-turn draw and energy reset.
 * This class stops at the native PLAYER_END boundary, before
 * MonsterGroup.applyPreTurnLogic().
 *
 * Unsupported native mechanics are reported, never silently skipped.
 */
public final class PlayerEndSimulator {
    public static final String BOUNDARY = "PLAYER_END";

    private static final Set<String> RESIDUAL_CLEANUP_ACTIONS = new HashSet<String>(Arrays.asList(
            "com.megacrit.cardcrawl.actions.utility.UseCardAction",
            "com.megacrit.cardcrawl.actions.utility.HandCheckAction"
    ));

    private PlayerEndSimulator() {
    }

    public static final class Result {
        public final SimState state;
        public final boolean predicted;
        public final String status;
        public final List<String> trace;
        public final List<String> unsupported;
        public final List<String> residualActions;
        public final boolean residualActionsModeled;
        public final boolean inputUnchanged;
        public final int rngCalls;
        public final String inputSignature;

        Result(SimState state, boolean predicted, String status, List<String> trace,
               List<String> unsupported, List<String> residualActions,
               boolean residualActionsModeled, boolean inputUnchanged,
               int rngCalls, String inputSignature) {
            this.state = state;
            this.predicted = predicted;
            this.status = status;
            this.trace = trace;
            this.unsupported = unsupported;
            this.residualActions = residualActions;
            this.residualActionsModeled = residualActionsModeled;
            this.inputUnchanged = inputUnchanged;
            this.rngCalls = rngCalls;
            this.inputSignature = inputSignature;
        }
    }

    public static Result simulatePlayerEnd(SimState input) {
        return simulatePlayerEnd(input, Collections.<String>emptyList());
    }

    /**
     * @param residualActionClasses action classes already in the S0 ActionManager
     *                              (e.g. UseCardAction / HandCheckAction). Empty means idle.
     */
    public static Result simulatePlayerEnd(SimState input, List<String> residualActionClasses) {
        if (input == null) throw new IllegalArgumentException("input S0 is null");
        List<String> trace = new ArrayList<String>();
        List<String> unsupported = new ArrayList<String>();
        List<String> residual = residualActionClasses == null
                ? Collections.<String>emptyList() : new ArrayList<String>(residualActionClasses);
        String before = input.signature();
        trace.add("S0_READ signature=" + before);

        boolean residualModeled = false;
        for (String action : residual) {
            if (!RESIDUAL_CLEANUP_ACTIONS.contains(action)) {
                unsupported.add("RESIDUAL_ACTION_UNSUPPORTED:" + action);
            } else {
                residualModeled = true;
            }
        }
        if (!residual.isEmpty()) {
            if (unsupported.isEmpty()) {
                trace.add("RESIDUAL_ACTION_MODEL recognized post-card cleanup only: " + residual);
            } else {
                trace.add("RESIDUAL_ACTION_UNSUPPORTED " + residual);
            }
        }

        SimState s = input.fork();
        trace.add("COPY_INPUT fork (original input is not mutated)");

        // Native applyEndOfTurnTriggers() runs before the discard action.
        // Metallicize is supported; other damaging end-of-turn powers are not.
        if (s.pCombust > 0) unsupported.add("POWER_UNSUPPORTED:Combust");
        if (s.constricted > 0) unsupported.add("POWER_UNSUPPORTED:Constricted");
        if (s.pOmega > 0) unsupported.add("POWER_UNSUPPORTED:Omega");
        if (s.bombTimer > 0) unsupported.add("POWER_UNSUPPORTED:Bomb");
        if (s.playerPlatedArmor > 0) unsupported.add("POWER_UNSUPPORTED:PlatedArmor");
        if (s.hasRelic("Cloak Clasp")) unsupported.add("RELIC_UNSUPPORTED:Cloak Clasp");
        if (s.hasRelic("Runic Pyramid")) unsupported.add("RELIC_UNSUPPORTED:Runic Pyramid");

        int handCount = s.hand.size();
        trace.add("HAND_END_TRIGGER hand_count=" + handCount);
        List<SimState.Card> keep = new ArrayList<SimState.Card>();
        int hpLosses = 0;
        boolean anyExhaust = false;
        // Native DiscardAtEndOfTurnAction triggers a shuffled hand; for the
        // supported deterministic subset the pile order is reverse hand order
        // (verified by T3: [Defend,Strike] -> discard [Strike,Defend]).
        for (int i = s.hand.size() - 1; i >= 0; i--) {
            SimState.Card c = s.hand.get(i);
            if ("Doubt".equals(c.id) || "Shame".equals(c.id)) {
                unsupported.add("CARD_UNSUPPORTED:" + c.id);
            }
            if (c.endTurnSelfDamage > 0) {
                s.hp -= c.endTurnSelfDamage;
                s.hpLossEvents++;
                hpLosses++;
                trace.add("HAND_TRIGGER " + c.id + " self_damage=" + c.endTurnSelfDamage);
            }
            if (c.regret) {
                s.hp -= handCount;
                s.hpLossEvents++;
                hpLosses++;
                trace.add("HAND_TRIGGER " + c.id + " regret=" + handCount);
            }
            if (s.hp <= 0) {
                unsupported.add("PLAYER_DIED_DURING_PLAYER_END");
                break;
            }
            boolean exhaust = c.ethereal || "AscendersBane".equals(c.id);
            if (exhaust) {
                anyExhaust = true;
                s.exhaustPile.add(c);
                trace.add("HAND_EXHAUST " + c.id);
            } else {
                keep.add(c);
            }
        }

        if (anyExhaust && (s.pFeelNoPain > 0 || s.pDarkEmbrace > 0)) {
            unsupported.add("EXHAUST_TRIGGER_UNSUPPORTED:FeelNoPain/DarkEmbrace");
        }
        if (hpLosses > 0 && s.pRupture > 0) {
            unsupported.add("HP_LOSS_TRIGGER_UNSUPPORTED:Rupture");
        }
        if (hpLosses > 1) {
            unsupported.add("MULTIPLE_HAND_HP_TRIGGERS_RANDOM_ORDER");
        }

        if (!unsupported.isEmpty() || s.hp <= 0) {
            trace.add("STOP_UNSUPPORTED " + unsupported);
            return new Result(s, false, "UNSUPPORTED", trace, unsupported, residual,
                    residualModeled, input.signature().equals(before), 0, before);
        }

        // DiscardAtEndOfTurnAction moves non-retained cards to discard. Native
        // discard insertion is top-of-group; the observed order is reverse hand.
        s.hand.clear();
        for (SimState.Card c : keep) {
            s.discardPile.add(c);
            trace.add("DISCARD " + c.id);
        }
        trace.add("HAND_AFTER_SIZE=" + s.hand.size()
                + " DISCARD_SIZE=" + s.discardPile.size()
                + " EXHAUST_SIZE=" + s.exhaustPile.size());

        // Native applyEndOfTurnPreCardPowers() runs after the end-of-turn
        // discard trigger and before the monster turn.
        if (s.pMetallicize > 0) {
            s.block += s.pMetallicize;
            trace.add("END_TURN_POWER Metallicize +" + s.pMetallicize + " block");
        }

        // PLAYER_END invariants: block and energy are NOT reset here; turn is
        // not advanced; no draw, no monster turn, no monster block clear.
        trace.add("BLOCK_KEPT=" + s.block);
        trace.add("ENERGY_KEPT=" + s.energy);
        trace.add("TURN_KEPT=" + s.turn);
        trace.add("NO_NEXT_TURN_DRAW");
        trace.add("NO_ENERGY_RESET");
        trace.add("NO_MONSTER_TURN");
        trace.add("NO_MONSTER_BLOCK_CLEAR");
        trace.add("STOP_BEFORE_MONSTER_GROUP_applyPreTurnLogic");
        trace.add("RNG_CALLS=0");

        boolean unchanged = input.signature().equals(before);
        if (!unchanged) {
            unsupported.add("INTERNAL_INPUT_MUTATED");
            return new Result(s, false, "UNSUPPORTED", trace, unsupported, residual,
                    residualModeled, false, 0, before);
        }
        return new Result(s, true, "PREDICTED_PLAYER_END", trace, unsupported, residual,
                residualModeled, true, 0, before);
    }
}
