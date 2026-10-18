package com.combatsolver1.runtime;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;

/** Brackets the native Continue-load call; no file or timestamp is inferred at battle start. */
@SpirePatch(clz = CardCrawlGame.class, method = "loadPlayerSave", paramtypez = {AbstractPlayer.class})
public final class CardCrawlGameNativeSaveLoadPatch {
    private CardCrawlGameNativeSaveLoadPatch() { }

    @SpirePrefixPatch
    public static void beforeNativeSaveLoad(CardCrawlGame __instance) {
        NativeSaveLoadCapture.beginNativeLoad();
    }

    @SpirePostfixPatch
    public static void afterNativeSaveLoad(CardCrawlGame __instance) {
        NativeSaveLoadCapture.finishNativeLoad();
    }
}
