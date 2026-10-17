package com.combatsolver1.runtime;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.actions.GameActionManager;

/** Captures the old-turn state before native end-of-turn relic, power, and card triggers run. */
@SpirePatch(clz = GameActionManager.class, method = "callEndOfTurnActions")
public final class GameActionManagerEndTurnRecorderPatch {
    private GameActionManagerEndTurnRecorderPatch() {
    }

    @SpirePrefixPatch
    public static void beforeEndTurnActions(GameActionManager __instance) {
        CombatRecorder.onTurnEnding();
    }
}
