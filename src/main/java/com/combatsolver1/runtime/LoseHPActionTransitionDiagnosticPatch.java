package com.combatsolver1.runtime;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.actions.common.LoseHPAction;

/** Captures non-blockable HP-loss actions separately from DamageInfo-based damage. */
@SpirePatch(clz = LoseHPAction.class, method = "update")
public final class LoseHPActionTransitionDiagnosticPatch {
    private LoseHPActionTransitionDiagnosticPatch() { }

    @SpirePrefixPatch
    public static void beforeUpdate(LoseHPAction __instance) {
        TurnBoundaryDiagnostics.beforeHpLoss(__instance);
    }

    @SpirePostfixPatch
    public static void afterUpdate(LoseHPAction __instance) {
        TurnBoundaryDiagnostics.afterHpLoss(__instance);
    }
}
