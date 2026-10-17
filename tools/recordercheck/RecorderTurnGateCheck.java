package com.combatsolver1.runtime;

/** Startup turn flags must not create an endTurn before the player can act. */
public final class RecorderTurnGateCheck {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        RecorderTurnGate gate = new RecorderTurnGate();
        gate.reset();
        check(!gate.consumeTurnEnding(true), "startup turnHasEnded was treated as a player turn ending");
        gate.observePlayerReady(true);
        check(gate.consumeTurnEnding(true), "real player turn ending was ignored");
        check(!gate.consumeTurnEnding(true), "one turn ending was consumed more than once");
        gate.observePlayerReady(true);
        check(gate.consumeTurnEnding(true), "next player turn ending was not accepted");
        System.out.println("Recorder turn gate regression passed.");
    }
}
