package com.combatsolver1;

/**
 * Minimal logging helper. The in-game log goes to the Slay the Spire console.
 */
public final class CombatLog {
    private CombatLog() {
    }

    public static void info(String message) {
        System.out.println("[CombatSolver1] " + message);
    }

    public static void error(String message, Throwable t) {
        System.out.println("[CombatSolver1] ERROR: " + message + " -> " + t);
        t.printStackTrace(System.out);
    }
}
