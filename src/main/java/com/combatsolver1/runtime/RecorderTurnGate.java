package com.combatsolver1.runtime;

/** Accepts turnHasEnded only after this battle has reached a player-ready frame. */
final class RecorderTurnGate {
    private boolean playerReady;

    void reset() { playerReady = false; }

    void observePlayerReady(boolean ready) {
        if (ready) playerReady = true;
    }

    boolean consumeTurnEnding(boolean turnHasEnded) {
        if (!turnHasEnded || !playerReady) return false;
        playerReady = false;
        return true;
    }
}
