package com.combatsolver1.runtime;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.actions.common.DamageAction;
import com.megacrit.cardcrawl.cards.DamageInfo;

/** Logs native damage source and target deltas during an explicitly enabled turn transition. */
@SpirePatch(clz = DamageAction.class, method = "update")
public final class DamageActionTransitionDiagnosticPatch {
    private DamageActionTransitionDiagnosticPatch() { }

    @SpirePrefixPatch
    public static void beforeUpdate(DamageAction __instance, DamageInfo ___info) {
        TurnBoundaryDiagnostics.beforeDamage(__instance, ___info);
    }

    @SpirePostfixPatch
    public static void afterUpdate(DamageAction __instance, DamageInfo ___info) {
        TurnBoundaryDiagnostics.afterDamage(__instance, ___info);
    }
}
