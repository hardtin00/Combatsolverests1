package com.combatsolver1.runtime;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.actions.common.DamageAction;

/** Logs native damage-action target deltas during an explicitly enabled turn transition. */
@SpirePatch(clz = DamageAction.class, method = "update")
public final class DamageActionTransitionDiagnosticPatch {
    private DamageActionTransitionDiagnosticPatch() { }

    @SpirePrefixPatch
    public static void beforeUpdate(DamageAction __instance) {
        TurnBoundaryDiagnostics.beforeDamage(__instance);
    }

    @SpirePostfixPatch
    public static void afterUpdate(DamageAction __instance) {
        TurnBoundaryDiagnostics.afterDamage(__instance);
    }
}
