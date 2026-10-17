package com.combatsolver1.runtime;

import basemod.interfaces.PostBattleSubscriber;
import basemod.interfaces.PostDeathSubscriber;
import com.combatsolver1.CombatSolver1Mod;
import com.combatsolver1.engine.SimState;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.actions.GameActionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Regression for recording the native endTurn boundary before hand cleanup runs. */
public final class CombatRecorderTransitionCheck {
    private static SimState state(int turn, int hp, int energy, String[] hand, String[] draw, String[] discard) {
        SimState s = new SimState();
        s.turn = turn;
        s.hp = hp;
        s.maxHp = 82;
        s.energy = energy;
        for (String id : hand) s.hand.add(card(id));
        for (String id : draw) s.drawPile.add(card(id));
        for (String id : discard) s.discardPile.add(card(id));
        return s;
    }

    private static SimState.Card card(String id) {
        return new SimState.Card(id, id, 1, 0, 1, 0,
                false, false, false, 0, 0, 0, 0, 0, false);
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    private static CombatRecorder.TurnTransitionTracker.EndTurn settleTurn(
            CombatRecorder.TurnTransitionTracker tracker,
            SimState beforeNativeEndTurn,
            SimState afterNativeDiscard,
            SimState firstNextTurnFrame,
            SimState stableNextTurnFrame) {
        tracker.turnEnding(beforeNativeEndTurn);
        check(tracker.observe(afterNativeDiscard) == null, "native cleanup itself wrote a completed transition");
        check(tracker.observe(firstNextTurnFrame) == null, "first next-turn frame wrote too early");
        return tracker.observe(stableNextTurnFrame);
    }

    public static void main(String[] args) {
        battleTerminationSubscribers();
        nativeEndTurnBoundary();
        CombatRecorder.TurnTransitionTracker tracker = new CombatRecorder.TurnTransitionTracker();
        List<String> timeline = new ArrayList<String>();

        // Root -> final card post -> native turn cleanup -> next-turn card pre.
        SimState root = state(1, 50, 3,
                new String[] {"LastAttack"}, new String[] {"NewDraw"}, new String[0]);
        timeline.add("root");
        SimState lastCardPost = state(1, 50, 0,
                new String[0], new String[] {"NewDraw"}, new String[] {"LastAttack"});
        check(root.hand.size() == 1 && "LastAttack".equals(root.hand.get(0).id)
                        && lastCardPost.discardPile.size() == 1,
                "root/card fixture did not model the final card transition");
        timeline.add("card");
        SimState afterLastCardDiscard = state(1, 50, 0,
                new String[0], new String[] {"NewDraw"}, new String[] {"LastAttack"});
        SimState nextTurn = state(2, 49, 3,
                new String[] {"NewDraw"}, new String[0], new String[] {"LastAttack"});
        SimState nextTurnStable = state(2, 49, 3,
                new String[] {"NewDraw"}, new String[0], new String[] {"LastAttack"});
        CombatRecorder.TurnTransitionTracker.EndTurn first = settleTurn(
                tracker, lastCardPost, afterLastCardDiscard, nextTurn, nextTurnStable);
        check(first != null, "immediate endTurn after last card was not recorded");
        check(lastCardPost.signature().equals(first.pre.signature()),
                "endTurn.pre did not connect to last card post");
        check(first.post.signature().equals(nextTurnStable.signature()),
                "endTurn.post did not capture next-turn stable state");
        timeline.add("endTurn");
        check(first.post.signature().equals(nextTurn.signature()), "transition state changed between stable frames");
        timeline.add("card");
        check(timeline.equals(Arrays.asList("root", "card", "endTurn", "card")),
                "root/card/endTurn/card ordering changed");

        // Multiple retained cards remain in hand; all other cards move to discard.
        tracker.reset();
        SimState retainedPre = state(2, 49, 0,
                new String[] {"RetainA", "RetainB", "Ethereal", "DiscardA", "DiscardB"},
                new String[] {"DrawA", "DrawB", "DrawC"}, new String[] {"OldDiscard"});
        retainedPre.hand.get(2).ethereal = true;
        SimState retainedCleanup = state(2, 49, 0,
                new String[] {"RetainA", "RetainB"},
                new String[] {"DrawA", "DrawB", "DrawC"},
                new String[] {"OldDiscard", "DiscardA", "DiscardB"});
        retainedCleanup.exhaustPile.add(card("Ethereal"));
        SimState retainedNext = state(3, 49, 3,
                new String[] {"RetainA", "RetainB", "DrawA", "DrawB", "DrawC"},
                new String[0], new String[] {"OldDiscard", "DiscardA", "DiscardB"});
        retainedNext.exhaustPile.add(card("Ethereal"));
        CombatRecorder.TurnTransitionTracker.EndTurn retained = settleTurn(
                tracker, retainedPre, retainedCleanup, retainedNext, retainedNext);
        check(retained != null && retainedPre.signature().equals(retained.pre.signature()),
                "retained-card boundary lost its pre state");
        check(ids(retained.post.hand).equals(Arrays.asList("RetainA", "RetainB", "DrawA", "DrawB", "DrawC")),
                "retained cards or next-turn draws missing from endTurn.post");
        check(ids(retained.post.discardPile).equals(Arrays.asList("OldDiscard", "DiscardA", "DiscardB")),
                "native discard cleanup missing from endTurn.post");
        check(retained.post.exhaustPile.size() == 1,
                "native ethereal cleanup missing from endTurn.post");

        // A further full turn proves no duplicate or stale pre snapshot survives.
        SimState secondPre = retainedNext;
        SimState secondCleanup = state(3, 49, 0, new String[0], new String[0],
                new String[] {"OldDiscard", "DiscardA", "DiscardB", "RetainA", "RetainB", "DrawA", "DrawB", "DrawC"});
        secondCleanup.exhaustPile.add(card("Ethereal"));
        SimState thirdTurn = state(4, 48, 3, new String[] {"DrawX"}, new String[0], secondCleanup.discardPile.stream().map(c -> c.id).toArray(String[]::new));
        thirdTurn.exhaustPile.add(card("Ethereal"));
        CombatRecorder.TurnTransitionTracker.EndTurn second = settleTurn(
                tracker, secondPre, secondCleanup, thirdTurn, thirdTurn);
        check(second != null && secondPre.signature().equals(second.pre.signature()),
                "consecutive turn transition reused the previous boundary");
        check(tracker.observe(thirdTurn) == null, "later stable frame duplicated endTurn");

        tracker.reset();
        check(tracker.observe(state(5, 47, 3, new String[0], new String[0], new String[0])) == null,
                "battle reset retained an old transition");
        System.out.println("CombatRecorder transition regression passed: native Burn/Metallicize/Juggernaut boundary, immediate-end, retained-hand, discard/draw, consecutive-turn, ordered-chain.");
    }

    private static void battleTerminationSubscribers() {
        Class<?>[] interfaces = CombatSolver1Mod.class.getInterfaces();
        check(Arrays.asList(interfaces).contains(PostBattleSubscriber.class),
                "normal room-end callback is not registered");
        check(Arrays.asList(interfaces).contains(PostDeathSubscriber.class),
                "native death-screen callback is not registered");
        try {
            CombatSolver1Mod.class.getMethod("receivePostBattle",
                    com.megacrit.cardcrawl.rooms.AbstractRoom.class);
            CombatSolver1Mod.class.getMethod("receivePostDeath");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("termination callback is missing", e);
        }
    }

    private static void nativeEndTurnBoundary() {
        SpirePatch target = GameActionManagerEndTurnRecorderPatch.class.getAnnotation(SpirePatch.class);
        check(target != null && target.clz() == GameActionManager.class
                        && "callEndOfTurnActions".equals(target.method()),
                "recorder hook must run at GameActionManager.callEndOfTurnActions, before native end-turn effects");
        boolean prefixFound = false;
        for (java.lang.reflect.Method method : GameActionManagerEndTurnRecorderPatch.class.getDeclaredMethods()) {
            if (method.getAnnotation(SpirePrefixPatch.class) != null
                    && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0] == GameActionManager.class) {
                prefixFound = true;
            }
        }
        check(prefixFound, "native queue boundary prefix signature is not registered");
        try {
            GameActionManager.class.getDeclaredMethod("callEndOfTurnActions");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("game runtime has no callEndOfTurnActions hook", e);
        }

        // Reproduce the observed native order: snapshot at the null-card end-turn queue item,
        // then Burn plays, end-turn powers trigger, room cleanup discards hand, and next turn starts.
        CombatRecorder.TurnTransitionTracker tracker = new CombatRecorder.TurnTransitionTracker();
        SimState cardPost = state(6, 2, 0,
                new String[] {"Burn", "Defend_R", "Strike_R"}, new String[] {"Uppercut", "Strike_R"},
                new String[] {"Shrug It Off", "Strike_R"});
        cardPost.block = 8;
        cardPost.pMetallicize = 3;
        cardPost.pJuggernaut = 5;
        SimState.Mob hexaghost = new SimState.Mob("Hexaghost", "六火亡魂", 141, 264, 0, 6, 2, 3, true);
        cardPost.mobs.add(hexaghost);

        // This is the state the new prefix captures before callEndOfTurnActions mutates anything.
        tracker.turnEnding(cardPost.fork());
        SimState nativeState = cardPost.fork();
        nativeState.block += nativeState.pMetallicize; // Metallicize runs before end-turn cards.
        nativeState.mobs.get(0).hp -= nativeState.pJuggernaut; // Its gained block triggers Juggernaut.
        removeCard(nativeState.hand, "Burn");
        nativeState.discardPile.add(card("Burn"));
        nativeState.block = Math.max(0, nativeState.block - 2); // Burn's 2 damage is absorbed by block.

        // AbstractRoom.endTurn/DiscardAtEndOfTurnAction: remaining hand is discarded and block expires.
        for (SimState.Card c : nativeState.hand) nativeState.discardPile.add(c.copy());
        nativeState.hand.clear();
        nativeState.block = 0;
        nativeState.turn = 7;
        nativeState.energy = 3;
        nativeState.hand.add(card("Uppercut"));
        nativeState.hand.add(card("Strike_R"));
        CombatRecorder.TurnTransitionTracker.EndTurn end = tracker.observe(nativeState);
        check(end == null, "transition was emitted before two stable next-turn frames");
        end = tracker.observe(nativeState.fork());
        check(end != null, "native end-turn sequence did not produce a transition");
        check(cardPost.signature().equals(end.pre.signature()),
                "endTurn.pre does not connect to the completed final-card post");
        check(end.post.block == 0 && end.post.turn == 7 && end.post.energy == 3,
                "endTurn.post missed native turn-start cleanup");
        check(end.post.mobs.get(0).hp == 136,
                "endTurn.post missed Juggernaut damage triggered by Metallicize");
        check(ids(end.post.discardPile).equals(Arrays.asList("Shrug It Off", "Strike_R", "Burn", "Defend_R", "Strike_R")),
                "Burn and remaining hand were not represented in endTurn.post discard pile");

        // The first observed break had Burn alone: it spent exactly two existing block before room cleanup.
        tracker.reset();
        SimState burnOnlyPost = state(5, 2, 0,
                new String[] {"Strike_R", "Defend_R", "Burn"}, new String[] {"NewDraw"},
                new String[] {"OldDiscard"});
        burnOnlyPost.block = 13;
        burnOnlyPost.pJuggernaut = 5;
        burnOnlyPost.mobs.add(new SimState.Mob("Hexaghost", "六火亡魂", 158, 264, 0, 6, 1, 0, true));
        tracker.turnEnding(burnOnlyPost.fork());
        SimState burnOnlyNative = burnOnlyPost.fork();
        removeCard(burnOnlyNative.hand, "Burn");
        burnOnlyNative.discardPile.add(card("Burn"));
        burnOnlyNative.block -= 2;
        check(burnOnlyNative.block == 11 && burnOnlyNative.mobs.get(0).hp == 158,
                "Burn-only boundary did not reproduce block 13->11 with unchanged Hexaghost HP");
        for (SimState.Card c : burnOnlyNative.hand) burnOnlyNative.discardPile.add(c.copy());
        burnOnlyNative.hand.clear();
        burnOnlyNative.block = 0;
        burnOnlyNative.turn = 6;
        burnOnlyNative.energy = 3;
        burnOnlyNative.hand.add(card("NewDraw"));
        tracker.observe(burnOnlyNative);
        CombatRecorder.TurnTransitionTracker.EndTurn burnOnly = tracker.observe(burnOnlyNative.fork());
        check(burnOnly != null && burnOnlyPost.signature().equals(burnOnly.pre.signature()),
                "Burn-only endTurn did not preserve the previous card post as its pre state");
        check(burnOnly.post.mobs.get(0).hp == 158
                        && ids(burnOnly.post.discardPile).equals(Arrays.asList("OldDiscard", "Burn", "Strike_R", "Defend_R")),
                "Burn-only native cleanup was not included in endTurn.post");
    }

    private static void removeCard(List<SimState.Card> cards, String id) {
        for (int i = 0; i < cards.size(); i++) {
            if (id.equals(cards.get(i).id)) {
                cards.remove(i);
                return;
            }
        }
        throw new AssertionError("missing native card " + id);
    }

    private static List<String> ids(List<SimState.Card> cards) {
        List<String> ids = new ArrayList<String>();
        for (SimState.Card card : cards) ids.add(card.id);
        return ids;
    }
}
