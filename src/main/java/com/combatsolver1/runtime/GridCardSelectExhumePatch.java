package com.combatsolver1.runtime;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;

/** Supplies a two-frame, hitbox-relative native click only for a validated Exhume choice. */
@SpirePatch(clz = GridCardSelectScreen.class, method = "update")
public final class GridCardSelectExhumePatch {
    private GridCardSelectExhumePatch() { }

    @SpirePrefixPatch
    public static void beforeGridUpdate(GridCardSelectScreen __instance) {
        CombatController.instance().beforeGridSelectUpdate(__instance);
    }
}
