package com.combatsolver1.runtime;

import com.combatsolver1.engine.SimState;

/** Regression for the recorder's multi-frame turn transition state machine. */
public final class CombatRecorderTransitionCheck {
    private static SimState s(int turn, int hp) { SimState s = new SimState(); s.turn = turn; s.hp = hp; return s; }
    private static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    public static void main(String[] args) {
        CombatRecorder.TurnTransitionTracker t = new CombatRecorder.TurnTransitionTracker();
        t.observe(s(1, 50));
        check(t.observe(s(1, 49)) == null, "old stable frame wrote transition");
        t.turnEnding(s(1, 49));
        check(t.observe(s(2, 49)) == null, "first new-turn frame wrote transition");
        CombatRecorder.TurnTransitionTracker.EndTurn e = t.observe(s(2, 48));
        check(e != null && e.pre.turn == 1 && e.post.turn == 2, "second stable frame missing or wrong endpoints");
        check(t.observe(s(2, 47)) == null, "duplicate endTurn after later stable frame");

        t.reset(); t.observe(s(1, 40)); t.turnEnding(s(1, 40));
        t.observe(s(2, 40));
        CombatRecorder.TurnTransitionTracker.EndTurn atCard = t.actionStarted(s(2, 39));
        check(atCard != null && atCard.pre.turn == 1 && atCard.post.turn == 2,
                "card before second stable frame did not close transition first");
        check(t.observe(s(2, 38)) == null, "card transition wrote duplicate endTurn");

        t.turnEnding(s(2, 38));
        check(t.observe(s(3, 38)) == null, "second turn emitted too early");
        CombatRecorder.TurnTransitionTracker.EndTurn second = t.observe(s(3, 37));
        check(second != null && second.pre.turn == 2 && second.post.turn == 3,
                "multiple consecutive turns lost order");

        t.reset(); t.observe(s(1, 30)); t.turnEnding(s(1, 30)); t.observe(s(2, 30));
        t.reset();
        check(t.observe(s(3, 29)) == null, "battle reset retained transition");
        System.out.println("CombatRecorder transition regression passed.");
    }
}
