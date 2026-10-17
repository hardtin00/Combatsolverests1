package com.combatsolver1.runtime;

import com.combatsolver1.engine.SimState;

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
        System.out.println("CombatRecorder transition regression passed: immediate-end, retained-hand, discard/draw, consecutive-turn, ordered-chain.");
    }

    private static List<String> ids(List<SimState.Card> cards) {
        List<String> ids = new ArrayList<String>();
        for (SimState.Card card : cards) ids.add(card.id);
        return ids;
    }
}
