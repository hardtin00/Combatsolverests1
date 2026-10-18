package com.combatsolver1.runtime;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.actions.GameActionManager;

/** Observes only changed state snapshots while an opt-in end-turn diagnostic is active. */
@SpirePatch(clz = GameActionManager.class, method = "update")
public final class GameActionManagerTransitionDiagnosticPatch {
    private GameActionManagerTransitionDiagnosticPatch() { }

    @SpirePostfixPatch
    public static void afterUpdate(GameActionManager __instance) {
        TurnBoundaryDiagnostics.afterManagerUpdate(__instance);
    }
}
